For this fork's Pixel/Shizuku build, upgrade notes and known limitations, see
[Pixel fork on the Android rewrite](PIXEL-REWRITE.md).

This fork uses the upstream Android rewrite's screens and per-device settings.
Apple is the default design; Material remains available in the app settings.

## Root Requirement

- The APK requires Android 13 or later. Rootless control also needs the updated
  Android Bluetooth stack. The app's compatibility check accepts:
  - Devices running Android 17+
  - Oppo/OnePlus/Realme devices running Android 16
  - Google Pixel devices running Android 16 with a build ID beginning `CP1A`
    **and the Google Play system update**
- This fork has been tested on a Pixel 10 Pro XL. Other accepted configurations
  depend on their Bluetooth implementation; they have not all been tested here.
- On older Bluetooth stacks, LibrePods needs root with an Xposed framework to
  work around the Fluoride/AirPods connection issue. Shizuku does not replace
  that workaround. [This issue was tracked here](https://issuetracker.google.com/issues/371713238).
  **Please do not comment on the issue thread.**
- The existing VendorID hook still needs root/Xposed. This fork's ATT reader and
  writer are unavailable, so protected settings and hearing-assistance
  enablement remain disabled. See [Android ATT access](PROTOCOL.md#android-att-access).

> [!IMPORTANT]
> This workaround with Xposed is not guaranteed to work on all devices.

## Installation

### GitHub Releases

This fork is distributed only through [GitHub releases](https://github.com/Ripthulhu/librepods/releases).
Install the signed release APK. Existing fork installations can be updated in
place; keep the app installed to preserve its settings and Shizuku authorization.
An APK signed with a different certificate cannot update an existing installation.

### Pixel/Shizuku setup

1. Install the signed APK and open LibrePods. Grant Nearby devices and allow
   notifications when requested. Allow display over other apps if you
   want nearby pairing cards and the Dynamic Island.
2. Start Shizuku using wireless debugging. Shizuku may need to be started again
   after a phone restart.
3. For a new pair, open the case near the phone and follow the pairing card's
   instructions until the case light flashes white, then tap **Connect**.
   Authorized Shizuku can confirm the selected pair's consent request; Android's
   normal confirmation remains the fallback.
4. In your AirPods' Microphone page or Recorder, tap **Connect Shizuku** and
   authorize LibrePods. This also enables Shizuku for later pairing attempts.

Ordinary Bluetooth music playback stays on Android. Shizuku is used for the
nearby island's explicit Connect action, pairing consent, background volume
access/recovery and optional experimental microphone routing. Opening the case
alone does not take audio from another host.

### As a system app (root module)

The upstream root module provides privileged Bluetooth permissions to

- show battery status in the system settings and widgets
- show AirPods icon in the system settings (xposed is also currently required for this)
- switch audio to phone speakers when you are not wearing your AirPods

This fork does not build or distribute a root module. These optional system-app
features are not required for the rootless Pixel/Shizuku setup above.

### A few notes on this rewrite

- Listening modes, Adaptive strength, Allow Off, ear detection, battery status,
  Conversation Awareness, nearby pairing, the Dynamic Island, Quick Settings and
  widgets use the rewrite's per-device state. Features vary by model and firmware.
- High-quality microphone recording and Shizuku app/cellular-call routing are
  experimental. Older-fork hardware tests do not validate this rewrite. Sensors,
  head gestures and call audio have not been retested on hardware here.
- Normal-quality media during cellular calls is still unresolved. Stereo AAC-ELD
  playback is blocked because the encoder does not produce the required
  480-sample configuration.
- Hearing aid fitting and enablement, custom transparency and loud sound
  reduction are unavailable while the ATT transport is disabled, even with
  VendorID spoofing or an existing iPhone fitting.

## Development builds

The [Android CI workflow](https://github.com/Ripthulhu/librepods/actions/workflows/ci-android.yml)
checks Android changes on pushes and pull requests. Successful runs provide an
`apk-debug` artifact containing `app-debug.apk`; these use a development
certificate and are not upgrades for the signed release APK. Installable updates
come from the signed APK attached to a GitHub release.

From `android/`, run `./gradlew :app:verifyPixel` with JDK 21 and the SDK/NDK
versions declared in the project. This runs core and app unit tests, Android
lint, and debug and minified release APK builds. There is one Android
distribution, with no Play flavors or app-bundle release flow.

Run `./gradlew :app:collectReleaseArtifacts` to collect the APKs in `release/`
at the repository root. Release signing uses the existing `RELEASE_STORE_FILE`,
`RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` entries
in an untracked `android/local.properties`. Without those entries, the release
APK is unsigned. Keep signing material out of the repository.

To publish a signed GitHub prerelease, run the workflow against `android/rewrite`
with its `publish_release` and `release_tag` inputs, or push a `v*` or `pixel-*`
tag. Publishing requires the fork's `RELEASE_KEYSTORE_FILE` (base64 keystore), `RELEASE_STORE_PASSWORD`,
`RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` secrets. Signing happens only in
the release path, after tests and builds pass.

## Screenshots

These are upstream screenshots and a demo. This fork uses the Android rewrite's
UI; the hearing/transparency screens below do not indicate working ATT access.

|                                                                                 |                                            |                                                                      |
| ------------------------------------------------------------------------------- | ------------------------------------------ | -------------------------------------------------------------------- |
| ![Settings 1](./imgs/settings-1.png)                                            | ![Settings 2](./imgs/settings-2.png)       | ![Head Tracking and Gestures](./imgs/head-tracking-and-gestures.png) |
| ![Long Press Configuration](./imgs/long-press.png)                              | ![Customizations 1](./imgs/customizations-1.png)                                | ![accessibility](./imgs/accessibility.png) |
| ![transparency](./imgs/transparency.png)                                        | ![hearing-aid](./imgs/hearing-aid.png)     | ![hearing-test](./imgs/hearing-test.png)   |
| ![hearing-aid-adjustments](./imgs/hearing-aid-adjustments.png)                  | ![Battery Notification and QS Tile for NC Mode](./imgs/notification-and-qs.png) | ![Widget](./imgs/widget.png)               |


here's a very unprofessional demo video

https://github.com/user-attachments/assets/43911243-0576-4093-8c55-89c1db5ea533

### Troubleshooting steps for common errors

- For rootless use, check your Android/Google Play system updates and restart
  Shizuku if it has stopped. Confirm that LibrePods is authorized in Shizuku.
- The following steps apply only to the optional root/Xposed setup:
- Ensure the correct scope is set in LSPosed/Vector.
- Ensure there is no root-hiding module preventing the hook from loading on the Bluetooth app.
- Restart your phone after confirming the scope.

### A few notes

- Due to recent AirPods' firmware upgrades, you must enable `Off listening mode` to switch to `Off`. This is because in this mode, loud sounds are not reduced.

- When renaming your AirPods through the app, you'll need to re-pair them with your phone for the name change to take effect. This is a limitation of how Bluetooth device naming works on Android.
