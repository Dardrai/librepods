# Pinned Note8 native FDK probe

Separate command-line process, synthetic PCM only. It does not call JNI_OnLoad,
Bluetooth, recording, playback, hooks or the LibrePods app. A normal linker load
still executes the library's ELF constructors; use only the pinned trusted image.
No dependency or linker namespace bypass is attempted. An inaccessible private
library/dependency is a diagnostic failure, not permission to try another image.

The exact13,786,880-byte `libbluetooth_jni.so` is pinned before loading:
SHA256 `d965f16cba9b9f103d657844eae618ac13b915581da36a4d7720995229e114a7`.
The backend binding additionally checks six exports' common image base, exact
offsets and32-byte prologues. The supplying image must remain loaded until all
encoder handles are closed. Unknown libraries are rejected.

The public FDK struct declarations follow AOSP13 `platform/external/aac` commit
`2474d98134b5fdf5d4d344bbc19a9ec070a06e58`:

- [`machine_type.h:226–230`](https://android.googlesource.com/platform/external/aac/+/2474d98134b5fdf5d4d344bbc19a9ec070a06e58/libSYS/include/machine_type.h#226): `INT_PCM` is signed16.
- [`aacenc_lib.h:1062–1134`](https://android.googlesource.com/platform/external/aac/+/2474d98134b5fdf5d4d344bbc19a9ec070a06e58/libAACenc/include/aacenc_lib.h#1062): info96bytes, buffer descriptor40bytes on AArch64, input args8bytes, output args16bytes.
- Exact pinned binary: `aacEncInfo` at`0x712a0c` clears96bytes; input channels at+12, frame length+16, ASC+28, ASC size+92. `aacEncEncode` at`0x7117b0` loads element-size array at descriptor+32, compares to2 at`0x7117d4`, and uses signed16→signed16 deinterleave at`0x7118ac`. Wider input uses32→16 at`0x71189c`. These match the public header, not the separately patched private Q31 build.

Each encoder handle must report480frames,2channels, ASC`f8e65000` and matching
actual AOT39/48k/480/SBRoff/raw/CBR/bitrate/peak settings. Signed16 input has exactly
960interleaved samples per call. Input consumption must be960, ancillary0 and AU
size within `(bitrate+799)/800+1`; errors poison the encoder until reset. Updates
and resets validate a replacement handle before swapping. No STL crosses the C API.

The probe tests128/192/256k,64blocks each, with100ms initial silence then two
distinct low-amplitude synthetic tones. It records actual configuration, encoded
AU sizes and time spent. No EOS flush is sent to a live encoder; this tool also
does not flush delayed tail samples. A15second alarm terminates only this probe
process, including a blocked native call. This is not a benchmark under Bluetooth
load and does not prove decoding, A2DP negotiation, media playback or audible quality.

## Offline build

Use an installed CMake, NDK and host C++ compiler. No Gradle/module wiring or
dependency downloads. All generated artifacts go into a new private output path.

```text
cmake -S android/tools/native-fdk-probe -B <new-host-build> -G "Visual Studio 17 2022" -A x64
cmake --build <new-host-build> --config Release
<new-host-build>/Release/fdk_encoder_test.exe <cached-pinned-libbluetooth_jni.so>

cmake -S android/tools/native-fdk-probe -B <new-arm64-build> -G Ninja -DCMAKE_TOOLCHAIN_FILE=<NDK>/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release
cmake --build <new-arm64-build>
```

The host tests use mock function pointers, verify exact input descriptors and
failure/poison/replacement behavior, and optionally check the cached image SHA
using the repo's existing XZ SHA256 implementation. No system codec runs on host.

## Later explicit runtime command

Only the hardware owner transfers/runs it after authorization. Do not inject it
into the Bluetooth process as a fallback for namespace rejection.

```text
/data/local/tmp/eld_fdk_probe <actual-pinned-system-library-path> /data/local/tmp/<new-synthetic-output>.aus
```

Capture stdout as newline-delimited JSON and preserve the process exit status.
File-pin rejection returns2, namespace/load3, symbol/ABI4, configuration/reset5,
encode6, file-output7. All three encode results plus final completion must succeed.
The output path must not exist. Format is ASCII`FDKPROBE1`, verified four-byte ASC,
then repeated little-endian u32 bitrate, u32 input-block index, u32 AU length and
raw synthetic AUbytes. There is no RTP or Apple transport framing in this file.
