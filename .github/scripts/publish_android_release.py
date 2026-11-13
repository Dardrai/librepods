"""Publish a complete immutable Android download, without replacing a newer Latest."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from urllib.parse import quote


REPOSITORY = "Ripthulhu/librepods"
PAYLOADS = {"LibrePods.apk", "aac-eld-source.tar.gz", "aac-eld-source.json"}
RELEASE_NOTES_DIRECTORY = Path(__file__).resolve().parents[2] / "android" / "release-notes"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def release_changes(version):
    require(re.fullmatch(r"[A-Za-z0-9._-]+", version), "Invalid release version")
    path = RELEASE_NOTES_DIRECTORY / f"{version}.md"
    require(path.is_file(), f"Missing version-specific release notes: {path}")
    changes = path.read_text(encoding="utf-8").strip()
    require(changes, "Release notes must explain this version's changes")
    return changes


def local_assets(directory, commit):
    expected = PAYLOADS | {"SHA256SUMS"}
    require({path.name for path in directory.iterdir()} == expected, "Release must contain exactly the APK, encoder source, provenance and checksums")
    require(all((directory / name).is_file() and not (directory / name).is_symlink() for name in expected), "Release assets must be regular files")
    hashes = {name: hashlib.sha256((directory / name).read_bytes()).hexdigest() for name in expected}
    checksums = {}
    for line in (directory / "SHA256SUMS").read_text().splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  (?:\./)?([^/\\]+)", line)
        require(match is not None and match[2] in PAYLOADS and match[2] not in checksums, "Malformed or duplicate release checksum")
        checksums[match[2]] = match[1]
    require(checksums == {name: hashes[name] for name in PAYLOADS}, "Release checksums do not match its complete payload")
    source = json.loads((directory / "aac-eld-source.json").read_text())
    require(source["source_commit"] == commit and source["source_checkout_dirty"] is False,
            "Encoder provenance must describe this clean source commit")
    require(source["archive_sha256"] == hashes["aac-eld-source.tar.gz"], "Encoder provenance does not match its source archive")
    return {name: (hashes[name], (directory / name).stat().st_size) for name in expected}


def existing_assets(release, expected, marker, commit, tag):
    require(release["tag_name"] == tag and release["target_commitish"] == commit and marker in (release.get("body") or ""),
            "Refusing to alter a release not created for this exact source commit")
    require(not release["prerelease"], "Refusing to change an existing prerelease")
    names = set()
    for asset in release["assets"]:
        name = asset["name"]
        require(name in expected and name not in names, "Unexpected or duplicate existing release asset")
        digest, size = expected[name]
        require(asset["state"] == "uploaded" and asset["size"] == size and asset.get("digest") == f"sha256:{digest}",
                f"Existing release asset differs: {name}; nothing will be overwritten")
        names.add(name)
    require(release["draft"] or names == set(expected), "Published release is incomplete; refusing to modify it")
    return set(expected) - names


def promote_latest(status):
    require(status in ("ahead", "behind", "identical"), "Latest release has unrelated source history")
    return status != "behind"


def gh(*arguments):
    return subprocess.check_output(["gh", *arguments], text=True, encoding="utf-8").strip()


def api(path, missing_ok=False):
    result = subprocess.run(["gh", "api", "--method", "GET", path], capture_output=True, text=True, encoding="utf-8")
    if result.returncode:
        if missing_ok and str(json.loads(result.stdout or "{}").get("status")) == "404":
            return None
        raise RuntimeError(f"GitHub request failed: {path}: {result.stderr.strip()}")
    return json.loads(result.stdout)


def release_by_tag(tag):
    # The CLI also finds draft pending tags, which REST's by-tag endpoint cannot.
    result = subprocess.run(["gh", "release", "view", tag, "--repo", REPOSITORY, "--json", "apiUrl"],
                            capture_output=True, text=True, encoding="utf-8")
    if result.returncode:
        if result.stderr.strip() == "release not found":
            return None
        raise RuntimeError(f"Cannot inspect existing release: {result.stderr.strip()}")
    url = json.loads(result.stdout)["apiUrl"]
    require(re.fullmatch(re.escape(f"https://api.github.com/repos/{REPOSITORY}/releases/") + r"[0-9]+", url), "Unexpected release API URL")
    return api(url.removeprefix("https://api.github.com/"))


def publish(directory):
    require(os.environ["GITHUB_REPOSITORY"] == REPOSITORY, "Publishing is restricted to the signed fork")
    commit, tag, version = (os.environ[name] for name in ("RELEASE_COMMIT", "RELEASE_TAG", "APP_VERSION"))
    require(re.fullmatch(r"[0-9a-f]{40}", commit), "Release commit must be a full SHA")
    require(re.fullmatch(r"(?:v|pixel-)[A-Za-z0-9][A-Za-z0-9._-]*", tag), "Invalid release tag")
    require(re.fullmatch(r"[A-Za-z0-9._-]+", version), "Invalid release version")
    require(gh_git_commit() == commit, "Publishing checkout does not match the build")
    changes = release_changes(version)
    assets = local_assets(directory, commit)
    prefix = f"repos/{REPOSITORY}"
    marker = f"<!-- librepods-android-ci:{commit} -->"

    def check_tag(required=False):
        reference = api(f"{prefix}/git/ref/tags/{quote(tag, safe='')}", missing_ok=True)
        require(not required or reference is not None, "Published release tag is missing")
        if reference is not None:
            # Missing commit lookups return 422; resolve only existing refs, peeling annotated tags.
            tagged = api(f"{prefix}/commits/{quote(tag, safe='')}")
            require(tagged["sha"] == commit, "Release tag points to another commit; it will not be moved")

    check_tag()
    release = release_by_tag(tag)
    if release is None:
        with tempfile.TemporaryDirectory() as temporary:
            notes = Path(temporary) / "release-notes.md"
            notes.write_text(
                f"## LibrePods {version}\n\n"
                "Preview build with the full AAC-ELD / PCM32 encoder. Install **LibrePods.apk**; "
                "the existing signing key preserves updates and app data.\n\n"
                f"{changes}\n\n"
                "The matching modified encoder source, source provenance and SHA256SUMS are attached. "
                "Codec compatibility still depends on the Android version and device.\n\n"
                f"Source: https://github.com/{REPOSITORY}/commit/{commit}\n\n{marker}\n", encoding="utf-8")
            gh("release", "create", tag, "--repo", REPOSITORY, "--target", commit, "--draft",
               "--title", f"LibrePods {version} (preview)", "--notes-file", str(notes))
        release = release_by_tag(tag)
    missing = existing_assets(release, assets, marker, commit, tag)
    if missing:
        gh("release", "upload", tag, "--repo", REPOSITORY, *(str(directory / name) for name in sorted(missing)))
        release = release_by_tag(tag)
    require(not existing_assets(release, assets, marker, commit, tag), "Release upload is incomplete")
    check_tag(required=not release["draft"])

    latest = api(f"{prefix}/releases/latest", missing_ok=True)
    promote = True
    if latest is not None:
        latest_commit = api(f"{prefix}/commits/{quote(latest['tag_name'], safe='')}")["sha"]
        promote = promote_latest(api(f"{prefix}/compare/{latest_commit}...{commit}")["status"])
    if release["draft"] or promote:
        gh("release", "edit", tag, "--repo", REPOSITORY, "--draft=false", f"--latest={str(promote).lower()}")
    print(f"Published complete release: https://github.com/{REPOSITORY}/releases/tag/{tag}; latest={promote}")


def gh_git_commit():
    return subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()


def publication_self_test(directory, assets, commit, tag, marker):
    from unittest.mock import patch

    state = dict(release=None, tag=None, newer=False)
    writes = []

    def fake_api(path, missing_ok=False):
        if path.endswith(f"/git/ref/tags/{tag}"):
            assert missing_ok
            return {"object": {"type": "tag", "sha": "c" * 40}} if state["tag"] else None
        if path.endswith(f"/commits/{tag}"):
            assert not missing_ok
            if not state["tag"]:
                raise RuntimeError("GitHub returns 422 for missing commit refs")
            return {"sha": state["tag"]}
        if path.endswith("/releases/latest"):
            return {"tag_name": "newer"} if state["newer"] else None
        if path.endswith("/commits/newer"):
            return {"sha": "b" * 40}
        if "/compare/" in path:
            return {"status": "behind"}
        raise AssertionError(f"Unexpected request: {path}")

    def fake_gh(*arguments):
        action = arguments[1]
        writes.append(arguments)
        if action == "create":
            assert "--draft" in arguments and "--prerelease" not in arguments
            assert release_changes(environment["APP_VERSION"]) in Path(arguments[arguments.index("--notes-file") + 1]).read_text()
            state["release"] = dict(tag_name=tag, target_commitish=commit, draft=True, prerelease=False,
                                    body=Path(arguments[arguments.index("--notes-file") + 1]).read_text(), assets=[])
        elif action == "upload":
            for path in arguments[5:]:
                name = Path(path).name
                assert name not in {item["name"] for item in state["release"]["assets"]}
                digest, size = assets[name]
                state["release"]["assets"].append(dict(name=name, size=size, state="uploaded", digest=f"sha256:{digest}"))
        elif action == "edit":
            assert not existing_assets(state["release"], assets, marker, commit, tag)
            state["release"]["draft"], state["tag"] = False, commit
        else:
            raise AssertionError(f"Unexpected mutation: {arguments}")
        return ""

    environment = dict(GITHUB_REPOSITORY=REPOSITORY, RELEASE_COMMIT=commit, RELEASE_TAG=tag, APP_VERSION="2.0.0-alpha.1-pixel115")
    with patch.dict(os.environ, environment), patch(__name__ + ".gh_git_commit", return_value=commit), \
            patch(__name__ + ".gh", side_effect=fake_gh), patch(__name__ + ".api", side_effect=fake_api), \
            patch(__name__ + ".release_by_tag", side_effect=lambda _: state["release"]), patch("builtins.print"):
        publish(directory)
        assert [arguments[1] for arguments in writes] == ["create", "upload", "edit"]
        assert "--latest=true" in writes[-1]
        writes.clear()
        publish(directory)
        assert [arguments[1] for arguments in writes] == ["edit"]  # Exact retry never overwrites assets.
        state["release"]["draft"], state["newer"] = True, True
        state["release"]["assets"].pop()
        writes.clear()
        publish(directory)
        assert [arguments[1] for arguments in writes] == ["upload", "edit"]
        assert len(writes[0][5:]) == 1 and "--latest=false" in writes[-1]
        writes.clear()
        publish(directory)
        assert not writes  # An older completed retry cannot change the newer Latest.
        state["tag"] = "b" * 40
        writes.clear()
        try:
            publish(directory)
        except ValueError:
            assert not writes
        else:
            raise AssertionError("Conflicting tag was accepted")


def self_test():
    from unittest.mock import patch

    def rejects(action):
        try:
            action()
        except (ValueError, KeyError):
            return
        raise AssertionError("Unsafe release input was accepted")

    commit, tag = "a" * 40, "v2.0.0-alpha.1-pixel115-aaaaaaaaaaaa"
    marker = f"<!-- librepods-android-ci:{commit} -->"
    assert promote_latest("ahead") and promote_latest("identical") and not promote_latest("behind")
    rejects(lambda: promote_latest("diverged"))
    with tempfile.TemporaryDirectory() as temporary, tempfile.TemporaryDirectory() as notes_temporary, \
            patch(__name__ + ".RELEASE_NOTES_DIRECTORY", Path(notes_temporary)):
        directory = Path(temporary)
        version = "2.0.0-alpha.1-pixel115"
        notes = RELEASE_NOTES_DIRECTORY / f"{version}.md"
        rejects(lambda: release_changes(version))
        notes.write_text(" \n", encoding="utf-8")
        rejects(lambda: release_changes(version))
        notes.write_text("## Changes\n\n- Confirm AirPods settings without disconnecting.\n", encoding="utf-8")
        assert release_changes(version) == notes.read_text().strip()
        rejects(lambda: release_changes("../outside"))
        rejects(lambda: release_changes("2.0.0-alpha.1-pixel116"))
        (directory / "LibrePods.apk").write_bytes(b"signed APK fixture")
        (directory / "aac-eld-source.tar.gz").write_bytes(b"source fixture")
        source = dict(source_commit=commit, source_checkout_dirty=False,
                      archive_sha256=hashlib.sha256(b"source fixture").hexdigest())
        (directory / "aac-eld-source.json").write_text(json.dumps(source))
        checksums = "".join(f"{hashlib.sha256((directory / name).read_bytes()).hexdigest()}  ./{name}\n" for name in sorted(PAYLOADS))
        (directory / "SHA256SUMS").write_text(checksums)
        assets = local_assets(directory, commit)
        publication_self_test(directory, assets, commit, tag, marker)
        release = dict(tag_name=tag, target_commitish=commit, body=marker, prerelease=False, draft=True, assets=[])
        assert existing_assets(release, assets, marker, commit, tag) == set(assets)
        release["assets"] = [dict(name=name, state="uploaded", size=size, digest=f"sha256:{digest}")
                             for name, (digest, size) in assets.items()]
        assert not existing_assets(release, assets, marker, commit, tag)
        release["draft"] = False
        assert not existing_assets(release, assets, marker, commit, tag)
        for field, value in (("body", "unrelated release"), ("target_commitish", "b" * 40), ("prerelease", True),
                             ("tag_name", "v-unrelated"), ("assets", release["assets"][:-1]),
                             ("assets", release["assets"] + [release["assets"][0]])):
            rejects(lambda field=field, value=value: existing_assets({**release, field: value}, assets, marker, commit, tag))
        release["assets"][0]["digest"] = "sha256:" + "0" * 64
        rejects(lambda: existing_assets(release, assets, marker, commit, tag))
        rejects(lambda: local_assets(directory, "b" * 40))
        (directory / "SHA256SUMS").write_text(checksums + checksums.splitlines()[0] + "\n")
        rejects(lambda: local_assets(directory, commit))
        (directory / "SHA256SUMS").write_text(checksums)
        (directory / "LibrePods.apk").write_bytes(b"changed APK")
        rejects(lambda: local_assets(directory, commit))
        (directory / "unexpected-key.jks").write_bytes(b"never publish")
        rejects(lambda: local_assets(directory, commit))
    print("Android release publication checks passed")


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    elif len(sys.argv) == 3 and sys.argv[1] == "--check-notes":
        release_changes(sys.argv[2])
        print(f"Release notes ready: {sys.argv[2]}")
    elif len(sys.argv) == 2:
        publish(Path(sys.argv[1]))
    else:
        raise SystemExit("Usage: publish_android_release.py ARTIFACT_DIRECTORY | --self-test | --check-notes VERSION")
