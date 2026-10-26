# Native Bluetooth identity for Android 17

This KernelSU/Magisk module uses Android's supported Device ID properties.
It sets only `bluetooth.device_id.vendor_id=76` and
`bluetooth.device_id.vendor_id_source=1`. Android registers the SDP record.
Product, version, controller identity, codec settings and firmware are untouched.
There are no Bluetooth binary offsets or whole-image hash gates.

Install the ZIP in the root manager, then use LibrePods' existing **Act as an
Apple device** switch. Its initial opt-in is off. Grant root access to LibrePods
when explicitly changing the switch. A Bluetooth restart is needed to register
the new SDP identity; the app does not restart Bluetooth automatically.
Older Android versions retain the Xposed backend.

The helper stores the original two properties, including whether each was
absent. It records a fresh baseline before applying an enabled profile on each
boot, so disabling after an OTA restores that boot's defaults. It verifies writes
and restores interrupted operations from an owned journal. If another
configuration changes the active values, disabling clears boot intent without
overwriting that configuration. Only a successful native ATT read authorizes
settings writes; the identity switch does not substitute for device readback.

The app invokes only fixed helper actions. Its open stdin is a lease: cancellation
or process loss closes the pipe and triggers rollback. Kernel-file locking
releases on process exit; pending journals are recovered by the next invocation.
The root-only state tree rejects symlinks, unexpected entries and unsafe owners
or modes. Originally absent properties require the root manager's `resetprop`
utility for exact restoration.

Build and test on a Linux host:

```sh
python3 test_identity.py
python3 build.py /path/to/librepods-bluetooth-identity.zip
```

The offline harness replaces the Android property commands and constant state
paths. It covers restoration, absent keys, misleading property values, partial
writes, cancellation, interrupted journals, changed boot defaults, external
ownership and symlink rejection. Actual SDP and ATT behavior still require a
capture on the target phone.
