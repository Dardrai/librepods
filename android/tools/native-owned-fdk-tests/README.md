# Owned AAC-ELD encoder experiment

This standalone project builds the unmodified S16 FDK encoder and a small typed
adapter, `eld/owned_fdk_encoder.cpp`. It does not inspect Android's private FDK
symbols or depend on a particular phone build. The default LibrePods APK does
not include this project or link FDK.

The shared `eld/owned_fdk.cmake` file owns the source pin and builds FDK as a
hidden static target without changing the parent's shared-library options.
The adapter returns the existing `EldFdkApi` table. Its caller must still use
`eld_fdk_open`, `eld_fdk_update`, `eld_fdk_reset`, `eld_fdk_encode` and
`eld_fdk_close`. Those functions verify every encoder parameter, actual ASC,
480-frame stereo S16 consumption and the negotiated AU bound. Populate
`EncoderHost.fdk` with `eld_owned_fdk_api()` for an explicitly opted-in private
experiment; keep the existing Note8 binding unchanged. Each encoder is owned by
one thread; separate encoders may run on separate threads. The module must stay
loaded until all its encoders are closed.

## Reproduce without a phone

```sh
cmake -S android/tools/native-owned-fdk-tests -B build/owned-fdk -DCMAKE_BUILD_TYPE=Release
cmake --build build/owned-fdk -j4
ctest --test-dir build/owned-fdk --output-on-failure
```

For an offline build, pass
`-DLIBREPODS_FDK_ARCHIVE=/absolute/path/to/fdk-aac-cac04476081a23c870fed05c36228cd02c5e7c3d.zip`.
The archive is rejected unless its SHA-256 matches
`1b572fcb5e2a81aa0447a92ca95bfe4979f3edfc09918e0627b726bcd0165ff1`.
Otherwise CMake fetches the same source from
[upstream revision cac04476081a23c870fed05c36228cd02c5e7c3d](https://github.com/mstorsjo/fdk-aac/tree/cac04476081a23c870fed05c36228cd02c5e7c3d).

For Android compile checks, add the NDK toolchain, `ANDROID_ABI=arm64-v8a`,
`ANDROID_PLATFORM=android-26` and `ANDROID_STL=c++_static`. The public NDK shim
logs FDK security events through logcat and changes no FDK source. This test
program neither plays nor records audio and makes no Bluetooth calls.

The tests encode 128, 192 and 256 kbps streams, check output boundaries and
deterministic reset, reject invalid inputs without consuming encoder state,
verify update/close isolation, and compare concurrent owners against serial
references. A successful compile is not evidence of phone throughput or AirPods
compatibility. The existing private Pixel synthetic encode/decode capture is
separate evidence.

## Build and license boundary

The new adapter, compatibility shim and test code are MIT licensed under
[LICENSE](LICENSE). The common validation wrapper used by the test is GPLv3,
and FDK has its own custom license. The combined test binary is for private
experiments: **do not link FDK into the normal GPL LibrePods APK or redistribute
this combined binary without resolving the license incompatibility**. The
existing NTPods encoder licensing note documents the separate worker approach;
process separation by itself does not establish compatibility.

FDK's complete upstream `NOTICE` is copied into `build/owned-fdk/licenses/NOTICE-FDK.txt`.
The downloaded archive and extracted source remain in CMake's private build
directory. FDK's license requires its notice with source and binary copies and
complete source availability to recipients; it grants no patent license.
Preserve that source and notice if packaging an allowed artifact. No Apple
binaries, device libraries, keys or captures are inputs to this build.

The static encoder is PIC and its symbols have hidden visibility. Any future
shared-module target must also use `-Wl,--exclude-libs,ALL` so its FDK symbols do
not interpose with Android's Bluetooth stack. The isolated test target applies
that flag already. The app's native CMake can be explicitly opted into a private
experiment using `-DLIBREPODS_PRIVATE_OWNED_FDK=ON`; it then defines
`ELD_PRIVATE_OWNED_FDK=1` and applies the same hidden-link rules. This option is
off by default, so normal builds neither fetch nor link FDK. An opt-in does not
make that combined GPL/FDK APK redistributable.
