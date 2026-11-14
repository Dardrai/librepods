# UHID ABI check

Compile-only layout checks for the direct Android UHID codec. This tool opens no
device and changes no settings. It reuses the descriptor in the proven native
head-tracker probe instead of carrying another descriptor copy.

The assertions cover the packed4380-byte event, every CREATE2/GET/SET/START/INPUT2
offset used by the codec, event/report constants and descriptor capacity. Build
once against host Linux headers and once against the Android NDK headers. The
Android object only needs to compile; it does not need to run on a phone.

CREATE2 itself is4372bytes, but the union rounds up to4376bytes because START
contains an aligned64-bit field. The packed outer event then adds its four-byte
type. Descriptor data still starts at offset280; the event has four trailing
padding bytes after the CREATE2 descriptor capacity.

Example commands, run from this directory with outputs outside the source tree:

```text
g++ -std=c++17 -Wall -Wextra -Werror abi_check.cpp -o <output>/uhid-abi-check
<output>/uhid-abi-check
<NDK>/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android33-clang++.cmd -std=c++17 -Wall -Wextra -Werror -c abi_check.cpp -o <output>/uhid-abi-android.o
```

The host executable prints event/descriptor sizes and the descriptor's hexadecimal
bytes, allowing comparison with the Kotlin CREATE2 payload without a hashing
dependency. Successful compilation proves header layout agreement only, not
device permissions, SELinux access, HAL discovery or live pose delivery.

For direct `android.system.Os` use, set little-endian byte order explicitly and
send each UHID event in one write. Never retry a partial write as a second event.
Nonblocking `read`/`write` can throw `ErrnoException` for EAGAIN/EINTR; recompute
monotonic deadlines on retry. A duplicated descriptor shares its file-status
flags, so setting O_NONBLOCK also affects other copies of that open file
description. Validate transferred pose descriptors as readable pipes; O_RDONLY
is zero and must be checked through O_ACCMODE rather than a standalone bit test.

GET/SET requests are synchronous in the kernel and need prompt replies. Do not
run blocking source-owner callbacks while a control reply is pending. Closing
the owning UHID descriptor destroys its device; keep that close on the I/O owner
thread to avoid descriptor-reuse races. A pipe can report POLLIN and POLLHUP
together, so EOF/producer loss must not become replay of buffered stale poses.

References: [Linux UHID API](https://www.kernel.org/doc/html/latest/hid/uhid.html)
and [Android Os API](https://developer.android.com/reference/android/system/Os).

## Isolated root sink contract

`RootHeadTrackerSink` and `HeadTrackerUhidProtocol` are internal app helpers used
by the bounded DEBUG root bridge below. The root caller must supply its existing
caller-authorization check, the selected
address/current owner, a transferred readable pipe and a nonblocking reporting
callback. The helper normalizes the address, requires UID0, verifies a single
selected native A2DP MEDIA/MOVIE route and normal audio mode, checks the live owner
and links its death recipient. It consumes/closes the transferred read endpoint,
including on open failure; callers must not read or reuse that endpoint afterward.

Each pose frame is exactly40 little-endian bytes:

| Offset | Field |
| --- | --- |
| 0 | Four ASCII bytes `HTP1` |
| 4 | Signed32-bit discontinuity counter, valid0–255 |
| 8 | Signed64-bit arrival time from `SystemClock.elapsedRealtimeNanos()` |
| 16 | Three float32 AOSP active world-from-head rotation-vector components, radians |
| 28 | Three float32 head-frame angular-velocity components, radians/second |

Frames contain already calibrated standard Android poses. No earbud decoding,
axis/sign fitting, scale inference, extrapolation or fabricated pose exists here.
The numeric quaternion is active `worldFromHead`, although AOSP names its input
`worldToHead` and the HID document calls it reference-to-head. The pinned native
recenter harness proves that the renderer applies the compensation inverse;
the input must not receive another blanket quaternion/rotation-vector inversion.
Rotation norm must be at most pi; angular-velocity norm is conservatively limited
to32rad/s. All values must be finite and timestamps positive, nonfuture and
strictly increasing. The codec uses the descriptor's signed16-bit physical scale.

Only one latest pose and one incomplete frame are retained. At most32 complete
frames are drained per iteration; a remaining backlog closes the sink. EOF,
malformed data, owner death/retirement, route loss or FD failure also closes it.
UHID replies are served before the source callback; one whole4380-byte event is
written at a time. No spatializer/preference, permission, policy or volume setter
is called.

Host power/reporting controls, numbered START/OPEN and identity GET gate output.
Reporting Off clears cached input, has no freshness deadline and notifies the
caller so it can release only its head-tracker AACP source owner. Keep the pipe
writer open while intentionally idle; closing it ends the sink. Every On
transition discards pre-enable frames and allows up to5s for the first fresh
frame. After that, input must stay within250ms, and a late new frame cannot reopen
an expired stream. The caller receives the host-selected10–100ms reporting interval
and must enqueue acquisition/release work rather than block the UHID worker.

`Session.close()` requests cancellation; `isClosed` becomes true after worker-owned
FD cleanup. The reporting state becomes false before cleanup. Kernel/remote calls
can block, so future wiring must observe completion rather than assume immediate
teardown. The owning process's death also closes/unregisters UHID. This helper
does not enable or restore any device/global preference; that remains an explicit
caller transaction.

Focused tests are `:app:testDebugUnitTest --tests
me.kavishdevar.librepods.shizuku.HeadTrackerUhidProtocolTest`. They cover descriptor
identity, feature replies and all control-byte states, fixed-point conversion,
malformed/range checks, idle behavior, cold-start grace, active freshness and
latest-only/timestamp-replay rules. The hardware loop has not been run on a phone.

## Bounded DEBUG root bridge

AIDL transactions35–37 start, inspect and finish one owner-scoped probe;
`IHeadTrackerEvents` reports host enable/interval changes and the stop reason.
Both client and service reject non-DEBUG starts. Start consumes the pose pipe's
read endpoint and requires existing UID0 access, the app's Bluetooth permission,
a bonded selected native A2DP pair in normal mode, and global/device spatial
audio already enabled. It requests no new permission and changes no route,
volume, codec or global/device spatial preference.

The root worker has a maximum30-second lifetime including its maximum3-second
startup. Before temporarily enabling only the selected head-tracker preference,
it requires successful HAL feature1+2 reads plus selected-address head-tracker
presence. Status calls this `hal_handshake`; `head_tracker_available` and
`actual_head_tracking_mode` are separate readbacks. Handshake alone does not
prove active audio association or correct real-motion calibration.

`RootHeadTrackerProbe` keeps its own captured preference snapshot. Finish,
deadline, owner loss, pipe/sink failure, route loss and root close converge on
conditional restoration without requiring a current owner. It writes the old
head-tracker flag only while the selected device's stored flags still match the
lease's expected values; external device/global changes are preserved. Open and
restore reserve the existing audio mutation gate briefly; the whole lease does
not exclude volume or call admission. A failed restore keeps ownership and can
be retried by finish; delayed sink closure is reflected in `cleanup_complete`.
App ownership callbacks run on one separate poller with a500ms freshness limit;
the sink and preference worker use a local root-side predicate. A blocked app
callback cannot hold sensor removal or preference restoration. Its old lease
keeps the slot until that single poller retires, preventing replacement starts
from accumulating blocked callback threads. System-service Binder calls retain
their platform behavior.

The client lease survives the short start coroutine, retires on opening
cancellation, ignores stale/foreign callbacks and exposes repeatable `state()`
and `finish()` operations. Closing the root process destroys UHID, but a forced
process kill cannot restore an in-memory preference snapshot. No snapshot is
persisted by this bridge.

Focused lifecycle checks are `RootHeadTrackerProbeTest` and the head-tracker
cases in `ShizukuAudioClientLifecycleTest`; the standard codec/ABI checks remain
separate from motion-format/calibration tests.

## Foreground real-motion DEBUG proof

The nonexported `TestActivity` accepts one explicit `spatial_head_tracking_probe`
intent with an exact `expected_mac` and `duration_seconds` from5–30. Both the app
controller and root service require a DEBUG build. A recreated activity, service
reconnection or later resume cannot replay the request. Closing the screen stops
the source and finishes its root lease. Nothing runs from normal app startup.

`SpatialHeadTrackingProbe` reuses the existing `HeadTracking.motion` flow and
owner-aware `AppleDevice.startHeadTracking(owner)`. It requires a connected Pro3,
a known current primary-bud role and an unchanged AACP audio socket. It refuses
calls, recordings or a configured motion interval slower than40ms. The source
owner remains continuous through a guided neutral → nose-UP → neutral calibration
and the bounded proof. Calibration is memory-only and never selected by a model
number or reused for another role/connection. Stable gravity is used only to
validate the calibration holds, never to drive a moving pose.

The converter composes the measured active Q15 orientation with the calibrated
head-to-raw mounting rotation. It derives head-frame velocity from successive
quaternions and the device's timestamp interval; no unverified raw-gyro scale,
prediction, pitch/yaw approximation or stereo upmixer is used. Host arrival time
is retained separately for sink freshness. Startup zero fields, reversed clocks,
counter/reference discontinuities and the unsupported quaternion hemisphere end
the proof rather than emitting a guessed pose.

The proof creates no audio and writes no volume, codec, Bluetooth identity,
global spatial setting or device spatial preference. Use a separately cued,
bounded multichannel playback probe to verify actual renderer delivery. HAL
handshake, successful app writes and user hearing a difference are distinct
observations. Preserve the baseline settings and an independent restoration
snapshot before live root testing; inspect native flags and sensor removal after
it ends, including on failure.
