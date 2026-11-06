# LibrePods for Android

AirPods controls with an Apple-style interface, native AAC-ELD/PCM32 on supported
rooted phones, and Pico dongle integration. This is the
[Ripthulhu fork](https://github.com/Ripthulhu/librepods) of
[LibrePods](https://github.com/librepods-org/librepods).

## Download

**[Download Android APK](https://github.com/Ripthulhu/librepods/releases/latest/download/LibrePods.apk)**

[Current release: notes, checksums and encoder source](https://github.com/Ripthulhu/librepods/releases/latest)

Requires Android 13 or later. Install over your existing fork build to keep its
settings. The APK uses this repository's established signing key.

[Installation and device compatibility](android/README.md#installation)

## Connect your AirPods

| Connection | Setup |
| --- | --- |
| Pico USB or Bluetooth dongle | The recommended route. LibrePods controls a compatible dongle through the app's device settings. See the [dongle guide](android/DONGLE.md). |
| Phone Bluetooth | Connect directly to Android. Root access can replace Shizuku, and supported rooted system builds can use native AAC-ELD/PCM32. See [Bluetooth access](android/README.md#bluetooth-access-with-root-or-shizuku). |

Listening modes, battery status, ear detection and supported AirPods settings
share the same interface. Feature availability depends on the AirPods model,
firmware and selected connection.

For NTPods on Windows, follow the [phone–PC connection guide](android/README.md#link-ntpods-on-a-pc).

## Development

- [Build, signing and CI instructions](android/README.md#development-builds)
- [GitHub Actions and check reports](https://github.com/Ripthulhu/librepods/actions/workflows/ci-android.yml)
- [Fork history and known limitations](android/PIXEL-REWRITE.md)
- [Android protocol documentation](android/PROTOCOL.md)

Normal Android builds compile the full AAC-ELD/PCM32 encoder from pinned source.
Debug and Release use the same optimized native codec and the existing repository
signing key for trusted builds. APK notices, encoder sources and checksums travel
with the release.

## Credits and licenses

Copyright (C) 2025 LibrePods contributors. LibrePods is licensed under
[GPL-3.0-or-later](LICENSE); existing third-party licenses also apply. See the
[encoder license boundary](android/tools/native-owned-fdk-tests/README.md#build-and-license-boundary).

The [earlier README and upstream background](docs/UPSTREAM.md) preserve the
original project information, supporter and contributor acknowledgments, Linux
information, license text and trademark notices. This fork is independent of
the upstream maintainers. AirPods and Apple trademarks belong to Apple Inc.;
the project is not affiliated with or endorsed by Apple.
