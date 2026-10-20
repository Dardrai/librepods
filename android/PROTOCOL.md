# Android protocol contracts

These contracts follow the captured formats and NTPods implementation at
[`58ad399`](https://github.com/Ripthulhu/ntpods/tree/58ad3998f18e89ea1ed611b9a2e8aec11b7382bf).
They describe wire handling, not a guarantee that every feature works on every host.

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

Control `0x16` carries the current right action first, then the left action:
`1` selects noise control and `5` selects the native assistant action. Outgoing
commands zero the remaining two bytes. Incoming reports may carry previous
actions in those bytes; only the current pair is retained. See the
[NTPods control contract](https://github.com/Ripthulhu/ntpods/blob/5502e286ba941ad25e664224fa7f94a23363ad95/docs/WINDOWS-AIRPODS-CONTROL-PROTOCOL.md#setting-values-and-confirmation).

Changing one bud preserves the other bud's pending or freshly reported action.
Cached pairs are cleared when loading or retiring a control session, so an old
host's setting cannot supply that other action. A failed write leaves the saved
preferences unchanged. A silent successful write retains its pending pair until a
matching report or transport retirement. Settings pages use that pair before
the last report. App-only legacy preferences do not establish a device action.

Assistant invocation uses the connected host's headset/assistant integration;
the on-device action and Android invocation need a hardware check together.

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
complete mode reports and, for enables, byte-identical fitting readback. Partial
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

## Diagnostics remain unavailable

There is no diagnostic read or self-check trigger in this Android implementation.
For future read support, `A4120002` version 8 is exactly 71 bytes: uint64 LE raw
measurement timestamps at 1/9, uint32 result words at 17/21, opaque ANC bytes
25..32, uint32 first-use values at 33/37, and an opaque 30-byte tail at 41..70.
Preserve unknown bits, tail and raw timestamps; never subtract a fixed timezone
offset. These cached dates are not Hearing Test dates or a current diagnosis.
Component interpretation requires product `2014/2024/2027`, a nonzero timestamp,
and completed measurement status 0/1 (bits 3..5); general status uses bits 0..2.

The discovered `A4120004` ASCII `31` Write Request and exact ATT `13` response
prove submission only. Fresh per-ear results are needed to establish a measurement.
No automatic or user-worn sweep is triggered, and no dosimetry value is presented
as an absolute sound-pressure level without verified stimulus, weighting and scale.
