# Native ELD regression checks

Run with a host C++ toolchain, without a phone or Bluetooth connection:

```sh
cmake -S android/tools/native-eld-tests -B /tmp/librepods-eld-tests -DCMAKE_BUILD_TYPE=Debug
cmake --build /tmp/librepods-eld-tests --config Debug
ctest --test-dir /tmp/librepods-eld-tests -C Debug --output-on-failure
```

The seven executables cover wire negotiation, PCM/packet ownership, codec-object
lifetime and quarantine, native peer/stream observations, software-HAL admission,
hook transaction and context rejection, and the pinned FDK ABI. Synthetic samples and mock callbacks
are used throughout. These checks do not establish real Bluetooth playback,
Android audio-policy compatibility or acoustic latency.
