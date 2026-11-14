# Read-only spatial audio and head tracker inspector

Standalone unsigned DEX JAR for `app_process`. It installs no APK, opens no audio
stream, enables no sensors and invokes no audio-service setter. It changes no
settings, policy, UID or hidden-API exemptions. Only the explicitly supplied A2DP
identity address and this experiment's head tracker are included in JSON output.
The v5 native AAudio probe remains separate and unchanged.

## Offline build

PowerShell7, an installed JDK and Android SDK; no downloads or signing keys.

```powershell
./build.ps1 -AndroidSdk 'C:/Users/dylan/AppData/Local/Android/Sdk' `
    -JavaHome 'C:/Program Files/Android/Android Studio/jbr' `
    -OutputDirectory '<new private output directory>'
```

The host check validates strict MAC parsing, expected Bluetooth sensor UUID,
Android sensor-visibility UID rules, exact reflection return types, and rejection
of setters by the audio-method allowlist. It invokes no fake getter and needs no
Android runtime. The remaining build compiles against SDK37, produces API33 DEX,
and packages only `classes.dex` in `spatial-inspector.jar`. `receipt.json` records
artifact and source hashes. Build success does not establish runtime availability
or permissions for any private Android API.

## Explicit runtime invocation, owned by the root runner

Copy the JAR to a unique temporary phone path only when the runner is ready.
Use an explicit verified phone serial and an outer phone-side timeout; Binder
getters do not provide per-call public timeouts. For example, the command executed
under the chosen UID has this form:

```text
CLASSPATH=<temporary JAR path> app_process / me.kavishdevar.librepods.spatialinspector.SpatialInspector <A2DP identity MAC>
```

The process reports its actual UID. It prepares a Looper if necessary, reflects
`ActivityThread.systemMain()` and its system context, then obtains the audio
Binder through `ServiceManager.getService("audio")` and
`IAudioService.Stub.asInterface(IBinder)`. System context does not change UID.
Its application UID/package are reported so identity mismatches are visible.
The process prints one JSON object and exits without entering a Looper loop.

Audio-service reflection uses the public interface's methods, exact parameter
types and exact erased return types; it never invokes proxy-class internals.
Each getter includes expected and discovered signatures, signature validation,
invocation status, and either a typed result or an exception class. Failed reads
do not become false Boolean values. Error messages/stacks and returned lists are
not dumped, avoiding unrelated peer addresses.

All getters used by `SpatialAudioAccess.Framework.read` are inspected, including
spatializer support/enabled/available, selected routing and compatible membership,
per-device availability/head-tracker flags, actual mode, and float48k5.1 support.
Additional read-only diagnostics include desired/supported tracking modes,
spatializer output and audio mode. Both MUSIC (bridge parity) and MOVIE (native
probe parity) attributes are queried. Returned audio-device lists are filtered
to the supplied A2DP address; AudioManager output IDs are likewise selected only.
No Bluetooth pairing/profile operations or bonded-peer enumeration occur.

The dynamic sensor query requests only type37. Sensor UUID, ID and handle method
signatures are checked by reflection. It prints records only for a matching
selected UUID or the exact synthetic probe name, with reporting-delay bounds.
No listener or dynamic-sensor callback is registered by this tool; initializing
SensorManager may create its normal process-local discovery receiver.

## UID0 sensor visibility is restricted

Android17 SensorService restricts head-tracker enumeration and original UUIDs to
system appId1000 or audioserver1041. UID0 is not exempt. Thus a root invocation
may see an empty dynamic list even while sensorservice dumps show the probe.
The JSON marks this limitation, and an unprivileged UUID mismatch is null rather
than false. Empty results never establish absence. A separate root-runner-owned
invocation under a verified permitted UID can inspect original UUIDs; the tool
never changes its own UID or relaxes the restriction.

Expected selected UUID is `new UUID(0, 0x4254000000000000L | MAC48)`. Equality is
reported only under a sensor-privileged UID. This tests framework association
metadata, not real AirPods pose calibration or actual sample delivery. Context
attribution mismatch and permission failures remain explicit typed read failures.

Primary sources:

- [IAudioService spatializer API](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/IAudioService.aidl#619)
- [Sensor UUID/ID API](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/Sensor.java#1168)
- [Android17 SensorService UID restriction](https://android.googlesource.com/platform/frameworks/native/+/refs/tags/android-security-17.0.0_r3/services/sensorservice/SensorService.h)
- [Sensor enumeration and UUID anonymization](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/services/sensorservice/SensorService.cpp)
- [Bluetooth head-tracker UUID construction](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/audio/UuidUtils.java)
