# Controlled AAC-ELD startup opt-in

This standalone instrumentation targets the normal `me.kavishdevar.librepods`
debug app. Sign it with the **same existing debug key** as that app. It changes
only `aac_eld_hook` through the existing `XposedServiceHolder.service` remote
preference API, using synchronous `SharedPreferences.Editor.commit()`; it does not edit
preference files or add a receiver/activity to the production app.

Build offline from PowerShell, using a new output directory:

```powershell
./build.ps1 -OutputDirectory C:/path/to/new-output -AndroidSdk $env:ANDROID_HOME -JavaHome $env:JAVA_HOME -DebugKeystore "$env:USERPROFILE/.android/debug.keystore"
```

The build runs pure argument/restore checks, compiles Java, creates dex and signs
the instrumentation APK. It never generates a key, installs or runs anything.
The device owner must compare the tool and installed target signing certificates
before using it. Installing/running it is a separate controlled operation.

After installation by the device owner, these are **device-shell** commands:

```text
am instrument -w -e action read me.kavishdevar.librepods.eldcontrol/.EldPrototypeControl
am instrument -w -e action enable -e expectedBefore false me.kavishdevar.librepods.eldcontrol/.EldPrototypeControl
am instrument -w -e action restore -e enabled false -e expectedBefore true me.kavishdevar.librepods.eldcontrol/.EldPrototypeControl
```

Read first. Use the actual reported value for `expectedBefore`; a mismatch rejects
the write. The result reports before/after, whether a write was attempted, and
the exact inverse restore command. `disable` also requires `expectedBefore`.
Readback confirms only this preference, not Bluetooth-process readiness or ELD.
Use a separate fresh instrumentation read to establish persistence; the report
includes commit success instead of relying on an in-process `apply()` cache.

The instrumentation thread waits up to five seconds for the existing Xposed
preference service and verifies the write within that deadline. It does not
block the main thread. The tool makes no Bluetooth/audio/profile calls and does
not restart Bluetooth. Starting instrumentation starts the target app process
with its normal application startup behavior; the device owner must account for
that before execution. Opt-in is read at Bluetooth-process startup, so changing
it does not relabel or unhook codec objects already in that process.
