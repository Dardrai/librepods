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

The current fork version is `2.0.0-alpha.1-pixel13`, code `200000098`.

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
- Saved, still-bonded Apple identities load even when Android's service UUID
  cache is empty, so nearby ownership does not wait for Classic discovery.
  Android 17 uses passive scanning to receive advertisements without waiting
  for a scan response. Older versions retain their existing scan type.
- Incomplete key responses retain the previous complete IRK/encryption pair;
  a valid replacement changes both together. Cache, metadata and settings
  updates are transactional so concurrent saves cannot overwrite each other.
- The island attaches immediately as a visible camera-sized capsule and uses
  the existing spring to open. Remove Animations starts at the requested size.
  It wraps the actual camera path and limits expanded width to 420 dp with
  screen margins. Passive battery updates retain the owner and dismissal time;
  a canceled exit cannot remove a reopened or replacement overlay.
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

`verifyPixel` passed with 135 tests in 31 suites, no failures, errors or skips,
Android lint with no errors, and successful debug/minified release builds.
Native pairing-card captures cover light mode and dark mode with narrow width
and larger text. The release was signed with the existing fork certificate and
installed in place on the Pixel as Pixel13. Android's scan state confirms
unbatched passive low-latency monitoring. The user confirmed the island now
appears promptly on Pixel12. Pixel13 restores its camera-origin opening
animation, with regressions for visible initial geometry, reduced motion and
interrupted exits. Physical animation confirmation is pending.

The first physical Pixel11 check still took roughly ten seconds after opening
the case, despite 32 ms from Android's scan timestamp to the overlay request and
58 ms to its first drawing pass. Closing the lid dismissed promptly. Pixel12
changes scanning and cached-identity admission. Its owned lid reports reached
the overlay request in 11–31 ms and first drawing pass in 27–62 ms; those are
radio-to-frame measurements, not physical lid-to-frame measurements. Fresh
pairing, sensors and call audio have not been retested on the rewrite.
