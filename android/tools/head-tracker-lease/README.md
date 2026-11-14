# Temporary selected-device head tracker enablement

An isolated root runner for the synthetic-sensor + silent5.1 experiment. It calls
only one mutating audio-service method: `setHeadTrackerEnabled(boolean, selected
AudioDeviceAttributes)`. It never writes global spatializer/tracking settings,
device spatial compatibility, volume, codec/profile, sensor policy or other peers.
The read-only inspector and v5 AAudio probe remain unchanged.

## Offline build

PowerShell7, JDK, SDK37/build-tools36, installed NDK and WSLgcc; no downloads/APK/key.

```powershell
./build.ps1 -AndroidSdk 'C:/Users/dylan/AppData/Local/Android/Sdk' `
    -AndroidNdk 'C:/Users/dylan/AppData/Local/Android/Sdk/ndk/28.2.13676358' `
    -JavaHome 'C:/Program Files/Android/Android Studio/jbr' `
    -OutputDirectory '<new private output directory>'
```

Host checks cover denied global/route/device preconditions, changed state before
write, lost Binder reply after mutation, failed readback, idempotent restoration,
already-enabled state and preserving an externally changed spatial flag. A native
host check raises SIGTERM/SIGINT/SIGHUP and verifies stop flags. Android output is
an unsigned API33 DEX JAR and an ARM64 signal library; receipts hash both and all
sources. No device command is part of the build.

## Explicit root invocation

The runner must use an explicit verified phone and fresh temporary paths.
Only UID0 is accepted. Paths must resolve under `/data/local/tmp/`.

```text
CLASSPATH=<jar> app_process / me.kavishdevar.librepods.headtrackerlease.HeadTrackerLease --hold <A2DP identity MAC> <seconds1..25> <new snapshot path> <absolute libheadtrackerlease.so path>
```

Before enabling, it validates exact reflection signatures, a single selected
MEDIA/MOVIE native A2DP route, normal audio mode, per-device compatibility,
global spatial enablement and selected-device spatial enablement. It captures
the original head-tracker and spatial flags into a new root-owned0600 snapshot,
flushes/fsyncs it before mutation, and logs `captured_before_write`. Existing
snapshot files are never overwritten. It revalidates the captured state before
writing, enables head tracking only when originally false, then checks readback.

The hold has a1–25second monotonic deadline including capture/validation. It
checks the selected route and flags every250ms; cancellation or changed state
ends the hold. It neither starts sensor samples nor creates audio; run the UHID
helper and silent AAudio probe separately under the root runner's control.

The small native library makes SIGTERM/SIGINT/SIGHUP set a flag, allowing the Java
loop to exit through `finally`; shutdown hooks also attempt restoration. The
library is loaded before mutation. Original false head-tracking state is restored
with readback even if a Binder write applied before its reply failed. Restoration
is idempotent. The spatial flag is checked but never written; concurrent external
changes are reported rather than overwritten. Originally true head tracking
requires no write and is not overridden if another actor subsequently changes it.

## Mandatory independent cleanup fallback

SIGKILL, process crashes and blocked Binder calls can prevent `finally` or hooks.
The root runner must retain the snapshot, own an outer phone-side timeout and
force-kill escalation, and **always** execute this separate command after the
hold process is confirmed stopped, even following a nominal successful exit:

```text
CLASSPATH=<jar> app_process / me.kavishdevar.librepods.headtrackerlease.HeadTrackerLease --restore <same A2DP identity MAC> <snapshot path>
```

Restore mode does not load the native library. It validates schema/kind/address,
regular-file type, root ownership, private permissions and a4096-byte size bound.
It can restore the captured head-tracker flag even if the selected device is no
longer the active route. It never adds/removes spatial-compatible devices. Keep
the snapshot until an independent inspector verifies both original flags, then
the root runner can remove temporary artifacts.

For the requested≤40s experiment, use at most25s hold, a35s phone-side outer guard,
a2s kill escalation, and reserve the remaining time for an independently bounded
restore/verification attempt. A host-only ADB timeout does not kill the remote
process. Native/Binder calls offer no guaranteed completion bound; if the audio
service itself stalls, report cleanup unverified and retain the snapshot rather
than claiming successful restoration. No policy relaxation or restart is required
or performed by this tool.

Exact setter/read signatures match the separately validated
[AOSP IAudioService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/IAudioService.aidl#625).
