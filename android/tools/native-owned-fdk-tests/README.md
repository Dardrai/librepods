# Owned AAC-ELD encoder and quality tests

This standalone project normally builds the unmodified S16 FDK encoder and a small typed
adapter, `eld/owned_fdk_encoder.cpp`. It does not inspect Android's private FDK
symbols or depend on a particular phone build. Normal LibrePods APKs use the
same owned encoder source with PCM32/Q31 enabled. Debug and Release app variants
both use optimized native Release builds, retain the captured Game Mode flush
baseline and keep verbose native tracing off.

The shared `eld/owned_fdk.cmake` file owns the source pin and builds FDK as a
hidden static target without changing the parent's shared-library options.
The adapter returns the existing `EldFdkApi` table. Its caller must still use
`eld_fdk_open`, `eld_fdk_update`, `eld_fdk_reset`, `eld_owned_fdk_encode` and
`eld_fdk_close`. Those functions verify every encoder parameter, actual ASC,
480-frame stereo consumption at the declared PCM width and the negotiated AU bound. Populate
`EncoderHost.fdk` with `eld_owned_fdk_api()` for the owned route; keep the
existing platform-bound Note8 binding unchanged. Each encoder is owned by
one thread; separate encoders may run on separate threads. The module must stay
loaded until all its encoders are closed.

## Reproduce without a phone

```sh
cmake -S android/tools/native-owned-fdk-tests -B build/owned-fdk-s16 -DCMAKE_BUILD_TYPE=Release
cmake --build build/owned-fdk-s16 -j4
ctest --test-dir build/owned-fdk-s16 --output-on-failure
cmake -S android/tools/native-owned-fdk-tests -B build/owned-fdk-q31 -DCMAKE_BUILD_TYPE=Release -DLIBREPODS_PRIVATE_WIDE_PCM=ON
cmake --build build/owned-fdk-q31 -j4
ctest --test-dir build/owned-fdk-q31 --output-on-failure
```

For PCM32 coverage, configure a separate build directory with
`-DLIBREPODS_PRIVATE_WIDE_PCM=ON`. It copies the pinned source into that build
directory and enables FDK's existing signed Q31 input/output paths. The original
S16 source cache is not modified. `EldOwnedPcm`, the API table, buffer element
width and configured frame consumption must agree; a mismatched S16/Q31 call is
rejected before input is read. The platform-bound Note8 encoder remains S16.
The app requires `LIBREPODS_PRIVATE_OWNED_FDK=ON` for this option. Normal app
builds enable both options; their legacy names do not identify a separate private
APK. The standalone host suite defaults to S16 so CI can exercise both widths.

The Pixel variant requests signed PCM32 from the software Bluetooth provider,
returns 32 from the native track-width getter, and consumes 3,840 bytes for each
480-frame stereo block. This is integer Q31, not Float32. It must be verified
against the actual phone provider before listening tests; a wider app source or
encoder alone cannot recover precision already discarded by an S16 provider.

For an offline build, pass
`-DLIBREPODS_FDK_ARCHIVE=/absolute/path/to/fdk-aac-cac04476081a23c870fed05c36228cd02c5e7c3d.zip`.
The archive is rejected unless its SHA-256 matches
`1b572fcb5e2a81aa0447a92ca95bfe4979f3edfc09918e0627b726bcd0165ff1`.
Otherwise CMake fetches the same source from
[upstream revision cac04476081a23c870fed05c36228cd02c5e7c3d](https://github.com/mstorsjo/fdk-aac/tree/cac04476081a23c870fed05c36228cd02c5e7c3d).

For Android compile checks, add the NDK toolchain, `ANDROID_ABI=arm64-v8a`,
`ANDROID_PLATFORM=android-33` and `ANDROID_STL=c++_static`. The public NDK shim
logs FDK security events through logcat and changes no FDK source. This test
program neither plays nor records audio and makes no Bluetooth calls.

The tests encode 128, 192 and 256 kbps streams, check output boundaries and
deterministic reset, reject invalid inputs without consuming encoder state,
verify update/close isolation, and compare concurrent owners against serial
references. The separate `owned_fdk_quality_test` passes eight seconds of
synthetic stereo through the production wrapper and decodes every raw AU with
the same pinned FDK library. It aligns the decoded samples using a noise window,
then checks channel separation, gain, four levels of the same tones, peaks,
transients, and frequency response through 17.5 kHz. No Python, FFT package,
audio device, private device data or saved recording is needed.

The 256 kbps profile explicitly selects 20 kHz bandwidth and enables FDK's
afterburner quality pass. Lower bitrates retain automatic bandwidth. All rates
retain 480-sample stereo frames and their existing peak/AU limits. The PCM32
variant adds a ninth second containing separate left/right tones below half an
S16 quantization step. The real encode/decode test checks that those tones keep
their level and waveform; truncating the input to S16 makes that check fail.
The original gain, channel separation, frequency, transient and peak checks run
at both widths. The source precision change does not make AAC-ELD lossless or
establish the AirPods' acoustic resolution.
The quality test fails with the old quality pass disabled or automatic 16 kHz
bandwidth, and with deliberately halved input or compressed peaks. It prints
host encoding times without treating desktop performance as a phone deadline
guarantee. CI runs both tests at S16 and Q31 widths, then verifies the actual APK
native build contract, encoder notices and signed artifact. Synthetic audio
fixtures are not published.

A successful compile or host decode is not evidence of phone throughput,
perceived sound quality or AirPods compatibility. The same-library decoder is
not an independent decoder implementation. The existing private Pixel synthetic
encode/decode capture is separate evidence and predates these quality changes.

## Build and license boundary

The adapter, compatibility shim and test code are MIT licensed under
[LICENSE](LICENSE). The common validation wrapper and app are GPLv3, and FDK
retains its own [custom license](https://raw.githubusercontent.com/mstorsjo/fdk-aac/master/NOTICE).
That license requires complete notices and free availability of the complete
FDK source and modifications, including prominent dated modification notices;
it grants no patent license.

Normal APKs now include this encoder under the fork maintainer's explicit
distribution decision. The known GPL/FDK compatibility issue remains:
[FFmpeg's license guidance](https://raw.githubusercontent.com/FFmpeg/FFmpeg/master/LICENSE.md)
identifies FDK as incompatible with GPLv2 and GPLv3, and the
[GNU guidance](https://www.gnu.org/licenses/gpl-faq.en.html#GPLIncompatibleLibs)
explains that a linking exception must come from the relevant copyright holders.
This build claims no new exception. Notices and source availability do not
resolve that compatibility issue or relicense upstream code.

Each APK's `assets/aac-eld/` directory contains `NOTICE-FDK.txt`,
`NOTICE-owned-fdk-MIT.txt` and `NOTICE-FDK-modifications.txt`. CI's `apk-debug` and
signed release artifacts place `aac-eld-source.tar.gz`, `aac-eld-source.json`
and `SHA256SUMS` alongside the APK. The archive contains the complete linked,
Q31-modified FDK source and owned wrapper; the JSON records the app commit,
upstream pin, source hashes and build settings. CMake also retains the notices
in each build's `licenses/` directory. No Apple binaries, device libraries,
keys, captures or previously compiled phone libraries are build inputs.

The static encoder is PIC and its FDK symbols stay hidden with
`-Wl,--exclude-libs,ALL` so they do not interpose with Android's Bluetooth stack.
Both the standalone tests and app use this rule. Normal native app builds enable
`LIBREPODS_PRIVATE_OWNED_FDK=ON` and `LIBREPODS_PRIVATE_WIDE_PCM=ON`; runtime
opt-in, image checks, selected-peer validation and failure fallback remain
independent of including the encoder in the APK.
