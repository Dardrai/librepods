# Pixel fork on the Android rewrite

This branch starts at upstream `android/rewrite`, commit
`a4d8377df0bb468f371289a1b53eeba7c23a9f06`. It retains the rewrite's screens,
design choices, settings and per-device architecture. The older
`pixel-shizuku-port` branch is a reference for previous hardware checks, not
the frontend base.

## Build and upgrade

Use JDK 21 and the Android SDK/NDK versions declared by the project. The existing
Gradle toolchain configuration selects/downloads JDK 25 for its daemon. Run
`./gradlew verifyPixel` from `android/`. This checks core and app unit tests,
Android lint, the debug APK and the minified FOSS release APK. Signing is
deliberately external; no signing key or password belongs in the repository.

Install a release signed with the existing fork certificate using an in-place
update. Do not uninstall first. The rewrite migrates the old app's selected
pair, local behavior choices and correctly owner-bound BLE keys once. Existing
rewrite database rows are preserved. Unconfirmed device control values are not
migrated as device reports. The Android Bluetooth bond stays with Android.

The current fork version is `2.0.0-alpha.1-pixel17`, code `200000102`.

## Pairing and island latency

- Company-filtered BLE scanning delivers individual reports with no batching.
  While the screen is on, the service temporarily uses low-latency scanning;
  screen-off scanning uses the configured mode. The preference is not changed.
- Public lid reports display a model invitation without waiting for Classic
  discovery, an audio connection or AACP initialization. Discovery starts only
  after Connect. A sole matching Classic candidate can proceed after two stable
  seconds, without waiting for the full inquiry and another fixed delay. Name
  updates are accepted only for Classic addresses found in that scan; multiple
  matching addresses prevent automatic selection.
- With already-authorized Shizuku, Connect warms the shell bridge without
  delaying discovery. It can confirm only the active selected AirPods request's
  consent step while Android reports that exact device is bonding. Closing,
  retrying or revoking permission retires the request. PIN/passkey variants and
  unavailable Shizuku retain Android's normal confirmation flow. Putting the
  case into pairing mode remains a physical step.
- The pairing card presents separate nearby, case setup, connecting, retry and
  completion states. Its title follows the current step, busy states use a
  compact progress indicator, and completion offers Continue without delaying
  device setup. The white card slides as a single sheet over a subdued backdrop;
  artwork and text retain their layout instead of scaling or fading separately.
  The existing Pro spinning video excludes its two blank opening frames and uses
  a matching first-frame poster until the current decoded texture is available.
  Unsupported models, reduced motion and playback errors retain model-specific
  static artwork. The video stays visible through the short exit, then releases.
- Owned battery/lid reports require a current bond and valid per-device keys.
  Public advertisements cannot establish a Classic identity or overwrite an
  owned device's batteries.
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
- Confirmed listening-mode changes display their localized mode name. Cached
  values, pending requests, first reports and duplicate reports stay silent;
  each connection establishes a fresh notification baseline.
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

The port keeps the verified physical PCM clock (64 kHz), bounded microphone
decoder and session recovery. Recording is explicit, has Stop/Play/Share and a
foreground indicator, and fails closed if microphone access is revoked. Short
diagnostic recordings remain private and are excluded from backups. The database
and retained legacy preferences are also excluded because they contain keys.

Previous physical tests on the older fork established normal-pitch HQ microphone
capture with good media quality and two-way cellular call relay. They do not
validate this new rewrite build. Normal-quality media during a cellular call is
still unresolved; Pixel Recorder's explicit microphone selection can bypass the
UID injection route. AAC-ELD playback remains gated because the available encoder
does not produce the verified 480-sample configuration.

Upstream rewrite ATT transport is disabled. Safe hearing/transparency codecs and
read-only handling of unsupported formats do not imply functioning rootless ATT
access. No fixed ATT handle has been reintroduced. Silent Allow Off/adaptive
requests are retained only for the current connection, not presented as reports.

## Validation on 2026-10-16

Pixel17's `verifyPixel` passed with 167 tests in 35 suites, no failures, errors
or skips, lint with no errors, and successful debug/minified release builds.
Native Android 17 rendering covers all five pairing states, narrow screens,
large text and short landscape layouts, plus coordinated sheet motion and
reduced motion. The Pro clip's 178 decoded frames contain no blank frames; its
first frame and loading poster match. Pixel17 was signed with the existing
certificate and installed in place after wireless debugging reconnected; Android
confirmed its version. The live appearance/motion check is pending. This polish
does not retest audio.

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
