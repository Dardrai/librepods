# Game Mode: current support and native integration boundary

LibrePods already controls Game Mode through the USB dongle. A private SDK37
Pixel switch is now available for controlled trials behind the validated native
boundary. A controlled native On/Off transition has now passed on the Pixel10
ProXL with Pro3 firmware9A348, accepted48kHz/256kbps AAC-ELD and continuous
playback. Broader release checks remain pending. The first preview refused its
Off clock request; the current implementation exposes that refusal and bounds
any retry to actual accepted audio progress. The successful captured trial
admitted both clocks on their first attempts, so it does not establish the
original refusal's exact reason.
The SDK37 Pixel prototype now includes guarded native media observations,
controller flush read/write completions, exact-baseline restoration and a finite
encoder-clock operation. Its controller round trip has passed on the Pixel 7 Pro;
the coordinated Pixel 10 trial below extends that evidence to one On/Off cycle.
These remain private experiments. The Note8 prototype has pinned, read-only
Start/Stop, DelayReport, actual source-peer and SCO helpers, with generation
guards. On the Note8, controller flush-timeout updates and their acknowledged
restoration are still missing. AACP control `0B` alone is insufficient to implement the coordinated
policy below. Keep the private trial separate from broader release support.
Encoder/codec readiness is separate from
Game Mode readiness.
The [AAC-ELD/Xposed design](AAC-ELD-XPOSED.md) tracks that separate native route.

## SDK37 Pixel validation stage

The private Pixel implementation resolves named functions and validates their
instruction roles and complete loaded bodies before installing an independent
Game Mode hook transaction. Unsupported Game Mode contexts leave the working
AAC-ELD route available. The actual BTA handle, AVDT handle, local media CID,
peer address and ACL handle are distinct and cross-checked on Bluetooth's native
main thread. A Java connection event or cached HAL delay cannot establish them.

The app communicates with the Bluetooth process through explicit, signed-app
broadcasts with shared sender identity, UID/package checks, process/client
nonces, increasing request IDs and an app-owned Binder lease. It accepts only
verified Google or AOSP Bluetooth packages with UID1002. No Bluetooth audio is
acquired through this control channel.

The first hardware prerequisite is deliberately separate from receiver tuning:
read the selected connection's Automatic Flush Timeout (`0C27`), briefly write
96 slots (`0C28`), read 96 back, write the exact captured original, then read that
original back. The original can be zero. A successful write acknowledgement
alone does not prove restoration. Commands bind to the native peer, media and
encoder generations and ACL; cleanup may outlive an encoder reset only while
the original peer, media incarnation, ACL and local CID still match.

The debug-only, non-exported `NativeGameProbeReceiver` exposes query, read and
that bounded round trip for the lab. It never sends the receiver's `0B` target,
starts a finite clock adjustment or changes codec/bitrate/packet grouping.
Native operation deadlines and restoration run independently of app polling.
Late native callback contexts remain owned until reclaimed, rather than being
freed on a Java timeout. A lost acknowledgement is treated as uncertainty.

The separate finite clock API uses accepted media frames and CLOCK_BOOTTIME,
retains the 100 ms primer and existing bounded catch-up behavior, and normalizes
on stop, owner change, queue pressure, cancellation or expiry. Its completion
is quantized to whole 480-sample access units. Neither source-clock accounting
nor a DelayReport measures acoustic latency.

### October 26 clock and control recovery fixes

A Pixel 10 follow-up caught a cancelled slow-clock adjustment after a 23,402 us
media gap, with the host queue empty. Ordinary playback already tolerates such
timer gaps through its four-AU catch-up budget. The old two-period clock guard
cancelled sooner. The repaired clock uses that same bounded catch-up capacity
(40 ms normally, 44 ms at interval 11), without enlarging queues or changing the
codec, primer or target. Completion is checked only after the whole callback's
accepted-AU ledger and a settled next media deadline. A first catch-up AU cannot
claim drift that later AUs undo. Remaining debt after a full burst cancels;
underflow, discard, reset, queue pressure, ownership and expiry still normalize
the clock. Thirteen sanitizer test targets and three regression mutations pass.
The later private manual acceptance below covers this repair on one Pixel 10;
the host checks do not establish acoustic latency.

The app's AACP validation callback runs both before and after a control write.
It previously moved the freshness boundary again during post-write publication,
which could reject a fast report that had already arrived. The timestamp now
comes from the exact owned-socket `beforeWrite` boundary, once. A separate
attempt receipt distinguishes a rejected, never-written target from a possibly
partial write. Only the former can avoid a new remote-restoration obligation;
an uncertain write still needs owned restoration and fresh readback. An explicit
preference change can retry pending same-owner cleanup once, without a polling
retry loop. Seventy-five focused transport, engine, policy and UI checks pass,
including a reply received during write before publication and a publication
failure after bytes were written. This does not turn a write ACK into proof of
the AirPods' target or acoustic latency.

Forced cleanup now retains its existing bounded report window after native
restoration finishes; a later valid Off report is still consumed. Previously,
the first native-restored tick moved straight to the unresolved state and
ignored that reply. Reports outside the original two-second freshness window,
known unsent writes and replaced control owners remain unconfirmed. A failed
Prepare that lost its owner before the first HCI read has a separate terminal
no-mutation proof. Only the exact request with no remote debt can be retired
that way; this is not called verified restoration and does not invent a baseline.

The UI distinguishes unsupported, waiting, failed-start and pending-restoration
states. A restored native controller with an unconfirmed remote target is not
reported as an active Game Mode or as fully confirmed Off.
The normal future restore obligation during startup/active playback is not an
error; tests cover the actual active/issue-NONE/pending-obligation state.

The private Pixel 10 follow-up (Pro 3, firmware 9A348) completed On and Off with
the captured controller baseline of 0 retained. Same-process receipts showed
fresh receiver reports, completed finite clocks and exact native restoration;
the listener reported good sound. About five minutes of subsequent host
observations stayed on one encoder generation without additional PCM underflows
or host discards. That is limited live evidence for this debug comparison, not
a default change or an acoustic-latency measurement. Background admission,
interrupted transitions and other supported devices remain separate checks.

The final October 26 manual check used private APK SHA-256
`3177cfb4908fa26b5786bfadafc5600fa6858bcd5d5ccf003676a5e19baa2264` and native library
`379898fd7941fbe484babf9dabbd2e46f5fe8ee937ea0547e867c1482f8c73d9`.
The user repeated On for five seconds, then Off for five seconds, and reported:
“Works now—switch and audio are correct.” Same-process observations remained on
media generation 1 and encoder generation 70. Final Off request 31 had no held
lease or pending restoration, controller baseline/current both 0 with verified
restoration, and a completed clock at normal interval 10 after a 77 ms drift
budget. The cumulative underflow/discard counters stayed at 127/116 throughout
the observed check; these are unchanged totals, not a claim of zero earlier
faults. Local evidence is `outputs/codec-quality-followup-20261026/game-accepted-private.json`.

This APK used `privateGameKeepFlushBaseline=true`, retaining the captured 0-slot
controller baseline. The normal source default remains 96 slots; the private
result does not validate that default or change it. Five-second user actions
are not measured transition times, and neither the drift budget nor the final
receipt measures acoustic latency.

Clock request acceptance is separate from actual encoder admission. A queued
request can be refused while the source is late or its queue is full. The
private implementation records a bounded, metadata-only admission reason and
retains every timing and ownership check. For transient media-age, deadline-debt
or queue-pressure refusals, it waits until a real accepted non-primer audio
callback and retries on Bluetooth's main thread within the original one-second
deadline. Polling alone cannot trigger a retry. At most one retry is queued;
cancellation, ownership changes and expiry invalidate it. A settled refusal
releases the saved controller baseline promptly and keeps the transition
unconfirmed rather than treating a zero clock serial as success.

Before treating the private switch as release support, extend the controlled
proof below to cancellation, calls and normal playback recovery. The Pixel 10
Pro XL's Bluetooth native library and framework match the tested Pixel 7 Pro
byte for byte in the October 25 preflight. Its later private ELD listening check
also passed. The saved Pixel 10 capture contains the same accepted ELD/Delay
Reporting negotiation and two accepted Starts separated by a Suspend. Its
subsequent typed-native controller round trip passed after that capture ended,
so that first capture does not prove controller operations. A later Pixel 10
capture contains the coordinated trial below. Pixel 7's full On/Off transition
and broader lifecycle checks remain pending.

### Pixel 7 Pro live controller round trip

The October 25/26 private SDK37 trial completed the selected connection's exact
Automatic Flush Timeout sequence: baseline **0 → 96 → 0**. The final zero was
read from the controller after restoration, not inferred from the write ACK.
All commands and completions named the same ACL handle `000B`, and every
Command Complete status was zero.

| Operation | Request frame | Completion frame | Verified result |
| --- | --- | --- | --- |
| Read original, `0C27` | 2557 | 2558 | 0 slots |
| Write trial value, `0C28` | 2560 | 2563 | 96 slots acknowledged |
| Read trial value, `0C27` | 2568 | 2569 | 96 slots |
| Restore original, `0C28` | 2573 | 2576 | 0 slots acknowledged |
| Verify original, `0C27` | 2577 | 2580 | 0 slots |

The same capture accepted the known 48 kHz/256 kbps Apple AAC-ELD configuration
at frames 388/390. SetConfig explicitly included Delay Reporting, category `08`
with zero payload bytes, alongside Media Transport and Media Codec. A fresh
peer DelayReport at frame 391 reported 1500 tenths of a millisecond (150 ms),
acknowledged at frame 392. Open completed at 393/395 and Start at 1046/1070.
The local media CID was `0043`, linked to peer CID `7B06` at 396/399. These are
capture identifiers, not a native BTA/AVDT handle assignment.

The five controller operations occupied 58.912 ms on wire, from the first Read
to the final readback; the app-side operation took about 237 ms. This establishes
controller acceptance and exact restoration on one active ELD route. There was
no AACP `09`/control `0B` write, receiver-target change, coordinated finite-clock
trial, Suspend or reconfiguration in that interval. It does not establish a
complete Game Mode transition, acoustic latency, or restoration during SCO,
disconnect, ownership replacement or a lost completion.

The local capture `game-roundtrip-private.btsnoop`, SHA-256
`f6747408c5be26b4824e3dd6b2a467a1f58a46053fad7f818e9eae3b7e3c02a0`,
contains 4052 complete records over 50.155982 seconds, with no reported drops,
truncated records or L2CAP reassembly errors. One 475 µs timestamp inversion at
2265/2266 is outside the controller sequence. The preceding bounded capture,
`game-initial-private.btsnoop` (SHA-256
`f83b8152017457e82f5ed954880ecac1dc1c10951d73fb3c9204cb3e2adcee9c`),
contains accepted ELD/Delay Reporting/Open but no Start, controller flush command
or receiver-target write. Capture absence claims cover only those saved intervals.

After capture cleanup and the Bluetooth restart, tracing was off and the user
confirmed clean playback and working stem controls. The complete native Game
Mode cycle was not tested by that controller-only trial.

### Pixel 10 coordinated On/Off trial

Pixel 10 Pro XL, Android 17 `CP3A.261005.005`, completed one controlled On/Off
cycle with Pro 3 model `A3064`, firmware `9A348`, using private APK SHA-256
`05f8215b4bd1b8c05b7541cf1fe4ff9785b03b21984371ff23efab3f60dc6c8c`.
The user reported continuous, clean music through both changes.

The local `game-clock-admission-private.btsnoop`, SHA-256
`d9d05c279005d285b1bafad5fdec0fcca34d8a9d06aae6ba5f26d4d41ff7d25c`,
accepted the existing 48 kHz/256 kbps ELD configuration at389/391 with negotiated
Delay Reporting `08/len0`. The initial report392/393 was1500 tenths ms.

| Operation | Request frame | Completion/report frames | Verified result |
| --- | --- | --- | --- |
| Read original flush | 12576 | 12579 | 0 slots, controller status00 |
| Apply/verify Game flush | 12583 / 12590 | 12585 / 12591 | 96 slots acknowledged and read back |
| Receiver target60 ms | 12622 | Delay12633 / ACK12634 | Fresh report1550 tenths ms |
| Receiver target150 ms | 15221 | Delay15237 / ACK15238 | Fresh report560 tenths ms |
| Restore/verify original flush | 15497 / 15501 | 15498 / 15505 | 0 slots acknowledged and read back |

Independent native receipts tie request1 to actual clock1, 11 ms cadence and an
85 ms drift budget, then Completed4 at normal10 ms. Request2 ties to clock2,
9 ms cadence and94 ms drift, then Completed4 at10 ms. Both admitted on their
first attempts; this hardware trial does not exercise the transient retry or
establish the original preview refusal's cause. Fresh reports need not equal
the requested receiver targets, and neither reports nor source-clock completion
measure acoustic latency.

The file contains19,526 complete records over112.771062 seconds, with no
reported drops, truncation or L2CAP reassembly errors. Two timestamp inversions
of1 and134 µs are outside the command/report exchanges. Its8689 single-AU RTP
packets have continuous sequence numbers and480-sample timestamp steps except
one2400-sample step across the initial pause. No independent AAC decode is
claimed. Codec, bitrate, grouping, volume and hearing profiles were unchanged.

The captured build restored the measured controller baseline and normal clock
but incorrectly reported StaleOwner during graceful cleanup. A deterministic
regression reproduced a held-state check racing explicit Restore. The fix
rechecks that the hold is still live under the same mutex before applying that
optimistic abort; actual HCI ownership checks are unchanged. It passed42 native
HCI/runtime checks and13 encoder checks with sanitizers.

The follow-up private APK SHA-256
`de3ac2de7425b0c3df5ac1348c372cf1fd8a12afb66298b7da0ceaf4a02571b0`
was installed and ordinary audio was confirmed. The user stopped before its
final On/Off test, so that earlier build did not complete a final live cycle.
The later baseline-preserving build's manual acceptance is recorded above.
Capture cleanup completed and tracing was off; raw evidence remains local.

### Delay Reporting negotiation race

One later Pixel 10 reconnect negotiated ELD without Delay Reporting. This was
visible on wire before Game Mode admission: `game-zero-delay-private.btsnoop`
(SHA-256 `7df83356bb65b56b0af1cbe7aaf01d387dd60e21b8a2ec88349bf6870e014520`)
used legacy GetCapabilities at frame 363 and SetConfig373 contained only
Media Transport `01` and Media Codec `07`. Reporting `08` was absent, so zero
delay/report serial in the native snapshot correctly meant no negotiated report.
Game Mode must remain unavailable in that session rather than inventing a delay.

The successful `first-eld-private.btsnoop` received the peer's AudioSink SDP1.3
response at983 before Discover1000/GetAllCapabilities1003. In the failed session,
the host's service-discovery sequence509–552 arrived after SetConfig373 and still
reported the peer's AVDTP1.3 support. Earlier SDP323–348 ran in the opposite
direction, from AirPods to phone, and did not supply Android with the peer's
sink version. GetAllCapabilities is signal `0C`; legacy GetCapabilities is `02`.

The retained native stack explains this choice. `bta_av_next_getcap` passes
`all_capabilities = avdtp_version > 0x0102` to `AVDT_GetCapReq`, which selects
`0C` or `02`. `tBTA_AV_SCB::OnConnected` loads the selected peer's two-byte
`AvdtpVersion` cache entry. The A2DP SDP callback updates that version from the
real service and persists a nonzero result; its failure path clears the current
SCB version. Thus capability discovery can run before usable peer-version
evidence arrives. This does not establish whether the failed run had a missing
cache entry or a later clear. A selected-key read after the third, valid
reconnect returned `03 01` (little-endian `0103`); that later cache value is not
a snapshot of the failed negotiation.

No cache or native negotiation patch has been applied for this race. A repair
must use actual per-peer SDP/version evidence and correct discovery ordering;
globally forcing GetAllCapabilities or setting the reporting flag would bypass
that contract. The complete failed capture has 10,998 records over72.576864s,
with no reported drops, truncation or L2CAP reassembly errors. Only its saved
interval is covered by the absence of Delay Reporting.

The complete retained Note8 symbol inventory has no simple
`L2CA_SetFlushTimeout`/`BTM_SetFlushTimeout` entry point. `L2CA_FlushChannel`
discards queued packets, and `L2CA_SetChnlFlushability` controls packet-boundary
flushability; neither sets the controller's automatic flush timeout. The GD ACL
connection exposes asynchronous automatic-flush read/write methods, but safely
owning that connection, dispatching on its handler and verifying completion is
still a prerequisite. The prototype reports `ControllerFlushUnavailable` rather
than treating one of the queue operations as a successful Game Mode transition.

## Existing paths and evidence

| Path | Established behavior | Limit |
| --- | --- | --- |
| LibrePods dongle | The live switch sends feature operation `7`, body `04 01` or `04 00`, then reads audio status with `09`. Byte 12 is the requested preference; byte 13 is the policy phase; flag `08` in byte 15 reports eligibility. Firmware permits Off even after eligibility is lost. | A requested preference is not an active-policy report. Phase 2 is sent/unconfirmed; phase 3 requires a fresh AVDTP delay report, not an acoustic measurement. |
| Dongle firmware | Recognized Pro 3, firmware major at least 8, negotiated AAC-ELD and endpoint Delay Reporting are required. It waits 500 ms after active playback, requests 60 ms and restores its explicit 150 ms policy on disable/pause/source or microphone changes. | These are the dongle's eligibility and restoration rules; they do not establish Android host support. |
| Captured macOS transition | The October 14 transition sent 60 ms, then 150 ms; immediate delay reports were 28 ms and 90 ms. Controller flush values were 96 and 320 slots, or 60 and 200 ms. Finite clock adjustments completed, with clean playback reported by the listener. | The run used patched Mac host checks. It proves one coordinated transition, not a universal original target, a 500 ms Mac rule or measured speaker latency. Idle/suspended restoration was not established by that capture. |
| Current NTPods policy | A 500 ms Start gate, explicit 60/150 ms targets, fresh-report wait, finite clock adjustment and media flush coordination. Its supported Pro 3 AAC path is independent of AAC-ELD. | Windows reopens its media channel to change flush settings because its driver cannot update them in place. Do not assume that mechanism or its fallback is available on Android. |

The wire command is a four-byte little-endian millisecond value, not a boolean:

```text
60 ms:  04 00 04 00 09 00 0B 3C 00 00 00
150 ms: 04 00 04 00 09 00 0B 96 00 00 00
```

The original receiver target is not generally readable. AVDTP DelayReport is a
different value and must not become the restoration target. Here 150 ms is an
explicit Windows/dongle restoration policy, not a discovered Android default.
There is no evidence that switching Game Mode must select ELD, change bitrate,
change RTP timestamps or change packet grouping. Those belong to the audio route.

The USB switch permits Off when its current requested preference is On, including
after the eligibility flag clears. Enabling still requires that flag. The UI
binds the edit to its displayed editor revision and replaces the audio controls
when controller, selected peer or control session changes; existing link,
microphone and controller transaction guards still apply. The controller checks
the current reported eligibility when admitting On and immediately before its
RPC commit, so a queued enable cannot outlive loss of the support flag. Off does
not acquire that eligibility requirement. This recovery change
does not establish new firmware behavior or native Android Game Mode support.

## Smallest required native boundary

Expose the private native UI only when the hook reports a usable boundary for
the actual route; keep unsupported routes unavailable. Java connection state, an Apple identity flag,
encoder availability and successful AACP transmission do not provide it.

| Hook observation/action | Required ownership and completion |
| --- | --- |
| Current route snapshot | Selected peer identity, ACL/media generation, codec, stream state, accepted Start time from a monotonic clock, Delay Reporting negotiation, current SCO/microphone demand and exclusive route ownership. |
| Delay observation | A valid AVDTP DelayReport for that media session, with its own reception serial and value in tenths of a millisecond. Preserve distinct receptions of identical numbers. |
| Flush preparation/restoration | Actual controller/media-path success for 96 slots and restoration to the original captured value, with the same owner checked before and after. The Pixel 7 Pro active-route round trip above passed; suspended/SCO and failure-path restoration still need live validation. |
| Receiver target write | Current AACP socket plus the matching audio owner/generation, 60 or 150 as LE32. Mark the restore obligation before a write that may reach the peer. A lost send result is not proof that nothing changed. |
| Finite clock adjustment | A versioned source-clock operation with a drift budget, a normal-clock cancellation path and a completion report tied to the same owner/clock generation. Expiry or interruption is failure, never successful drift. |
| Stop/SCO/disconnect | Cancel unsent application, restore the local normal clock, and retire stale callbacks. Remote restoration can only use an explicitly owned connection to the original peer; never send old cleanup to a replacement peer/session. Call audio must not wait for the full Game Mode transition deadline. |

`AACPManager.captureAudioSocket()`, `ownsAudioSocket()` and the guarded
`sendControlCommand()` can supply control-channel ownership. They cannot supply
the missing media observations, flush or clock actions. Those must come from the
real native A2DP route, including any offload path actually selected by Android.
Build-pinned Note8 Start/stop/timer functions have been identified, but static
function addresses are not a usable runtime adapter. Restoration while media is
suspended, especially during SCO, still needs Android-specific validation.
The source's `set_tx_flush` queue-discard function is not the controller's
Automatic Flush Timeout operation. Updating cached HAL delay metadata likewise
does not establish a fresh on-wire DelayReport.

Peer and host eligibility remain separate. NTPods reads canonical SDP attribute
`A001`, bit 20, for its ELD policy; explicit false disables it. Its tested Pro 3
AAC fallback must not be silently copied into native Android. An eventual Android
AAC policy needs its own verified host adapter and route evidence. The native
private Mac capability query `31` is also **not** LibrePods' AACP capability enum
`HEARING_AID_V2(31)`.

## Bounded transition contract and vectors

These are integration requirements derived from the current Windows policy,
not proof of a complete native Android transition. The pure
`NativeGameModePolicy` tests admission, owner/fresh-report guards and clock math.
The private SDK37 coordinator uses it and binds its actual AACP writer to the
original socket/control session and a fresh native Hold proof. The Note8's
`ControllerFlushUnavailable` continues to reject admission. Bind every pending
operation to the peer, media/control generations and explicit user intent.
Apply only after all adapters are ready, Delay Reporting is negotiated, the
reported delay is at least 60 ms, Start has settled for 500 ms and no SCO/microphone
or competing route operation is pending. Do not invent a delay for an unknown
report or import Windows' previously-held zero-delay exception without evidence.

Use a two-second post-write report deadline, a ten-second independent clock
safeguard and a fifteen-second transition deadline if porting this policy.
These are Windows policy bounds, not timings recovered from every Apple host.
Drain preceding reports before saving the serial for a target write. Only a
subsequent valid same-owner reception can drive the clock calculation. Successful
transmission, response arrival and completed coordination are separate results.

| Case / input | Required result |
| --- | --- |
| Hook missing; or flush/clock/offload adapter unavailable | No native Game Mode switch or target write. Keep ordinary playback available. |
| Pro 3 using Android AAC; only the Windows AAC fallback is known | No automatic native eligibility inference. Host readiness must be established independently. |
| Fully ready owner; Start age 499 ms | Wait without sending `0B`. |
| Same ready owner; Start age 500 ms; requested on; delay 1500 tenths | Admit one owned transition toward 60 ms. Do not declare it active or change codec/bitrate from this event. |
| Delay Reporting absent; delay unknown/zero; or current delay below 600 tenths | No new 60 ms application. Keep the reported/unknown state separate from the preference. |
| Fully ready owner; current delay exactly 600 tenths | Admit through the same owned prepare/write/fresh-report sequence. Equal post-write delay needs no clock drift; it does not bypass restoration. |
| Off before admission; peer/generation replaced; or SCO begins before the write | Cancel the unsent target. No delayed 60 ms write may escape the cancellation. |
| Target write accepted; only pre-write report serial is present | Remain unconfirmed; the cached number cannot complete the transition. |
| Fresh same-owner report has the same numeric value as before the write | Accept its freshness by serial, not numeric inequality. It still does not prove the target buffer value or audible latency. |
| Fresh report 280 tenths after target 60 | Windows calculation: interval multiplier `0.9`, drift 22 ms; use this post-write report, not the older 1500-tenths report. |
| Fresh report 900 tenths during explicit restore to 150 | Interval multiplier `0.9`, drift 60 ms. The target-150 exception does not subtract 10 ms. |
| Report from another peer/media generation; or clock completion from another clock generation | Ignore as confirmation and retire/cancel stale work; never publish active success. |
| No fresh valid report within two seconds; clock expiry; or transition deadline | Fail the transition, cancel the local rate adjustment and attempt only owned restoration. No automatic retry loop. |
| Off, pause, microphone/SCO demand or route loss after target may have been sent | Cancel further application, normalize the local clock, and retain an explicit original-peer restoration obligation until completed or marked unresolved. A disconnect is not remote restoration proof. |
| Saved preference is read without an active eligible route | Do not claim active Game Mode or send a target from UI hydration. Wait for real current-session admission. |

The finite clock calculation truncates the report's tenths to whole milliseconds.
Let `difference = abs(report_ms - target_ms)`. Subtract 10 ms only when the
difference exceeds 10 and the target is not 150. Use interval multiplier `1.1`
when the report is above target, `0.9` when below, or `1.0` when equal. For example,
`1500 -> 60` gives `1.1 / 80 ms`, `1499 -> 100` gives `1.1 / 39 ms`,
`1100 -> 100` gives `1.1 / 10 ms`, and `1110 -> 100` gives `1.1 / 1 ms`.
These are the tested NTPods calculation, not permission to approximate the source
clock by sleeping Java audio callbacks. A hook must measure completed versus
nominal submitted samples and report bounded completion.

## Source references and acceptance boundary

- Current LibrePods [dongle editor](app/src/main/kotlin/me/kavishdevar/librepods/dongle/DongleController.kt), [audio status decoder](app/src/main/kotlin/me/kavishdevar/librepods/dongle/DongleProtocol.kt) and [existing USB UI](app/src/main/kotlin/me/kavishdevar/librepods/presentation/screens/settings/DongleSettings.kt).
- Existing guarded [AACP transport](core/src/main/kotlin/me/kavishdevar/librepods/core/bluetooth/aacp/AACPManager.kt) and [control IDs](core/src/main/kotlin/me/kavishdevar/librepods/core/bluetooth/aacp/types/ControlCommand.kt).
- [Dongle Game Mode contract](https://github.com/Ripthulhu/airpods-dongle/blob/main/docs/CONTROL-EXTENSIONS.md#game-mode) and [firmware policy](https://github.com/Ripthulhu/airpods-dongle/blob/main/src/btstack/playback_options.h).
- [Captured Mac transition](https://github.com/Ripthulhu/ntpods/blob/main/docs/MACOS-AUDIO-CAPTURE-2026-10-12.md#working-gaming-transition), [Windows policy and limits](https://github.com/Ripthulhu/ntpods/blob/main/docs/WINDOWS-AUDIO-LATENCY.md#automatic-receiver-buffer-policy), and [Windows state machine/calculation tests](https://github.com/Ripthulhu/ntpods/blob/main/windows/daemon/src/playback/buffer.rs).

The private SDK37 receiver-target coordinator and control are available for
guarded trials. The separately controlled AAC-ELD prototype has codec-identity
hooks and an owned encoder-failure fallback. One controlled Pixel 10 cycle is
established above; release support still needs cancellation on SCO/disconnect,
explicit restoration and continued ordinary playback on failure. Confirm the
actual negotiated codec separately. Reported transport delay and a completed
toggle remain distinct from measured acoustic latency.

## Portable follow-up

Default builds keep `LIBREPODS_PRIVATE_OWNED_FDK=OFF`; they do not include the
private owned encoder used for these live results. The pushed source and passing
host tests do not turn an ordinary APK into this private ELD/Game Mode build.
See [the private encoder boundary](AAC-ELD-XPOSED.md) before preparing a local
experiment. Raw captures, device keys and private APKs are not repository inputs.

The current baseline-preserving private build passed the manual On/Off check
above, including final native Complete/error0, no hold/clock/restore obligation,
and exact original controller readback. Use diagnostic **query only** while the
coordinator owns a hold; the separate read/roundtrip probe has its own cleanup.
Do not force Game Mode when category08 was not negotiated: preserve ordinary
ELD playback and capture SDP/discovery ordering for the unresolved race above.

Calls/SCO and HQ-microphone recovery, Game Mode pause/case return, long-run
stability and Pixel 7's full On/Off cycle remain pending. Ordinary ELD one-bud,
stem and automatic case-return checks passed separately; they do not establish
those Game Mode lifecycle checks.

From `android/`, portable Android checks are:

```sh
./gradlew :app:testDebugUnitTest --tests '*NativeGame*' :app:lintDebug :app:assembleDebug
```

The native host suites require no phone or private FDK encoder. For example,
in a Unix host shell with CMake and an ASan/UBSan-capable compiler:

```sh
cmake -S tools/native-pixel17-hci-tests -B build/game-tests -DCMAKE_BUILD_TYPE=Debug -DCMAKE_CXX_FLAGS='-fsanitize=address,undefined -fno-omit-frame-pointer' -DCMAKE_C_FLAGS='-fsanitize=address,undefined -fno-omit-frame-pointer'
cmake --build build/game-tests -j2
ctest --test-dir build/game-tests --output-on-failure
cmake -S tools/native-eld-tests -B build/eld-tests -DCMAKE_BUILD_TYPE=Debug -DCMAKE_CXX_FLAGS='-fsanitize=address,undefined -fno-omit-frame-pointer' -DCMAKE_C_FLAGS='-fsanitize=address,undefined -fno-omit-frame-pointer'
cmake --build build/eld-tests -j2
ctest --test-dir build/eld-tests --output-on-failure
```
