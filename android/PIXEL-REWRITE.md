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

The current fork version is `2.0.0-alpha.1-pixel11`, code `200000096`.

## Pairing and island latency

- Company-filtered BLE scanning delivers individual reports with no batching.
  While the screen is on, the service temporarily uses low-latency scanning;
  screen-off scanning uses the configured mode. The preference is not changed.
- Public lid reports display a model invitation without waiting for Classic
  discovery, an audio connection or AACP initialization. Discovery starts only
  after Connect. Pairing still requires Android's native confirmation.
- Owned battery/lid reports require a current bond and valid per-device keys.
  Public advertisements cannot establish a Classic identity or overwrite an
  owned device's batteries.
- Bluetooth connection work runs off the UI thread. Construction and connection
  attempts are serialized per device. The cached identity loads before scanning.
- The island attaches in its visible state immediately, wraps the actual camera
  path and limits its expanded width to 420 dp with screen margins. Passive
  battery updates retain the original owner and dismissal time.
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

`verifyPixel` passed with 125 tests in 28 suites, no failures, errors or skips,
Android lint with no errors, and successful debug/minified release builds.
Native pairing-card captures cover light mode and dark mode with narrow width
and larger text. The release was signed with the existing fork certificate and
installed in place on the Pixel; the rewrite opened with the saved AirPods entry.

Physical case-opening timings, a fresh pairing, sensors and call audio have not
been retested on this build. A call was active after installation, so further
device/audio changes were left for a later test.
