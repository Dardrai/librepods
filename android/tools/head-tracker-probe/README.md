# Android head tracker UHID probe

An isolated root command for testing whether the phone's existing dynamic sensor
HAL discovers a standard Android HID head tracker. No APK, module, app integration,
Bluetooth connection, dongle, settings change, SELinux change, mount or reboot.
Running the build accesses no phone. Invoking the binary without the exact
`--probe AUDIO_IDENTITY_MAC SECONDS [--synthetic-yaw]` arguments prints usage and exits.

The explicit live command creates **one synthetic head tracker** for
1–60 seconds. It can become associated with the audio device having the supplied
Bluetooth identity address. Pause media before a discovery test: the audio system
may consume its stationary pose after association. This is a sensor-discovery
experiment, not an AirPods motion implementation.

## Contract and sources

- [Android HID head tracker protocol](https://source.android.com/docs/core/interaction/sensors/head-tracker-hid-protocol):
  Sensors page `0x20`, Custom usage `0xe1`; description `#AndroidHeadTracker#1.0`;
  16-byte persistent ID = eight zero bytes, ASCII `BT`, six MAC bytes in displayed
  address order. The address must be the audio device's identity address.
- [AOSP dynamic sensor configuration](https://android.googlesource.com/platform/hardware/libhardware/+/refs/heads/main/modules/sensors/dynamic_sensor/README.md):
  requires HIDRAW, the dynamic sub-HAL, device-node permissions and SELinux access.
- [AOSP HIDRAW discovery](https://android.googlesource.com/platform/hardware/libhardware/+/refs/heads/main/modules/sensors/dynamic_sensor/HidRawSensorDaemon.cpp):
  watches `/dev/hidraw[0-9]+`.
- [AOSP HID feature I/O](https://android.googlesource.com/platform/hardware/libhardware/+/refs/heads/main/modules/sensors/dynamic_sensor/HidRawDevice.cpp):
  GET/SET buffers include their report ID byte.
- [AOSP head tracker recognition](https://android.googlesource.com/platform/hardware/libhardware/+/refs/heads/main/modules/sensors/dynamic_sensor/HidRawSensor.cpp):
  recognizes the description, uses the persistent ID as the sensor UUID, and
  translates the three custom input usages into head-tracker data.
- [Linux UHID lifetime and feature replies](https://www.kernel.org/doc/html/latest/hid/uhid.html):
  one file descriptor owns one device; `UHID_DESTROY` or descriptor closure removes it.

The descriptor follows Android's v1 appendix layout. Feature report 2 is 40 bytes
including ID: 23 ASCII description bytes plus 16 identity bytes. Feature report 1
is two bytes including ID: reporting in bit 0, power in bit 1, interval in bits
2–7. The interval maps logical 0–63 linearly onto 10–100 ms, supporting 100 Hz and
50 Hz. Input report 1 is 14 bytes including ID: three signed little-endian 16-bit
rotation-vector values scaled to ±pi radians, three velocity values scaled to
±32 radians/second, and an eight-bit reference-reset counter.

Stationary is the default: all input values are zero. The optional
`--synthetic-yaw` mode emits `rz=0.1*sin(pi*t/2)` radians and its matching derivative
`vz=0.1*pi/2*cos(pi*t/2)` radians/second, a four-second cycle with about5.73degree
peak rotation and0.15708rad/s peak speed. Only Z rotation/velocity are nonzero;
the discontinuity counter stays zero because the reference frame does not reset.
Values are rounded to the descriptor's signed16-bit physical scaling. Phase starts
at the first host-enabled input and follows monotonic elapsed time across pauses.
Inputs remain gated by the same GET/SET, START and OPEN requirements in both modes.

The head axes required for a future real
adapter are X left ear to right, Y back to nose, Z neck to top of head; orientation
transforms reference to head and velocity is expressed in the head frame.
No AirPods Pro 3 scale, axis, quaternion layout, clock, or calibration is assumed.
Synthetic discovery and real-motion calibration must pass separately.

## Offline build

PowerShell 7 on Windows, an installed Android NDK, and local WSL `g++` are required.
No dependency downloads. Use a new output directory outside this source folder.

```powershell
./build.ps1 -AndroidNdk 'C:/Users/dylan/AppData/Local/Android/Sdk/ndk/28.2.13676358' `
    -OutputDirectory '<new private output directory>'
```

The build runs a host assert check, then builds one ARM64/API33 native executable
with only system shared-library dependencies. `receipt.json` records source and
binary hashes. The host check validates malformed MAC/features, the exact BT ID
layout, all 256 power/reporting/interval states, bounded timing, stationary bytes,
and report bit lengths parsed from the actual descriptor. Yaw checks scan60s at
100Hz, validate little-endian fields and position/rate scaling to half an LSB,
bound peaks, preserve zero axes/counter, and reject nonfinite/out-of-range times.
It does not run UHID or
prove that a device HAL accepts the descriptor.

## Bounded live test (separate explicit invocation)

Use only the verified intended device serial, root context and actual audio
identity address. Copy the executable to a unique temporary path, grant execute
permission, then run it in a foreground process owned by the test runner:

```text
<temporary executable path> --probe <audio Bluetooth identity MAC> 30
# Explicit synthetic motion, only for a separately controlled silent-rendering test:
<temporary executable path> --probe <audio Bluetooth identity MAC> 30 --synthetic-yaw
```

The helper initially sends no pose. It responds to feature requests, and sends
the selected synthetic input only after all of: numbered feature/input reports confirmed by
`UHID_START`, `UHID_OPEN`, identity `GET_REPORT(2)`, and a valid feature
`SET_REPORT(1)` enabling full power and all events. It follows the selected
interval, never sends catch-up bursts, and stops inputs when controls or open
state disable them. Unsupported feature requests receive an error reply.

The runner must retain the process ID and a bounded timeout. SIGINT, SIGTERM,
SIGHUP and normal deadline exit send `UHID_DESTROY`, then close the descriptor.
Even an unhandled termination closes the owning descriptor and removes the
device. No background service or persistent state is installed. The runner owns
removal of its copied executable and logs after preserving evidence.

Take `dumpsys sensorservice` and audio/spatializer baselines before the run.
During the run, independently check the new `/dev/hidraw*` label/owner,
`android.sensor.head_tracker(37)` with this probe's name/UUID, and the spatializer's
head-sensor handle/association. Capture relevant bounded dynamic-HAL logs and
SELinux denials on failure; do not relax policy. After exit, verify the probe's
HIDRAW node and sensor disappear and the prior head-sensor state is restored.
Successful exit only means helper completion; feature requests and emitted input
counts do not establish framework discovery or audio association.

## Read-only Pixel observation, 2026-11-14

Explicit endpoint `192.168.50.196:5555` reported serial `58151FDCQ0057X`, model
Pixel 10 Pro XL, Android 17, SELinux Enforcing, and root `u:r:ksu:s0` context.
`/dev/uhid` exists with label `uhid_device`, owner/group `uhid`, mode 0660.
`/sys/class/hidraw` exists and was empty; no `/dev/hidraw*` existed.
`/vendor/etc/sensors/hals.conf` lists `sensors.dynamic_sensor_hal.so`, and the
library is mapped in the running `android.hardware.sensors-service.multihal`
process under `hal_sensors_default`, UID/GID system. `/system/etc/ueventd.rc`
configures `/dev/hidraw*` as system:system 0660. No relevant denial was present in
the bounded recent log sample. These observations establish prerequisites,
not actual UHID creation, HIDRAW labeling, policy permission, sensor discovery,
sample delivery, or audio association; each still requires the live test.
