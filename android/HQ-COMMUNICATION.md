# High-quality microphone routing for calls

## Status and scope

The file-free AirPods PCM backend and a private SDK 37 call-routing preview are
implemented. The current Phone route has briefly delivered audible audio in both
directions, but sustained Phone operation and Kettu/Discord validation are still
pending; this is not production call support. The preview's High-Quality Calls switch
defaults off. With it off, calls keep Android's native route. Recorder retains
WAV output and its 60-second limit. Its previous app-input and cellular-call
relays were removed in `4f62b90`; the new owner-scoped path does not restore them
wholesale. Creating a policy or stream is not proof that a call works.

`LibrePodsService.startLiveMicrophone()` returns an owned close handle for an
explicit consumer, with no WAV or recording time limit. Delivery preserves
64 kHz mono PCM16 samples and timestamps, drains immediately, and stops capture
on consumer failure or overflow. Its decoded queue is capped at 16 access units
(120 ms); this is capacity, not added buffering or an end-to-end latency bound.
It shares Recorder's permission/privacy, current-transport and Game Mode guards,
plus existing Stop/quiet/playback-recovery handling. Ordinary live consumers
remain denied during calls. Only the selected, already-prepared call-route owner
can use the separate call admission path. After explicit opt-in, the call
controller watches the current Phone or supported VoIP session; it does not
place or answer calls.

A nonexported debug-only microphone-route probe now connects that PCM consumer
to the existing root helper over a bounded, nonblocking local socket pair. The
helper, reached through libsu or root-mode Shizuku, owns both the
UID-0/session-specific policy and its receiving AudioRecord;
the input must be its exact `REMOTE_SUBMIX` before recording starts. It uses a
48 kHz mix and a checked 64 kHz source playback rate, reports aggregate sample
statistics only, and stops after at most 20 seconds of input. Game Mode must be
off, and existing microphone permission, privacy and active-call guards remain
in force. A bounded live check on the Pixel 10 Pro XL now passes through the
root-UID Shizuku helper, including explicit cleanup and playback recovery. This
does not by itself validate third-party or cellular call routing. The separate
call implementation below still needs those listening and route checks.

The private call path uses `CommunicationAudioController`, `CallPcmConsumer`
and `RootCommunicationRoute`. It requires the app's Record Audio, Phone State
and Bluetooth Connect permissions, microphone privacy access, a current root
helper and the selected AirPods connection. Foreground-service arming belongs
to the exact controller, so late cleanup cannot disarm a replacement. Game Mode
must release its changes before the call microphone starts. The root route
verifies actual input, downlink and selected A2DP output routes before reporting
ready and keeps checking them while relaying samples.

This work does not change the existing private PCM32 music encoder path, codec,
bitrate, priming or packet timing. Call microphone PCM remains 64 kHz mono
PCM16; the platform call mixes and downlink relay use 48 kHz PCM16. Those call
stream formats do not reduce every music stream to PCM16, nor do PCM32 mixer
metadata or successful writes prove end-to-end call or music fidelity.

The intended result is AirPods AACP microphone input for cellular Phone calls
and Kettu/Discord VoIP, with AAC-ELD stereo playback retained. Allowing another
app's music during a call is a separate, explicit choice. It is not implied by
working duplex audio. Existing [AAC-ELD](AAC-ELD-XPOSED.md),
[Game Mode](GAME-MODE.md) and [protocol](PROTOCOL.md) requirements still apply.

### Evidence available on 2026-10-26

| Check | Result | Limit |
| --- | --- | --- |
| Root capability probe, Pixel 10 Pro XL, Android 17/SDK 37, `CP3A.261005.005`, patch 2026-10-05 | All six queried API groups present; all twelve queried permissions granted; telephony TX sink and RX source present; `isPstnCallAudioInterceptable()` true | No streams, policies, mode changes, recording or call test |
| File-free PCM backend regression checks | 25 focused tests passed: unchanged samples/64 kHz timestamps, bounded immediate delivery, consumer failure/close, stale sockets, privacy cancellation, Game Mode admission and Recorder WAV/time-limit behavior | Synthetic decoder and host tests; separate live-route evidence below. Consumers must support interruption or bounded writes |
| Integrated private root microphone probe regression checks | 60 focused tests passed across nine suites; debug APK assembly and Android lint passed. Covers UID-0 root/Shizuku admission, bounded connection wait, exact route admission, ordered teardown, bounded/partial socket writes and EAGAIN retries, retained AudioTrack short-write suffixes, clean retries, stale owner cleanup, unauthorized descriptor disposal, privacy admission and root connection death | Host/Robolectric checks; a 120 ms current-buffer write deadline is not an end-to-end latency bound |
| Private call-path integration checks | 98 tests in 15 suites, debug assembly and lint passed at the recorded call-build snapshot. The diagnostic update separately passed 27 checks: five controller and 22 media-lease checks, including the three subsequent focus-isolation races; assembly and lint passed again | Host checks only. The subsequent HFP handover and two-phase teardown are a separate implementation requiring their own checks. The optional media path remains dormant. No Phone/Kettu call pass or live interruption-free stop is implied |
| Selected HFP handover and two-phase teardown checks | 127 checks passed in two focused runs: 100 microphone, transport and HFP-lease checks plus 27 call-controller, framing, UI and service-ownership checks. Assembly and lint passed. Includes changed/forbidden preferences, competing connecting headsets, uncertain replies, exact quiet/no-Start receipts and stale owners | Current private handover preview; actual Phone/Kettu audibility, native stem call controls and post-call restoration still require live acceptance |
| Live AirPods microphone to owned virtual recipient, Pixel 10 Pro XL | A roughly 19.5-second run accepted 1,248,704 source frames at the physical 64 kHz clock and received 932,400 frames at 48 kHz after warmup/tail exclusions. No clipped samples or audio files. 143 pipe EAGAIN waits completed, maximum write delay 24.87 ms; AudioTrack short writes retained their suffix. Cleanup, AACP Stop and playback recovery passed; final microphone state Idle, mode Normal and no owned policy ports remained. User confirmed clean music/pause-resume and working stems afterward | Music was paused during capture. Frame counts are consistent with conversion; live RMS/peak statistics do not prove pitch, frequency response, gain neutrality, acoustic latency or call quality. Duplex music and actual calls still need separate checks |
| Live microphone with AAC-ELD music, Pixel 10 Pro XL | A second roughly 19.5-second run accepted 1,251,941 source frames and received 932,880 frames after exclusions. No clipped samples; bounded pipe waits completed in at most 11.42 ms. User confirmed clean music throughout capture. Explicit cleanup, Idle/Normal state and policy removal passed | The existing Recorder recovery cycled A2DP afterward (encoder generation 128 to 132), pausing the player at the end. User could resume cleanly. This is not an interruption-free stop or an actual call-route pass |
| Current own-session policy probe, Pixel 10 Pro XL | Exact UID/session/source policy routed synthetic PCM to its own `REMOTE_SUBMIX` input, verified before recording. 48,000 samples received; 8,192 aligned samples matched exactly (correlation and gain 1, mean absolute error 0). Policy ports disappeared and explicit cleanup passed | Synthetic local input only; no physical microphone, call, external playback or audio file. Does not prove Kettu, cellular routing or process death with an active receiver |
| Owned policy process-death check, Pixel 10 Pro XL | A verified virtual source wrote only zeros to keep its own policy ports alive. The exact helper PID/start-time identity was checked before termination; afterward the process, current policy ports and current player were absent, and audio mode remained Normal | No AudioRecord was created, no samples were read and no audio file was written. Confirms policy/virtual-player Binder-death cleanup, not an active microphone or call session |
| Earlier standalone cellular prototype, same model on `CP3A.260905.009` | Remote listener confirmed AirPods uplink; bounded downlink relay made both directions audible with the phone silent | Older app and ordinary AAC, not current AAC-ELD integration or long-call validation |
| Earlier synthetic UID-scoped input injection | Matching remote-submix endpoints preserved 500 Hz and 6 kHz tones and duration using a 48 kHz mix with a 64 kHz source playback rate | Did not establish that every third-party app accepts this input |
| Earlier Pixel Recorder test | Explicit phone-microphone selection bypassed injection; automatic Bluetooth selection requested SCO and interrupted custom uplink | Recorder failure is not evidence that all VoIP apps behave the same way |
| Earlier music during cellular call | User reported telephone-like music despite a 48 kHz stereo float A2DP mixer, no active effects and no music-track underruns; HFP disconnected and SCO off | Cause unresolved; mixer metadata does not prove encoded or audible fidelity |
| Current Phone trial 1, Pixel 10 Pro XL | User confirmed two-way audio, but Android stayed in native in-call mode with SCO active; the call ended back in Normal mode | No private call-ready receipt was retained. The rotating log buffer prevents an exact retrospective admission diagnosis. This verifies normal calling only |
| Current Phone trial 2, Pixel 10 Pro XL | Diagnostic preview reached the final SCO-idle gate and failed with `Android kept hands-free audio active`. In-call mode and SCO remained active; after the user ended the call, mode was Normal and SCO off | Earlier input/output/downlink checks passed by source control flow, not a separately retained full readiness receipt. The AACP call microphone was not admitted; selected HFP handover is required |
| Current Phone trial 6, Pixel 10 Pro XL | The private route passed actual input/output/downlink checks and fresh selected-A2DP playback-head progress. User confirmed both call directions were audible during the brief HQ window. AACP supplied about 2.45 seconds of decoded microphone audio; root accepted 149,280 uplink frames and 149,760 downlink frames before the microphone consumer queue overflowed | Brief audibility only. Slow control-service calls were still running on the PCM worker; startup-inclusive timing maxima cannot be treated as steady-state IO measurements. No sustained-call, mute, media-quality or latency pass |
| Current Phone trial 7, Pixel 10 Pro XL | After moving owner/privacy/HFP/route checks to a separate guard worker, root accepted 396,485 uplink frames and 317,760 downlink frames over 6,255 ms without the previous consumer-queue overflow. Active-only maxima were 1,477 us for output writes, 7,778 us for the audio loop and 38,461 us for control checks; maximum guard age was 100 ms. The route then stopped when the selected HFP profile reconnected at roughly six seconds. Cleanup confirmed HFP was already restored | The control-worker repair passed this short run; automatic HFP reconnection still prevents a sustained call. Aggregate frame counts and timings do not prove end-to-end acoustic latency, mute behavior or music quality |
| Transient selected-pair HFP suppression, route protocol 2 | 108 targeted checks across 14 suites, debug assembly and Android lint passed. Private preview installed on Pixel 10 Pro XL; Bluetooth restarted and AAC-ELD stereo 48 kHz / PCM32 negotiation retained | Suppression discovery and an actual sustained call remain unvalidated. High-Quality Calls is left off pending that check; no claim that the reconnect failure is fixed |
| Current Kettu/Discord calls, Phone mute/stem controls and music alongside calls | Pending | The short Phone duplex result does not establish these separate behaviors |

Private receipts are retained outside the repository under
`outputs/codec-quality-followup-20261026/communication-capability-probe/`
(`pixel10-metadata.json`, source, DEX and hashes),
`outputs/codec-quality-followup-20261026/communication-policy-probe/`
(`pixel10-run-1.json`, `pixel10-run-2.json`, `pixel10-binder-death.json`, source, DEX and hashes), and
`outputs/codec-quality-followup-20261026/communication-live-probe/`
(`gradle-validation.json`, `call-build-validation.json`, `pixel10-run-1.json`
through `pixel10-run-3.json`, `pixel10-duplex-run-4.json`,
`no-cycle-floor-call-admission-tests.log`, `service-call-ownership-tests.log`,
and other host-test logs). Historical receipts are under
`outputs/librepods-pixel-shizuku/pixel3-verified-delivery/` plus its original
`duplex-probe/` receipts. Do not publish call audio, account details or raw dumps.
The historical typed focus-isolation test did obtain and release a token. Its
later relay-gain comparisons remained inconclusive because music stayed paused;
those runs do not establish a quality fix.

The current call-preview packages, build logs and Phone trial receipts are under
`outputs/communication-calls-20261026/`, including `phone-trial-6-control.log`
and `phone-trial-7-control.log`. The live-verified private PCM32 encoder
and its separate listening evidence are under `outputs/p17wide/`.

The first live run stopped on a legitimate short nonblocking AudioTrack write.
The second delivered about 5.4 seconds before its producer closed; its exact
producer failure was not recorded and must not be guessed from the EOF alone.
The completed third run uses one fixed 480-frame pending block at AudioTrack
and bounded partial/EAGAIN write retries on the existing PCM delivery worker.
No new queue, gain conversion or larger target buffer was added. Stop first
quiets that producer, finishes the root recorder/track/policy, then closes the
owned AACP session and descriptors. Current source data is never replayed from
the beginning after a partial write.

The first current probe safely aborted with a stopped recorder routed to the
phone microphone. The corrected order starts the explicitly policy-bound virtual
output without PCM, waits for its exact input port, then constructs and verifies
the scoped recorder before starting it. Android publishes an injector's input
only when that virtual output starts. A null or different pre-start route still
aborts; no physical-input fallback was added.

## Use the existing app and root owners

The implementation keeps `RootAudioService` as the bound libsu service and uses
the existing `ShizukuAudioService` bridge, also available through root-mode
Shizuku. It does not start a permanent second daemon.
`RootAudioConnection` already retires late grants and bindings. New operations
must keep the expected-app-UID check, clear Binder calling identity only around
privileged work, and restore it in `finally`. Root authority never substitutes
for LibrePods' microphone permission, foreground-service lifecycle or user choice.

The implemented boundaries are:

1. Use the implemented `AirPodsMicrophone` live consumer and owned service handle.
   Socket/generation ownership, packet validation, the decoder, no-data deadline
   and failed-stop recovery remain shared with Recorder. Separate call admission
   requires the exact prepared controller, consumer and current socket; the
   ordinary live API does not bypass call guards.
2. Carry PCM to the root helper through one bounded, owned Unix stream socket,
   with no Binder transaction for every audio unit. Call frames carry the mute
   epoch and physical sample position; the standalone microphone probe keeps
   its raw PCM transport.
   The Bluetooth receive path does not block on the consumer. Partial writes
   retain only their unwritten suffix; the existing delivery worker waits within
   a fixed deadline. A closed pipe or expired write deadline ends the affected
   session without growing a queue or replaying old speech.
3. Put the policy, injection track, downlink recorder, selected-output track and
   any temporary mode request under one communication-session owner. The AIDL
   uses new IDs 16–17 for the private microphone probe and 18–23 for call
   inspection, preparation, mute/state/finish and read-only media selection.
   Removed IDs 0–2 are not reused; IDs 3–15 remain unchanged. A mismatched helper
   must fail preparation rather than report a working route.
4. Bind that owner to the selected AirPods transport generation, active call or
   VoIP owner, and receiving UID. Switching buds alone must not retire a healthy
   pair; a replaced connection, different selected pair or ended call must.

The removed `AudioRouting`, `CallDuplexRouting` and pipe lifecycle at
`4f62b90^` are references, not code to restore wholesale. They include prototype
timeouts, fixed relay gain, broad call guards and a settings UI that did not pass
production acceptance.

## Route contracts

### Microphone clock and format

Pro 3's current decoder describes 48 kHz AAC-ELD coding but produces 480 samples
per 7.5 ms of physical microphone time: 64 kHz mono PCM. Do not reinterpret those
samples as 48 kHz; that changes speech pitch and accumulates delay. Preserve the
existing clock interpretation unless new model-specific evidence contradicts it.

The previously tested platform conversion uses a 48 kHz policy/track format and
`AudioTrack.setPlaybackRate(64000)`. Check the accepted playback rate and the
actual receiving stream. A 64 kHz remote-submix configuration previously selected
an 8 kHz driver stream and lost the 6 kHz test tone. Call injection accepts only
8–48 kHz formats, so it also needs this checked conversion or an explicitly
tested 64-to-48 kHz resampler. Use unity PCM gain; do not copy Windows' separate
gain/limiting choices into Android.

### VoIP: Kettu/Discord

The preview uses platform call-redirection APIs and scoped policy mixes, not
replacement `AudioRecord.read()` buffers or private WebRTC offsets:

- `getCallUplinkInjectionAudioTrack(format)` uses a recorder `AudioMix` matching
  `VOICE_COMMUNICATION` for VoIP. `getCallDownlinkExtractionAudioRecord(format)`
  extracts voice-communication and signalling playback.
- `MODE_COMMUNICATION_REDIRECT` keeps Android's communication policy while
  mapping the HAL to normal mode. The downlink output track explicitly uses
  `USAGE_VOICE_COMMUNICATION` and speech content, with the selected AirPods A2DP
  device as its preference. Preference alone is insufficient: every actual
  output route must be that pair's A2DP sink before the output gate opens and
  before downlink delivery. No speaker or earpiece fallback is accepted. This
  allows the existing AAC-ELD encoder to remain the playback path; current
  call-device behavior still needs a live check.
- The convenience APIs match communication audio broadly. The preview instead
  builds equivalent mixes with call-redirection attributes: uplink matches the
  selected app UID, exact recording session and `VOICE_COMMUNICATION` source;
  downlink matches that UID's voice-communication/signalling playback. It checks
  the assembled criteria and exact owned policy addresses. It does not redirect
  another app's capture merely because it also uses `VOICE_COMMUNICATION`.
- Verify the receiving app's real source, session and routed device before
  claiming readiness. Explicit input-device selection takes precedence over
  policy mixes in `AudioPolicyManager`. If Kettu bypasses the mix, first identify
  its selected-device request. A narrowly scoped, signature-checked framework
  hook may then redirect that request only while our session owns the route.
  A Java `AudioRecord` hook alone is not a guarantee for native/AAudio clients.

A2DP is absent from Android's valid `setCommunicationDevice()` types. Reporting
success for `setCommunicationDevice(A2DP)` would not implement duplex routing.
Do not invent a SCO or USB device to make that API accept the route.

### Cellular Phone calls

Phone uplink goes to the modem/HAL, not an ordinary application `AudioRecord`.
A UID-scoped recorder mix by itself cannot feed it. The preview requires actual
telephony TX/RX ports and `isPstnCallAudioInterceptable()` before opening the
system call injection/extraction streams. Their presence is necessary, not
sufficient. Phone trial 6 confirmed brief uplink/downlink audibility; sustained
operation, mute and recovery across ordinary call transitions remain unverified.

Use a temporary, helper-owned `MODE_CALL_REDIRECT` request; the platform maps it
to HAL `CALL_SCREEN`, unlike VoIP redirection's HAL normal mode. Validate that
the downlink is telephony RX and uplink reaches telephony TX. Render the downlink
only through the selected AAC-ELD output, with no speaker/earpiece duplicate.
On release, remove only this helper's mode request; retain Telecom's ownership.

The old Pixel experiment received no AACP microphone PCM while HFP remained
connected, even with Speaker selected and SCO inactive. It worked after only the
selected pair's Phone calls profile was disabled. Recheck that interaction with
the current Apple identity and ELD route. A prototype may use a recorded manual
profile choice; production needs a selected-pair HFP lease and verified recovery,
not permanent/global HFP removal or a falsified connection state. Preserve older
buds and the user's original Phone calls preference.

The current private repair uses a selected-device HFP **disconnect-only** lease.
It never writes the saved Phone calls policy or uses global SCO/audio-route
controls. It rejects other connected or connecting headsets, verifies the exact
profile and audio are disconnected, and fails if HFP returns during the call.
On release it reconnects only an originally connected, policy-enabled pair,
using the same live service and bond, with the selected stereo output still
present and no newer headset owner. A changed policy or full-device disconnect
is preserved. Abrupt helper-process death cannot guarantee restoration of this
raw Bluetooth disconnect; no persistent preference was changed. Native stem
mute/end-call behavior while HFP is released remains a hardware acceptance check.

Trial 7 exposed an automatic HFP return after about six seconds. AOSP
`PhonePolicy` has a six-second delayed "connect other profiles" retry that can
reconnect a disconnected HFP profile while its saved policy remains enabled.
That source path matches the timing; the retained receipt does not establish
which exact caller initiated the Pixel's reconnect.

Route protocol 2 adds transient Xposed suppression for the exact selected pair
and the current root/app Binder owners. Exact-signature hooks in
`HeadsetService.connect(BluetoothDevice)` and
`okToAcceptConnection(BluetoothDevice, boolean)` block internal automatic or
incoming connection attempts while the lease is active. An external explicit
connect retires that lease and follows the stock path. The hooks do not change
`getConnectionPolicy`, the Bluetooth preference database or another pair.
Hook availability is required before disconnecting HFP. A separate authenticated
discovery/typed-Binder channel keeps this lease independent of Game Mode's owner.
This source change still needs its build and live acceptance checks.

Root acquires suppression before its selected-profile disconnect, and the guard
checks the exact peer, owner and generation while the call runs. A lost acquire
reply retains a cleanup obligation; an explicit unowned rejection does not.
After verified microphone quiet, release must confirm no active replacement
owner and the expected generation before HFP restoration can connect. An old
token cannot release a replacement call or reconnect over it. Owner death and
Bluetooth-service teardown retire the in-memory suppression.

Cleanup is two-phase: stop and remove the owned root audio resources, retain
the HFP lease while the exact AACP consumer stops, then acknowledge verified
microphone quiet before HFP restoration. Producer EOF alone is not a quiet
proof. A bounded unconfirmed-quiet path leaves HFP disconnected and reports
incomplete cleanup rather than reconnecting over a potentially active uplink.
If quiet remains unconfirmed with the app owner alive, protocol 2 retains the
suppression token in the failed route rather than allowing an automatic HFP
return over an uncertain uplink. Explicit external connection or owner death
can still retire it; no persistent Phone calls preference was changed.
The private bridge requires call route protocol 2 before microphone admission;
incompatible app/helper transactions fail before preparing a new route.

The audio worker performs bounded PCM IO, head accounting and cached admission
checks. A separate guard worker verifies call ownership, privacy/mute, selected
HFP state and actual input/downlink/A2DP routes without holding the PCM lock.
Its immutable observation is timestamped at the start of the complete check,
not after a slow service reply, and expires after 250 ms. Delivery and the
independent watchdog reject stale observations. Setup keeps the original
five-second deadline and requires fresh output-head advancement after route
convergence. Active-only IO/loop/control maxima exclude checks that began during
setup; no queue or timeout was enlarged to hide the earlier queue overflow.

## Start, mute and teardown

Preflight reads capabilities without recording: permissions, privacy/mute,
selected AACP owner, selected ELD playback, active call identity and available
platform endpoints. Restore active Game Mode controller changes through its
existing owner before reserving the microphone. Do not force a new peer or audio
profile from a stale callback.

Start with a bounded deadline. Establish and verify the selected output before
making a relayed call audible; verify microphone PCM and the receiving route
before declaring the uplink ready. A successful write or nonzero decoded PCM
proves neither remote audibility nor microphone exclusivity.

Phone mute, hardware/software microphone privacy, permission revocation and
the app's call-mute action must gate the injected PCM explicitly. An injection
`AudioTrack` is not the native microphone, so a native microphone mute alone is
not sufficient proof. Never clear a user's mute/privacy setting. Muting an active
call should silence its uplink while preserving downlink; do not treat mute as
call termination. Do not transmit buffered speech after unmuting. Permission or
privacy loss stops AACP capture; any retained route must remain silent.

The implemented call transport carries versioned, bounded frames containing a
mute epoch and physical 64 kHz sample position. At each mute edge, the root
uplink is gated to zero immediately, pending speech is erased, and old epochs
are discarded. On unmute, the app obtains the exclusive received-frame floor
before the decoder queue and trims samples older than that floor. The root
requires fresh current-epoch PCM and a playback-head position past the old
accepted-sample watermark before reopening the gate. Android's ordinary call
mute may therefore silence the uplink without stopping downlink. This exception
is limited to the exact prepared call route or its owned closing microphone;
it never bypasses sensor privacy or missing permissions. Remote mute/unmute
audibility still needs a real call test.

Cancel on call end, owner or pair change, lost root Binder, denied access, failed
route verification, transport loss or a stalled PCM consumer. Teardown is
idempotent: stop delivery, finish the owned virtual recorder before removing its
source track or mix, then close the pipe and stop the owned AACP uplink. Release
the owned mode request and
restore only temporary profile/focus changes still owned by that session.
Keep bounded cleanup receipts and block a new session if cleanup is uncertain.
Binder death must release platform resources even if explicit cleanup fails.
Successful live-uplink Stop now waits for 500 ms of post-Stop quiet on the same
socket and generation, then joins/checks the existing native playback connection
without cycling it. The no-cycle path is accepted only while that ownership and
quiet state remain valid. Failed or uncertain Stop, a late packet or a failed
native connection check still uses the existing recovery path; Recorder keeps
its previous recovery behavior. Focused tests cover those branches. A live
call or duplex test has not yet confirmed interruption-free end-of-capture
behavior for this change.

## Music alongside the call

This requires its own user opt-in and exact-player ownership. The private bridge
contains an optional media-focus lease, but `allowMedia` defaults false and the
call controller currently leaves it off. There is no enabled media-during-call
claim or live approval for this dormant path. First prove duplex with ELD, then
test music continuity separately.

The lease uses a caller-owned focus-isolation Binder for one explicitly selected
media package, UID and unique MediaSession whose actual output is the selected
AirPods A2DP route. Shared UIDs, ambiguous sessions/players and route changes are
rejected. It sends no Play, Pause or volume commands. Manual pauses after a
playing observation retire the lease, including a pause before callback
registration. Unknown cleanup retains the exact token for retry; it does not
release another owner's isolation. Normal end may retain focus only for the
same still-playing session; other exits lose focus.

Android may send `AUDIOFOCUS_GAIN` when an initially paused selected player
enters isolation. The player can resume in response even though LibrePods sends
no Play command. This path cannot promise to preserve an initial manual pause.
Android may also separately call-mute MEDIA/GAME players during cellular calls;
focus isolation does not undo that mute, prove audibility or establish preserved
music quality.

Where available, use the platform focus-isolation token for the selected media
UID and release it with the session. If call muting still prevents playback,
inspect its specific player and use only an owned, reversible exception. Do not
disable focus management globally, force all player volumes, capture/rebroadcast
all device audio, or repeatedly press Play. A manual pause, changed player, route
change or revoked choice cancels any automatic resume. Preserve the user's media
volume and call volume separately.

## OTA compatibility and acceptance

Use framework APIs and reflection by exact class/method signature. Query runtime
permissions, policy/endpoint availability and actual routes; do not assume that
a matching SDK, root grant or device name proves support. Keep this routing work
independent of the existing native ELD provider's compatibility gates. It adds
no Bluetooth/HAL binary offsets, vendor library patches or fake endpoint types.
An unavailable or changed API disables this feature and cleans up its session;
it must leave normal Android calling available. Minor-OTA resilience remains a
validation target, not a guarantee for hidden system APIs.

Before treating the private preview as production call support:

- Test pure ownership/lifecycle rules: stale generations, double start/stop,
  cancellation at each resource boundary, short/blocked writes, pipe closure,
  Binder death, late callbacks and failed cleanup. Verify original modes,
  profiles and user playback choices remain owned correctly.
- Repeat synthetic input tests at the real receiver: clock/duration, 500 Hz and
  6 kHz content, clipping, channel mapping, bounded queue delay and privacy/mute
  silence. Verify there is no physical phone-microphone fallback.
- Test Kettu and cellular Phone separately with a remote listener. Check both
  directions, mute/unmute, incoming/outgoing calls, hold/end, changing output,
  app termination and recovery to normal calling. Do not start or answer calls
  automatically for these checks.
- Verify ELD remains negotiated and its actual frame cadence/PCM format stay
  stable during microphone use. Compare music before, during and after calls;
  distinguish call-network bandwidth from local music fidelity. Run the final
  listening checks without tracing and include a sustained duplex run.
- Check one-bud docking, full case return, Game Mode restoration and simultaneous
  media only after the base call path passes. Repeat on Pixel 7 Pro and Pixel 10
  Pro XL; list untested builds/apps explicitly rather than inheriting a pass.

### Platform references

- [AudioManager call injection/extraction and focus isolation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/media/java/android/media/AudioManager.java).
- [AudioTrack VoIP injection mix](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/media/java/android/media/AudioTrack.java) and [AudioRecord downlink mix](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/media/java/android/media/AudioRecord.java).
- [AudioPolicy injection and permission checks](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/media/java/android/media/audiopolicy/AudioPolicy.java).
- [Explicit input-device precedence](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android17-release/services/audiopolicy/managerdefault/AudioPolicyManager.cpp) and [HAL call-mode conversion](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android17-release/services/audiopolicy/service/AudioPolicyInterfaceImpl.cpp).
- [Valid communication devices and SCO suspension](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/services/core/java/com/android/server/audio/AudioDeviceBroker.java), [forced call muting](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/services/core/java/com/android/server/audio/PlaybackActivityMonitor.java) and [focus ownership](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/services/core/java/com/android/server/audio/MediaFocusControl.java).
- [AOSP delayed profile reconnection](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/745ee92b87ed62b5a8a1bac47d5df8cad623bf59/android/app/src/com/android/bluetooth/btservice/PhonePolicy.java). This source explains the six-second retry mechanism; it is not a trace of the Pixel's reconnect caller.
