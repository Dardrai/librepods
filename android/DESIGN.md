# Apple settings interactions

The Apple interface follows the shared LibrePods section order and existing
`StyledScaffold`, inset groups, sliders and switches. Behavioral fixes preserve
those primitives, both appearances, native focus and accessibility semantics.

Settings take effect from their controls. Do not add Apply or Read settings
buttons. Native session initialization reads supported records automatically,
defers while microphone or call audio owns the connection, and keeps each read
bound to its original socket and session. It does not reopen AACP or audio.
Unsupported records remain unavailable rather than receiving invented defaults.
Empty hearing and Transparency screens use the session's explicit read status:
waiting before admission, reading during an attempt, and unavailable after a
failed or unsupported read. Empty bytes alone do not imply an active read.
Disconnected screens ask for a connection without promising that reconnecting
will enable an unsupported feature.

Sliders display local movement immediately. Writers use a 150 ms leading window,
one transaction in flight and one replaceable latest intent. Keep the final value
through the transaction's own readback; an independent record or owner change
retires stale gestures. Cancel discards unsent intent and does not repeat a write
already admitted. Numeric hearing thresholds commit only complete valid entries
on keyboard Done or focus loss, preserving unrelated full-record bytes.
The Apple slider exposes a labeled accessible range and keyboard adjustments,
including disabled state. Optimization uses three discrete levels. Resolve
clamping, steps and snapping before comparing an action with its current value;
a no-op accessibility action must not send a setting or finish callback. Pointer
movement still accumulates before snapping and cancellation keeps its existing
ownership guards. EQ graph controls retain their 18 dp visible dots inside 48 dp
touch targets, with labeled range actions and keyboard adjustment in both themes.

Received records and requested values remain separate. A successful silent PME
send can supply the next same-session edit baseline, but is not device
confirmation. Fresh received records take precedence. Audiogram selection needs
a validated current saved profile; unknown curves must not receive that label.
Profile and transport guards remain active with automatic updates.

Automatic settings reads do not start diagnostics, performance checks, sounds,
hearing tests or microphone capture. Those actions remain explicit.
