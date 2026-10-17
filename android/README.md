For this fork's Pixel/Shizuku build, upgrade notes and known limitations, see
[Pixel fork on the Android rewrite](PIXEL-REWRITE.md).

## Root Requirement

- Features requiring the VendorID hook ([the features marked with an asterisk here](https://github.com/kavishdevar/librepods#key-features)) will always require root regardless of your device/OS.
- You can use LibrePods without root on following device configurations:
  - All devices running Android 17+
  - Oppo/OnePlus/Realme devices running Android 16
  - Google Pixel devices running Android 16 March update or later **and the Google Play system update**
- On other devices, LibrePods needs root because of a bug in the Android Bluetooth stack Fluoride/non-compliance of Apple with Bluetooth standards. You must have an Xposed framework installed for the app to work around this and connect to AirPods. [This issue was being tracked here](https://issuetracker.google.com/issues/371713238). **Please do not comment on the issue thread.** The issue has already been resolved and should be available in **Android 17** for all devices.

> [!IMPORTANT]
> This workaround with Xposed is not guaranteed to work on all devices.

## Installation

### GitHub Releases

This fork is distributed only through [GitHub releases](https://github.com/Ripthulhu/librepods/releases).
Install the signed release APK. Existing fork installations can be updated in
place; keep the app installed to preserve its settings and Shizuku authorization.
An APK signed with a different certificate cannot update an existing installation.

### As a system app (root module)

If you want LibrePods to have privileged Bluetooth permissions to 
- show battery status in the system settings and widgets
- show AirPods icon in the system settings (xposed is also currently required for this)
- switch audio to phone speakers when you are not wearing your AirPods

you can install the root module. This is optional and only provides extra features, but it is not required for the app to work.

## Development builds

The [Android CI workflow](https://github.com/Ripthulhu/librepods/actions/workflows/ci-android.yml)
checks Android changes on pushes and pull requests. Successful runs provide an
`apk-debug` artifact; these use a development certificate and are not upgrades
for the signed release APK.

From `android/`, run `./gradlew :app:verifyPixel` with JDK 21 and the SDK/NDK
versions declared in the project. This runs core and app unit tests, Android
lint, and debug and minified release APK builds. There is one Android
distribution, with no Play flavors or app-bundle release flow.

Run `./gradlew :app:collectReleaseArtifacts` to collect the APKs in `release/`
at the repository root. Release signing uses the existing `RELEASE_STORE_FILE`,
`RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` entries
in an untracked `android/local.properties`. Without those entries, the release
APK is unsigned. Keep signing material out of the repository.

To publish a signed GitHub prerelease, use the workflow's `publish_release` and
`release_tag` inputs, or push a `v*` or `pixel-*` tag. Publishing requires the
fork's `RELEASE_KEYSTORE_FILE` (base64 keystore), `RELEASE_STORE_PASSWORD`,
`RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` secrets. Signing happens only in
the release path, after tests and builds pass.

## Screenshots

|                                                                                 |                                            |                                                                      |
| ------------------------------------------------------------------------------- | ------------------------------------------ | -------------------------------------------------------------------- |
| ![Settings 1](./imgs/settings-1.png)                                            | ![Settings 2](./imgs/settings-2.png)       | ![Head Tracking and Gestures](./imgs/head-tracking-and-gestures.png) |
| ![Long Press Configuration](./imgs/long-press.png)                              | ![Customizations 1](./imgs/customizations-1.png)                                | ![accessibility](./imgs/accessibility.png) |
| ![transparency](./imgs/transparency.png)                                        | ![hearing-aid](./imgs/hearing-aid.png)     | ![hearing-test](./imgs/hearing-test.png)   |
| ![hearing-aid-adjustments](./imgs/hearing-aid-adjustments.png)                  | ![Battery Notification and QS Tile for NC Mode](./imgs/notification-and-qs.png) | ![Widget](./imgs/widget.png)               |


here's a very unprofessional demo video

https://github.com/user-attachments/assets/43911243-0576-4093-8c55-89c1db5ea533

### Troubleshooting steps for common errors
- Ensure the correct scope is set in LSPosed/Vector.
- Ensure there is no root-hiding module preventing the hook from loading on the Bluetooth app.
- Restart your phone after confirming the scope.

### A few notes

- Due to recent AirPods' firmware upgrades, you must enable `Off listening mode` to switch to `Off`. This is because in this mode, loud sounds are not reduced.

- When renaming your AirPods through the app, you'll need to re-pair them with your phone for the name change to take effect. This is a limitation of how Bluetooth device naming works on Android.
