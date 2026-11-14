# Apple settings interactions

Onboarding requests permissions only after an explicit action. Grant All visits
each missing permission once, keeps an in-flight request across recreation and
continues after optional denials. Only Nearby Devices is required to finish.
Microphone access does not start capture. Health Connect uses its own contract
when the provider is available; overlay access opens Android Settings and is
checked on return. Optional Bluetooth access reuses the settings screen's root
helper with Shizuku fallback. Resume may reconnect an already authorized helper,
but never requests a fresh superuser grant just by rendering the screen.

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

The listening-mode control keeps its segment geometry fixed for each drag, so
mode echoes cannot move boundaries beneath the finger. Dongle screens and the
Quick Settings picker display the same queued editor intent while received
reports remain separate. Each visible screen, tile and picker owns its polling
token; closing the shade must not stop an open picker's updates. A remembered
but unplugged dongle does not hide a connected native device from the tile.

The expanded Quick Settings panel keeps that rail above the selected supported toggle
controls, with two columns at regular phone widths and one column for narrow
windows or enlarged text. Its height stays inside the available window and its
content scrolls. Conversation Awareness, Automatic Ear Detection, Game Mode,
Personalized Volume, Loud Sound Reduction, EN 352 Protection, Hearing Aid and Media Assist reuse
the native or dongle writers. Listening mode, Adaptive strength and ordinary
toggles update their displayed intent immediately while Bluetooth and preference
writes run on IO through one ordered writer. Each control keeps one in-flight value and one replaceable unsent choice.
Automatic Ear Detection uses the main screen's configured-choice precedence;
its preferences change only after an accepted command. Toggle labels show the
chosen On or Off value immediately, including while an owned write is pending.
The received reports and write authority remain separate from that presentation.
Game Mode can be cancelled while it waits for playback.

Hearing Aid, Media Assist and Loud Sound Reduction are disabled while scalar
writes remain queued or in flight. An admitted typed request disables duplicate
admission. During the panel's owned clinical confirmation refresh, known toggle
values remain visible and disabled while their reports reload; they supply no
write authority or fitting baseline. An uncertain clinical mode offers the full
screen's guarded Turn Off recovery button without presenting an unknown mode as
On. Other unknown values remain unavailable and disabled.

An open panel owns its application-context service binding independently of the
Quick Settings tile. Transient disconnects, settings reloads and replaced routes
do not dismiss it. If its captured device or route is lost, the last presentation
stays visible with every control disabled; only that exact owner can resume the
panel. Pending work is retired rather than replayed when the owner returns.
Explicit close, Back and outside dismissal still release its binding and work.

Quick controls use the existing Backdrop glass material, a shared captured layer,
neutral translucent silhouettes and circular icon wells, following the supplied
Control Center reference and AndroidLiquidGlass's KMP examples. Active icon wells
use the app accent; the glass body stays neutral. A short press spring and 180 ms
icon color transitions give immediate feedback without moving the control
geometry. Compose
animations retain the system duration scale, including disabled animations.
Touch targets are at least 48 dp and expose switch state and disabled semantics. Opening or rendering the panel never changes a setting.

Configure visible controls only in LibrePods Settings → Quick Settings. The panel
has no editing controls. Its local visibility preferences do not change device
settings; all supported controls appear by default, and hidden controls leave no
empty grid cells. The listening rail remains when every toggle is hidden.
Filter support by the AirPods model and the current connection's actual feature
support, independently of temporary read or write readiness. Native protected
settings require an observed active Apple identity hook; dongles use their own
capabilities. Game Mode visibility requires observed native backend support,
retained through temporary route loss. Preserve guarded Off recovery for an
existing clinical mode, without offering unsupported modes an On action.

Received records and requested values remain separate. A successful silent PME
send can supply the next same-session edit baseline, but is not device
confirmation. Fresh received records take precedence. Audiogram selection needs
a validated current saved profile; unknown curves must not receive that label.
Profile and transport guards remain active with automatic updates.

Automatic settings reads do not start diagnostics, performance checks, sounds,
hearing tests or microphone capture. Those actions remain explicit.

Pairing invitations follow the app's Light, Dark or System appearance. An effective
theme change repaints the existing card and switches its matching artwork without
replacing the pairing session, current stage, actions, battery readings or expiry
deadlines. Unchanged appearance does not restart the artwork player. Retired
decoder callbacks cannot reveal an old frame. Action text keeps at least 4.5:1
contrast through the pressed ripple in both appearances.
