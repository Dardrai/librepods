# AAC-ELD MediaCodec probe

Isolated research APK, not a LibrePods codec implementation. It has no Activity,
permissions, audio playback/recording, network or Bluetooth calls. It requires no
AirPods. Nothing is registered with A2DP, Xposed or the normal application.

## What it measures

An explicitly started instrumentation run enumerates non-alias AAC components and
tries at most eight AAC encoders, including components which omit ELD from their
advertised profiles. Each is configured using public keys: AAC-ELD profile39,
48kHz stereo PCM16, 256kbit/s CBR, SBR mode0. The input is one second of bounded,
distinct997/1499Hz synthetic stereo tones; samples are never played.

The probe queues480-sample input chunks **without claiming this selects480-sample
codec frames**. It inspects the emitted codec-specific data, records each encoded
buffer's size/PTS/hash, and decodes that component's own output using its actual
ASC. It records decoded format, frame-count histogram, PCM duration/hash and
nonzero samples. Full synthetic encoded buffers are retained for offline analysis.

Required Apple core contract: AOT39,48kHz,2channels,480samples/10ms, SBRoff,
ASC`f8e65000`, raw encoded AU limit321bytes at256kbit/s. `f8e64000` describes the
512-sample alternative (10.666…ms), not a compatible480-sample stream. The parser
reads only the ELD core header and only calls the exact four-byte ASC a match;
unfamiliar extensions are never rewritten. AAC-LC fallback is a mismatch.

`matches_observed_core_contract` additionally requires at least16 encoded buffers,
10ms PTS steps, all nonempty decoder buffers480frames, stereo48kPCM and nonzero
decoded audio. This conservative diagnostic may reject decoder buffer batching.
It proves neither real-time performance nor AVDTP registration/Apple playback.
Encoder delay/EOS padding affect aggregate decoded duration; a total of precisely
48000 output frames is not assumed. PTS alone is insufficient evidence.

Rejection of the requested profile/CBR/SBR combination is recorded as rejection,
not absence of an AAC-ELD implementation. No undocumented frame-size key is used,
and the desired ASC is never injected into the encoder.

## Primary-source finding (Android13)

All AOSP references below are pinned to `android-13.0.0_r1`:

- [`MediaCodecInfo.java:3935`](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/media/java/android/media/MediaCodecInfo.java#3935): `AACObjectELD=39`.
- [`MediaFormat.java:815–835`](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/media/java/android/media/MediaFormat.java#815): public AAC profile and encoding-only SBR mode0/1/2. The public format has no AAC granule-length key.
- [`C2SoftAacEnc.cpp:222–305`](https://android.googlesource.com/platform/frameworks/av/+/refs/tags/android-13.0.0_r1/media/codec2/components/aac/C2SoftAacEnc.cpp#222) and [`SoftAACEncoder2.cpp:403–485`](https://android.googlesource.com/platform/frameworks/av/+/refs/tags/android-13.0.0_r1/media/libstagefright/codecs/aacenc/SoftAACEncoder2.cpp#403): map ELD and SBRoff to FDK, but never set `AACENC_GRANULE_LENGTH`.
- [`aacenc_lib.cpp:969–970`](https://android.googlesource.com/platform/external/aac/+/refs/tags/android-13.0.0_r1/libAACenc/src/aacenc_lib.cpp#969): bundled FDK defaults ELD to512 when `userFramelength` is unset. Its default is initialized at677; explicit granule-length handling starts2257.
- [`FDK transport ASC writer`](https://github.com/mstorsjo/fdk-aac/blob/cac04476081a23c870fed05c36228cd02c5e7c3d/libMpegTPEnc/src/tpenc_asc.cpp#L740) maps512→frameLengthFlag0,480→1 and stores SBR presence separately. This is the pinned private portable encoder's upstream revision.

Thus the stock AOSP software path is expected to emit512, despite accepting AOT39.
This is a source-level expectation, not a measurement of any particular device or
vendor component. Public `KEY_MAX_INPUT_SIZE` would not select a granule length.
The private FDK port explicitly sets480 and validates its actual ASC; these are
different encoder paths. The normal app's existing ELD decoder is mono AACP
microphone input (`f8e63000`) with a separate64k physical clock; it is not a stereo
A2DP encoder and is not reused as one.

## Offline build and checks (PowerShell, no Gradle)

Requires PowerShell7, an installed JDK and Android SDK build-tools36.0.0/platformandroid-36.
Versions can be passed as parameters. No dependency download is performed.
Use a new output directory outside this tool; previous evidence is not overwritten.

```powershell
$env:JAVA_HOME = '<JDK directory>'
$env:ANDROID_HOME = '<Android SDK directory>'
./build.ps1 -OutputDirectory '<private outputs directory>'
# Optional: sign only with the standard Android debug keystore:
./build.ps1 -OutputDirectory '<another private outputs directory>' -DebugKeystore '<standard debug.keystore>'
```

The build runs pure-Java host contract checks (ASC480/512/mono/SBR/malformed,
synthetic bounds and timestamps), then compiles the Android harness with
`javac`, dexes with`d8`, packages with`aapt2` and aligns with`zipalign`.
`receipt.json` records host results, source hashes and APK hash. This compile does
not enumerate or execute a device codec. Signing is optional and never uses the
LibrePods release key. The probe's package/signature is independent of LibrePods.

## Later explicit runtime invocation (not executed during the offline build)

Only run these after separate authorization for device access. Disconnecting or
pairing AirPods is unnecessary. There is no startup receiver or automatic action.

```text
adb install <aac-eld-probe-debug.apk>
adb shell am instrument -w me.kavishdevar.librepods.eldprobe/me.kavishdevar.librepods.eldprobe.EldProbe
adb shell run-as me.kavishdevar.librepods.eldprobe ls files
adb shell run-as me.kavishdevar.librepods.eldprobe cat files/<probe-timestamp>/report.json
```

Instrumentation prints its exact report path. A completed instrumentation run
does not itself mean the codec met the contract; inspect every encoder result.
Each polling operation has a10second deadline and the run a60second polling
budget. Output is bounded (256buffers,256KiB encoded data per attempt, limited PCM).
`configure`, native component calls and`stop/release` can block inside vendor code;
the Java polling deadlines cannot forcibly interrupt those calls. A future runner
must be able to stop this isolated probe process if it hangs.

`N.aus` format: ASCII`ELDPROBE1`, big-endian32-bit ASC length, ASCbytes,
big-endian32-bit buffer count, then for each buffer big-endian32-bit size,
big-endian64-bit signed PTSmicroseconds, raw bytes. These are synthetic buffers,
not an Apple transport stream. No profile, microphone or user-media data is read.
