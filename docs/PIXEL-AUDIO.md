# Pixel playback, microphone and sensors

This fork adds nearby pairing, battery freshness, requested-versus-reported
settings, AAC-ELD microphone tests, optional Shizuku microphone routing, and
heart-rate/movement sessions to LibrePods' main Android application.

Ordinary controls and Android Bluetooth playback work without Shizuku on
supported firmware. Experimental routing requires Shizuku started through ADB
and permission granted to LibrePods. Start Shizuku again after a phone reboot.

## Using the features

Open the case to show the nearby card. Select Connect and follow the case's
pairing gesture. One stable discoverable AirPods candidate pairs automatically;
multiple matching candidates need a selection. Android can still request pairing
confirmation. The public advertisement identifies a model, not a trusted Classic
address. Closing the lid cancels the invitation. Renamed or ambiguous devices can
still be paired in Android Bluetooth settings.

Open Microphone in the AirPods settings for Playback and microphone. Connect
Shizuku, then select Check audio support. Play a recognizable test sound in
another app during the playback-copy check. Diagnostics do not save captured PCM
or initiate calls; use Copy report to share the result explicitly.

Microphone tests require Shizuku for selected-device playback recovery. Record
a microphone test first. Test recordings are private WAV files, stop after
one minute, and can be played or shared explicitly. Compare spoken pitch and
recording duration with the elapsed capture time before relying on the microphone.
An initialized decoder or a quiet stopped stream does not establish calibrated
audio or healthy stereo playback.

The AAC-ELD configuration describes 48 kHz coding, but the Pro 3 microphone's
physical PCM clock is 64 kHz: each 480-sample unit lasts 7.5 ms. WAV headers and
Shizuku injection tracks use that physical rate; Android handles conversion for
playback and receiving apps. Changing the decoder's AAC configuration to 64 kHz
would describe a different bitstream.

To test another app, enter its exact package name and select Use microphone in
selected app. Start recording in that app and verify the AirPods are the source.
For calls, start a deliberate call, select Use microphone for this call, and ask
the remote listener to verify the source. These routes are separate tests; a
successful recorder test does not establish VoIP or cellular-call support.
Call microphone PCM is processed live and is not saved by LibrePods.

Use Stop before changing routes. Bluetooth loss, revoked microphone access or
Shizuku loss ends the experimental session. Check ordinary playback and the native
microphone afterwards. A recovery error requires reconnecting the AirPods.

Heart rate and raw movement are optional controls on the main settings page for
supported models. They stop on disconnect and pause during microphone capture.
Movement data is not a spatial-audio renderer. Unknown models do not inherit
Pro 3 sensor or microphone support.

## What the diagnostics establish

Each operation reports proven, blocked or untested with its reason. Stream
allocation alone is not proof that the intended app or remote caller receives
the injected microphone. Keep the Android fingerprint, Bluetooth module version,
AirPods model/firmware and tested receiving app with a result.

Playback remains Android's native Bluetooth route. This fork does not advertise
AAC-ELD stereo playback merely because a codec exists. The encoder must produce
Apple-compatible 480-sample, 48 kHz stereo access units, and the application must
own usable AVDTP signaling/media channels. Stock Android's native AV profile can
reserve those channels; a shell permission does not remove that reservation.

No standalone 60 ms receiver-buffer toggle is enabled. NTPods' coordinated policy
requires delay reports, producer-clock control and controller flush handling,
plus explicit restoration. Its 60 ms request is not a measured end-to-end latency.

## Development and hardware checks

The initial Pixel 10 Pro XL check used Android 17 / SDK 37,
`CP3A.260905.009` (security patch 2026-09-05), with Shizuku's shell backend.
Playback copying delivered nonzero stereo PCM while music was playing; both
temporary AudioPolicies were unregistered afterwards. A UID-scoped microphone
injection policy and AudioTrack allocated at 64 kHz mono and released successfully.
The call API reported PSTN interception
support, with no call running. Neither result establishes a receiving app or
remote caller's microphone source.

The native stack rejected the PSM `0x19` socket probe with `IOException: Error: -1`.
The Bluetooth module was `com.google.android.bluetooth` version 17 with APEX
`com.google.android.bt` version 371899999.

The ELD encoder produced 48 kHz stereo **512-sample** frames, so it failed the
480-sample Apple playback requirement. Native Bluetooth playback remains enabled.
The signed, minified application connected to the paired Pro 3 and Shizuku.
Live capture produced decoded PCM. A 60.006-second capture contained 79.520 seconds
of samples when interpreted at 48 kHz, corroborating the 64 kHz physical clock
used by NTPods. A later build labels the actual PCM rate accordingly; recording
pitch and receiving-app conversion still require their own acceptance checks.

During the first microphone UI test, one earbud stopped playing; closing the case
restored both earbuds. The activity binding lifetime and selected-pair A2DP recovery
were fixed. A subsequent one-minute capture stopped its uplink and completed the
selected-pair recovery in about two seconds. That profile cycle can pause music;
resume it explicitly. A shared Compose list recomposition defect also hid the
conditional Stop and Play rows; a runtime regression reproduces and verifies its
small inline fix. Physical pairing, pitch, sensors, call recipients and extended
recovery still need their respective hardware checks.

Build the FOSS application with JDK 21 and the SDK/NDK versions pinned in Gradle:

```sh
cd android
./gradlew verifyPixel
```

Packet and state tests cover malformed input, per-pair identity, partial battery
updates, control reports, descriptor remapping, audio framing and session ownership.
Before promoting a build, check lid cancellation, two same-model pairs, selected
device changes, manual volume changes during Conversation Awareness, permission
revocation, Shizuku death, app termination and normal routing recovery.

Audio acceptance includes a recorder, a game, VoIP and cellular calls separately;
confirm the source with a remote listener for calls. Run 30 minutes of playback
and duplex capture plus 20 microphone start/stop cycles. Numerical latency claims
require an external timing measurement with a calibrated reference.

## Protocol sources

- NTPods `fb8fa01d3ef8ef2a7a9240dad881000202dfa0a5`: protocol/state handling,
  packet validation, microphone lifecycle and descriptor-driven sensors.
- LibrePods Android rewrite `34076c85f0fc2eaf32ad1d5cad1bb7315df9c89c`:
  AACP microphone packet/decoder research and Android implementation reference.
- LibrePods main `efccc4499b2c1fd2ac243e975ae1f42d8e828ffd`: existing Android
  application, controls, Bluetooth and interface.

GPL notices remain with the implementation. Windows drivers and FDK-AAC worker
binaries are not included; Android MediaCodec supplies the codec implementation.
