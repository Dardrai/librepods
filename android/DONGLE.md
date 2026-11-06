# Pico dongle controls

Choose an attached USB dongle or a paired Bluetooth dongle under
**Settings → Pico dongle**. USB uses the vendor HID feature collection; Bluetooth
uses the encrypted, bonded RFCOMM service. Both share the existing controller,
settings readers and live writers. Bluetooth controls do not require a PC, root
or Shizuku. USB controls use the existing root helper when Android's HID driver
owns the shared media/control interface. Choosing a control transport never selects an audio route,
changes volume or opens a direct AirPods connection.

USB discovery accepts both `CAFE:4005` (controls only) and `CAFE:4015` (audio
composite), then validates the HID usage page `FF31`, usage `1`, both 64-byte
feature reports and the APDG handshake. Interface numbers come from the actual
descriptors. Android's USB filesystem rejects interface requests with `EBUSY`
while its HID driver owns that interface, even after USB permission is granted.
The root helper opens only the matching board's hidraw node; feature-report
ioctls share the HID driver with Android's media keys. LibrePods never detaches
that driver or opens the CDC console. Ordinary USB audio remains available
without this control connection.

The UID-0 app helper must also be classified as a client process. libsu 6 starts
it through `ActivityThread.systemMain()`, which leaves `mSystemThread` set.
Android 17 then rejects a USB device containing the system server's remote serial
reader. The helper clears that classification once, with exact runtime checks,
before constructing its bridge; it never changes the actual system server,
UID, attribution or USB grant. Root permission checks use the calling app's
package, PID and UID. Serial identity in the helper comes from sysfs and the
opened hidraw handle; Android's serial-reader package check rejects an app
package attributed to UID 0.

The selected board is remembered by its USB serial. A descriptor change retires
all pending edits and reopens a new control generation after re-enumeration.
A different known serial is never silently substituted, and several unknown
boards require explicit selection. Android can ask for USB consent again after
detach or a personality change; accepting LibrePods as the default handler for
both personalities lets Android grant the normal attachment permission. A saved
serial does not bypass USB permission. Failed control reads reconnect with a
one-to-fifteen-second backoff; an uncertain write is never replayed.

The screen distinguishes the control link, the AirPods' audio-profile connection
and the presence of USB headphone interfaces. An available interface does not
prove that Android selected it, nor establish S16 versus float32 transport.
No default output or host volume is forced. Both USB formats stay under the
host's normal audio selection. Firmware's Bluetooth phone mode can leave USB
controls available while omitting its headphone and microphone interfaces.

**Reconnect AirPods**, **Pair AirPods** and **Allow Bluetooth phone pairing**
use the firmware's explicit basic commands. Acceptance means local firmware
acceptance; subsequent connection status is authoritative. Pairing remains an
explicit action. Hardware takeover is the paired board's single BOOTSEL press;
there is no app takeover API. Double-press BOOTSEL changes Game Mode, which
starts off at power-up. The firmware performs its clock adjustment.

An attached supported USB candidate inhibits LibrePods' automatic native AirPods
control connection. A selected Bluetooth dongle does the same while controls
connect or remain connected, or its matching Android audio endpoint is present. Startup, audio
output and ACL callbacks share that gate, including an automatic request already
waiting for a route. Explicit native Connect remains available; this guard does
not disconnect a user's existing route or rewrite Android's connection policy.

The selected AirPods open the existing Apple settings screen. Its battery,
listening, stem, microphone, accessibility and hearing controls use the dongle
controller through the same screen components as a direct connection. The model
and firmware come from the dongle's current metadata; a disconnected native
device's cache must not decide whether Adaptive appears. Direct-only actions are
omitted. The Pico page retains transport selection, pairing, codec preferences,
Hardware Volume, Game Mode, performance checks and nearby finding.

The listening-mode Quick Settings tile and its existing popup also use this
controller in dongle mode, without requiring a direct phone-to-AirPods connection.
Queued mode/strength edits appear immediately. The picker stays bound to its
original board, AirPods and control session, and closes when that target changes.
Its polling remains active after the shade closes. A saved but unplugged dongle
selection allows the tile to use a currently connected native device again.

Availability depends on the current model, firmware and supported report schema.
Received values and pending or saved choices remain separate. A newer received
revision retires older pending intent even when it disagrees; it is not a causal
write acknowledgement. Documented preferences without a getter retain accepted
user choices by AirPods identity without inventing a report or resending on load.
Codec changes may reconnect Bluetooth.
The active bitrate is the configured local encoder limit, not measured throughput.
For firmware 0.1.53, requested CBR 256000 can report a configured 255200 bit/s
limit while the separate negotiated wire ceiling remains 256000 with variable-rate
permission. Permission does not mean local VBR encoding. The live adaptive budget
in feature 19 can be lower and is not displayed as the configured limit.
Startup priming remains fixed at 100 ms.

Supported settings load automatically and changes apply live, without Apply or
Read settings buttons. Initial hearing reads retry at most three times, at least
five seconds apart, within the same control owner. Microphone and call activity
defers protected reads and writes.
Temporary contention also waits five seconds, but does not consume that failure
budget. Releasing a competing client's settings lease allows automatic loading
to resume; a retired owner cannot modify the replacement owner's retry state.

Hold assignments use native `16`: right in the low byte, left in the next byte,
`1` Assistant and `5` Noise Management. An unknown complete pair requires two
explicit choices before writing; a single-side edit never guesses the other bud.
The firmware does not forward stem events or a USB assistant action to Android,
so selecting Assistant is unavailable on this transport. Received Siri
assignments remain visible. Direct AirPods connections retain LibrePods' existing
`39` raw-hold delegation and Android assistant launcher.

Hearing adjustments reuse the existing complete-profile parser and writer.
They preserve both audiograms, check for a changed baseline before writing and
require readback. Each complete action stays bound to the selected pair and
connection; a change requires a fresh read. Untouched left/right adjustments
remain separate, even when the screen presents a shared control.
Fitting confirmation now matches native ATT: up to five reads, 75 ms apart,
only while the unchanged baseline remains. A conflicting, malformed or retired
result fails without repeating the write.

Before automatic health reads on a supported hearing model, LibrePods supplies
the current phone UTC clock once per control owner using the shared `0x1B`
packet builder. The existing pair-bound RPC/settings lease carries this message;
Pico uptime is never used as calendar time. A failed clock send does not block
the ordinary profile reads, and retries use the existing bounded health budget.
Named presets and Audiogram reuse the native accommodation planners. Audiogram
comes from the validated saved fitting. Both PME and Custom Transparency curves
are coordinated while the complete clinical fitting is read before and after
and must remain byte-for-byte unchanged. A silent PME send stays a sent choice,
not confirmed device state; its same-session baseline supports the next live
edit until a fresh device report confirms or replaces it.

This screen does not generate a fitting or start a hearing
test. Hardware checks read saved results only; they do not play sweeps or
diagnose the current condition of the earbuds.

On the Bluetooth control transport, automatic pause uses LibrePods' existing
root/Shizuku player ownership checks against
the dongle's actual Android audio route. It resumes only the player LibrePods
paused, while that claim remains valid. It never sends a global Play command.
Observed USB playback revokes a phone pause, and reinsertion checks current
the current input before resuming. Idle input alone does not revoke the pause.
The app does not run this Bluetooth-address-based pause policy for USB.
USB media and volume keys remain firmware/system paths, without a second relay.
Settings, battery reports and finding do not require Shizuku; the shared USB
HID path requires the existing root helper on hosts that bind the HID driver.

Nearby finding uses only advertisements matched by the dongle to its selected
AirPods. No proximity keys leave the dongle. Signal strength is not distance or
direction. Leaving the screen, disconnecting controls or changing the selected
pair stops finding; a firmware lease also bounds an interrupted session. Finding
pauses during microphone use and never acquires audio.
If finding identity is missing, the screen explains that the selected AirPods
must connect to the dongle once before finding can start.

Idle background control checks run every five seconds. Opening settings wakes
the faster refresh; automatic pause, active finding and pending setting
confirmation retain the responsive polling cadence.

Firmware capability 64 adds component-presence and case-source masks to status
bytes 44 and 45. Each battery keeps its own age. An absent or expired component
retains its last percentage as cached and loses its charging indicator. Older
firmware without that capability retains the original status layout. The case
source is a key-associated battery observation, not a verified nearby-bud signal;
it cannot establish audio ownership, ear state or a connection. Firmware using
the extension requires an updated app.

The wire contract lives in `airpods-dongle/docs/CONTROL-EXTENSIONS.md`:
RFCOMM UUID `c6c4f8a0-6d47-4bd1-9b6a-415044470001`, bounded APDG frames,
reports 2/3, transactions of at most 512 bytes and typed feature operation 7.
Malformed streams close the connection. Requests have time limits and are not
replayed after an uncertain write. Codec uncertainty is checked through a
read-only profile query for the same selected pair.

Protocol, cancellation, ownership and theme rendering checks run on the host.
Pico radio coexistence, USB permission/re-enumeration, controls and listening
checks remain pending hardware. Firmware build and startup checks do not establish
power savings, host mixer behavior or maximum-volume cue alignment. The USB
volume prototype needs coordinated host and firmware validation; this app adds
no compensating gain. See `airpods-dongle/docs/VOLUME-EVIDENCE.md` and
`airpods-dongle/docs/HOST-VOLUME.md`.
There are no new phone/PC handoff controls.

## Exclusive USB volume prototype

Firmware 0.1.45 introduced capability `128` and typed feature `16` (a 24-byte physical
volume snapshot) and `17` (acquire, renew, release or set under a five-second
USB authority lease). The app protocol checks the version, reserved bits,
physical range, observation revision, client-visible token, TTL and USB media
generation. It rejects lease writes over RFCOMM or without a target-bound action.
A SET response is admission, not proof of a new physical observation. Readback
must advance beyond the pre-write revision before it can confirm the change.

The Audio group has an explicit **Hardware volume** switch, off until enabled
for that board. Only the requested boolean is saved against its USB serial;
Off or Forget removes that request. Case returns, USB re-enumeration and app or
phone restarts retain the request, never an active lease or token. It is shown for USB firmware advertising capability
`128`, and is available only after root confirms the matching system policy and
selected USB route. Older firmware without that capability receives neither
feature. Discovery only inspects readiness; it never raises
gain. Enabling the switch requires paused playback and microphone use, acquires
the firmware lease and preserves its exact fresh physical gain without an initial
gain SET. Native unity then seeds the host display from that level; coarse UI
rounding never changes the AirPods' physical level or rounds positive gain to
local PCM mute. The existing
controller serializes all firmware requests. The coordinator renews within its
conservative deadline, forwards bounded absolute host changes, and reports only
settled physical observations to Android. Unknown or pending physical volume can
renew the same valid lease; it cannot confirm a SET or update the host index. The native
unity policy must be disabled before releasing firmware authority. If native
cleanup cannot be confirmed, let the firmware lease expire quietly rather than
restarting its standalone relay over an unknown host policy. A long hearing
workflow must retire volume authority before occupying the shared control path.
Background status/audio reads restore a requested lease only for the same verified
board after fresh root readiness, confirmed previous cleanup and idle audio,
microphone, call and settings work. The switch shows the saved request; working,
waiting or unavailable text does not claim active ownership. Off remains reachable
while disconnected. Actual activation or lease failures suppress automatic gain
attempts for that target until an explicit retry or a fresh connection. Protected
settings work keeps its whole-workflow target and token while renewing physical
and native volume leases between complete paginated transactions. Bounded transport
budgets reserve cancellation and renewal time; a true kernel/USB hang can still
expire the lease. Details and the unchanged firmware wire contract are in
[USB-ABSOLUTE-VOLUME.md](USB-ABSOLUTE-VOLUME.md).

The earlier host-alignment preview passed clean playback, native stem/index
following, no premature maximum cue, explicit Off/On cleanup and a closed-case
return at unchanged index 19/25. Those results do not validate this new
physical-master activation or uninterrupted live settings. Actual PCM unity and
the new flow still require coordinated hardware validation.

The guarded saved-record workflow is currently limited to the validated Pro 2/3
model catalog and complete known records. Pro 1 Custom Transparency through the
dongle has not been admitted or verified; unsupported records stay unavailable.

## Bluetooth relay codec controls

The 30 October Pixel 10 relay check negotiated **LHDC v5, 48 kHz, 24-bit stereo**
from the phone to the Pico. This input codec is separate from the Pico's outgoing
AirPods codec. Grayed Android codec controls do not identify the active codec;
the current A2DP status does.

The phone reported SBC source capability at 44.1 kHz only, while the Pico's SBC
sink supports 48 kHz only. Their rates do not overlap, so SBC was absent from the
selectable list. The cached Pixel 7 SDK37 framework requires a selectable
mandatory SBC codec before accepting codec preferences. The current Pixel 10 UI
and status agree with this explanation; its framework was not freshly inspected.

A separate source-only native fix stops ELD appearing as selectable before a
peer advertises compatible Apple capabilities. Its host tests pass, but it is
**not installed** and does not resolve the SBC rate mismatch. No Android UI gate
is bypassed and no unsupported 44.1 kHz input is advertised. See
[the capability reporting check](AAC-ELD-XPOSED.md#peer-selectable-codec-reporting-30-october-source-only).
