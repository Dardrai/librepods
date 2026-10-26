# Controlled Pixel codec route probe

Temporary instrumentation targeting the existing LibrePods app, like
`eld-prototype-control`. It supports Pixel 7 Pro / SDK37 only. It never connects
the AirPods or acquires audio, playback or microphone access.

Unlock the owner user before starting instrumentation. It launches the target's
normal application first; before credential unlock, that application's settings
migration cannot access its encrypted preferences and may fail before the probe runs.

Build offline, outside this source directory, using the installed SDK/JDK:

```powershell
./build.ps1 -OutputDirectory C:/private/eld-route-build -AndroidSdk C:/path/to/Android/Sdk -JavaHome C:/path/to/jdk
```

The script runs the pure-Java regression checks and produces an **unsigned** APK
and source/hash receipt. Use the existing target-app signing process to sign it
with the same certificate as the installed LibrePods. It creates no keys and
does not install or run anything.

Use an explicit already-bonded pair address for every command. Read-only status
is the default and includes native codec inventory, current codec and selectable
extended carriers. Pass `--no-hidden-api-checks` on each instrumentation command;
never change the phone's global hidden-API settings.

```text
am instrument --no-hidden-api-checks -w -e address AA:BB:CC:DD:EE:FF me.kavishdevar.librepods.eldroute/.EldRouteProbe
am instrument --no-hidden-api-checks -w -e address AA:BB:CC:DD:EE:FF -e action selectEld -e expectedBeforeCodecId 2 me.kavishdevar.librepods.eldroute/.EldRouteProbe
am instrument --no-hidden-api-checks -w -e address AA:BB:CC:DD:EE:FF -e action selectAac -e expectedBeforeCodecId 0x8001004cff me.kavishdevar.librepods.eldroute/.EldRouteProbe
```

Only the explicit write actions change codec preference. They require a fresh
matching `expectedBeforeCodecId` and select a carrier already present in the
pair's selectable list, with its exact ID/index also in the native inventory.
The copy preserves its extended type object and all nine fields except priority,
which is set to 1,000,000. IDs and codec-specific 64-bit fields are exported as
strings. Actual post-request ID and native index must match before success is
reported; this does not prove the wire codec or audible playback.

The Android preference API has no atomic compare-and-set. The probe rechecks
immediately before writing, sends at most one request, and never retries a
preference write or changes another setting on failure. A failed readback may
still mean the request took effect: read status again before another action.

The proxy wait and optional readback wait each have a five-second deadline.
Synchronous Binder calls still need a **25-second external controller timeout**;
the instrumentation runs inside its target, LibrePods. On timeout, with the
owner application already paused, stop the target **and** the helper:

```text
am force-stop me.kavishdevar.librepods
am force-stop me.kavishdevar.librepods.eldroute
```

Stopping the helper package alone may leave the target instrumentation process
running. Do not stop the Bluetooth system package. On ordinary completion,
`finally` closes the owned A2DP proxy and drops temporary
BLUETOOTH_CONNECT/BLUETOOTH_PRIVILEGED shell identity; late callbacks close their
own handle. A forced process kill cannot guarantee that `finally` ran. Android
releases the instrumentation's UiAutomation permission adoption on its death,
but report cleanup receipts and the operation's outcome as **unknown**, not
complete. A preference request may already have taken effect; use a fresh
read-only status check before any subsequent write.

The result names the private JSON report in the target app's files directory
and includes cleanup receipts when available. No permanent privileged grant is used.
