# Android protocol contracts

These contracts follow the captured formats and NTPods implementation at
[`58ad399`](https://github.com/Ripthulhu/ntpods/tree/58ad3998f18e89ea1ed611b9a2e8aec11b7382bf).
They describe wire handling, not a guarantee that every feature works on every host.

Native Android AAC-ELD and coordinated Game Mode remain research work. See
the [Xposed integration boundaries](AAC-ELD-XPOSED.md) and
[Game Mode ownership and timing contract](GAME-MODE.md). MediaCodec support,
Apple identity and a successful receiver-buffer write do not establish an
active low-latency Bluetooth route. The existing USB Game Mode control is a
separate, implemented path.

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

## Automatic settings and live edits

Routine settings use their current reports without an Apply, Read settings or
Refresh action. AACP initialization subscribes to device reports; opening a
settings screen does not resend saved choices or reconnect audio.

Native ATT initialization belongs to one control socket and session. It waits
for Connected, a known supported model and idle microphone/call state, then
reads the full hearing profile followed by Custom Transparency where supported.
One complete AHPS read publishes both the profile and its validated Audiogram
source. Busy microphone, recording, call or settings work defers an unstarted
read rather than skipping it permanently. Each admitted characteristic read is
bounded to 15 seconds and attempted once per session; a failed bearer is not
reopened repeatedly. Replaced sessions and cancelled workers cannot publish old
records. Loud Sound Reduction's UUID remains unverified on native Android, so
initialization does not substitute a captured fixed handle for it.

Full-record edits retain one in-flight request and one latest desired record,
with 150 ms pacing. Further field edits build on that latest intent. Exact own
readback advances the baseline before the next request; identical cloned polls
do not reset a gesture. Temporary busy state preserves the queued target but
prevents its next send. A foreign record or owner retires it, and a failed write
callback cannot become success merely because a cached value matches. Cancelling
a gesture drops its unsent tail; it does not claim to undo an in-flight write.
The final send still checks the captured socket/session and the complete current
baseline. AHPS header normalization and exact CT comparison remain as described
below.

These ordinary edits never force an AACP refresh or reconnect to obtain an echo.
The separate clinical mode confirmation path below retains its owned refresh
when fresh mode reports are required. Dongle controls remain gated by the
selected firmware's capabilities, current peer and settings lease; a native
feature does not imply dongle support. Saved diagnostic reads remain separate
from explicit test starts, calibration, sensor activation and sound playback.
No test or sound is started by settings initialization. Note8 testing confirmed
automatic profile loading without a Read action; it did not establish audible
Headphone Accommodations processing. Subsequent loading-race and accessibility
fixes have offline regression coverage and still need live acceptance. Capture
results below retain their recorded builds and conditions.

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

Cycle selection sends native control `0x1A` through the live latest-value queue;
there is no Apply step. Only a complete supported selection can be submitted.
Its shared mask is Off `01`, ANC `02`, Transparency `04` and Adaptive `08`;
any supported subset of at least two modes is accepted (for example `0B` omits
Transparency). The subset applies to either stem assigned Noise Control; per-ear
action assignments stay separate. Unknown or malformed current masks are not a
read-modify-write baseline: the user must choose a complete subset. Opening the
screen or receiving a report never writes a mask. Each send rechecks the captured
model and control socket; unsupported Adaptive or an unmet Allow Off prerequisite
cannot be submitted. A successful send remains unconfirmed intent until a fresh
matching device report. Companion-saved choices retain that same unconfirmed
status and are not applied automatically on synchronization or reconnect.

Raw-hold delegation is reapplied to each new control transport. Events carry their
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
their bit patterns. Clinical enrollment comes from `2C` and the validated fitted
record; an opaque PME enrollment zero is retained rather than converted to one
or used to hide independently known enable flags. This does not authorize the
separate legacy Headphone Accommodations writer. Non-finite PME curves invalidate
the report. Missing profiles are never synthesized. The separate short
`0x53` subtype-1 route property (`01 01` / `01 02`) has unresolved activation policy
and is not written. These controls do not establish where processing occurs.
With known PME flags, an explicit clinical `33` Off reports Media Assist Off
even when legacy Phone/Media flags are On. This presentation does not bypass
the separate complete-report, enrollment and consistency checks for enabling it.

Mode changes are serialized and bound to their current transport. Enabling
requires a supported, enrolled identity, complete current mode reports, a fresh
valid saved-fitting read and inactive Custom Transparency. Hearing Aid selects
Transparency; Media Assist preserves the listening mode. Success requires fresh
reports for every field written and, for enables, byte-identical fitting readback.
Unchanged complete current-session reports remain valid; an unchanged PME curve
report is not required to be emitted again on the same owner. If one second
passes without confirmation, the coordinator may reopen its owned AACP/ATT
connection once, without repeating the mode writes or reconnecting native audio.
The replacement must report complete fresh `2C`, `33` and PME values matching
the requested modes, original enrollment and every original curve byte.
Preflight, send and confirmation share a 15-second cancellation deadline.
Microphone use, recording, calls and replacement owners block this refresh;
cancellation closes only its owned connection. Partial sends, mismatches and
timeouts remain unconfirmed. An explicit Off recovery preserves the other mode
and sends no fitting or subscription writes. Unknown status is shown separately
from an unchecked switch, with an explicit Turn Off action when recovery is
allowed.

Clinical adjustment drags use the full-record queue above. Each submitted
profile carries its exact baseline; additional edits remain available while its
readback is pending and coalesce into one latest target. An own acknowledgement
rebases that target without losing another field edit. Identical polling replies
do not reset the draft. Changed records or sessions retire it, and a switch edit
does not carry a previously cancelled slider adjustment into its write.

Generic independent `0x2C`/`0x33` writes cannot bypass the mode coordinator.
Custom Transparency enable requires both hearing modes confirmed off; unknown is
not off. Fitting edits remain separate from pure mode switches and do not inherit
authorization from an older connection or an in-progress mode request.

Hearing fitting values are exactly 104 bytes, with header `02 00 60 00`,
`02 00 64 00`, `02 02 60 00` or `02 02 64 00`. The `02 00` forms are read-only;
`02 02` permits validated edits. Writes normalize the header to `02 02 64 00`
and preserve untouched body bytes, including both eight-band audiograms and
own-voice values. Unknown headers, lengths, flags and out-of-range values fail
closed. The complete AACP `09/2C` control value `02 00 00 00` establishes only
inactive, unenrolled Hearing Aid state; it cannot supply an editable profile.

The edit coordinator carries the caller's original raw baseline. Before a write,
it must match both the current cache and a fresh read in the same connection.
Only matching device readback is published. Hearing readback must have a known
fitted header and the exact 100-byte requested body; Custom Transparency must
match every requested byte. An unchanged complete original record permits at
most four further reads, 75 ms apart, without repeating the write. Other
mismatches fail immediately and retire that cached editing baseline. ATT work
reserves microphone/refresh admission and cannot publish into a replacement
control session. Native ATT now implements these reads and guarded writes over
the separate settings connection described below. Rooted Android 13 has verified
CT reads/scalar writes and the separate Hearing Aid mode/fitting checks below;
unrooted Pixel access remains a separate hardware check. Later named-preset
writes and readback succeeded on Note8, but their audible effect did not; see
the separate comparison below.
A parser, identity preference or completed packet send alone
does not authorize enablement.

## Headphone Accommodations

Legacy Headphone Accommodations uses a complete 140-byte AACP `0x53` packet:
header `04 00 04 00 53 00 84 00`, version `02` at offset 8, enrollment at 9,
Phone at 10 and Media at 11 (`01` enabled, `02` disabled). Four eight-float32
arrays follow at offsets 12, 44, 76 and 108: voice left, media left, voice right,
media right. Preserve enrollment and every curve byte when changing a flag.
The captured native host writes use enrollment `02`, while complete device
reports use `00`. Received decoding retains that zero as opaque; it must not
relabel zero as Off or modify the received snapshot. A legacy plan separately
constructs the captured command header `02` and the exact expected received
header from its original `00`/`02` record. Write eligibility requires both
clinical hearing paths independently known off; enrollment `01` is excluded
from legacy writes. It must not reuse clinical Media Assist's shared `09/33`
gate to turn on legacy accommodations.
Master-off sends both flags off while each app remembers the selected
Phone/Media destinations per pair.

Pro 3 / 9A348 captures on Tahoe 25G241 establish Balanced Tone, Vocal Range
and Brightness with three discrete levels: Slight, Moderate and Strong.
All nine stock 128-byte curve blocks match the pinned iOS 27.0.1 PersonalAudio
presets. They are tuning inputs at 250–10000 Hz, not newly measured hearing
thresholds. Native preset changes also update the separate Custom Transparency
EQ arrays: its offsets 4–35 and 52–83 equal the stock voice-left and voice-right
arrays. All four PME arrays within each stock preset are identical, so that
observation does not establish the mapping for personal curves.

Custom Transparency's 104-byte value includes Own Voice Amplification at
offset 100 (`0..1`, native centre `0.5`). Its combined amplification and balance
controls use `L = a - min(b, 0)` and `R = a + max(b, 0)`, with normalized
`a,b` in `[-1,1]` and wire gains clamped to `[-1,2]`. Tone remains `[-1,1]`.
PersonalAudio UUID `FC2D1EB8-2D5F-3C86-AC56-E49F730A76DD`, function
`transparencySettingsv4ForAddress:` at `0x2949106D8`, establishes that conversion.
Preserve noncanonical per-ear values when editing an unrelated field.

The typed native Phone/Media path uses AACP, but remains unavailable when its
incoming record cannot establish write eligibility. Full named
presets also require fresh ATT reads, a coordinated stock EQ update and complete
readback. The native coordinator captures both transport sessions, compares the
full starting CT and clinical records, and requires final CT readback and an
unchanged clinical profile. A PME echo alone cannot confirm the composite edit.
Partial failure or cancellation invalidates the editing baseline and remains
unconfirmed; it does not automatically overwrite a possibly changed profile.

A successful native send with complete ATT/profile verification is `SENT`,
separate from a confirmed received PME report. Its requested selection and
monotonic edit revision do not modify the received snapshot or its reception
revision. A follow-up edit can use that owned sent record only within the same
socket/session and with matching received and edit revisions. Fresh PME,
clinical/profile changes and owner loss retire the chain; partial sends or failed
verification block reuse until a fresh report. Clinical mode enable cannot plan
from the older received PME while such a requested chain exists. No automatic
reconnect, subscription replay or guessed `0F` getter is used to confirm a flag.

Audiogram is an explicit choice backed by a fresh, strictly validated enrolled
104-byte AHPS record. Unknown curves do not imply Audiogram, and unsupported
sources leave it unavailable. The legacy mapping averages left/right thresholds
when their PTA4 difference is below 15 dB; otherwise it selects the better-PTA
ear, including at exactly 15 dB. PTA4 uses 500, 1000, 2000 and 4000 Hz. The
8000 Hz endpoint fills the legacy 10000 Hz slot. Zero values in the 500–6000 Hz
slots take that endpoint; a zero 250 Hz value stays zero. The same normalized
vector fills all four PME arrays; both
CT EQ arrays use it clamped to `0..80`, preserving every other CT byte. PME
values remain in `0..90`. No new measured band or clinical fitting is created.
Full source equality is checked before and after the transaction, and AHPS is
never written by this operation. A fresh received record is classified as
Audiogram only against its validated current-session source.

The dongle coordinator performs both records under its existing
settings lease when eligible. Confirmation requires a newer, complete received PME report
matching the intended packet, plus complete CT readback when changed. Cached
reports and completed sends alone are insufficient. The native captures contain
startup PME reports but no immediate post-write echo; `0F` is not a proven getter.
Received prefixes are `02 00 02 02` (frame 4770) and `02 00 01 01` (frame 1504);
corresponding host prefixes are `02 02 02 02` (4907) and `02 02 01 01` (1659).
Neither of these two captures contains complete `09/2C` or `09/33` guard
reports; absence does not establish Off. Two additional saved native captures
resolve the direction-specific header comparison: `060195f7` host 5157 →
fresh received 7780 and `816495a0` host 3397 → fresh received 6256 have exact
equality in all 139 other bytes, including flags and all 128 curve bytes,
without an intervening full host PME write. Fresh guards at 7726/7738 in the
first capture report enrolled/Hearing Aid off and shared clinical gate off.
Its pre-write connection began before recording; the second capture separately
binds both channels to the same peer but lacks complete clinical guards.
Confirmation therefore compares an exact intended received record, retaining
its original enrollment byte; it does not ignore byte 9. A command-only header
difference is not a changed user setting. Restoration uses the saved raw
received record and the native command header independently.

Evidence captures: `20261024T142933Z-ee0c8aff` and
`20261024T150956Z-00aee364`. PME stock frames are 9569/9985/10037 (Balanced),
10261/10359/10379 (Brightness), and 30849/31398/31555 (Vocal). CT write targets
exist for all nine presets, with native reads for five. Raw captures, native
binaries and individual hearing profiles remain private. The Custom Audio Setup
wizard was a preference comparison; its branches, samples and clinical calibration
were not reconstructed.

The v3 Note8 / Android 13 test on Pro 3 A3063 / 9A348 subsequently verified all
four Phone/Media combinations in six flag changes and restorations. Each sent
one full 140-byte command with enrollment `02` and received the exact intended
record with its original enrollment `00`; all other 139 command/report bytes,
including the unchanged 128-byte curves, matched. Eligibility came separately
from fresh complete clinical Off reports (`2C=01 02 00 00`, `33=02 00 00 00`).
No clinical-mode or ATT write accompanied those flag transactions, and no
stock preset was applied. New startup PME reports arrived 1.285–1.437 seconds
after the command; complete guards and unchanged CT/AHPS reads were available
after 1.580–1.832 seconds. These measure data readiness, not UI or audible onset.

With both destinations On, the separate CT master/amplification test verified
four complete 104-byte writes and matching reads. Only offsets 0 and 36/84
changed; all unrelated CT bytes and the complete clinical profile remained
unchanged. Two inverse attempts were not exact before the final correction.
The controlled-test checkpoint at fresh control/ATT connections 166554/166614,
CT/AHPS reads 166691/166697 and PME report 166608 restored all original
104/104/140 bytes and both clinical Off packets. Later user-requested CT
full-scale Amplification with master On matched write/read 174081/174087 under
Phone/Media On and independent clinical Off reports. Manual exploration then
began, so the earlier restoration does not describe the current settings.
Those Note8 results did not establish audible legacy CT DSP behavior, native
named presets or calibrated gain. The later Windows acoustic result below is
separate. See the
[v3 flag and restoration evidence](https://github.com/Ripthulhu/ntpods/blob/main/docs/NOTE8-ATT-WRITES-2026-10-24.md#headphone-accommodations-flags-and-ct-with-pme-v3)
for private capture references and the retained failed inverse attempts.

A later Windows comparison on the same Pro 3 / 9A348 did establish audible
legacy CT processing with user-loaded Brightness / Strong stock curves,
Phone/Media On, Transparency listening mode and independent clinical Off
reports. The listener heard amplification `-0.5` to `+0.5` as louder. A second
comparison held both gains at `+0.5` and all 40 non-EQ CT bytes identical:
zero arrays first, saved stock arrays second. The listener clearly heard the
second interval differ. Full CT writes/reads 701/709 and 971/985 in
`windows-ct-curves-20261025-004322` confirm that only offsets 4–35 and 52–83
changed. Restore 1295/1303 matched all original 104 bytes; the final PME
restored both destinations Off, with unchanged 128-byte curves and all clinical
profile reads identical. No CCCD or other ATT write, clinical-mode write or
short `0x53` activation accompanied the comparison. This proves an acoustic
effect of those CT arrays in that configuration; it does not establish a
universal zero-array gate, a PME destination prerequisite, Android acoustic
parity or the native named-preset transaction. A separate Windows PME music
On/Off test was acoustically inconclusive. Windows negotiated Apple AAC-ELD;
the Mac wizard capture begins after stream configuration, so its actual codec
and any codec difference remain unverified. See the
[Windows CT differential evidence](https://github.com/Ripthulhu/ntpods/blob/main/docs/NOTE8-ATT-WRITES-2026-10-24.md#windows-legacy-ct-acoustic-differential-2026-10-25).

The 2026-10-25 live-settings Note8 comparison sent 21 full PME commands and
16 full CT writes, with Phone/Media On through stock-preset and Audiogram
comparisons. CT readback matched every byte, including all 40 preserved non-EQ
bytes; one old-record response preceded exact readback 155.6 ms after the write.
AHPS remained unchanged, no clinical-mode write was sent, and a fresh
PME report after reconnect contained the requested enabled flags. Nevertheless,
the listener reported no audible difference. This verifies storage and guarded
transactions, not Android acoustic parity.

Wire evidence confirms Apple DID `004C/1200/1436`, vendor source `0001`, with
no A000/A001/AFFF attributes. Audio was MPEG-2 AAC-LC, 44.1 kHz stereo CBR
256 kbps, sink SEID 2, with no SCO. The Windows CT comparison negotiated
Apple AAC-ELD at 48 kHz / 256 kbps; its separate PME music On/Off comparison
was inconclusive. Source-4D masks also differed (`D7` on
Android, `FF` on Windows); neither difference is a demonstrated cause.
The same Pro 3 pair's firmware was last verified as 9A348, not re-read here.

An isolated probe additionally sent the source-backed short `PME On Buds`
route command: `04 00 04 00 53 00 02 00 01 01` for On, ending in `02` for
Off. Both appeared on wire, but the listener again heard no difference. The
normal build does not send this experiment. The user disconnected deliberately
afterward; the last probe command was On, so Off cleanup is pending. Its
original state was not read and exact gate restoration is not claimed. See
the [Note8 comparison and packet references](https://github.com/Ripthulhu/ntpods/blob/main/docs/MACOS-ACCOMMODATIONS-CAPTURE-2026-10-24.md#note8-storage-succeeds-audible-processing-unresolved-2026-10-25).

### Pixel 7 Pro AAC-ELD accommodations check, 2026-10-25

The SDK37 Pixel subsequently played working 48 kHz / 256 kbps Apple AAC-ELD
through the private software-encoder preview. Starting from Brightness / Strong,
Headphone Accommodations On and both Phone/Media On, the listener heard no
difference when switching the accommodations master Off/On. Comparing Audiogram
with Brightness / Strong also produced no reported difference in music or
surrounding sound. The independent Custom Transparency master was Off in the
saved baseline and restoration. These negative comparisons do not establish
whether PME media processing was activated or whether CT enablement gates it.
Encoding AAC-ELD alone does not implement Apple's host hearing processor.

An automatic-read race was corrected before this comparison: the initial ATT
reader now starts only after one complete successful AACP initialization pass.
The AACP input reader and three-second initialization repeat remain independent;
partial initialization, cancellation and a replaced owner cannot launch that
reader. The focused run passed 59 tests: 13 initial-reader tests, including five
new ordering/lifecycle cases, 35 hearing tests, seven ATTManager tests and four
protocol tests. Nevertheless, the server still returned AHPS `0x0E` after the
completed initialization. The ordering repair did not resolve that read failure,
and no fixed-delay retry or authorization bypass was added.

Temporarily setting only Android's supported vendor/source properties to `76/1`
and restarting Bluetooth produced Apple DID on wire and successful protected
reads. No identity module was installed. The following private captures retain
the original and restored records; handles were discovered on each bearer.

| Capture | Fresh host DID frame | AHPS104 / CT104 / PME140 response frames |
| --- | --- | --- |
| `apple-accommodations-read-private.btsnoop` | 254, `004C/410A/0000/0001` | 513 / 516 / 635, saved original |
| `accommodations-restored-apple-private.btsnoop` | 323, `004C/410A/0000/0001` | 587 / 590 / 691, exact original bytes |
| `accommodations-restored-stock-private.btsnoop` | 360, `00E0/410A/0000/0001` | 601 / 604 / 704, exact original bytes |

Their SHA-256 values, in table order, are
`1f7555eea19413b7966ed797dbcfdb39c67819247cb2d3f46603794835fdb5ad`,
`70b8d9c3da6f73f1f0529947cd9c04a30bced237ea29e1f34b142dd7ed01450f`
and `97866692fff4296c644906abb8ef9ddf20aa68f4e9d9140b82aea173f96fd6da`.
All three snapshots are complete, with zero reported drops, truncation or ACL
reassembly errors. The read/restoration snapshots contain no ATT value write or
outgoing PME; ordinary AACP initialization is separate. A private backup retains
the exact raw records and peer/session/frame binding. Both original CT EQ arrays
match Brightness / Strong, so returning to that preset preserved all 104 CT bytes,
including the 40 bytes outside those arrays. AHPS was never an edit target.

The warm restored-Google session succeeded, but the stock capture ends at
`22:09:53.601668Z`. After capture cleanup and another Bluetooth restart, the
uncaptured final session returned AHPS `0x0E` at `22:10:06.135Z`. Do not extend
the captured warm success to that final session or claim a vendor-only or
cold-stock authorization rule. All temporary vendor/source properties were
restored to their original absence, packet capture was off, and no new short
PME On Buds command was sent. Brightness / Strong, both Phone/Media On and the
original AHPS104/CT104/PME140 were restored. The user's final ordinary music,
pause/resume and listening-mode stem check passed on ELD with tracing off.

Apple has two separately verified processing paths: the host `aufx/hear` Audio
Unit in `libAudioDSP` constructs `AUHearingLossCompensator` and
`PrescriptiveAmplification`, while PersonalAudio can also send four threshold
arrays through PME to a supported accessory. The native short PME On Buds
serializer independently sends subtype `01`, value `01` for enabled and `02`
for disabled. Both iOS and Tahoe confirm that Boolean mapping; the higher-level
activation policy, a getter and the original gate state remain unresolved.
The earlier Windows positive CT result proves ambient CT processing in its
tested configuration, not a PME music gate. Neither these controls nor the
working Android codec establish audible accommodations parity. See the
[private ELD prototype status](AAC-ELD-XPOSED.md#pixel-headphone-accommodations-check).

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
an Apple Device ID. That earlier source comparison did not include a rootless
identity change or Pixel ATT probe. The rooted Pixel experiment below provides
separate transport and property-path evidence.

### Pixel 7 Pro native path, 2026-10-25

Pixel 7 Pro build `CP3A.261005.002.A1` (Android 17, SDK 37) established both AACP
`0x1001` and Classic ATT `0x001F` with the legacy SDP/FCR detours disabled.
Fresh Pro 3 information reported model `A3064`, hardware `1.0.0` and internal
firmware `90.3431000025000000.6807`; no friendly firmware label is inferred.
Both channels negotiated Basic mode with successful configuration responses in
both directions. The peer reported no ERTM support. This build's native
`l2c_should_skip_ertm` checks the peer's discovered AACP UUID; the observed Basic
connections agree with that path without proving execution of a particular CPU
branch. Do not generalize its presence to every Android 17 build.

Setting the real Device ID vendor property to decimal `76`, with source `1`,
before Bluetooth initialization produced a fresh SDP response with vendor
`004C`, product `410A`, version `0000` and source `0001`. Product and version
properties were preserved. The stock response was `00E0/410A/0000/0001`.
This uses Android's record registration, with no fixed native offset or complete
image hash in the identity configuration path. A property readback is not wire
confirmation, and the active SDP record changes only after Bluetooth restarts.

Discovery resolved Hearing Aid UUID `a4120005-95c5-4d6f-9098-0f0b41457e0a` to
value handle `002A` and Custom Transparency UUID
`d5621cc1-f7ab-45db-9403-9eaf744d5390` to `0018` in these captures. Both advertised
Read, Write Without Response and Notify (`0x16`). These handles remain capture
references, not constants for another connection or firmware.

| Session | Pixel SDP response | Hearing Aid read | Transparency read |
| --- | --- | --- | --- |
| Initial stock pairing | Frame 3049, vendor `00E0` | Request 4245, server error `0x0E` at 4247 | Not attempted after bearer retirement |
| First Apple configuration | Frame 264, vendor `004C` | 104 bytes at 468 | 104 bytes at 470 |
| Restored stock control | Frame 238, vendor `00E0` | 104 bytes at 436 | 104 bytes at 439 |
| Apple return | Frame 250, vendor `004C` | 104 bytes at 479 | 104 bytes at 483 |

The first error is ATT Unlikely Error, not an explicit Insufficient
Authentication result. It arrived about 39.9 seconds after ACL connection, so
the evidence does not support adding a short fixed startup delay. The restored
stock session succeeded with byte-identical records; current Apple vendor
identity is therefore not proven necessary under this peer history. Cold-pairing,
authorization/cache and initialization state remain competing explanations.
The common AACP feature/subscription requests were byte-identical. No ATT value
write occurred during these comparisons, and no stored hearing values are
published here.

The accepted snapshots have complete H4/ACL boundaries, zero reported drops,
truncation or ACL reassembly errors. A 44-byte unflushed snapshot was excluded;
the rotated, flushed capture supplied the restored-stock control. Ordinary AAC
playback, pause/resume and the listening-mode stem control subsequently passed
the user's listening check with packet capture disabled. This does not establish
audible accommodations, AAC-ELD playback or Game Mode.

The same Pixel later completed LibrePods' selected-pair consent through an
app-owned root helper, without Shizuku. This root backend supplies Android
privileged pairing, audio-policy and owned-media operations; it does not itself
set the Device ID, change L2CAP framing or authorize protected ATT writes.
Those remain separate transport and current-bearer readback checks. Its setup
and hardware verification are recorded in [the Pixel fork notes](PIXEL-REWRITE.md#root-bluetooth-access).

`ATTManager` uses the secure Classic socket helper on PSM `0x001F`. Connection
and UUID discovery run independently of audio and AACP, with bounded operations
and one outstanding ATT request. Discovery resolves the service and
characteristic handles on each bearer. The negotiated L2CAP send/receive MTUs
determine ATT's MTU; BR/EDR must not use the LE Exchange MTU procedure. See
the [Bluetooth GATT transport requirements](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-62/out/en/host/generic-attribute-profile--gatt-.html).

Only complete typed 104-byte CT and clinical records are accepted. A full-MTU
response, unsupported length or malformed record is rejected; long-value
assembly is not implemented. Writes require a fresh matching baseline, the
advertised write method, and exact readback tied to the same bearer and AACP
owner. Use `0x12` with `0x13` when Write is advertised, otherwise `0x52` only
when Write Without Response is advertised. A timeout retires the ATT bearer
without closing the working AACP or audio connection. No guessed handle or
CCCD is used, and an authorization error does not start a retry loop.

The saved Mac sequence enables CT/AHPS notifications before its edits; native
Android does not yet subscribe. A separate captured startup value `01 01 00 07`
targets DoAP Select Codec, not the CT service. The positive Windows CT array
comparison above also sent neither initialization, so neither is a universal
prerequisite for audible legacy CT. This does not establish Android acoustic
parity or explain every stored CT change without an audible effect.

On 2026-10-24, a Note8 SM-N950F running LineageOS 20 / Android 13 with
Magisk 30.7 and Vector 2.2 read Custom Transparency from Pro 3 A3063 / 9A348.
The module was scoped only to the Bluetooth process. Read-only memory checks
confirmed its native identity flag was enabled and both Bluetooth entry points
were patched; a fresh control trace also showed the L2CAP hook executing.
After a fresh connection, the production Transparency page displayed all five
sliders and Conversation Boost with its master available. That page requires a
complete valid 104-byte record from the current ATT owner; it does not restore
this record from disk, and reconnect clears it. This verifies the native CT
read path. A subsequent bounded write test established the on-wire Apple
Device ID, isolated CT master/five-slider/Boost writes and complete matching
readback. Fresh reads after restoration matched all 104 original CT bytes and
all 104 original clinical AHPS bytes. PME and the clinical modes were unchanged;
no clinical or PME write was sent. Native named-preset confirmation and
unrooted Pixel access were not established. The listener reported no audible
amplification at maximum stored CT gain, so readback must not be presented as
proof of effective DSP application. See the
[write and restoration evidence](https://github.com/Ripthulhu/ntpods/blob/main/docs/NOTE8-ATT-WRITES-2026-10-24.md)
in the companion repository; captures and record bodies remain private.

The v2 Note8 preview subsequently verified Hearing Aid On and Off twice using
one owned AACP/ATT refresh per request, without repeating mode writes or writing
the fitting record. Fresh complete `2C`, `33` and PME reports plus an unchanged
fitting read were available 1.358–1.546 seconds after the first on-wire mode
command. These are data-readiness times, not touch-to-audio or UI timings.
The listener confirmed audible On/Off and a louder large-amplification setting.
The separate fitting test recorded 14 writes across changes, restores and two
small restore corrections; this is not a count of distinct controls. Each
`02 02 64 00` write received `02 02 60 00` with its exact 100-byte body on the
first subsequent read after 9.015–25.330 ms. Amplification/Balance use offsets
36/84, Tone 40/88, Boost 44/92 and Ambient Noise Reduction 48/96. Audiogram and
Own Voice bytes were unchanged; no CT or mode-control writes occurred during
the fitting edits. Final fresh CT/AHPS reads at 143393/143396 matched all 104
original bytes each, with exact original PME and clinical-mode packets and both
modes disabled. These clinical results do not establish legacy CT DSP behavior,
native named-preset confirmation or calibrated acoustic gain. The earlier
mode-control snapshot had no spontaneous complete reports after its accepted
commands, which explains the explicit owned-refresh confirmation path.
Received PME enrollment remains opaque zero. Clinical mode state and guarded
legacy Headphone Accommodations eligibility stay separate; mode refresh does
not rewrite the received header. The linked evidence documents the exact
restoration, known header normalization and timing limits without personal values.

Loud Sound Reduction is not enabled by this transport work. The native capture
declares UUID `A48FEC08-3921-43DB-82AA-AFBCE8EBB4FB` with properties `0x06`
(read and write without response), at frame 1424 in
`20261024T150956Z-00aee364`. This does not establish the legacy fixed-handle
Write Request contract; its typed write integration remains unverified.

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

### Multimodal Context and sensor streaming frequency

AACP `0x52` carries a uint16 LE length followed by that many opaque bytes. For
example, body `03 00 02 01 00` has length 3 and data `02 01 00`. Tahoe's receive
path names this a Multimodal Context message and forwards the data to accessory
observers. Its data fields, any required response and any relationship to audio
timing remain unverified; it is not an established RTP-clock instruction.

AACP `0x55` is a versioned Sensor Streaming Config report, rather than Adaptive
Volume or four independent audio flags. Body `01 01 00 32` contains version 1,
uint16 LE length 1, and one data byte `32` (decimal 50). The observed idle body
`01 01 00 19` similarly carries decimal 25. Tahoe stores the first data byte in
the per-device field returned by `BTAccessoryManagerGetSensorStreamingFrequency`.
The getter establishes the field's purpose; these observations do not calibrate
its units or establish a measured 25/50 Hz sensor stream. Unknown versions,
lengths or additional data must remain opaque. This report does not authorize a
sensor subscription, receiver-buffer write, Game Mode change or audio restart.

Static evidence is the retained Tahoe `25G241` x86-64 `bluetoothd` image,
SHA-256 `93f3afbf2bb7d84855ae1f2b24c9a222250f33c2808a7b80094bae15c0f62f65`.
Its opcode dispatcher `0x1000c0b66` selects `0x52` at `0x1000c1d69` and `0x55`
at `0x1000c1036`. Callback-table offsets `0x128`/`0x138` reach the named Context
and Sensor Streaming Config consumers. The latter calls `0x1005a827c`; its stored
field is read by `0x1005a8340` and the named frequency getter at `0x100498fe7`.
The Pro 3 playback capture `20261014T203429Z-20e66ab9` observed 25 while idle,
50 during playback and 25 after Suspend. These are separate from Adaptive Volume
opcode `0x4C` and from the host logging clock `0x1B` above.

### Primary bud and coordinated handover

Bud Role `0x08` requires the complete four-byte body. Byte 0 selects the primary:
`1` is left and `2` is right. Byte 1 remains unknown. Tahoe's consumer describes
byte 2 as Secondary Connected and byte 3 as Quickdisconnect Enabled. Preserve
their raw values; an unknown primary selector cannot change ear-detection mapping.
The ear-state `0x06` payload is primary then secondary, so a fresh recognized role
report determines its physical left/right mapping. A role report does not itself
request a media pause, codec reset or controller operation.

The Tahoe `0x10029eabe` consumer and the wearing/movement capture
`20261012T221248Z-ebeb25ec` establish those labels: frames 477/713 carry
`01 00 01 00`; frame 2144 carries `02 00 01 01`, with a matching native log for
right primary, secondary connected and Quickdisconnect enabled. That session had
no AVDTP/RTP playback and cannot establish audible handover behavior.

Coordinated Bud Swap 2.0 is a separate exchange. On `0x47`, Tahoe stalls the
selected connection's non-media L2CAP queues and requests chipset LMP Halt;
after completion it sends `0x48` with a uint32 LE status. On `0x49`, it unstalls
the queues and requests LMP Resume; completion sends empty `0x4A`. Controller
support and completion must be established before acknowledging success. Do not
synthesize these acknowledgements in response to an ordinary `0x08` role change.
The bounded native paths do not themselves show AVDTP Suspend/Start, an encoder
reset or an RTP-clock write; this does not exclude indirect consumers elsewhere.

The private 2026-10-25 Pixel 7 Pro / SDK 37 AAC-ELD experiment distinguished
docking the secondary, which stayed audible in its control capture, from docking
the primary, which reproduced silence after a right-to-left primary handover.
In `eld-primary-handover-full-final-private.btsnoop`, frames 559/707 report
right primary (`02 00 01 00`), and frame 3387 reports left primary
(`01 00 01 01`) after the original primary's ear-removal and case reports at
2999/3245. Valid encoded audio continued and no `0x47`–`0x4A` exchange appeared
anywhere in that capture. This establishes a baseline handover failure, not a
missing-ack requirement. A later private bounded-pacing preview passed the
targeted right-primary docking test, then docking/reinsertion on both sides
with tracing detached. Its complete 5,352-AU capture preserved sequence and
48 kHz timestamp continuity without Suspend/Start or reconfiguration. This is
initial validation of the private preview. The final check also passed automatic
full-case return, two clean pause/resume cycles and listening-mode stem control,
with tracing off and ELD still active afterward. It does not establish every
receiver/controller condition or authorize synthetic coordinated-swap acknowledgements.
The pair is Pro 3, previously recorded as firmware
`9A348`; that handover observation does not independently refresh its firmware
identity. See the [Android AAC-ELD integration boundaries](AAC-ELD-XPOSED.md).

CaseInfo `0x23` decodes only the established v3/25-byte body, including
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
