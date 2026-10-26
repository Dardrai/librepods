# Pixel fork on the Android rewrite

This branch starts at upstream `android/rewrite`, commit
`a4d8377df0bb468f371289a1b53eeba7c23a9f06`. It retains the rewrite's screens,
design choices, settings and per-device architecture. `android/rewrite` is the
fork's active branch. The older port is preserved at
`archive/pixel-shizuku-port-2026-10-20` as a reference for previous hardware
checks, not the frontend base.

## Build and upgrade

Selective ports from upstream `android/rewrite` through `80aa746` retain the
fork's protocol, ownership and presentation safeguards:

- Primary-bud role `0x08` maps 1 to left and 2 to right, matching the saved
  macOS handoff in `MACOS-CASE-CAPTURE-2026-10-12.md`, frame 2144. Exact-length,
  header and owning-socket checks remain; unknown roles retain the current side.
- Battery presentation compares component membership, percentage, charging state,
  presence and whether a reading exists. Fresh observation timestamps still reach
  device state, replay protection, expiry and the pairing/island age timers.
  Timestamp-only updates no longer refresh the main screens, widgets or notification.
- Translations from upstream `946ecb39` add Japanese (`values-ja`) and Korean and
  improve existing locales, especially German and Simplified Chinese. Fork-specific
  copy and saved stem-action names remain unchanged. Untranslated fork features
  use the default English resources.
- EQ Low/Mid/High labels use translated resources, adapting `1f70650` without its
  unrelated onboarding changes. No BLE takeover, theming or database migration
  from the upstream branch is included.

Use JDK 21 and the Android SDK/NDK versions declared by the project. The existing
Gradle toolchain configuration selects/downloads JDK 25 for its daemon. Run
`./gradlew verifyPixel` from `android/`. This checks core and app unit tests,
Android lint, the debug APK and the minified release APK. There is one GitHub
distribution with standard `debug` and `release` tasks, without Play flavors,
billing, reviews or AAB publishing. `collectReleaseArtifacts` collects APKs in
the repository's `release/` directory. Signing is deliberately external; no
signing key or password belongs in the repository.

Install a release signed with the existing fork certificate using an in-place
update. Do not uninstall first. The rewrite migrates the old app's selected
pair, local behavior choices and correctly owner-bound BLE keys once. Existing
rewrite database rows are preserved. Unconfirmed device control values are not
migrated as device reports. The Android Bluetooth bond stays with Android.

The current fork version is `2.0.0-alpha.1-pixel94`, code `200000179`.

### Root Bluetooth access

Settings → Bluetooth access can authorize the app-owned root helper instead of
Shizuku. Both backends use the same pairing consent, selected-profile recovery,
microphone-privacy, owned-player and bounded volume-repair operations. Each
operation checks the app UID and its current selected-device owner. Root uses
its real UID and attribution; it does not impersonate Android's shell package.
The helper exposes no arbitrary shell commands and no system Binder handles.
Closing a screen releases only that client's binding and leases; the helper
cleans up when the last client leaves. Failed grants, late callbacks, connection
loss and timeouts cannot revive an abandoned request. First root authorization
is explicit; later connections reuse the grant. Shizuku remains the non-root
fallback. Older hardware-check entries below describe the backend used at the
time and are not proof of the new root path.

On 2026-10-25, Pixel 7 Pro build `CP3A.261005.002.A1` with KernelSU-Next 3.4.2
ran the helper as UID 0 with Shizuku absent. KernelSU initially had SU
compatibility disabled; enabling that interface and allowing LibrePods under
Superuser made standard `su` available to the app. The helper verified the
app's `CONTROL_AUDIO` operation was allowed and observed healthy volume sync.
Stopping the app retired its root process; restarting and a later phone reboot
both restored the authorized helper. A fresh pairing confirmed selected-device
consent at 5.498 seconds and reached BONDED at 6.060 seconds after Connect; those
times include Classic discovery, not just the bond handshake. The user confirmed
pairing worked and final playback, five-second pause/resume and listening-mode
stem controls were clean with packet capture off. No stored hearing profile was
written. Root operation on other phones and future OTAs remains untested.

Android 17's optional native Device ID module is separate from privileged app
operations. It uses supported Bluetooth properties with transactional restoration
and a fresh baseline after each boot, rather than Bluetooth binary offsets or
whole-image hash gates. See its [setup and limits](tools/bluetooth-identity-module/README.md).

Apple is the default design system for new or unset preferences, including the
initial loading frame. Saved theme choices are preserved, and light/dark mode
continues to follow the existing night-theme preference.

The listening-mode slider includes the first movement beyond Android's drag
threshold, so short Adaptive-strength drags commit their changed position.
Drags starting in Adaptive soften movement just beyond either strength endpoint,
adding up to 16dp of resistance before switching modes. The full 0–100 range,
deliberate mode changes, direct taps and accessibility adjustments stay available.
Mode changes requested through the on-screen listening controls update those
controls without opening another island notice. Independent changes made on
the AirPods still announce their mode.
Volume recovery receives route changes even while a check is running. A handoff
clears the old fault timer and pending restoration, then requires a fresh stable
fault after returning; healthy capability remains tied to the connected owner.
Disconnect cleanup retires checks immediately without waiting for Bluetooth
queries or the volume ramp, and retired checks cannot continue writing volume.

The Quick Settings tile preserves its last reported availability while the shade
stops listening or reattaches to the service. Actual device or binding loss still
marks it unavailable; a tap during attachment waits briefly for that binding
before rechecking the current device and opening the picker.

Quick Settings opens the listening rail closer to the camera/status-bar safe
area. Android's public window APIs blur only the pill-shaped field, with a more
opaque fallback when system blur is unavailable. It needs no Shizuku or screen
capture. The island stays suppressed while the picker is open. The shared Apple
listening slider keeps clear, refractive glass; its 56dp bubble grows to 78dp on
press without resizing the track or changing drag coordinates. The popup's
public elevation buffer allows that bubble to extend beyond the blurred field.
Mode icons stay fixed in the track; the empty bubble optically magnifies the
captured pixels beneath it without stretching its contents with the glass.
At rest the selection is a flat tinted pill with normal-size icons. Pressing
animates the reference tab bubble's refraction to 10dp/14dp and uniform
magnification to 1.2x; release or
cancellation returns both to zero refraction and 1x with the press lighting.
Its capture temporarily backs the icons
with white or black so the shader can split contrasting RGB samples into
rainbow edges. A cached shader removes that backing after dispersion, restoring
transparency so Android's blurred pill remains visible through the lens.
The outer 3dp retains only the dispersed color, blending back to full content
by 6dp, so the optical rim does not repeat the icon as a black selection bar.
Holding the bubble brings in the reference tab bubble's directional highlight
and a moving radial press light beneath the fixed icons. Inner shading, offset
shadow and 78/56 expansion on both axes provide the raised shape; horizontal
growth is limited only near the endpoints to stay inside the popup buffer.
The track omits the rectangular default press highlight. Material controls
retain their selected theme.

Apple-theme floating buttons share one Backdrop glass material with a legible
resting tint, restrained refraction, edge lighting and damped touch feedback.
Navigation chrome samples the actual scrolling body from a sibling layer,
avoiding feedback between the source and its glass controls. Titles reserve
space for the measured actions. Grouped settings retain their solid reading
surface; switch thumbs and the shared listening-mode rail use matching optical
details. The Material theme keeps its existing controls. The listening rail also
serves the island and Quick Settings dialog without changing their layout or
capturing another application's screen. The library remains pinned to
`io.github.kyant0:backdrop:2.0.1`.

Back from the root screen returns to Android instead of sliding the only screen
offscreen. Nested screens retain predictive Back and the optional anywhere swipe;
stale queued Back requests at the root leave its content in place.

The island requests white status-bar icons while its wide, dark shell covers the
status bar. Its transparent, untouchable application-overlay companion is attached
at alpha zero before the island starts drawing, so it cannot affect system-bar
policy. Actual dark, rounded coverage switches the prepared window to alpha one
in the same composition commit, avoiding a new attachment on the critical path.
Folding returns it to alpha zero; disposal and context replacement remove it.
The current foreground app's appearance resumes without changing the system theme.
Narrow, light, translucent and displaced islands leave that appearance alone.
This policy has been checked on the Pixel; other Android implementations may
treat overlay appearance differently.
The appearance companion is limited to a positively identified bottom gesture
navigation bar. Button navigation and unknown layouts retain the foreground
app's system-bar appearance, because Android's dimming-window policy otherwise
also overrides navigation-button contrast.
The full outline is retained. Its shoulders open over 64ms while the body keeps
its 320ms unfold and closing keeps its 220ms fold. Camera clearance remains 4dp.
Android owns the native icon fade; its duration and per-frame color are not
controlled by this overlay or Shizuku.

Automatic Ear Detection uses the same effective choice in the screen and media
automation: a pending choice, then a valid device report, then the saved local
setting. Unknown device state never overrides a saved disabled choice.
Ear Detection control `0x0A` uses `2` for enabled and `1` for disabled, opposite
to ordinary boolean controls. Both Boolean writes and reported-state decoding
use this command-specific mapping; cached raw reports are not inverted.
Targeted noise-control widgets resolve their saved pair before acting and never
fall back to another connected pair. Invalid Adaptive strength reports preserve
the last confirmed value and any pending edit.

Conversation Awareness's temporary Transparency switch and matching mode recovery
do not announce themselves in the island. Live speech stages are correlated with
confirmed mode reports; ordinary mode changes still announce themselves. Only an
unrequested Transparency report while Conversation Awareness is enabled gets a
150ms presentation grace for a speech event arriving immediately afterward.

The expanded island header uses a content-sized height instead of stretching to
fill the shell. Its title and artwork stay near the camera-safe top even with a
tall custom island or hidden battery rows. The mode rail reserves its actual
64dp height. Expanded artwork and text align at the top, 4dp below the physical
camera; the same clearance controls the payload's folding and clipping.

The listening-mode Quick Settings tile opens a native tile dialog rather than an
app task, avoiding the full-screen activity launch transition. It shows only the
shared glass slider near the top, with 16dp side margins on phones. Outside touch
and Back dismiss it. The dialog owns its device binding and island suppression
until dismissal, even after Quick Settings stops listening; device replacement,
disconnection and lock checks still prevent stale commands.

On Android 17, a service started in the background can keep Bluetooth connected
while Android silently rejects its volume changes. With Shizuku authorized, the
service grants the `CONTROL_AUDIO` app-op only to LibrePods' own package and UID,
and verifies the result. Startup and Shizuku availability or permission changes
retry this setup; global audio hardening, other applications and microphone
permissions are unchanged. This allows Conversation Awareness ducking after an
update or background restart without first opening the app. See Android's
[background audio hardening guidance](https://developer.android.com/about/versions/17/changes/bg-audio).

After a car/AirPods handoff, Android can lose its AVRCP absolute-volume support
flag while Bluetooth still remembers the AirPods' capability. The foreground
service checks active-device changes, output changes and its existing maintenance
tick. Shizuku recovery requires the sole selected, bonded AirPods route and a
previous healthy absolute-volume observation for that same device object and
A2DP connection. Disconnection, unpairing and Bluetooth shutdown retire that
observation. A bounded, read-only Shizuku Bluetooth dump must also confirm native
absolute volume is enabled; inaccessible or unknown state prevents repair.
Calls, microphone operations and Conversation Awareness volume transitions defer
recovery. The dump runs only for a confirmed fault, never during healthy polling.

Recovery verifies numeric zero before reasserting absolute-volume behavior,
then ramps back to at most the pre-repair index, the last healthy index and 44%
of the stream maximum. Each write rechecks route, ownership and volume readback;
manual changes stop the ramp. Duplicate Bluetooth notifications coalesce, and
temporary system failures retain only the exact owned restoration for a bounded
retry. This repairs the framework state through the same
[volume-behavior API used by Bluetooth](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/avrcp/AvrcpVolumeManager.java);
it does not reconnect devices or replace Bluetooth's volume controller.

Bluetooth startup rechecks current Nearby devices permissions, including boot
and upgrade entry points. Missing grants return to the existing permissions step.
Screen changes retain a healthy nearby scan, and actual scan registrations obey a
rolling restart limit. Independent device-state changes are handled independently.
Call gestures are bound to their current connected, worn device and stop when that
owner retires. Heart-rate alerts respect the enabled setting and measurement state.
Island customization restores its entry values; short displays retain a bounded,
scrollable payload without changing the normal portrait animation.

Pixel42 retains recognized lid invitations until the case closes or the user
dismisses them. Pairing content transitions keep one shared viewport, and the
matching poster covers video startup through the card entrance. Battery rings
interpolate from their displayed level without delaying the numeric reading;
another pair, missing readings and reduced motion never inherit that sweep.

Protocol formats, hearing-mode safety and the current profile-reading limitation
are documented in [PROTOCOL.md](PROTOCOL.md).

## Behavior shared with NTPods

The [NTPods pairing and island design](https://github.com/Ripthulhu/ntpods/blob/main/DESIGN.md)
and this implementation share these interaction rules:

- A nearby case opening displays presence and battery information immediately;
  it never authorizes an audio connection. Connect explicitly requests that pair.
  A control socket alone is not proof that this host owns the audio output.
- Pairing instructions ask for the model's physical pairing gesture before
  Connect. The same card stays visible through discovery, pairing and success.
  Its spinning video and poster are the same assets on both platforms.
- Success uses fresh readings belonging to that invitation and newly paired
  identity, including eligible observations made before the bond completed.
  Public model advertisements and readings from earlier attempts cannot supply
  a success battery. Continue dismisses the card; only a subsequent verified
  case close/open permits another automatic battery island for that pair.
- The island reserves space for its status, connection action and battery ages.
  Nearby, Connected, progress and cached readings must not shift its contents.
  Fold motion reveals a laid-out payload, and dismissal retains the outgoing
  values. Reduced motion displays the requested state directly.
- Case display grace uses the original observation time and never preserves a
  charging claim after presence is lost. It does not grant connection ownership.
- A local automatic return may briefly hide Connect, without claiming success
  or initiating a connection. Android completion requires a matching native
  A2DP output to be available; automatic media actions also require the selected
  media route. Retired requests and cleanup cannot affect a replacement session.

When changing these paths, compare fresh pairing, lid closure during discovery,
immediate success batteries, repeated lid cycles, failed takeover and takeover in
both directions while media plays. Include missing readings, reduced motion and
large text. Source and unit checks establish state handling; two-host playback
and perceived animation smoothness still require physical-device checks.

## Connect from the nearby island

For an already-paired device, the expanded nearby island offers a Connect button
beside its name. It uses the existing authorized Shizuku bridge to request that
pair's Android media connection, including when another host currently owns it.
Opening the case alone never requests this handover. A control-only AACP
connection does not hide the action or count as a media connection.

The button stays in the fixed folding payload, shows progress without resizing,
and offers Retry if the request fails. The island stays open while connecting;
closing it cancels the request, and stale results cannot update a reopened island.
Tapping a compact nearby island expands it to reach the action. Unpairing,
Bluetooth shutdown, calls and microphone work prevent obsolete or conflicting
requests. The existing recovery guard preserves other connected Android audio
devices and does not change profile policies. Shizuku must already be authorized.

Case readings can refresh from newer authenticated BLE reports while the earbuds
are connected. With battery caching enabled, unknown or unavailable reports keep
the last valid percentage and its original age, without a charging indication.
The island keeps a recently observed case percentage current for up to 90 seconds
from its original report when the buds are removed, without retaining a charging
indication. It then shows the original age. One reserved age line keeps live, mixed
and cached readings from shifting the content.

Pixel30 was installed in place on the Pixel 10 Pro XL. The user confirmed that
Connect takes the AirPods back from another host, but reported a slow connection
and a premature Retry state before the connection completed. Pixel31 separates
this connection request from microphone playback recovery: it joins an existing
connection attempt and keeps an already-connected media profile intact. It waits
for the selected profile to finish connecting instead of treating Android's
Connecting state as failure. Completion also checks the live audio connection
before publishing a failure. Microphone playback recovery retains its existing
selected-profile restart behavior.

The user confirmed Pixel31's handover was much better, but Connect briefly
reappeared. Its timing capture showed profile completion at 6.855 seconds after
the tap, followed by Android publishing the output roughly 180 ms later. Pixel32
keeps Connecting until that output is available, bounded by a one-second wait;
it does not substitute cached connection state for live output readiness.

Pixel33 keeps Connect out of the way during an automatic return to this phone.
The battery island still opens immediately. An authenticated case close during
a local media connection permits a four-second presentation hint on the next
opening; Android's subsequent profile connection progress also defers the action
until output readiness or a bounded failure. This hint never claims that audio
is connected and never starts a Bluetooth connection. Unknown or remote case
closes offer Connect immediately. Failed automatic returns restore the action
with a fresh dismissal interval; Bluetooth shutdown and unpairing clear hints.

Pixel34 experimentally overlaps the direct secure AACP connection with Android's
media connection after an explicit Connect tap. Both paths keep the existing
selected-pair, Shizuku, call and microphone guards. Pending control work has an
eighteen-second deadline and can cancel only its captured unpublished socket;
live or replacement connections remain intact. Passive case openings still
start neither connection. Only the selected live audio output completes takeover.

The Pixel33 baseline exposed a false failure after native media connection:
Android registered the output, but the app did not observe it within one second.
Pixel34 allows up to four seconds for output readiness within the existing total
request budget and records redacted query diagnostics on failure. This is
tolerance for delayed observation; the baseline does not establish the exact
cause. Speed comparisons must measure the selected live output, not just the
control socket or native profile connection.

Pixel35 makes the island read its final audio state after evaluating the
reconnection hint. A newly published audio output can clear that hint, so
using an earlier audio reading could otherwise briefly expose Connect.
Rapid case closes and reopens also retain an existing reconnect hint's original
deadline instead of briefly clearing or renewing it. A failed native reconnect
clears both the hint and eligibility to start it again from that failure.
These presentation fixes add no delay to discovery or takeover.

Pixel36 dismisses the matching case-opening island on a newer authenticated
case close, before disconnected battery data can replace its outgoing payload.
It keeps that payload, including any connection progress, stable throughout the
existing fold animation. Stale closes, another pair, previews and listening-mode
notices cannot dismiss the current case announcement.
Status changes use a short clipped vertical transition, while Connect and its
progress state animate inside a fixed action area. The name and artwork keep
their positions. Hidden controls stop accepting input immediately; system
animation settings also apply to these transitions.

Pixel38 removes the old BLE reconnect path, including its hidden, previously
default-enabled takeover choice. Existing saved flags cannot reactivate it.
Case openings remain fast battery/presence observations and offer the explicit
Connect button when another host owns audio. Available-device construction and
late ACL/SDP notifications cannot initiate a control connection without a native
local A2DP or headset audio output; newly published Android outputs attach control
without paging an otherwise remote device.

Ear detection now pauses or resumes only while Android selects this pair for
media. It never enables, disables or reconnects media/call profiles. The old
Disconnect when not wearing control is removed; its serialized value is retained
for compatibility. Android's native reconnection and the AirPods' Automatic
Connection preference remain separate from LibrePods' explicit Connect action.
The optional microphone's selected-transport recovery is unchanged: Android does
not expose a reliable peer-owner signal during that deliberate media transition,
so switching to another host during microphone cleanup still needs a hardware
check. No cross-host test was performed for this change.

## Listening-mode controls

Pixel44 refines the shared Apple listening-mode control with an inset glass
thumb, restrained drag deformation and fixed label space. Adaptive retains its
expanded strength track; pending mode and strength choices remain visible until
a device report replaces them. Unsupported Adaptive modes are hidden, and mode
options expose names, selection and disabled state to accessibility services.

The Android ANC Quick Settings tile opens a compact listening-mode picker instead
of cycling modes. Opening it never writes a command. The picker uses the same
Apple control, preserves the selected pair, respects Allow Off and model
capabilities, and closes if that device disconnects or is replaced. Locked phones
require unlocking first. Outside touch and Back dismiss it and release its
service binding. The 64dp rail stays the same size through loading, pending and
error states; accessibility announces status, and failed writes show a brief
message. The tile dialog needs no overlay permission and leaves the underlying
screen undimmed, with a short animation confined to the slider window.

Pixel45 coordinates this picker with the dynamic island. A visible picker owns
presentation until it stops: an existing island is removed, new automatic island
notices are suppressed, and dismissal never replays old notices. Reports and
pending controls continue through the same device state. Mode changes in an
already-visible island update its controls in place without restarting its fold
or replacing a battery view. Allow Off, connection and model changes refresh the
island's controls; disconnected or unsupported controls cannot issue commands.
The paired island uses dark appearance against its black surface while retaining
the app's selected design system and font preferences. Its compact control aligns
with the header and batteries.

## Android audit fixes

Pixel29 rejects reports from retired control connections and prevents an early
disconnect from being published as connected. App settings use atomic updates
and one database writer per row. The Head Gestures switch applies both when a
call rings and when a pending gesture result arrives.

The island recovers after failed window attachment, excludes unknown battery
reports and cancels old dismissal timers for settings previews. A pairing card
that is busy with another model no longer consumes the second model's invitation.
The Quick Settings tile observes current per-device state, and noise-control
widget actions retain their own widget identity.

Recorder shares the microphone permission flow, offers Shizuku connection and
shows failures.
The optional Xposed vendor hook respects its switch and registers each record
once. The commented-out predecessor service has been removed.

## Pairing and island latency

- Unpaired lid invitations require readings of at least -55 dBm from the same
  advertiser sustained for 350 ms, with no gap over 1.5 seconds. A passing strong
  sample cannot qualify after a weak report. Only the admitted advertiser can
  close its invitation; dropping signal does not cancel an active pairing.
  Authenticated paired-device batteries and islands bypass this invitation gate.
  RSSI is a proximity heuristic; the threshold is not a distance measurement.
- Company-filtered BLE scanning delivers individual reports with no batching.
  While the screen is on, the service temporarily uses low-latency scanning;
  screen-off scanning uses the configured mode. The preference is not changed.
- Public lid reports display a model invitation without waiting for Classic
  discovery, an audio connection or AACP initialization. The first card explains
  the model's double-tap or setup-button gesture until the light flashes white,
  then says to tap Connect. Discovery starts only after Connect. A sole matching
  Classic candidate can proceed after two stable
  seconds, without waiting for the full inquiry and another fixed delay. Name
  updates are accepted only for Classic addresses found in that scan; multiple
  matching addresses prevent automatic selection.
  A selection timer that fires before its eligibility deadline re-arms for the
  remaining interval; it cannot strand a valid candidate until inquiry finishes.
- Incoming native pairing can begin before discovery selects the device.
  The same attempt receiver tracks that progress, admits fresh Classic results
  already bonding, and adopts only the sole stable selected address instead of
  starting another bond. A protected consent request received during discovery
  can be revisited once that address is selected, for at most fifteen seconds from
  receipt, provided the exact bond remains active. Before selection it never
  confirms; the separate consent worker can defer the native prompt for at most
  seven seconds while discovery continues. Removing the bond, changing the variant,
  retrying or closing retires
  the hint. PIN/passkey prompts retain Android's normal confirmation flow.
- With already-authorized Shizuku, Connect warms the shell bridge without
  delaying discovery. It can confirm only the active selected AirPods request's
  consent step while Android reports that exact device is bonding. Closing,
  retrying or revoking permission retires the request. PIN/passkey variants and
  unavailable Shizuku retain Android's normal confirmation flow. Putting the
  case into pairing mode remains a physical step.
- For an ordered consent request from that selected bonding device, the
  controller briefly holds delivery while Shizuku confirms it. Success or an
  observed completed bond retires the request before Android opens its dialog;
  failure, cancellation or a 1.5-second deadline releases the normal prompt.
  This avoids the system dialog briefly hiding the card when the controller
  receives the request first. Cross-process receiver order remains platform
  dependent; unordered requests keep the existing confirmation behavior.
- The pairing card presents separate nearby, case setup, connecting, retry and
  completion states. Its title follows the current step, busy states use a
  compact progress indicator, and completion offers Continue without delaying
  device setup. The white card slides as a single sheet over a subdued backdrop;
  artwork and text retain their layout instead of scaling or fading separately.
  The existing Pro spinning video excludes its two blank opening frames and uses
  a matching first-frame poster until the current decoded texture is available.
  Unsupported models, reduced motion and playback errors retain model-specific
  static artwork. The video stays visible through the short exit, then releases.
- The success card stays open for Continue or a real lid close, and updates its
  AirPods/case battery row from the exact newly bonded device. Readings from
  earlier invitations or pairing attempts, unknown statuses and unavailable or
  stale components do not become live percentages. Different bud levels remain
  separate. Recognizing the new owned BLE identity does not synthesize a close
  for the active card.
- Successful pairing suppresses that pair's automatic island announcements for
  the current case opening, including queued connection updates. Continuing or
  dismissing the success card keeps that suppression. A newer verified lid-close
  report re-enables the next opening. Listening-mode notices and other pairs
  remain available; replayed batteries and model-only invitations cannot reset
  the suppression.
- Owned battery/lid reports require a current bond and valid per-device keys.
  Public advertisements cannot establish a Classic identity or overwrite an
  owned device's batteries.
- Repeated case cycles retain known battery percentages when disconnected caching
  is enabled. Disconnected readings keep their original age and lose their live
  charging state; unavailable slots cannot fabricate a fresh zero. Battery updates
  reach the island independently of coalesced capability changes.
- Recent LE advertisements stay in a small memory-only buffer for 15 seconds.
  When the bonded device's saved keys are available, or its current control
  connection supplies a complete key pair, matching frames are verified and
  decoded to fill missing battery readings without waiting for another
  advertisement or a control battery report. Their original
  observation times are preserved, and current live component readings win.
  Pairing completion accepts verified readings from its active invitation and
  pairing attempt; earlier attempts and foreign devices remain excluded.
  Replaying a battery reading does not replay lid, reconnect or pairing actions.
- Bluetooth connection work runs off the UI thread. Construction and connection
  attempts are serialized per device. The cached identity loads before scanning.
- Saved, still-bonded Apple identities load even when Android's service UUID
  cache is empty, so nearby ownership does not wait for Classic discovery.
  Android 17 uses passive scanning to receive advertisements without waiting
  for a scan response. Older versions retain their existing scan type.
- Incomplete key responses retain the previous complete IRK/encryption pair;
  a valid replacement changes both together. Cache, metadata and settings
  updates are transactional so concurrent saves cannot overwrite each other.
- The island attaches immediately as a visible camera-sized capsule. Its shell
  expands around a payload measured at its final size, so text and artwork do
  not reflow while opening or closing. Remove Animations snaps to the requested size.
  It wraps the actual camera path and limits expanded width to 420 dp with
  screen margins. Passive battery updates retain the owner and dismissal time;
  a canceled exit cannot remove a reopened or replacement overlay.
- Content scales together to fit inside the shell below the camera, with camera
  clearance kept outside that movement. Compact artwork and battery occupy
  separate spaces beside the camera. Closing returns to the capsule before removal.
  The native window retains enough width for the outgoing content until the
  matching transition finishes. Cached battery ages use coarse units and share
  one last-seen label when all components match; their stored ages stay separate.
- Confirmed listening-mode changes made on the AirPods display their localized
  mode name. Confirmations of on-screen slider choices, cached values, pending
  requests, first reports and duplicate reports stay silent; each connection
  establishes a fresh notification baseline.
- Adaptive strength keeps the requested slider position separate from reported
  controls because Pro 3 can accept the write without echoing it. A subsequent
  valid strength report remains authoritative, including rounded values. Merely
  opening the Material slider does not send its initial/default value.
- Pro 3's Allow Off choice is saved per pair after a successful current-session
  write. It stays separate from device reports, survives reconnects and app
  restarts, and is replaced by a valid fresh report. Reopening does not resend it.
- Refresh device settings reopens only the selected AirPods' control session.
  It retains the Bluetooth bond and media connection, requests fresh settings,
  and refuses calls, microphone sessions, audio recovery and concurrent refreshes.
- Debug timing logs measure Android's radio timestamp to the overlay request
  and the first drawing pass.
  They do not measure when the physical case opened or prove Windows parity.
  Screen-on scanning trades additional radio activity for prompt detection.

## NTPods protocol cross-check

Reviewed against NTPods' control, audio and macOS case/sensor references on
2026-10-16. Findings transferred into the rewrite include:

| Area | Correction |
| --- | --- |
| Information | Preserve all positional strings, including empty fields; select the public firmware safely rather than interpreting an internal build's first digit. |
| Battery | Validate counted records, unknown/unavailable values and duplicates; retain independent component ages; charging expires with the live observation. |
| Nearby BLE | Validate complete Apple records, RPA ownership, model mapping, battery slots and known versus unknown lid state. Reject stale/reordered scan observations. |
| Keys | Unsigned bounded TLV lengths, duplicate/zero-key rejection, exact identity binding and no general key packet history. |
| Controls/EQ | Validate lengths and supported values; distinguish requested values from reports; EQ uses opcode `0x63` and the verified Pro 3 firmware gate. |
| Rename | Count UTF-8 bytes and put the reserved zero before the name; reject invalid/overlong names; remove optimistic reported-name changes. |
| Connected hosts | Reject incomplete/duplicate lists without replacing the current list with a partial result; host flags do not establish device type. |
| Conversation awareness | Process status independently of unrelated battery changes; bind ducking/restoration to the owning transport and route; suppress host ducking during HQ microphone capture. |
| Sensors | Use validated descriptors and signed channels, bounded frame reassembly and HR initialization; suspend the captured sensor session during microphone use and preserve its intent. |
| Privacy/lifetime | Bound packet history; exclude audio, keys and identity-bearing packets; redact raw logs; retire callbacks with their owning connection. |

## Experimental audio boundaries

Microphone Mode contains only the native Automatic, Always Right and Always Left
choices. The failed audio diagnostics, duplicate test recorder, app microphone
injection and cellular-call relay experiments have been removed with their
exclusive routing code. Native Android/HFP call audio remains on Android.

Recorder retains the physical PCM clock (64 kHz), bounded microphone decoder and
session recovery. Recording is explicit, has Stop/Play/Share and a foreground
indicator, and fails closed if microphone access is revoked. Short diagnostic
recordings remain private and are excluded from backups. The database and
retained legacy preferences are also excluded because they contain keys.

Previous physical tests on the older fork do not validate Recorder in this
rewrite. High-quality two-way call audio is unavailable.

Upstream rewrite ATT transport is disabled. Safe hearing/transparency codecs and
read-only handling of unsupported formats do not imply functioning rootless ATT
access. No fixed ATT handle has been reintroduced. Live pending requests remain
connection-owned. Pro 3's saved Allow Off choice is a separate presentation
fallback, and is not presented as a device report.

## Validation

Pixel28 moves the existing model-specific pairing-mode instructions onto the
initial card, followed by “Then tap Connect.” Pairing, discovery, consent guards,
layout and animation behavior are unchanged. Existing rendering fixtures use the
longer real message for normal, narrow large-text and landscape checks.
On 2026-10-17, `verifyPixel` passed all 233 tests in 43 suites with no failures,
errors or skips; lint reported 0 errors, 289 warnings and 1 hint; debug and
minified FOSS release builds passed. Native rendering checks and batched visual
inspection confirmed the longer instructions fit, with Connect reachable at
normal size, narrow large text and scrolled landscape large text. Review caught
and repaired a missing local context in a rendering fixture; the obsolete first
snapshot was stopped and the corrected full build passed. Pixel28 was signed
with the existing fork certificate and installed in place. Android confirmed
version `2.0.0-alpha.1-pixel28`, code `200000113`, with Shizuku permission retained.
Fresh pairing was not repeated for this copy change; no bond, audio route or
call setting was changed.

Pixel27 defers compatible incoming consent in a separate, non-exported app
process during an explicit pairing attempt. Android serializes broadcasts per
process, so this lets the main process continue fresh Classic discovery while
the worker holds the dialog for at most seven seconds from receipt. The main
observer runs first and retains native-cycle/variant invalidation. Approval
still requires the sole stable fresh Classic selection, a live attempt,
native bonding, and the existing guarded Shizuku confirmation. The helper gets
at most 1500 ms and never extends the worker's original deadline. Unavailable
Shizuku, ambiguity, another selected identity, expiry, cancellation, owner death
and process teardown return the request to Android without aborting it.
A one-time 100 ms capture grace preserves automatic confirmation if a worker
acknowledges but misses an already-resolved broadcast. No native dialog is
disabled globally.
On 2026-10-17, `verifyPixel` passed with 233 tests in 43 suites, no failures,
errors or skips, lint with 0 errors, 289 warnings and 1 hint, and successful
debug and minified release builds. Eleven new tests cover worker admission,
exact request ownership, native fallback, acknowledgement and disconnect
retirement, late callbacks, delayed fresh selection, a lost capture callback,
and the original hold deadline limiting a late helper. Existing controller
tests supply a valid service binding because Robolectric's default null
connection callback does not match Android's separate `onNullBinding` callback.
An initial compile failure was repaired by retaining the paired-device callback
as the trailing constructor argument; a second run exposed that fixture gap.
The full third run passed after fixture repair, with production unchanged.
Independent review found no remaining blockers. Pixel27 was signed with the
existing certificate and installed in place; Android confirmed version
`2.0.0-alpha.1-pixel27`, code `200000112`, and the retained Shizuku grant.
The user repeated fresh pairing twice and confirmed automatic completion
without the Android prompt flashing in either attempt. Both traces captured
the worker-held request before guarded confirmation; helper responses took
41 ms and 30 ms after capture, with native bonding complete at 11270 ms and
10616 ms after Connect. Discovery and physical pairing-mode entry vary, so
these totals do not measure a fixed speedup. Both requests arrived after fresh
selection; incoming-before-discovery is covered by the delayed-selection
regression and still needs another physical sample. The bounded capture is
stopped, the worker service is unbound, and the original empty logging property
is retained. No microphone or call route was started; normal Android audio mode
was verified before installation.

A subsequent controlled timing check on the unchanged Pixel27 build put the
case into flashing-white pairing mode before tapping Connect. The user reported
noticeably faster pairing. First fresh Classic discovery arrived at 708 ms,
sole-candidate selection at 2709 ms, BR/EDR bond creation at 2716 ms, consent at
3183 ms, guarded confirmation at 3286 ms, and native BONDED at 4087 ms after
Connect. This is one physical sample, compared with the earlier 11270/10616 ms
attempts whose gesture happened after Connect; it does not isolate all radio
variation or prove a fixed 6–7 second improvement. Keep the current two-second
identity/ambiguity window and prompt fallback. No implementation or rebuild was
needed for this faster order. Do not start ambient Classic inquiry just to hide
the wait: it uses the shared Bluetooth radio and can degrade other connections.
The timing capture is stopped and the original logging setting is retained.

Pixel26 repairs a one-shot selection callback that could drop a still-valid
candidate when it ran just before the elapsed-time eligibility deadline.
It rechecks and re-arms only for a positive remaining interval; ambiguity,
cancellation, current-attempt lifetime and the two-second sole-candidate window
remain enforced. Candidate-check timestamps distinguish timer delivery from
later bond/discovery IPC. The earlier Pixel25 trace is consistent with a
stranded selection, but did not record timer delivery and does not prove that
specific attempt's cause.
On 2026-10-17, `verifyPixel` passed with 222 tests in 42 suites, no failures,
errors or skips, lint with 0 errors, 289 warnings and 1 hint, and successful
debug and minified release builds. Two new regressions simulate a timer at
1999 ms and verify one BR/EDR bond at 2000 ms without inquiry completion, plus
ambiguity or cancellation retiring the replacement timer. Independent review
found no remaining blockers. Pixel26 was signed with the existing certificate
and installed in place; Android confirmed version `2.0.0-alpha.1-pixel26`,
code `200000111`, and the retained Shizuku grant. The user reported fast pairing;
Android's prompt appeared but required no tap and dismissed automatically.
The captured attempt selected exactly 2000 ms after its first fresh Classic
candidate, adopted the incoming bond in 5 ms, and completed at 10153 ms after
Connect. Its first candidate arrived at 6817 ms; this includes time before the
phone found the case and does not isolate entry into physical pairing mode.
No early re-arm or full-scan stall occurred in that attempt, so it does not prove
the old trace's cause or a fixed speedup across radio conditions. Bounded captures
are stopped and the original system logging setting is retained. Layout and
motion are unchanged; no microphone route or call started.

Pixel25 suppresses automatic island announcements for the exact newly paired
device's current case opening. Suppression is armed before asynchronous device
setup and checked on the main thread after queued shows arrive. A newer owned,
decoded close releases it; battery replay, stale or foreign close reports and
ordinary UI dismissal do not. Explicit listening-mode notices remain available.
Unpairing, Bluetooth-off and service teardown retire the matching suppression.
No layout or animation geometry changes are included.
On 2026-10-17, `verifyPixel` passed with 220 tests in 42 suites, no failures,
errors or skips, lint with 0 errors, 289 warnings and 1 hint, and successful
debug and minified release builds. Two new window regressions cover queued
announcements, exact-owner dismissal, stale/equal/foreign closes, the next
opening, ordinary dismissal, explicit notices and cleanup. Their synthetic
window supplies lifecycle, saved-state and view-model owners for real queued
main-thread traversal. Independent review found no remaining blockers.
Pixel25 was signed with the existing certificate and installed in place;
Android confirmed version `2.0.0-alpha.1-pixel25`, code `200000110`, and the
retained Shizuku grant. On 2026-10-17, the user confirmed two fresh pairings
complete automatically without Android's Pair button. They also confirmed
the success card appears alone, and after Continue plus a case close/open the
island returns on that next opening. The bounded timing capture was stopped;
the system logging preference remains at its original setting.
No experimental microphone route or call was started.

Pixel24 retains an exact active incoming consent for fifteen seconds from its
original receipt, allowing the observed 5667 ms discovery/selection path to
reach Shizuku. Duplicate requests do not renew it. The selected helper still
has at most 1500 ms and never outlives the hint; shell and client deadlines,
native fallback, cycle/variant retirement and fresh candidate selection are
unchanged. Tests reproduce the observed delayed candidate and cover exact
fifteen-second expiry plus a late selection with only 500 ms remaining.
On 2026-10-17, `verifyPixel` passed with 218 tests in 42 suites, no failures,
errors or skips, lint with 0 errors, 289 warnings and 1 hint, and successful
debug and minified release builds. Independent review found no remaining
blockers. Pixel24 was signed with the existing certificate and installed in
place; Android confirmed version `2.0.0-alpha.1-pixel24`, code `200000109`,
and the retained Shizuku grant. Subsequent physical checks are recorded under
Pixel25. Layout and motion are unchanged; no experimental microphone route or
call was started.

Pixel23 adds release-visible, identity-free pairing diagnostics for request
variant and native state, selected consent age, helper availability and guard
rejection. No names, addresses, keys or pairing codes are recorded. Pairing
consent policy and timing bounds are unchanged. A new regression models the
observed two incoming native cycles during one uninterrupted Classic inquiry,
with both orderings of the second bonding broadcast and protected consent.
On 2026-10-17, `verifyPixel` passed with 216 tests in 42 suites, no failures,
errors or skips, lint with 0 errors, 289 warnings and 1 hint, and successful
debug and minified release builds. Pixel23 was signed with the existing
certificate and installed in place. Android confirmed version
`2.0.0-alpha.1-pixel23`, code `200000108`, and the retained Shizuku grant.
The physical diagnostic repeat still required Android's Pair button on one
attempt. The trace captured protected consent with native BONDING state, then
its first Classic candidate 3660 ms later. Sole stable selection followed
another 2007 ms later, while the same bond remained active. Its valid consent
hint was 5667 ms old and skipped the former five-second limit, identifying a
concrete cause of the intermittent manual confirmation. No experimental
microphone route or call was started.

On 2026-10-17, Pixel22's `verifyPixel` passed with 215 tests in 42 suites,
no failures, errors or skips, lint with 0 errors, 289 warnings and 1 hint,
and successful debug and minified release builds. Six new controller regressions
cover incoming consent before selection, continuous receiver ownership, adoption
without duplicate bonding, completed-bond reconciliation, expired and invalidated
hints, changed variants, native fallback and a new bond cycle with a delayed
state broadcast. Independent review found no remaining blockers. Layout and
animations are unchanged; existing rendering and input tests still pass.

Pixel22 was signed with the existing certificate and installed in place.
Android confirmed version `2.0.0-alpha.1-pixel22`, code `200000107`, and the
retained Shizuku grant. In the physical repeat, the user reported that the first
pair completed automatically, but the second required Android's Pair button.
Native history showed the second request arriving during uninterrupted inquiry,
about two seconds before candidate selection, so the scan-retry listener gap
does not explain that attempt. Android's earlier
history showed one local bond completing in 895 ms, while an incoming pairing
request waited 3423 ms for the user's manual confirmation. These are native bond
timings, not measurements from tapping Connect. No experimental microphone
route or call was started for this update.

On 2026-10-17, Pixel21's `verifyPixel` passed with 209 tests in 42 suites,
no failures, errors or skips, lint with 0 errors, 289 warnings and 1 hint,
and successful debug and minified release builds. New regressions cover raw
pre-key reports, their original ages, bounded retention, expired/foreign data,
latest-frame unavailable slots, missing-slot fill during AACP, current device,
bond, transport and key guards, saved-key replay without a control socket,
pre-Connect invitation readings, retry boundaries and stale key publication.
The service replay tests exercise the actual handoff method. Overlay layout
and animations are unchanged; existing rendering and input tests still pass.

Pixel21 was signed with the existing certificate and installed in place.
Android confirmed version `2.0.0-alpha.1-pixel21`, code `200000106`, and the
retained Shizuku grant. On 2026-10-17, the user confirmed that after forgetting
and pairing again, both the success card and island show battery percentages
immediately, without the temporary dashes.
A first-ever pair still needs its keys before encrypted LE readings can be
verified. No experimental microphone route or call was started for this update.

On 2026-10-17, Pixel20's `verifyPixel` passed with 201 tests in 40 suites,
no failures, errors or skips, lint with 0 errors, 289 warnings and 1 hint,
and successful debug and minified release builds. New regressions exercise
Allow Off through real transport writes, Room persistence, app reload and fresh
contradictory reports; control-only refresh, concurrent/canceled replacement
sockets and audio reservations; and repeated case cycles with partial battery
reports, retained zero readings and stale callbacks. The real Refresh touch
test also caught a shared Apple button's stale enabled-state capture; disabled
buttons now reject taps and cancel their pressed appearance cleanly. Native
Refresh captures cover narrow/large-text light and dark Material settings;
Apple input checks use the existing legacy-renderer workaround.

Pixel20 was signed with the existing certificate and installed in place.
Android confirmed version `2.0.0-alpha.1-pixel20`, code `200000105`, and the
retained Shizuku grant. On 2026-10-17, the user confirmed that repeated case
cycles keep the island's battery and charging indicators updating, Allow Off
stays selected after reconnecting and reopening LibrePods, and Refresh completes
without leaving the settings page or interrupting music playback.
No experimental microphone route or call was started for this update.

Pixel19's `verifyPixel` passed with 183 tests in 36 suites, no failures, errors
or skips, lint with 0 errors, 289 warnings and 1 hint, and successful debug and
minified release builds. New regressions cover exact post-bond battery ownership,
retaining the success card, independent reading expiry, unpair cleanup, rounded
Adaptive reports, actual drag/release and reopening, and Material's input-only
writes. Battery layouts were checked with native API 37 rendering in light,
narrow/large-text dark and short landscape layouts, with both balanced and split
bud levels. The Apple drag test uses API 33's legacy renderer because Robolectric's
native AGSL compiler rejects the existing backdrop shader; Material uses native
rendering. The expiry test ages its readings before real scheduled callbacks
because this runner's virtual Handler clock does not advance the wall clock.
Pixel19 was signed with the existing certificate and installed in place; Android
confirmed version `2.0.0-alpha.1-pixel19` and the retained Shizuku grant. On
2026-10-17, the user confirmed that Adaptive strength holds after release and
reopening, and that fresh pairing shows AirPods/case percentages on the success
card until Continue. No audio checks were repeated.

Pixel18's `verifyPixel` passed with 173 tests in 35 suites, no failures, errors
or skips, lint with no errors, and successful debug/minified release builds.
New regressions cover successful held consent, completed bonding before IPC
returns, cancellation with a late successful reply, the independent fallback
deadline, helper failure and unrelated/passkey requests. Robolectric does not
propagate an asynchronous pending result's abort flag through its ordered
receiver chain, so success checks assert that result's abort/completion flags;
fallback checks retain delivery to a lower-priority receiver. Pixel18 was signed
with the existing certificate and installed in place; Android confirmed its
version. After forgetting the old bond, the user confirmed that the card stays
visible through Connecting to Paired and pairing completes. This verifies the
handoff on this Pixel/Shizuku setup; receiver order remains platform dependent.
No audio checks were repeated.

Pixel17's `verifyPixel` passed with 167 tests in 35 suites, no failures, errors
or skips, lint with no errors, and successful debug/minified release builds.
Native Android 17 rendering covers all five pairing states, narrow screens,
large text and short landscape layouts, plus coordinated sheet motion and
reduced motion. The Pro clip's 178 decoded frames contain no blank frames; its
first frame and loading poster match. Pixel17 was signed with the existing
certificate and installed in place after wireless debugging reconnected; Android
confirmed its version. The user reported a brief disappearance and return of the
card during automatic confirmation, prompting Pixel18's consent handoff fix.
This polish does not retest audio.

Pixel16's `verifyPixel` passed with 163 tests in 35 suites, no failures, errors or skips,
Android lint with no errors, and successful debug/minified release builds.
Pairing regressions cover the live selection deadline, repeated/name-delayed
reports, ambiguous or LE candidates, older Android's bond API, discovery
ownership, canceled receivers and narrowly scoped Shizuku consent. Pixel16 was
signed with the existing certificate and installed in place; Android confirmed
its version. After forgetting the old bond, the user confirmed that Pixel16
pairs automatically and is much faster. No pairing timing logs
were retained for that attempt, so there is no measured end-to-end duration.
Native pairing-card captures cover light mode and dark mode with narrow width
and larger text. Pixel15 was signed with the existing fork certificate and
installed in place, retaining the app's data. Android confirmed its version.
Android's scan state confirms
unbatched passive low-latency monitoring. The user confirmed the island now
appears promptly on Pixel12 and confirmed Pixel13's camera-origin expansion.
Pixel14 added cached-age cleanup and confirmed listening-mode notices, but the
user reported a white flash on dismissal and rejected the content fading.
Pixel15 replaces that motion with fixed-layout payloads and a coordinated
camera-capsule transition, and applies a dark framework context to the overlay
to opt out of Android's color inversion. Pixel15 passes native rendering checks
for fixed layout and complete content fitting during folds, including compact
camera clearance and the shortest accepted expanded layout. Rendering uses
API 33 because Espresso's legacy input bridge is unavailable on API 37; theme,
native window width and callback lifetime checks run on API 37.
The user confirmed Pixel15 opens and closes smoothly, with no overlapping text
or white flash, and that listening-mode changes display the new mode in the island.
Regression coverage includes visible initial geometry, reduced motion,
interrupted exits, age grouping, per-transport mode baselines and concurrent
control/battery/wear cache updates.

The first physical Pixel11 check still took roughly ten seconds after opening
the case, despite 32 ms from Android's scan timestamp to the overlay request and
58 ms to its first drawing pass. Closing the lid dismissed promptly. Pixel12
changes scanning and cached-identity admission. Its owned lid reports reached
the overlay request in 11–31 ms and first drawing pass in 27–62 ms; those are
radio-to-frame measurements, not physical lid-to-frame measurements. Fresh
pairing was subsequently confirmed on Pixel16; sensors and call audio have not
been retested on the rewrite.
