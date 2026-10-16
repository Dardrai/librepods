# Pixel playback, microphone and sensors

This fork adds nearby pairing, battery freshness, requested-versus-reported
settings, AAC-ELD microphone tests, optional Shizuku microphone routing, and
heart-rate/movement sessions to LibrePods' main Android application.

Ordinary controls and Android Bluetooth playback work without Shizuku on
supported firmware. Experimental routing requires Shizuku started through ADB
and permission granted to LibrePods. Start Shizuku again after a phone reboot.

Conversation Awareness handles the start, deeper-duck and partial-recovery
stages. A continuation cannot start a new conversation or take over another
connection. Manual volume changes end the app's volume ownership, and a confirmed
disable restores an active conversation without waiting for a final status packet.
Route changes stop adjustment before another output's volume is changed.

Opening Accessibility or Equalizer settings does not send default values to the
AirPods. Settings follow device reports and successful pending requests; Custom EQ
keeps requested and confirmed values separate. Failed EQ writes restore the graph
to its known setting. Control writes require the displayed pair, selected pair
and actual Bluetooth transport to agree.

Hearing and transparency pages send only deliberate edits. They preserve the
other ear's settings and unknown profile fields; loading a report or opening a
page sends nothing. Attribute writes are debounced for their original pair and
connection, and stop when that connection changes. An attribute timeout retires
that attribute connection so a late reply cannot be mistaken for another setting.

## Using the features

Open the case to show the nearby card. Select Connect and follow the case's
pairing gesture. One stable discoverable AirPods candidate pairs automatically;
multiple matching candidates ask you to close the other cases and try again. Android can still request pairing
confirmation. The public advertisement identifies a model, not a trusted Classic
address. Closing the lid cancels the invitation. Names such as “Dylan’s Airpods”
can be candidates even when they omit “Pro”; explicit conflicting models remain
excluded. Completely renamed or ambiguous devices can still be paired in Android
Bluetooth settings.

The nearby invitation follows NTPods' white pairing card in both themes, with
the original spinning buds-and-case clip for Pro models, one Connect action and
the model's setup gesture. Other models, reduced motion and playback errors use
static model artwork. The silent loop stops as soon as the card closes and does
not request audio focus. Discovery and
bonding share a 60-second request; closing the card or lid retires LibrePods'
scan and callbacks. Android owns its confirmation dialog and may finish a bond
after the card closes. Microphone capture and playback recovery block new
invitations. Active pairing is protected from synthetic BLE absence expiry;
physical lid closure still cancels it.

The card owns its colors through a separate framework theme so Android 17's
expanded dark mode cannot invert it around the opaque video. Its background
matches the original clip, with a shorter artwork area. Preview pairing popup
in the popup-animation settings opens this same card without Bluetooth discovery
or bonding; Done, expiry or leaving Settings closes it and releases playback.

BLE lid reports use immediate delivery. The previous 500 ms batching request
was clamped to a five-second batch interval on the Pixel, delaying both the
nearby invitation and the paired battery island. Timestamp validation, device
trust and duplicate suppression still apply to each report.

The Dynamic Island follows the Windows black capsule, with staged motion,
model artwork and separate left, right and case battery rings (one for Max).
Unknown readings show an em dash. Cached readings retain their age, dim, and
cannot show charging. An existing pair's lid event uses this battery capsule;
the nearby-card preference controls those lid events, and the island preference
controls connection and ownership events. Passive battery and metadata updates
never reopen the capsule or extend its dismissal. Tap or swipe down to open
controls; swipe up or sideways to dismiss. Android's overlay permission is
required. Reduced motion is respected and no looping video is used.
For a centered top camera in portrait, the capsule surrounds the actual camera
path and reserves space inside for the artwork and text below it. Other display
shapes use the normal safe top area. The application overlay stays beneath
Android's system status-bar layer.
Confirmed noise-control changes use the same capsule; initial readback and
duplicate reports stay quiet. Some models reuse the existing bundled artwork.

Open Microphone in the AirPods settings for Playback and microphone. Connect
Shizuku, then select Check audio support. Play a recognizable test sound in
another app during the playback-copy check. Diagnostics do not save captured PCM
or initiate calls; use Copy report to share the result explicitly.

Microphone tests require Shizuku for selected-device playback recovery. Record
a microphone test first. Test recordings are private WAV files, stop after
one minute, and can be played or shared explicitly. Compare spoken pitch and
recording duration with the elapsed capture time before relying on the microphone.
Starting a new recorded test keeps that recording and the two most recent older
tests, removing earlier diagnostic WAV files from the private test directory.
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

Call routing implements experimental two-way cellular audio through Shizuku.
For the tested Pixel cellular-call setup, turn off Phone calls for the selected
AirPods in Android Bluetooth settings, leaving Media audio on. Select the phone's
speaker or earpiece and keep the call unmuted. Then select Use AirPods for this call and
ask the remote listener to verify the AirPods microphone with the phone away from
you. Speaker alone did not suffice while the native headset profile remained
connected. Stop the experimental route and restore Phone calls after the test.
The app requires this manual setup; it does not disconnect that profile
automatically. After the first microphone PCM reaches the uplink, the helper owns
a temporary `MODE_CALL_REDIRECT` request and relays telephony downlink to the exact
selected A2DP output. Stop closes both directions before releasing that request;
Telecom retains its original mode ownership. Process death also releases the
request. Muting the call stops the experimental session. VoIP is not implemented
by this duplex path.

Two-way AirPods audio and a silent phone were confirmed with bounded prototypes
and then with the installed pixel3 application on the test Pixel.
**Normal-quality music during calls is not yet supported.**
Outside a call, music and private microphone recording worked together on
pixel3; the user reported only a small quality drop and substantially better
sound than native HFP with Phone calls both enabled and disabled, keeping Media
audio enabled. The severe degradation was reported in the call experiment.
Android mutes existing media players for a call. A separate one-player experiment
made YouTube audible alongside the call, but the listener reported telephone-like
music quality. That experiment is not enabled in the app. The production APK
passed a basic cellular-call test; exclusive microphone input, long-call
reliability and concurrent call/music quality remain unverified.
See the [Android call APIs](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/AudioManager.java)
and [native call routing](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/services/audiopolicy/managerdefault/AudioPolicyManager.cpp).
A successful recorder test does not establish VoIP or cellular-call support.
Call microphone PCM is processed live and is not saved by LibrePods.

Use Stop before changing routes. Bluetooth loss, revoked microphone access or
Shizuku loss ends the experimental session. Check ordinary playback and the native
microphone afterwards. A recovery error requires reconnecting the AirPods.

Heart rate and raw movement are optional controls on the main settings page for
supported models. They stop on disconnect and pause during microphone capture.
Movement data is not a spatial-audio renderer. Unknown models do not inherit
Pro 3 sensor or microphone support.

BLE control decisions expire after 15 seconds without a fresh radio observation.
Battery percentages retain their original component timestamps, including the
case; keeping a last-known percentage does not make charging or presence current.
New scans and pairing retries ignore callbacks from retired attempts. Sensor
remapping discards samples from retired service mappings, and microphone
suspension prevents new sensor starts until capture ends.

Further ports with a grounded implementation path are per-device readback and
pending state for existing hearing/transparency controls, where their transport
is available, and a separate raw-movement capability for additional models in
the NTPods catalog. Neither is enabled for an untested model by this change.
Their remaining work is access and device validation, rather than a new codec.

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

Subsequent bounded duplex probes retained that uplink and acquired raw call mode
5 (`MODE_CALL_REDIRECT`). They delivered 48 kHz mono `VOICE_DOWNLINK` from the
telephony input to a 48 kHz stereo track routed only to the selected AirPods.
The listener confirmed both directions used the AirPods and the phone was silent.
Each completed probe released its mode request and restored Telecom's mode 2.
No output-stream mute or call PCM file was used. The installed pixel2 app supplied
the microphone while standalone shell probes supplied the temporary downlink.

The installed `1.0.1-pixel3` application then passed its own real cellular-call
test using Use AirPods for this call. The user confirmed two-way AirPods audio
and a silent phone. Before Stop, the UI reported about 459.2 seconds of decoded
64 kHz mono microphone PCM over 460 seconds elapsed, approximately 7.7 minutes.
The helper owned raw mode 5, with the call-assistant uplink routed to telephony
and a 48 kHz stereo media track routed to the selected AirPods. Stop released
the helper's tracks, returned mode 5 to Telecom's mode 2 while the call remained
active, and dropped the foreground-service mask from `0x90` to `0x10`. No call
PCM file was created. This establishes basic two-way functionality for that
setup, not exclusive microphone input, VoIP or long-duration reliability.

During the music-quality failure, the exact YouTube player and caller relay were
both active on a 48 kHz stereo, floating-point A2DP mixer. There were no active
effects or music-track underruns. The selected AirPods negotiated AAC at 44.1 kHz,
16-bit stereo with codec-specific value `0x85` (VBR quality 5, not 133 kbps).
This metadata does not establish the actual encoder bitrate or acoustic fidelity,
and it does not explain the reported quality loss. NTPods' successful AAC/microphone
checks used 48 kHz CBR playback; the selected Pixel device's local/selectable AAC
and SBC capabilities exposed only 44.1 kHz. The good no-call result means that
codec difference alone does not establish the cause or a fix.
The Android 17 focus-isolation client wrapper returned no token. Later typed
service probes entered isolation and accepted exit with `LOSE` on both attempts.
Both relay-gain controllers completed 30 seconds and restored multiplier 1 on
their captured player. The music comparisons were inconclusive: music was
paused in the first, and a diagnostic guard failed after an explicit YouTube
MediaSession play request in the second. The cause of that failure was not
established. These probes do not demonstrate a music-quality fix. No codec
setting was changed; media-focus experiments remain outside the application.

The native stack rejected the local PSM `0x19` listening-socket probe with
`IOException: Error: -1`. This does not establish whether outbound AVDTP can work.
The Bluetooth module was `com.google.android.bluetooth` version 17 with APEX
`com.google.android.bt` version 371899999.

The tested platform ELD encoder configuration produced 48 kHz stereo
**512-sample** frames, so it failed the 480-sample Apple playback requirement.
An Android software encoder has not been tested. Native Bluetooth playback remains enabled.
The latest full FOSS validation, `pixel-pairing-spin2` (pixel9, versionCode 94),
passed 172 tests in 31 suites, debug lint, debug assembly and minified release
assembly. Regressions cover custom names without a Pro suffix, conflicting models,
ambiguous candidates, muted looping, first-frame fallback, decode failure,
reduced-motion changes and late media callbacks after dismissal. Ten native host
previews include both themes, larger text, independent battery readings and camera
geometry. Pixel9 is signed with the existing fork key and installed on the
Pixel 10 Pro XL. Its physical pairing and animation check is pending.
Read-only Pixel diagnostics established that the old name filter rejected the
discoverable, unbonded “Dylan's Airpods” before requesting an Android bond.
The user
confirmed the preceding pixel7 surrounds the camera; its measured window starts
at y=34px. Pixel8 keeps that placement and slightly widens the expanded capsule,
with 16dp side margins on this display. The width still needs a visual check.
The earlier pixel5/code90
audit build passed 143 tests and was installed at the user's request; its audit
fixes remain unverified on hardware. The earlier pixel3 build
passed 86 tests in 21 suites and the basic live-cellular-call checks described
above; those hardware results do not establish acceptance of the new changes.
The signed `1.0.1-pixel3` build (versionCode 88) was installed and connected to
the selected AirPods and Shizuku. Android was in normal mode with SCO off;
the selected pair's Phone calls and Media audio settings had both been restored.
During private microphone recordings with no call active, the user confirmed
good music playback with only a small quality drop, far better than native HFP.
This passed with Phone calls enabled and again with Phone calls disabled while
Media audio stayed enabled. The profile setting alone therefore does not explain
the call experiment's quality loss. Simultaneous music and custom microphone
capture can work outside a call; call-quality acceptance remains separate.
The earlier signed, minified pixel2 application connected to the paired Pro 3
and Shizuku.
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
Physical pairing, VoIP, multi-pair call routing and long-duration recovery still
need their hardware checks.

Build the FOSS application with JDK 21 and the SDK/NDK versions pinned in Gradle:

```sh
cd android
./gradlew verifyPixel
```

The overlay tests render the production Android Views with Robolectric's native
graphics into `android/app/build/overlay-previews`; CI includes these with check
results. They cover both themes, a narrow screen with larger text, individual
fresh/stale/unknown batteries, Max, expiry and interruption. These host renders
do not verify actual overlay permission, camera cutouts or physical pairing.

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
