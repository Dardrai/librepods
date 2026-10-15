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
physical PCM clock is 64 kHz: each 480-sample unit lasts 7.5 ms. WAV headers use
that physical rate. Shizuku injection uses a 48 kHz policy mix and sets the track's
effective playback rate to 64 kHz, letting Android convert the source samples.
The remote-submix driver does not support a 64 kHz mix directly. Changing the
decoder's AAC configuration to 64 kHz would describe a different bitstream.

To test another app, enter its exact package name and select Use microphone in
selected app. Start recording in that app and verify the AirPods are the source.

Call routing currently implements experimental microphone uplink injection only.
For the tested Pixel cellular-call setup, turn off Phone calls for the selected
AirPods in Android Bluetooth settings, leaving Media audio on. Select the phone's
speaker and keep the call unmuted. Then select Use microphone for this call and
ask the remote listener to verify the AirPods microphone with the phone away from
you. Speaker alone did not suffice while the native headset profile remained
connected. Stop the experimental route and restore Phone calls after the test.
The app currently requires this manual setup; it does not disconnect that profile
automatically. The injection API does not guarantee that the phone microphone is
excluded from the call, and muting the call stops the experimental session.
Full duplex through
the AirPods, including caller audio over A2DP, remains unverified: Android may
suspend A2DP during a cellular call, and a downlink capture alone does not establish
that phone output is silent. See the [Android call APIs](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/AudioManager.java)
and [native call routing and A2DP suspension](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/services/audiopolicy/managerdefault/AudioPolicyManager.cpp).
A successful recorder test does not establish VoIP or cellular-call support.
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
injection policy and AudioTrack allocated successfully. A synthetic default-input
recorder test then verified both ends used the same remote-submix device. It
exposed an 8 kHz driver fallback when registering a 64 kHz mix: a 6 kHz test tone
was lost. A 48 kHz mix with the track's effective rate set to 64 kHz preserved
both 500 Hz and 6 kHz tones and the source duration. These tests used synthetic
samples only; no user recordings were read or exported.
The call API reported PSTN interception support. Its input format is limited to
48 kHz, so the call path uses the same checked 64 kHz source-rate conversion.
A subsequent real cellular call on pixel2 verified audible AirPods uplink at
normal pitch with a remote listener. The first attempt, with Speaker selected
and SCO inactive but HFP still connected, delivered no PCM and stopped after
five seconds. Disabling only this pair's Phone calls profile disconnected HFP;
the retry delivered 129.330 seconds of PCM over 140.217 seconds elapsed, including
2.367 seconds before the first packet. The user confirmed clear AirPods input
while the phone was placed away from them. Stop released the route and completed
selected-pair recovery. After the call, Phone calls was restored and Android
returned to normal audio mode with no active recording configuration or microphone
foreground-service type. No call audio was saved or exported. This establishes
AirPods uplink contribution for that setup, not exclusive input, VoIP support,
AirPods downlink, full duplex or long-call reliability.

The native stack rejected the local PSM `0x19` listening-socket probe with
`IOException: Error: -1`. This does not establish whether outbound AVDTP can work.
The Bluetooth module was `com.google.android.bluetooth` version 17 with APEX
`com.google.android.bt` version 371899999.

The tested platform ELD encoder configuration produced 48 kHz stereo
**512-sample** frames, so it failed the 480-sample Apple playback requirement.
An Android software encoder has not been tested. Native Bluetooth playback remains enabled.
The signed, minified application connected to the paired Pro 3 and Shizuku;
77 unit tests, debug lint, debug assembly and minified release assembly passed.
Live capture produced decoded PCM. A 60.006-second capture contained 79.520 seconds
of samples when interpreted at 48 kHz, corroborating the 64 kHz physical clock
used by NTPods. After correcting the physical rate, a short capture contained
10.650 seconds of PCM in 11.056 seconds elapsed, including 408 ms startup. The
user confirmed normal voice pitch, working Stop/Play controls and music in both
earbuds after selected-pair recovery.

Enabling Android's software microphone privacy during active capture terminated
the capture and completed selected-pair playback recovery. The foreground-service
type mask dropped from `0x90` (microphone and connected device) to `0x10` (connected
device only). The privacy toggle was restored to its previous disabled state.

The installed `1.0.1-pixel2` build passed 20 consecutive short microphone
start/stop cycles, each with decoded PCM, an idle final state and successful
selected-pair playback recovery. An earlier run stopped on its fourth cycle
after a two-second incoming-packet gap; recovery succeeded, but the cause was
not established. No microphone code or timeout changed between those runs.
The passing repeat does not establish long-duration or full-duplex reliability.

Pixel Recorder did not pass cross-app injection. Its automatic Bluetooth input
requested native SCO and interrupted the custom uplink; recording stayed at zero.
With Recorder's Bluetooth input disabled, its timer advanced but it recorded the
phone microphone. Its original preference was restored after testing. Creating an
AudioPolicy and AudioTrack does not prove that an app consumes the injected route;
apps that explicitly select a physical input can bypass the UID policy mix.
The Pixel's seven built-in-microphone input profiles also exclude remote-submix
devices, so patching Recorder's existing input stream cannot bridge that gap.
This UID-scoped route cannot override Recorder's explicit physical-input selection;
Recorder would need to allow the injected route for this approach to work.

During the first microphone UI test, one earbud stopped playing; closing the case
restored both earbuds. The activity binding lifetime and selected-pair A2DP recovery
were fixed. A subsequent one-minute capture stopped its uplink and completed the
selected-pair recovery in about two seconds. That profile cycle can pause music;
resume it explicitly. A shared Compose list recomposition defect also hid the
conditional Stop and Play rows; a runtime regression reproduces and verifies its
small inline fix. Live Pro 3 heart-rate reports were also observed. Packed firmware
metadata such as `9442752` is normalized to `9A348` before selecting a movement
service; late metadata updates reach the existing sensor session. After that fix,
the signed build displayed changing horizontal/vertical reports and a live trace
on the Pixel. Both optional sensor toggles were verified off after testing.
Physical pairing, VoIP, full duplex and long-duration recovery still need their hardware checks.

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
