# USB absolute volume broker

The optional Pico volume broker uses the existing LibrePods root service and an
SDK37-only Xposed hook in `system_server`. Other Android versions leave the bridge
unavailable. It is off unless the user explicitly enables
it for that board and the selected firmware advertises the volume-authority capability. The request
is saved by board serial; active ownership and tokens are never saved. Off and
Forget remove the request. Reconnection and reboot can restore it only after fresh
identity/readiness checks, confirmed cleanup, and paused playback, microphone,
call and settings work, including when the settings screen is closed. A real
activation/lease failure blocks automatic retries for the same target until an
explicit retry or a fresh connection. Ordinary
USB audio and standard host volume remain available without root or the broker.
Firmware 0.1.45 introduced this capability; 0.1.46 retains the same version-1
firmware contract. Physical-master activation adopts the AirPods' confirmed gain
instead of aligning it to Android's saved index. The current version-4 ordering
patch has not been installed or tested on hardware. Earlier listening results
below distinguish the host-alignment and version-3 physical-master previews.
Actual USB PCM unity remains unmeasured.
After installing an updated module, the new system-server hooks require a phone
restart. Restarting Bluetooth alone does not load them.

With the broker off on the tested firmware 0.1.46, standalone near-maximum gain and Android's
software attenuation remain separate. A stem volume-up can therefore reach the
AirPods' physical maximum and play its cue below Android's maximum index. This
is not repaired by granting USB permission or merely installing the firmware's
authority API. The broker must actually own the native route and physical-gain
lease before this behavior can be considered fixed.

## Ownership

The signed app provides fresh firmware observations through its bound root IPC.
The root helper checks capability 128, snapshot version 1, known physical gain,
idle write state, USB scope and a live authority token. Control session,
connection generation, USB media generation, token and board serial must stay
the same. A lease is bounded to five seconds. The app uses a 500 ms expiry margin;
the root helper uses 250 ms.
An existing lease may renew while a physical SET awaits readback; an unknown
physical level cannot start a lease or update Android's confirmed volume.
Unknown capability, denied root, unsupported Android version or missing hooks
leave the ordinary USB path in place.

The UID0 helper checks the selected audio device's ALSA card against the board
serial in sysfs before inspection, activation, every renewal and every confirmed
volume notification. It returns the verified device address during inspection
and binds that address into the firmware proof. A changed address or unreadable
identity retires the current policy. No app-supplied verification boolean is
accepted. System server does not read those sysfs files: their labels deny that
access under the stock enforcing SELinux policy.

The system-server endpoint independently checks the actual media route, a sole
USB audio output, its exact root-verified address and the USB framework's board
serial. It refuses a preexisting
absolute/fixed/full-volume behavior or native USB driving-stream mapping.

Activation acquires the firmware lease and adopts its exact confirmed physical
level. It sends no initial feature `17`/action `3` SET and does not round that
level through Android's saved index. After enabling native USB unity routing,
the endpoint seeds Android's UI through the official volume notification path,
under its own-operation guard. Registration still uses the four-argument
`setDeviceAbsoluteVolumeBehavior(attributes, volume, executor, listener)` API.
`notifyAbsoluteVolumeChanged` updates framework state without echoing another
listener request; no guessed stem increment or duplicate media key is needed.

Physical gain remains exact in the `0..127` lease domain. Only the host UI is
rescaled to `0..getStreamMaxVolume(STREAM_MUSIC)`. Any positive physical level
maps to at least host index `1`, so physical `1` or `2` cannot become Android PCM
mute through display rounding. Physical zero remains zero. The exact pre-mute
physical level is retained only for the current lease and restored on an unmute
that carries no new slider intent. An explicit slider change wins over that
remembered level. Activation and a plain unmute with an exact remembered level
do not round-trip physical gain through a coarse UI index.

Callback registration and native AudioPolicy unity routing belong to the same
system-server lease. The root process never directly enables the native mapping.
Root Binder death, app-owner death, firmware-lease expiry, USB topology changes,
foreign policy changes and audio-server recovery retire the lease. Cleanup removes
the replay cache, disables native unity routing, then unregisters the callback.
Failed cleanup retains its obligation and prevents another activation until the
disable is confirmed. A stale owner cannot undo a replacement policy.

Firmware owns physical gain and suppresses its ordinary HID volume relay during
the authority lease. Android index changes become bounded physical-gain requests;
only subsequent firmware observations become Android volume notifications.
The application does not add a PCM gain stage or synthesize duplicate media keys.
A newly observed native gain retires older queued host requests, including requests
still waiting for controller I/O at their pre-commit guard. An exact acknowledgement
of our own SET preserves later slider intents; a differing clamp or external report
becomes the new truth. This is local observed-event ordering, not a synthesized
firmware timestamp or proof of acoustic timing.
The pre-commit guard also inspects the transport's fresh physical snapshot, so
a newer revision or unsettled gain cannot be ignored merely because it has not
yet reached the publication loop. All publication paths share the same in-flight
host SET identity; a checkpoint that receives its acknowledgement first cannot
mistake it for an unrelated native change.

System server assigns a monotonic, lease-local sequence to each valid host volume
request, including a duplicate or mute/unmute request. An exact own SET
acknowledgement carries that sequence back through the root bridge. Under the
same system-server gate that accepts new key requests, an older acknowledgement
is not published over a newer, different target. The actual physical proof still
renews the lease. A newer same-target request is compatible; exact unmute
restoration can still seed a host index of zero back to its remembered gain.
Native or clamped observations carry sequence zero and remain authoritative.
Unchanged duplicate physical observations update proof freshness without
inventing another native gain change. This does not add firmware fields or
infer a native gesture timestamp.

## Firmware handoff contract

Capability `128` advertises the existing typed report-3 feature extension. Feature
`16` returns exactly 24 bytes, version `1`. Multi-byte fields are little-endian:

| Offset | Field |
| --- | --- |
| 0 | Version `1` |
| 1 | Flags: known gain `1`, pending `2`, in flight `4`, active authority `8`, eligible USB scope `16`; no other bits |
| 2, 3 | Confirmed and desired raw levels `0..127`, or `255` when absent; confirmed exists exactly when known, desired exactly while pending or in flight |
| 4 | Physical-observation revision, u32 little-endian; root activation/renewal proofs require nonzero |
| 8 | Current USB owner's token, u32; zero without ownership |
| 12 | Remaining lease milliseconds, u32; active `1..5000`, otherwise zero |
| 16 | USB media generation, u32; active scope requires an odd generation |
| 20 | Reserved zero, u32 |

Feature `17` is `[17, action, tokenLE32, level]`, exactly seven bytes. Actions are
`0` acquire, `1` renew, `2` release and `3` SET. Acquire uses zero token and level;
renew/release require the current nonzero token and zero level. SET accepts
`0..127`. Every successful action returns the same snapshot. Only the USB client
may manage authority; phone clients may inspect it but receive no owner token.
An already-active lease is not stolen after an app restart: acquisition waits
for its expiry. The firmware's USB client identity is shared, so separate host
processes must not compete for the token.

The lease binds USB media generation, report-2 control generation, selected peer
and AVRCP channel. The app also fences its own local control session; these are
distinct identifiers. Pause/resume preserves the scope. Session, peer, channel
or ownership loss revokes it. Report-3 sequence and generation validation remain
required; a successful transport SET is admission, not physical confirmation.
After SET, Android waits up to 1.5 seconds for confirmed gain, cleared
pending/in-flight flags and a different physical-observation revision in the same
token/USB scope. A fresh settled clamp or intervening native gesture can differ
from the requested level; Android mirrors that actual level and keeps unity.
A stale baseline, unknown gain or pending write is not confirmation.

Renewal returns a fresh remaining TTL but need not change the physical revision.
Android renews about every 750 ms and notifies the framework only for a changed,
confirmed, idle observation. Revisions represent accepted device observations,
not renewal, queued intent, a synthesized UAC notification or acoustic dB
calibration. Preserve the full raw `0..127` range and actual native stem reports.

While authority is active, standalone UAC gain requests, HID volume relay,
anchor repair and headroom adjustment must not compete with the bridge.
Release or uncertain loss cancels unsent authority writes and retains actual
gain; an already-transmitted command may finish and must not be replayed.
Firmware remains quiet for that USB session after release or expiry because it
cannot prove that Android restored software attenuation. A fresh lease may
adopt current gain; a new USB session may return to standalone behavior. Android
must confirm native-policy cleanup before releasing firmware authority. If that
cleanup fails, the app leaves the firmware quiet until the lease expires.

Protected ATT/hearing/accommodations workflows now retain native unity and their
whole-workflow controller mutex and settings token. They renew physical/native
authority at complete RPC boundaries, after all response pages have been copied;
they never interleave another command into an unread report-3 response. The
checkpoint reuses the existing target, does not nest `withTarget`, and suppresses
its own recursive hooks. Direct settings-lease acquisition is checkpointed too.
The normal background heartbeat resumes between workflows.

Each checkpoint has one aggregate 1,000 ms budget, including its settings-token
renewals, physical read, volume renewal and root publication. A settings RPC uses
the smaller of its ordinary 6,000 ms limit and the fresh remaining volume-lease
budget minus 1,500 ms reserved for cancellation and renewal. The remaining budget
reaches each transport watchdog and late-return validation; polling deadlines alone
cannot bound a blocking read. Confirmed cancellation gets its own 1,000 ms budget. Timeouts,
lost scope and missing readback fail without replaying a settings write; genuine
lease/owner loss still retires unity safely. Live continuity through these
checkpoints remains a hardware validation requirement, not an accepted result.
The shared hidraw path ultimately uses a kernel-owned blocking ioctl. Closing
its root handle currently waits for that handle's in-flight operation, so the
watchdog is not a hard preemption guarantee during a real kernel/USB hang.
Such transport failure can expire the lease and retire native unity; this patch
does not claim uninterrupted playback through failed USB I/O.

## Framework constraints

The reviewed [Android17 AudioDeviceVolumeManager](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/media/java/android/media/AudioDeviceVolumeManager.java)
offers absolute-volume registration and `notifyAbsoluteVolumeChanged`. Registration
requires `MODIFY_AUDIO_ROUTING` or `BLUETOOTH_PRIVILEGED`; notification requires
`MODIFY_AUDIO_SETTINGS_PRIVILEGED` or `BLUETOOTH_PRIVILEGED`. Hidden methods and the
complete lifecycle hook set must match before the endpoint is published.
Internal discovery uses version `4` for atomic acknowledgement ordering and rejects
older boot-loaded endpoints, including the version-3 physical-master policy. The firmware snapshot contract remains
version `1`; this changes no firmware wire fields. An older endpoint loaded
before an app update cannot be used until the phone restarts.

An unsuccessful native-policy activation can return `failure_stage` (`preflight`,
`framework_registration`, `callback_ownership` or `native_unity`) and
`exception_type` alongside its existing reason and cleanup receipt. These fields
describe only that attempt and contain no exception message, serial or profile.
Root filesystem verification failure returns `root_identity_unavailable`.

Readiness resolves the selected board in the root helper, rather than requiring
the phone app's cached `AudioManager` device list to contain it. Inspection may
request device ID zero to resolve the sole matching USB output; the result
contains a positive `device_id` and root-verified `device_address`. Activation
and subsequent proofs bind both values and never accept zero as an active ID.

Root and system server read fresh device-port snapshots through the guarded
SDK37 Java API. The inspected Pixel framework exposes
`AudioSystem.listAudioPorts(ArrayList, int[], int)`, with port type `1` selecting
device ports. The known two-argument API lists all ports and is filtered to USB
sinks. Unknown layouts, failed reads, invalid generations and ambiguous outputs
leave the bridge unavailable. This does not reset Android's global port cache.
A controller-owned public audio-device listener wakes readiness after topology
changes; an epoch fence rejects an older inspection that finishes afterward.

Generic callback registration alone does not prove a unity PCM path. The reviewed
[AudioService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/services/core/java/com/android/server/audio/AudioService.java)
does not add USB to its native absolute-volume driving streams through that call.
The [native AudioPolicy implementation](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android17-release/services/audiopolicy/managerdefault/AudioPolicyManager.cpp)
applies the driver stream's maximum curve level for nonzero absolute-volume
indices. Its mapping currently ignores device address, so multiple USB outputs
are rejected. Do not assume older low-index pre-scale behavior or infer native
unity from a reported `ABSOLUTE` behavior alone.

## Earlier hardware results

The previous host-alignment preview was checked on Pixel 10 Pro XL, Android 17,
Pro 3 and dongle 0.1.46. Listening accepted clean playback, native stem/index
following and no premature maximum cue while active. Explicit Off removed both
USB mappings, On restored them, and each transition preserved the current media
index and non-USB mappings. The volume-retention preview restored both USB
mappings in the background after a fifteen-second closed-case return without
pressing Connect. The index stayed at 19/25; listening remained clean and stem
changes followed the phone without an early maximum cue. These results do not
validate the new physical-master activation or prove actual USB PCM unity.
Phone-reboot retention has not been repeated on hardware.

The version-3 physical-master preview was installed on Pixel 10 Pro XL, Android 17
with dongle 0.1.53. Native framework and AudioPolicy USB mappings activated;
the AirPods retained raw gain 103 while Android displayed 20/25. The user
confirmed native stem following and the correct maximum cue. An active stereo
float AudioFlinger track showed zero dB track/channel/stream/port gain and no
effects, which supports the intended route but is not a digital USB measurement.
Fast phone-key changes exposed an older ACK restoring host index 24 after a
newer key target had reached 15, followed by the latest ACK returning it to 15.
The user also reported a pause/resume level drop; uncontrolled volume changes
prevented a conclusive before/after cause. Full acceptance was not reached.

The version-4 ordering preview was then installed on the same Pixel and firmware,
and the phone restarted to load its matching system-server endpoint. Both USB
mappings activated. The user confirmed smooth rapid phone-button adjustments;
AudioService recorded targets falling from 21 to 15 while intermediate older
acknowledgements no longer raised the index, followed by the final index-15
notification and settled physical gain 76/127.

For one controlled pause/resume comparison, the user chose maximum volume while
paused, then played for ten seconds, paused for five and resumed for ten without
changing volume. The user reported unchanged loudness. Forty cached observations
over 58.5 seconds retained gain 127/127, host index 25/25, revision 11, USB
generation 5, the same control/status scope and an active framework mapping.
The greatest reported observation age was 1,280 ms; no sample timed out. The
final native AudioPolicy mapping was also present. This validates that cycle,
not the cause of every earlier reported drop or actual digital USB amplitude.

Separately, a Pi 4 GPIO SWD link to the PC Pico 2 W returned RP2350 DPIDR
`0x4c013477` (DPv3). With the exact 0.1.53 r2 ELF, a MEM-AP-only capture read two
allowlisted scalar snapshots without creating a CPU target, halting, resetting
or accessing flash. The snapshots and fences matched: gain 119/127, no pending
volume request and no active command-17 lease while PC PCM was idle. These are
PC-board observations, not evidence about the phone's pause cycle. OpenOCD was
shut down after the 0.641-second command. The Pi's linuxgpiod driver does not
enforce `adapter speed`; its configured value is not a measured SWD clock limit.

An independent PC USB quality check played an eight-second 48 kHz stereo float
reference through the existing shared WASAPI route. All 384,000 stereo frames
matched completed ISO OUT payloads bit for bit, including sub-S16-resolution
content. Two canceled transfers occurred after the reference and were excluded.
This establishes sample preservation for that Windows test window, not Android's
native-unity policy or downstream AAC encoding. A separate Pixel float AudioTrack
test selected USB alternate 1 (S16_LE); USBmon captured no ISO traffic, so actual
Pixel USB sample amplitudes remain unverified. Float AudioFlinger or AudioTrack
formats must not be presented as proof of float USB transport.

## Validation still required

The version-4 candidate passed 176 targeted ordering, transport, policy and
coordinator checks. After adding the cached diagnostic, 48 service/lifecycle
checks passed, including two new diagnostic tests; the debug build and lint
also passed. These counts include repeated lifecycle checks. Source checks do
not establish the result on the phone.

For a live before/after capture, use the existing service's memory-only snapshot:

```
adb shell dumpsys activity service me.kavishdevar.librepods/.services.LibrePodsService --dongle-volume
```

This command does not open USB, issue a firmware request, initialize an unused
controller or attach a debugger. It reports the last observation's age and scope,
gain, revision and authority flags, without addresses, profile data or token
values. TTL and pending flags describe that observation, not a new device read.
An unavailable or old snapshot cannot prove the current gain or lease state.

Offline tests cover admission, stale generations, owner replacement, bounded
renewals, failed cleanup, native-before-callback cleanup ordering, framework map
iteration, atomic old-ACK admission and capability guards. The version-4 hardware
checks above cover activation, rapid phone keys and one maximum-level pause
cycle. Broader acceptance still needs low/middle/high indices, mute, stem changes,
removal, re-enumeration, process death and audio-server recovery. Compare measured USB PCM
gain while the AirPods' physical gain changes; a successful registration or a
source file's format cannot substitute for that check.
