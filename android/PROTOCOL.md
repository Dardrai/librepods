# Android protocol contracts

These contracts follow the captured formats and NTPods implementation at
[`58ad399`](https://github.com/Ripthulhu/ntpods/tree/58ad3998f18e89ea1ed611b9a2e8aec11b7382bf).
They describe wire handling, not a guarantee that every feature works on every host.

## Dongle settings

Dongle hearing actions require report-2 capability `0x20`. A client/session-bound
lease covers baseline reads, edits, verification and mode cleanup, preventing a
concurrent PC edit from being silently overwritten. Expired leases cannot be
reacquired inside an action; older firmware needs an update. Completed RPCs retain
their receipt, while failed requests must finish cancellation before cleanup or
release. Queued device-setting writes complete only after local transmission;
device readback remains separate. The
[dongle reference](https://github.com/Ripthulhu/airpods-dongle/blob/main/docs/CONTROL-EXTENSIONS.md#settings-workflow-lease)
defines lease actions, tokens and deadlines.

## Boolean control values

Ear Detection control `0x0A` uses `2` for enabled and `1` for disabled. Ordinary
boolean controls such as Conversation Awareness and Allow Off use `1` for
enabled and `2` for disabled. Boolean writes and state decoding must use the
identifier's mapping rather than treating `value == 1` as universally enabled.
The Ear Detection mapping agrees with Apple's consumer setter and NTPods;
this is static source evidence, not a new controlled Android wire capture.

Known boolean reports require a value of `1` or `2` and zero padding. Invalid
reports preserve the previous confirmed value and pending request. Ear Detection
retires a pending choice only on a matching report; a contradictory valid report
does not confirm it. Pending choices expire through the existing request lifetime.
Persisted raw reports keep their wire values across upgrades; changing the host
decoder does not authorize another device write.

## Connection state and parsing

Only reports from the current control socket update device state. AACP message
headers include the full little-endian opcode; high-byte aliases are rejected.
Malformed reports preserve the last accepted state. Connection-specific capability
and hearing reports are cleared when their transport is replaced or retired.

The capability request is `04 00 04 00 01 00 08`. A response starts
`04 00 04 00 02 00`, followed by a count and fixed-width ID/value entries. IDs
`03..08` and `30` carry four little-endian bytes; other recognized IDs carry one.
Unknown widths, duplicate IDs, truncation, count mismatches and trailing bytes are
rejected. Values remain raw bytes: Apple mappings can use `2` affirmatively, so
`value == 1` is not a general capability test.

Connected-host reports (`0x002E`) must contain exactly `9 + 8 * count` bytes.
Duplicate or malformed entries and trailing data are rejected. Routing logs do
not expose peer names or addresses.

## Press and Hold

### Native call controls

Control `0x24` is a versioned packed Call Management setting. The reviewed legacy
values are `00 03` (double press to end) and `00 02` (single press to end), optionally
zero-padded to four bytes. They do not establish a complementary mute gesture.
Cached iOS 26.1 code decodes the low nibble as version: version 0 describes end-call
fields; version 1 also has independent mute status/configuration fields. The Apple
sender updates end-call bits 8–11 and mute bits 16–19 separately. These static
sources do not justify sending a guessed version-1 payload or explain all native
mute prerequisites. Sources: `bluetoothd_49-26.1.mm` functions `sub_1003388B8` and
`sub_100338AC0`, and `CBStackControllerBTStack-26.1.mm` `modifyDevice:btDevice:settings:error:`.

LibrePods offers only the reviewed end-call choice. Mute/Unmute directs the user
to the phone's call controls, including restored legacy mute-detail routes; it
does not send an end-call value under a mute label. A fresh supported legacy
report from the current transport is required before writing. Missing, cached-only,
unknown-status or newer-version reports cannot be flattened into legacy values.
Writes capture the current model metadata and control socket, reject retired
sessions and publish intent only after a successful write. Pending intent stays
separate from reports. Opening a screen, receiving an echo and reconnecting never
automatically write `0x24`. Stable action route tags survive locale changes;
unrecognized saved translated labels are disabled.

On 2026-10-23, Pro 3 A3063 firmware 9A348 with installed Pixel89 on Android 17
(SDK 37) and stock Phone reported legacy `00 03` freshly. The user confirmed that
ending a short call worked. A single press produced three lower-pitched tones
without changing the Phone mute icon. In a second call, muting manually in Phone
and then pressing the stem still did not toggle Phone's mute state. The other
party also confirmed that pressing the stem did not silence the user's voice:
uplink mute failed in this tested configuration, rather than merely failing to
update the icon. This does not establish behavior in other calling apps or
identify the cause.

A bounded 120-second metadata monitor observed another Call Management report
and no Software Mute report among the selected application log events. It was
not a complete Bluetooth/HFP packet trace; absence from that log window does not
prove that the accessory never transmits a mute request or identify its transport.

Apple's [WWDC23 session 10233](https://developer.apple.com/videos/play/wwdc2023/10233/)
describes host handling of the mute gesture: iOS mutes uplink and notifies the app;
macOS requires the application to mute its input. Cached iOS code separately
handles received control `0x27` (Software Mute), stores its value and posts
`com.apple.audioaccessoryd.MuteState`. That notification's constant state `1` is a
change signal, not a decoded microphone-mute value. No verified Android host-mute
handler, native mute capture or complete version-1 configuration is implemented.
HQ microphone recording and ordinary AVRCP controls are separate paths.

Call configuration never writes raw-gesture control `0x39`. Hold customization
continues to write exactly `08` (holds only) or `00` (fully native); bits for
single/double/triple presses remain clear. Host handling ignores those ordinary
press events. Packet regressions cover both legacy end-call values, unsupported
payload preservation, failed/retired writes
and the existing left Assistant/right Noise Control arrangement with cycle `0B`.
Those tests establish software contracts, not physical call behavior.

### Hold delegation

No native hold control `0x16` report has been observed on the Pixel. Android hold
choices therefore use LibrePods' existing raw-gesture mechanism: control `0x39`,
bit `0x08`, delegates holds from both buds while leaving other presses native.
The service dispatches each hold using that bud's saved action. The other bud's
Noise Control action uses the same validated native cycle selection as the editor,
including pending or saved choices when a mask echo is unavailable. Its fallback
uses only the model/firmware-supported modes. Off remains opt-in where the
accessory requires Allow Off.

Cycle selection writes native control `0x1A` once, only after explicit Apply.
Its shared mask is Off `01`, ANC `02`, Transparency `04` and Adaptive `08`;
any supported subset of at least two modes is accepted (for example `0B` omits
Transparency). The subset applies to either stem assigned Noise Control; per-ear
action assignments stay separate. Unknown or malformed current masks are not a
read-modify-write baseline: the user must choose a complete subset. Opening the
screen or receiving a report never writes a mask. Apply rechecks the captured
model and control socket; unsupported Adaptive or an unmet Allow Off prerequisite
cannot be submitted. A successful send remains unconfirmed intent until a fresh
matching device report. Companion-saved choices retain that same unconfirmed
status and are not applied automatically on synchronization or reconnect.

The configuration is reapplied to each new control transport. Events carry their
source socket; retired sessions and devices without local audio output cannot
launch an assistant or cycle modes. A failed configuration write does not change
the saved choice. Assistant launch targets Android's configured assistant package.
On Pixel72, the user confirmed that holding the left stem opens the assistant
from the Home screen and holding the right still changes listening modes.

On Pixel83 with A3063 firmware 9A348, the user enabled Allow Off, applied only
Off + Adaptive + ANC to the right stem's shared cycle, and confirmed repeated
holds cycle those three modes while skipping Transparency. The left Assistant
assignment remained selected. Android received no cycle-mask echo; the requested
selection remains separate from confirmed device state. This result is
user-observed behavior, not fresh `0x1A` wire readback.

The 45 saved Tahoe captures contain 16 hold-action and 32 cycle-mask writes,
all Mac to AirPods; none is a device report or a per-setting read request.
In `20261015T184734Z-91b4ace1`, initialization frames 4158 (`0x16`) and
4169/4488 (`0x1A`) are outgoing, while neighboring controls arrive from the
Pro 3 pair. No marked configuration change was recorded. Tahoe build `25G241`
understands the incoming `0x16` layout (current right/left, then previous
right/left), but its receive dispatcher sends `0x1A` to Unsupported control type.
The cycle getter reads a host cache. These observations do not establish a
device readback method; a marked native configuration change with fresh incoming
reports is still needed. Never use send success or a host getter as confirmation.

## Conversation Awareness

The enabled setting is control `0x28` in opcode `0x09`; accept only normalized
`1`/`2` values. Activity opcode `0x4B`, byte 9, drives owned media ducking and
recovery only. It never changes the enabled setting. A successful current-session
setting request remains separate from the last report until a fresh valid report
or disconnect; lack of an echo is not confirmation of the older value. A report
received during the write wins over that request. Retiring the transport also
clears the activity stage so a new session's first speech event is observable.

Speech transitions also travel as ordered, live events carrying their source
socket. The service subscribes before connecting and handles them independently
of UI updates and cache writes; the conflated state snapshot is not an event
queue. Retired sockets, disabled settings and replaced devices cannot start
ducking, and cached activity is never replayed when the app opens. Stages `2` and
`3` only modify an existing speech session; terminal stages restore only volume
still owned by that session.

Island mode announcements correlate the live activity stage with confirmed mode
reports. A speech-triggered Transparency change and one return to the preceding
mode are silent. Terminal stages persist, so they do not suppress later manual
changes; a pending restoration expires two seconds after speech ends. A matching
local mode request takes precedence over this presentation suppression. An
unrequested Transparency transition while the feature is enabled waits 150ms for
a speech event that can arrive just after its mode report. This wait never
delays speech ducking or changes the reported listening mode.
Current-session activity sequence and successful mode-request markers survive
conflated state updates, including a speech start/end or request/echo between
collector turns. They are cleared on transport retirement and never cached.

## Independent hearing modes

Control `0x2C` carries enrollment first and Hearing Aid enable second. Preserve
enrollment; unknown enrollment never becomes enrolled merely because a switch is
pressed. Control `0x33` is shared: keep it on while either Hearing Aid or Media
Assist is on. Unknown or contradictory reports do not establish Off.

A complete PME report is 140 bytes, beginning
`04 00 04 00 53 00 84 00`. Its 132-byte body has these fields:

| Body offset | Field |
| --- | --- |
| 0 | Selector/version `2` |
| 1 | PME Voice enrollment |
| 2 / 3 | Voice / Media enable (`1` on, `2` off) |
| 4 / 36 / 68 / 100 | Voice-left, Media-left, Voice-right, Media-right: eight float32 LE values each |

Media Assist starts with an exact current report and changes only the two enable
bytes, both on or both off. Preserve enrollment and all 128 curve bytes, including
their bit patterns. Missing profiles are never synthesized. The separate short
`0x53` subtype-1 route property (`01 01` / `01 02`) has unresolved activation policy
and is not written. These controls do not establish where processing occurs.

Mode changes are serialized and bound to their current transport. Enabling
requires a supported, enrolled identity, complete current mode reports, a fresh
valid saved-fitting read and inactive Custom Transparency. Hearing Aid selects
Transparency; Media Assist preserves the listening mode. Success requires fresh
reports for every field written and, for enables, byte-identical fitting readback.
Unchanged complete current-session reports remain valid; an unchanged PME curve
report is not required to be emitted again. Partial
send failures and timeouts remain unconfirmed. An explicit same-session Off
recovery preserves the other mode and sends no fitting or subscription writes.
It does not restart a microphone session or force a control reconnect.

Generic independent `0x2C`/`0x33` writes cannot bypass the mode coordinator.
Custom Transparency enable requires both hearing modes confirmed off; unknown is
not off. Fitting edits remain separate from pure mode switches and do not inherit
authorization from an older connection or an in-progress mode request.

Hearing fitting values are exactly 104 bytes, with header `02 00 60 00`,
`02 00 64 00`, `02 02 60 00` or `02 02 64 00`. The `02 00` forms are read-only;
`02 02` permits validated edits. Writes normalize the header to `02 02 64 00`
and preserve untouched body bytes, including both eight-band audiograms and
own-voice values. Unknown headers, lengths, flags and out-of-range values fail
closed. The complete Custom Transparency value `02 00 00 00` establishes only
inactive, unenrolled state; it cannot supply an editable profile.

The edit coordinator carries the caller's original raw baseline. Before a write,
it must match both the current cache and a fresh read in the same connection.
Only matching device readback is published. Hearing readback must have a known
fitted header and the exact 100-byte requested body; Custom Transparency must
match every requested byte. An unchanged complete original record permits at
most four further reads, 75 ms apart, without repeating the write. Other
mismatches fail immediately and retire that cached editing baseline. ATT work
reserves microphone/refresh admission and cannot publish into a replacement
control session. These coordinator checks do not supply the missing transport.

**The Android ATT reader and writer currently return unavailable.** Consequently
this build cannot confirm a fresh fitting or enable these modes, even if a
profile was fitted on an iPhone. A parser or successful packet send alone does
not authorize enablement.

## Android ATT access

Classic ATT uses authenticated, encrypted BR/EDR PSM `0x001F`. Android's public
`createL2capChannel` selects LE; its internal `createL2capSocket` selects secure
Classic L2CAP. The existing socket helper already selects Classic type 3 with
authentication and encryption. The regular socket-manager path checks the active
user and Bluetooth connect permission; offload has an additional privileged gate.
See the pinned [BluetoothDevice](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/745ee92b87ed62b5a8a1bac47d5df8cad623bf59/framework/java/android/bluetooth/BluetoothDevice.java)
and [socket manager](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/745ee92b87ed62b5a8a1bac47d5df8cad623bf59/android/app/src/com/android/bluetooth/btservice/BluetoothSocketManagerBinder.java).
These source paths do not prove that a Pixel accepts PSM `0x001F` or that the
AirPods permit a protected read from this host.

AOSP reads `bluetooth.device_id.vendor_id` and
`bluetooth.device_id.vendor_id_source` when enabling Bluetooth. Their generated
API is read-only; this alone is not proof that property-service writes are
impossible. The property context and caller's SELinux permissions also apply.
See [Device ID properties](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/745ee92b87ed62b5a8a1bac47d5df8cad623bf59/sysprop/device_id.sysprop),
[record registration](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/745ee92b87ed62b5a8a1bac47d5df8cad623bf59/system/btif/src/btif_core.cc),
[property contexts](https://android.googlesource.com/platform/system/sepolicy/+/4571ddd9440721fec583c906a337de949a77749e/private/property_contexts)
and [shell policy](https://android.googlesource.com/platform/system/sepolicy/+/4571ddd9440721fec583c906a337de949a77749e/private/shell.te).
The existing native identity hook runs through Xposed inside the Bluetooth
process. An ADB-started Shizuku service does not run in that process or establish
an Apple Device ID. No rootless identity change or Pixel ATT probe was performed
for this comparison; platform denial and AirPods identity rejection remain
unverified on that device.

An enabled transport still needs UUID discovery for the service and value handle,
serialized and bounded ATT exchanges, the advertised write method, and exact
readback tied to the same request and control connection. Properties `0x16` mean
Write Command `0x52`; use `0x12` and require `0x13` only when Write is advertised.
Do not revive fixed-handle writes or subscribe to a guessed CCCD. The removed
legacy socket body never ran and did not meet those requirements.

## Host clock and sensors

Once a fresh current-device identity establishes hearing support, one `0x001B`
calendar-clock message is submitted for that control connection:
`04 00 04 00 1B 00`, uint32 LE Unix seconds at 6, uint32 LE microseconds at 10
(below 1,000,000), uint16 LE UTF-8 text length at 14, and whole-second RFC3339
text at 16 with UTC `+00:00` and no NUL. Both representations use one clock
sample. Out-of-range clocks are rejected. This is unrelated to RTP timestamps.

Discovered RTBuddy heart-rate report mappings take precedence over legacy IDs
`19/20/84`. Descriptor-generation changes, including unknown-only descriptors,
retire stale mappings. Once discovery withdraws a known HR mapping, legacy report
fallback remains suppressed until a new sensor/control session. Report IDs and
firmware-specific activation service IDs are separate. Unknown services do not
invent a supported sensor, and no unverified SPL scale or meter is exposed.

Bud Role `0x08` requires the complete four-byte body. Preserve unknown primary
and flag bytes; an unrecognized primary selector cannot change ear-detection
mapping. CaseInfo `0x23` decodes only the established v3/25-byte body, including
the split uint64 case version, uint32 product and reserved value, and bounded
strict UTF-8 name. Both snapshots belong to the current AACP socket and are
cleared on retirement. Malformed or stale reports cannot replace them. CaseInfo
does not authorize a nearby BLE connection and its case version is not a DULT
firmware value. Routine handling does not log the case name.

## Charging-case BLE battery fallback

The separate case advertisement is accepted only for a saved, bonded AirPods
Pro 3 with a current, nonzero 16-byte advertising key. Exactly one Apple type
`07`, length `11` record must occur in a complete manufacturer-data TLV stream;
its total size is 19 bytes and byte 2 must be `06`. AES-128-ECB decrypts bytes
3..18. The plaintext must begin `29 20`, have zeros at 8/10/11, and have zero
upper four bits in lid byte 2. Slots 3/4/5 are case/left/right: `FF` means absent;
otherwise bit 7 is charging and the remaining percentage must be 0..100.
`7F` is invalid here. Variant bytes 6/7, transient byte 9 and counter bytes 12..15
stay opaque; neither a fixed variant nor a monotonic counter is required.

The advertising key plus layout associates a case report with one saved pair;
it does not authenticate a sender or establish an IRK-resolved earbud identity.
Ambiguous matches are rejected. Saved disconnected pairs can receive battery
updates without an AACP socket or IRK. Case packets cannot trigger pairing,
connection, lid invitations, finder ownership, ear state or media ownership.
Live connected component readings keep priority. Missing readings may be filled,
but case-sourced presence and charging expire after the existing 90-second
battery lifetime, including while the earbuds remain connected. An absent slot
keeps only its previous percentage and original timestamp when caching is on.

An identical encrypted block never renews freshness for that pair/key, even
after address rotation, scanner restart or battery expiry. Any changed block,
including a counter decrease or reset, can be a fresh observation if the scan
timestamp is current and newer than the preceding accepted case report. This
duplicate rule is not replay protection. Key changes, unbonding and service
retirement discard the association history.

The layout and changing variant/counter behavior follow the source and synthetic
regressions in [Stuffz AirPods at `8bd0354`](https://github.com/stuffz/airpods/tree/8bd035451668388860e62fcc0a4a624a608d9d3a).
The strict checks above are this client's acceptance policy. Live short-format
acceptance remains unconfirmed; the local closed-case check received only the
existing bud format. A public synthetic regression uses key
`000102030405060708090a0b0c0d0e0f`, plaintext
`29200a3ddedd510b00000000f7821600`, and manufacturer record
`071106aea248933e2159872f2c5ae5c8bf0f31`.

## Saved diagnostic decoding; live access remains unavailable

There is no live diagnostic read or self-check trigger in this Android implementation.
The offline parser accepts `A4120002` version 8 at exactly 71 bytes: uint64 LE raw
measurement timestamps at 1/9, uint32 result words at 17/21, opaque ANC bytes
25..32, uint32 first-use values at 33/37, and an opaque 30-byte tail at 41..70.
Preserve unknown bits, tail and raw timestamps; never subtract a fixed timezone
offset. These cached dates are not Hearing Test dates or a current diagnosis.
Component interpretation requires product `2014/2024/2027`, a nonzero timestamp,
and completed measurement status 0/1 (bits 3..5); general status uses bits 0..2.
Full unsigned timestamp/result widths, unknown bits and the opaque tail are
retained. ANC scores have the separate `201B` product gate; Pro 3 does not pass
it. A self-check baseline requires both ears' timestamps to change independently
and both resulting measurements to be completed; an unchanged ear is not fresh.

Sensitivity (`82F6BC0A-1BCA-4873-AFC9-EC5890E3469A`) accepts one finite float32 LE;
volume curve (`D5F96AFA-2F2C-41BB-A7E6-F54ABE6235B4`) requires exactly 17 finite
float32 values. These decoders do not establish personal thresholds or absolute SPL.

The discovered `A4120004` ASCII `31` Write Request and exact ATT `13` response
prove submission only. Fresh per-ear results are needed to establish a measurement.
No automatic or user-worn sweep is triggered, and no dosimetry value is presented
as an absolute sound-pressure level without verified stimulus, weighting and scale.

## Charging-case sound research

The unsuccessful case-sound experiment has been removed from App Settings,
including its discovery, pairing and playback actions. A restored legacy screen
returns to App Settings. The normal charging-case sound preference, battery
observations and read-only case metadata remain separate.

Protocol parsers and guarded GATT/window helpers remain for research tests; no
production screen invokes them. The PnP fixture identifies Apple vendor `004C`,
source `1`, Pro 3 case product `2029`, version `1`. That identity does not prove
ownership or bind the case to saved Classic AirPods.

Routes stay separate:

| Route | Service / characteristic | Start / Stop |
| --- | --- | --- |
| DULT | `15190001-12F4-C226-88ED-2AC5579F2A85` / `8E0C0001-1D68-FB92-BF61-48377421680E` | `00 03` / `01 03` |
| Hawkeye configuration | `FD44` / `4F860002-943B-49EF-BED4-2F730304427A` | `01 00 02` / `01 01 02` |
| Hawkeye non-owner | `FD44` / `4F860003-943B-49EF-BED4-2F730304427A` | `01 00 03` / `01 01 03` |

DULT Command Response is exactly six bytes: `02 03`, request opcode uint16 LE,
status uint16 LE. Its separate completion is exactly `03 03`, as specified in
[DULT draft 03, section 3.13.4](https://datatracker.ietf.org/doc/html/draft-ledvina-apple-google-unwanted-trackers-03#section-3.13.4).
Hawkeye configuration response is exactly seven bytes: `01 0B 02`, request opcode
uint16 LE, status uint16 LE; `01 0D 02` is a separate completion event. Unknown
non-owner reply framing is not interpreted using the configuration protocol.

The retained window helper attempts Stop once after at most two seconds,
including time waiting for the Start write, or sooner on cancellation or detected
audio activity. Stop does not prove the case obeyed it. A cancelled/timed-out ATT
operation poisons confirmation on that connection: best-effort Stop cannot
inherit a late Start callback. The GATT helper saves, restores and reads back the
original CCCD before closing its owned connection, without changing bonds.

ATT acceptance, typed responses, completion and audible playback remain distinct.
Windows tests were silent; these research fixtures do not establish a working
locator.
