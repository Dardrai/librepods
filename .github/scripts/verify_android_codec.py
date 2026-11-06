"""Verify full AAC-ELD/PCM32 APKs and bundle their exact encoder source."""
import argparse
import gzip
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import struct
import subprocess
import sys
import tarfile
import tempfile
import zipfile

from android_ci_metadata import app_version


ROOT = Path(__file__).resolve().parents[2]
NOTICE_NAMES = ("NOTICE-FDK.txt", "NOTICE-owned-fdk-MIT.txt", "NOTICE-FDK-modifications.txt")
MARKER_PREFIX = b"LIBREPODS_AAC_ELD_BUILD_V1;"
FDK_REVISION = "cac04476081a23c870fed05c36228cd02c5e7c3d"
MARKER = MARKER_PREFIX + b"backend=owned-fdk;pcm_bits=32;fdk=" + FDK_REVISION.encode() + b"\0"
NATIVE_ENTRY = "lib/arm64-v8a/libfluoride_hooks.so"
NATIVE_OPTIONS = {"CMAKE_BUILD_TYPE": "Release", "ANDROID_ABI": "arm64-v8a",
                  "LIBREPODS_PRIVATE_OWNED_FDK": "ON", "LIBREPODS_PRIVATE_WIDE_PCM": "ON",
                  "LIBREPODS_NATIVE_TRACE": "OFF", "ELD_KEEP_GAME_FLUSH_BASELINE": "ON"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def elf_sections(data):
    require(len(data) >= 64 and data[:7] == b"\x7fELF\x02\x01\x01", "Expected little-endian ELF64 native library")
    header = struct.unpack_from("<16sHHIQQQIHHHHHH", data)
    require(header[1:4] == (3, 183, 1), "Expected an ARM64 shared library")
    offset, size, count = header[6], header[11], header[12]
    require(size == 64 and 1 <= count <= 4096 and 64 <= offset <= len(data) - size * count,
            "Invalid ELF section table")
    sections = []
    for index in range(count):
        section = struct.unpack_from("<IIQQQQIIQQ", data, offset + index * size)
        kind, flags, start, length = section[1], section[2], section[4], section[5]
        if kind == 8:  # SHT_NOBITS occupies memory rather than bytes in the file.
            continue
        require(start <= len(data) and length <= len(data) - start, "ELF section exceeds the native library")
        sections.append((kind, flags, start, length))
    return sections


def native_contract(data):
    sections = elf_sections(data)
    require(data.count(MARKER_PREFIX) == 1, "Owned AAC-ELD encoder build contract missing or ambiguous")
    marker_at = data.find(MARKER_PREFIX)
    require(data[marker_at:marker_at + len(MARKER)] == MARKER, "Encoder is not the pinned PCM32/Q31 build")
    marker_allocated = False
    executable = False
    for kind, flags, start, length in sections:
        if kind == 1 and flags & 2:
            executable |= bool(flags & 4 and length)
            marker_allocated |= bool(not flags & 1 and not flags & 4 and
                                     start <= marker_at and marker_at + len(MARKER) <= start + length)
    require(marker_allocated and executable, "Encoder contract must belong to mapped read-only native data")


def build_id(data):
    found = []
    for kind, _, start, length in elf_sections(data):
        if kind != 7:  # SHT_NOTE
            continue
        end = start + length
        while start < end:
            require(start + 12 <= end, "Truncated ELF note")
            name_length, value_length, note_type = struct.unpack_from("<III", data, start)
            name_at = start + 12
            value_at = name_at + ((name_length + 3) & ~3)
            next_note = value_at + ((value_length + 3) & ~3)
            require(next_note <= end, "ELF note exceeds its section")
            if note_type == 3 and data[name_at:name_at + name_length] == b"GNU\0":
                require(4 <= value_length <= 64, "Unexpected GNU build-id length")
                found.append(data[value_at:value_at + value_length].hex())
            start = next_note
    require(len(found) == 1, "Native library must have one GNU build-id")
    return found[0]


def payload(archive, expected_notices):
    names = archive.namelist()
    require(len(names) == len(set(names)), "APK has duplicate entries")
    require(NATIVE_ENTRY in names, "APK has no ARM64 audio library")
    require(archive.getinfo(NATIVE_ENTRY).file_size <= 64 * 1024 * 1024, "Native library exceeds verification bound")
    native = archive.read(NATIVE_ENTRY)
    native_contract(native)
    notices = {}
    for name in NOTICE_NAMES:
        entry = "assets/aac-eld/" + name
        require(entry in names, "APK is missing " + entry)
        require(archive.getinfo(entry).file_size <= 256 * 1024, "Encoder notice exceeds verification bound")
        data = archive.read(entry)
        require(data and data == expected_notices[name], "APK notice differs from its source-built copy: " + name)
        notices[name] = hashlib.sha256(data).hexdigest()
    return {"arm64_native_sha256": hashlib.sha256(native).hexdigest(),
            "arm64_build_id": build_id(native), "notice_sha256": notices}


def tool(*args):
    result = subprocess.run([str(arg) for arg in args], capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=120)
    require(result.returncode == 0, Path(str(args[0])).name + " failed: " + result.stderr.strip())
    return result.stdout


def verify(apk, notices_dir, version_name, version_code, build_tools):
    expected_notices = {name: (notices_dir / name).read_bytes() for name in NOTICE_NAMES}
    with zipfile.ZipFile(apk) as archive:
        result = payload(archive, expected_notices)
    manifest = tool(build_tools / "aapt", "dump", "badging", apk)
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", manifest, re.M)
    require(package is not None and package.groups() == ("me.kavishdevar.librepods", str(version_code), version_name),
            "APK package/version does not match the checked-out source")
    tool(build_tools / "zipalign", "-c", "-P", "16", "4", apk)
    result.update(apk=str(apk), apk_sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
                  version_name=version_name, version_code=version_code,
                  owned_fdk_pcm32=True, source_built_notices_match=True, zip_alignment_16k_verified=True)
    return result


def read_cache(path):
    return dict(match.groups() for line in path.read_text().splitlines()
                if (match := re.match(r"^([^#/:][^:=]*):[^=]*=(.*)$", line)))


def validate_options(values):
    for name, expected in NATIVE_OPTIONS.items():
        require(values.get(name) == expected, "Native build option mismatch: " + name)


def source_tree(path):
    result = {}
    require(path.is_dir() and not path.is_symlink(), "Invalid encoder source directory")
    for file in sorted(path.rglob("*")):
        require(not file.is_symlink(), "Encoder source contains a symbolic link")
        if file.is_dir():
            continue
        require(file.is_file() and file.resolve().is_relative_to(path.resolve()), "Unsafe encoder source file")
        result[file.relative_to(path).as_posix()] = file.read_bytes()
    require(result, "Encoder source is empty")
    return result


def q31_source(cache_path, values):
    path = Path(values["fdk-aac_SOURCE_DIR"])
    expected = cache_path.parent / "fdk-q31-source"
    require(path.resolve() == expected.resolve() and path.resolve().is_relative_to(cache_path.parent.resolve()),
            "Encoder source must be the selected build's private Q31 source copy")
    return source_tree(path)


def matching_cache(expected_build_id, build_tools):
    matches = []
    home = ROOT / "android/app/src/main/cpp"
    assets = ROOT / "android/app/.cxx/eld-assets"
    ndk = re.search(r'ndkVersion = "([^"]+)"', (ROOT / "android/app/build.gradle.kts").read_text())
    require(ndk is not None, "Cannot read native toolchain version")
    toolchain = build_tools.parents[1] / "ndk" / ndk[1] / "build/cmake/android.toolchain.cmake"
    for path in (ROOT / "android/app/.cxx").glob("*/*/arm64-v8a/CMakeCache.txt"):
        values = read_cache(path)
        if (any(values.get(name) != value for name, value in NATIVE_OPTIONS.items()) or
                Path(values.get("CMAKE_HOME_DIRECTORY", "")).resolve() != home.resolve() or
                Path(values.get("LIBREPODS_ELD_ASSETS_DIR", "")).resolve() != assets.resolve() or
                Path(values.get("CMAKE_TOOLCHAIN_FILE", "")).resolve() != toolchain.resolve()):
            continue  # Old configurations do not describe the newly packaged encoder.
        library = Path(values.get("CMAKE_LIBRARY_OUTPUT_DIRECTORY", "")) / "libfluoride_hooks.so"
        require(library.resolve().is_relative_to((ROOT / "android/app/build/intermediates").resolve()),
                "Native output is outside the app build directory")
        if library.is_file() and build_id(library.read_bytes()) == expected_build_id:
            matches.append((path, values))
    require(len(matches) == 1, "Cannot identify one complete native build matching the APK build-id")
    return matches[0]


def compiler_defines(command):
    result = {}
    for action, name, value in re.findall(r"(?:^|\s)-([DU])\s*([A-Za-z_][A-Za-z0-9_]*)(?:=([^\s]+))?(?=\s|$)", command):
        result[name] = (value or "1") if action == "D" else None
    return result


def compile_contract(cache_path, source):
    expected = {"fluoride_hooks.cpp": ROOT / "android/app/src/main/cpp/fluoride_hooks.cpp",
                "owned_fdk_encoder.cpp": ROOT / "android/app/src/main/cpp/eld/owned_fdk_encoder.cpp",
                "aacenc_lib.cpp": source / "libAACenc/src/aacenc_lib.cpp"}
    found = {}
    for entry in json.loads((cache_path.parent / "compile_commands.json").read_text()):
        name = Path(entry["file"]).name
        if name not in expected or Path(entry["file"]).resolve() != expected[name].resolve():
            continue
        require(name not in found, "Duplicate encoder compilation unit")
        command = entry.get("command") or " ".join(entry["arguments"])
        definitions = compiler_defines(command)
        optimizations = re.findall(r"(?:^|\s)(-O(?:[0123szg]|fast))(?=\s|$)", command)
        require(optimizations and optimizations[-1] in ("-O1", "-O2", "-O3", "-Os", "-Oz"),
                "Encoder compilation lost its optimized audio path: " + name)
        if name in ("fluoride_hooks.cpp", "owned_fdk_encoder.cpp"):
            require(definitions.get("ELD_OWNED_PCM_BITS") == "32", "Encoder compilation is not Q31")
        if name == "owned_fdk_encoder.cpp":
            require(re.fullmatch(r'\\*"' + FDK_REVISION + r'\\*"', definitions.get("ELD_OWNED_FDK_REVISION") or ""),
                    "Encoder revision does not match its source pin")
        if name == "fluoride_hooks.cpp":
            for define in ("ELD_KEEP_GAME_FLUSH_BASELINE=1", "ELD_PRIVATE_OWNED_FDK=1",
                           "ELD_PRIVATE_WIDE_PCM=1", "LIBREPODS_NATIVE_TRACE=0"):
                key, value = define.split("=", 1)
                require(definitions.get(key) == value, "Native compilation contract mismatch: " + define)
        found[name] = optimizations[-1]
    require(found.keys() == expected.keys(), "Cannot verify all encoder compilation units")
    return found


def write_archive(path, files):
    require(not path.is_symlink(), "Source bundle destination is a symbolic link")
    with path.open("wb") as output, gzip.GzipFile(filename="", mode="wb", fileobj=output, mtime=0) as zipped:
        with tarfile.open(fileobj=zipped, mode="w") as archive:
            for name, data in sorted(files.items()):
                member = PurePosixPath(name)
                require(bool(member.parts) and not member.is_absolute() and ".." not in member.parts and "\\" not in name and
                        ":" not in member.parts[0], "Unsafe source archive member")
                info = tarfile.TarInfo(name)
                info.size, info.mode, info.mtime = len(data), 0o644, 0
                archive.addfile(info, io.BytesIO(data))


def source_bundle(results, build_tools, notices_dir):
    require(results[0]["arm64_build_id"] == results[1]["arm64_build_id"],
            "Debug and Release must contain the same native audio build")
    cache_path, values = matching_cache(results[0]["arm64_build_id"], build_tools)
    validate_options(values)
    tree = q31_source(cache_path, values)
    source = Path(values["fdk-aac_SOURCE_DIR"])
    optimization = compile_contract(cache_path, source)
    q31 = (b"typedef LONG INT_PCM;\n#define MAXVAL_PCM MAXVAL_DBL\n#define MINVAL_PCM MINVAL_DBL\n"
           b"#define WAV_BITS 32\n#define SAMPLE_BITS 32")
    require(q31 in tree["libSYS/include/machine_type.h"].replace(b"\r\n", b"\n"),
            "Encoder source does not contain its Q31 PCM definition")
    require(tree["NOTICE"] == (notices_dir / "NOTICE-FDK.txt").read_bytes() and
            tree["LIBREPODS-MODIFICATIONS.txt"] == (notices_dir / "NOTICE-FDK-modifications.txt").read_bytes(),
            "Packaged notices do not match the complete encoder source")
    cmake = ROOT / "android/app/src/main/cpp/eld/owned_fdk.cmake"
    content = cmake.read_text()
    pin = re.search(r"set\(FDK_REVISION ([0-9a-f]{40})\)", content)
    digest = re.search(r"set\(FDK_SOURCE_SHA256 ([0-9a-f]{64})\)", content)
    require(pin is not None and pin[1] == FDK_REVISION and digest is not None, "FDK source pin changed")
    files = {"fdk-aac/" + name: data for name, data in tree.items()}
    for name in ("LICENSE", "android/app/src/main/cpp/eld/owned_fdk.cmake",
                 "android/app/src/main/cpp/eld/owned_fdk_encoder.cpp", "android/app/src/main/cpp/eld/owned_fdk_encoder.h",
                 "android/app/src/main/cpp/eld/fdk_encoder.cpp", "android/app/src/main/cpp/eld/fdk_encoder.h",
                 "android/tools/native-owned-fdk-tests/LICENSE"):
        path = ROOT / name
        require(not path.is_symlink(), "Wrapper source contains a symbolic link")
        files["librepods/" + name] = path.read_bytes()
    require(files["librepods/android/tools/native-owned-fdk-tests/LICENSE"] ==
            (notices_dir / "NOTICE-owned-fdk-MIT.txt").read_bytes(), "Wrapper notice does not match its source")
    commit = tool("git", "-C", ROOT, "rev-parse", "HEAD").strip()
    require(re.fullmatch("[0-9a-f]{40}", commit), "Cannot identify the source commit")
    dirty = bool(tool("git", "-C", ROOT, "status", "--porcelain", "--untracked-files=no"))
    require(os.environ.get("GITHUB_ACTIONS") != "true" or not dirty, "CI source changed during the build")
    out = ROOT / "android/app/build/outputs/codec-source"
    require(out.resolve().is_relative_to((ROOT / "android/app/build").resolve()), "Unsafe source bundle directory")
    out.mkdir(parents=True, exist_ok=True)
    bundle = out / "aac-eld-source.tar.gz"
    write_archive(bundle, files)
    provenance = dict(repository="https://github.com/Ripthulhu/librepods", source_commit=commit,
                      source_checkout_dirty=dirty, fdk_revision=pin[1], upstream_archive_sha256=digest[1],
                      native_build_id=results[0]["arm64_build_id"], native_options=NATIVE_OPTIONS,
                      ndk_version=Path(values["CMAKE_TOOLCHAIN_FILE"]).parents[2].name,
                      native_cache=cache_path.relative_to(ROOT).as_posix(), optimization=optimization,
                      archive_sha256=hashlib.sha256(bundle.read_bytes()).hexdigest(),
                      files={name: hashlib.sha256(data).hexdigest() for name, data in sorted(files.items())})
    provenance_file = out / "aac-eld-source.json"
    require(not provenance_file.is_symlink(), "Source provenance destination is a symbolic link")
    provenance_file.write_text(json.dumps(provenance, indent=2) + "\n")
    return {"archive": bundle.name, "archive_sha256": provenance["archive_sha256"],
            "provenance": "aac-eld-source.json", "source_commit": commit,
            "native_build_id": results[0]["arm64_build_id"], "native_options": NATIVE_OPTIONS,
            "optimization": optimization, "source_files": len(files)}


def self_test():
    def elf(marker=MARKER, machine=183, flags=2):
        start = 64 + 4 * 64
        note = struct.pack("<III", 4, 20, 3) + b"GNU\0" + b"\x42" * 20
        header = struct.pack("<16sHHIQQQIHHHHHH", b"\x7fELF\x02\x01\x01" + b"\0" * 9,
                             3, machine, 1, 0, 0, 64, 0, 64, 0, 0, 64, 4, 0)
        sections = b"\0" * 64 + struct.pack("<IIQQQQIIQQ", 0, 1, flags, 0, start, len(marker), 0, 0, 1, 0)
        sections += struct.pack("<IIQQQQIIQQ", 0, 1, 6, 0, start + len(marker), 4, 0, 0, 4, 0)
        sections += struct.pack("<IIQQQQIIQQ", 0, 7, 2, 0, start + len(marker) + 4, len(note), 0, 0, 4, 0)
        return header + sections + marker + b"\xc0\x03\x5f\xd6" + note

    native_contract(elf())
    assert build_id(elf()) == "42" * 20
    failures = (b"", elf()[:63], elf(machine=62), elf(flags=3), elf(flags=0), elf(marker=b"stub\0"),
                elf(marker=MARKER.replace(b"pcm_bits=32", b"pcm_bits=16")),
                elf(marker=MARKER + MARKER), elf()[:-1])
    for malformed in failures:
        try:
            native_contract(malformed)
        except ValueError:
            pass
        else:
            raise AssertionError("Malformed, stub or mismatched native payload accepted")
    notices = {name: (name + "\n").encode() for name in NOTICE_NAMES}
    entries = {NATIVE_ENTRY: elf(), **{"assets/aac-eld/" + name: data for name, data in notices.items()}}

    def check(entries):
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as archive:
            for name, value in entries.items():
                archive.writestr(name, value)
        with zipfile.ZipFile(data) as archive:
            return payload(archive, notices)

    assert check(entries)["arm64_native_sha256"] == hashlib.sha256(elf()).hexdigest()
    for removed in (NATIVE_ENTRY, *("assets/aac-eld/" + name for name in NOTICE_NAMES)):
        try:
            check({key: value for key, value in entries.items() if key != removed})
        except ValueError:
            pass
        else:
            raise AssertionError("Incomplete APK accepted")
    for value in (b"", b"different notice\n"):
        try:
            check({**entries, "assets/aac-eld/NOTICE-FDK.txt": value})
        except ValueError:
            pass
        else:
            raise AssertionError("Incorrect encoder notice accepted")
    validate_options(NATIVE_OPTIONS)
    assert compiler_defines("clang -DLIBREPODS_NATIVE_TRACE=0 -D LIBREPODS_NATIVE_TRACE=1")["LIBREPODS_NATIVE_TRACE"] == "1"
    assert compiler_defines("clang -DELD_PRIVATE_WIDE_PCM=1 -DELD_PRIVATE_WIDE_PCM=0")["ELD_PRIVATE_WIDE_PCM"] == "0"
    assert compiler_defines("clang -DELD_OWNED_PCM_BITS=32 -U ELD_OWNED_PCM_BITS")["ELD_OWNED_PCM_BITS"] is None
    escaped_revision = r'\\\"' + FDK_REVISION + r'\\\"'
    assert compiler_defines("clang -DELD_OWNED_FDK_REVISION=" + escaped_revision)["ELD_OWNED_FDK_REVISION"] == escaped_revision
    for option in NATIVE_OPTIONS:
        try:
            validate_options({**NATIVE_OPTIONS, option: "wrong"})
        except ValueError:
            pass
        else:
            raise AssertionError("Wrong native build configuration accepted")
    with tempfile.TemporaryDirectory(prefix="eld-source-contract-") as temporary:
        directory = Path(temporary)
        source = directory / "fdk-q31-source"
        source.mkdir()
        (source / "NOTICE").write_bytes(b"source notice\n")
        assert q31_source(directory / "CMakeCache.txt", {"fdk-aac_SOURCE_DIR": str(source)}) == {"NOTICE": b"source notice\n"}
        try:
            q31_source(directory / "CMakeCache.txt", {"fdk-aac_SOURCE_DIR": str(directory)})
        except ValueError:
            pass
        else:
            raise AssertionError("Source directory escape accepted")
        if os.name != "nt":
            (source / "outside").symlink_to(directory / "elsewhere")
            try:
                source_tree(source)
            except ValueError:
                pass
            else:
                raise AssertionError("Symbolic source link accepted")
            (source / "outside").unlink()
        bundle = directory / "source.tar.gz"
        write_archive(bundle, {"fdk-aac/NOTICE": b"source notice\n"})
        first = bundle.read_bytes()
        write_archive(bundle, {"fdk-aac/NOTICE": b"source notice\n"})
        assert bundle.read_bytes() == first
        with tarfile.open(bundle) as archive:
            assert archive.getnames() == ["fdk-aac/NOTICE"]
            assert archive.extractfile("fdk-aac/NOTICE").read() == b"source notice\n"
        for name in ("", "../outside", "/outside", "C:/outside", "back\\slash"):
            try:
                write_archive(directory / "bad.tar.gz", {name: b"bad"})
            except ValueError:
                pass
            else:
                raise AssertionError("Unsafe source archive member accepted")
    print("AAC-ELD APK verification checks passed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--build-tools", type=Path)
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    require(args.build_tools is not None, "--build-tools is required")
    gradle = (ROOT / "android/app/build.gradle.kts").read_text()
    version = app_version((ROOT / "android/version.properties").read_text(), gradle)
    code = re.search(r"^val appVersionCode = ([0-9]+)$", gradle, re.M)
    require(code is not None, "Cannot read APK version code")
    results = []
    notices_dir = ROOT / "android/app/build/generated/eld-assets/aac-eld"
    for variant in ("debug", "release"):
        apks = list((ROOT / "android/app/build/outputs/apk" / variant).glob("*.apk"))
        require(len(apks) == 1, "Expected exactly one " + variant + " APK")
        results.append(verify(apks[0], notices_dir, version + ("-debug" if variant == "debug" else ""),
                              int(code[1]), args.build_tools))
    report = {"apks": results, "encoder_source": source_bundle(results, args.build_tools, notices_dir)}
    receipt = ROOT / "android/app/build/reports/codec-packaging.json"
    receipt.parent.mkdir(parents=True, exist_ok=True)
    receipt.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print("AAC-ELD APK verification failed: " + str(error), file=sys.stderr)
        sys.exit(1)
