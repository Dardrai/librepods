# Pixel Dolby spatial control

This ARM64 JNI client implements the app-neutral route and spatial controls
for the separate Pixel Dolby decoder. The regular LibrePods APK builds and
packages it from source. It loads only in the app's root helper on a
supported Android version.

CMake uses Android SDK Build-Tools 36.0.0 to generate NDK bindings from the
frozen AIDL below. It checks the four frozen package hashes before compiling
and checks the remote Codec2 store's version and hash at runtime.

The AIDL and Binder support files retain their AOSP notices and are licensed
under Apache 2.0. The source revisions are

- Hardware interfaces `0162af698935100a590b7359581ac8b1b80693e5` for Codec2 V2, BufferPool2 V2, Common V2, and FMQ V1.
- Framework native `ae266dcb706d083868578cfedce381ef44488a07` and framework base `94b4c163b7dfe5ce3607f7bb8456f9573f7de57d` for the framework parcelables and Binder headers.
- AIDL `9a94664f74536bf28010207fd1804b04c6435e2c` for supporting headers.
