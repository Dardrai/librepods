# Pico dongle controls

Pair the Pico dongle in Android Bluetooth settings, then choose it under
**Settings → Pico dongle**. LibrePods opens an encrypted, bonded RFCOMM control
link to that explicitly selected device. A PC is not required. This connection
does not select an audio route or replace direct AirPods controls.

The screen provides battery reports, listening and stem settings, case settings,
codec preferences, Game Mode, hearing adjustments, saved hardware checks,
performance counters and nearby finding. Availability depends on the AirPods
model, firmware and the dongle's current connection. A requested setting stays
marked as a choice until a new device report confirms it; a successful write is
not proof that the AirPods applied it. Codec changes may reconnect Bluetooth.
The bitrate shown for variable encoding is a negotiated peak limit, not a
measured average. Startup priming remains fixed at 100 ms.

Hearing adjustments reuse the existing complete-profile parser and writer.
They preserve both audiograms, check for a changed baseline before writing and
require readback. Each complete action stays bound to the selected pair and
connection; a change requires a fresh read. Untouched left/right adjustments
remain separate, even when the screen presents a shared control.
This screen does not generate a fitting or start a hearing
test. Hardware checks read saved results only; they do not play sweeps or
diagnose the current condition of the earbuds.

Automatic pause uses LibrePods' existing Shizuku player ownership checks against
the dongle's actual Android audio route. It resumes only the player LibrePods
paused, while that claim remains valid. It never sends a global Play command.
Observed USB playback revokes a phone pause, and reinsertion checks current
input ownership before resuming. Idle input alone does not revoke the pause.
Settings, battery reports and finding do not require Shizuku.

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

The wire contract lives in `airpods-dongle/docs/CONTROL-EXTENSIONS.md`:
RFCOMM UUID `c6c4f8a0-6d47-4bd1-9b6a-415044470001`, bounded APDG frames,
reports 2/3, transactions of at most 512 bytes and typed feature operation 7.
Malformed streams close the connection. Requests have time limits and are not
replayed after an uncertain write. Codec uncertainty is checked through a
read-only profile query for the same selected pair.

Protocol, cancellation, ownership and theme rendering checks run on the host.
Pico radio coexistence, controls and listening checks remain pending hardware.
There are no new phone/PC handoff controls.
