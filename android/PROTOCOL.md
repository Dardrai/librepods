# Android protocol contracts

These contracts follow the captured formats and NTPods implementation at
[`17c91110`](https://github.com/Ripthulhu/ntpods/tree/17c91110fab6066146c7967724fd49d88f708a16).
They describe wire handling, not a guarantee that every feature works on every host.

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

`A4120005` fitting headers `02 00 60 00` and `02 02 60 00` are readable, but not
editable. Their eight-band per-ear curves are preserved. **The Android ATT reader
and writer currently return unavailable.** Consequently this build cannot confirm
a fresh fitting or enable these modes, even if a profile was fitted on an iPhone.
No Windows host-identity override or connection-refresh workaround is assumed to
work on Android. A successful packet send alone never enables a UI switch.

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
