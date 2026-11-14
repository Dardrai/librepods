# Native spatial audio probe

Isolated public-AAudio diagnostic for an explicitly selected Android output device.
No APK, app integration, audio recording, profile/codec selection, volume/gain
change, global audio setting, effect-control call or persistent state. Bare
invocation only prints usage and exits. Building or running `--self-check` opens
no audio stream. Requires Android API33 or newer and ARM64.

## Offline build and checks

PowerShell7, installed Android NDK and local WSL `g++`; no dependency downloads.
Use a new output directory outside this source folder.

```powershell
./build.ps1 -AndroidNdk 'C:/Users/dylan/AppData/Local/Android/Sdk/ndk/28.2.13676358' `
    -OutputDirectory '<new private output directory>'
```

Host assertions scan every frame of both eight-second patterns, checking silence,
channel isolation, finite samples, peak and ten-millisecond fades. They also check
device-ID bounds, modes and malformed arguments. A second host test exercises the
real stream loop against a simulated900ms startup, short writes, an effective
buffer smaller than its capacity, and a stalled STARTED transition. It checks
that both complete patterns remain exact and the active window excludes startup.
The Android binary includes the waveform/CLI assert check. `receipt.json` records source and binary hashes and explicitly
records that no audio stream was opened during the build.

## Explicit runtime modes

```text
spatial-audio-probe --self-check
spatial-audio-probe --silent <positive current output device ID>
spatial-audio-probe --silent <positive current output device ID> --baseline
spatial-audio-probe --tones <positive current output device ID>
spatial-audio-probe --tones <positive current output device ID> --baseline
```

Resolve the intended native Bluetooth audio output ID immediately before a run;
IDs can change. Select the intended physical phone explicitly in the runner.
`--silent` writes only zero PCM samples. `--tones` is a separate listening test,
to be invoked only after the user's listening cue. It contains one second of
silence, then one second per channel in FL,FR,FC,LFE,BL,BR order, then one second of
silence. Other channels stay zero. LFE uses80Hz, the others440Hz; each tone has10ms
linear entrance/exit ramps and absolute digital peak at most0.03, about−30.5dBFS.
Existing device/media volume still determines the physical sound level.

Both stream modes request shared output,48kHz,float PCM, explicit5.1 BACK
position mask`0x3f`, MEDIA/MOVIE attributes, content-not-already-spatialized,
and AUTO spatialization. `--baseline` changes only that stream's spatialization
behavior to NEVER, enabling a controlled A/B test of the same six-channel source.
No performance mode or MMAP policy is forced, and no fallback stream is opened.

Before starting or writing, the probe requires the exact device, sample rate,
six-channel mask/count, float format, shared mode and requested attributes.
A mismatch closes the stream without starting. These checks repeat during the
active window so a reported route/contract change stops playback without fallback.
There can be a short device-change detection delay while a bounded write is in
flight; the tool does not control the platform's routing implementation.

Before starting, the probe nonblockingly prefills up to the output buffer capacity
using only the pattern's first second of silence. Accepted prefill counts toward
the same384000-frame pattern; tones cannot enter the prefill. Startup then waits
at most three seconds in50ms slices for STARTED, rechecking the selected route and
format. This startup allowance is separate from the eight-second active window.
The probe logs startup duration and prefill counts explicitly.

The active window starts only after STARTED, has an eight-second monotonic
deadline, and accepts at most384000 total frames including prefill. Completion
still requires all384000 frames; an incomplete pattern is a probe error.
Each active write has at most50ms timeout, bounded by the remaining window;
partial writes preserve the sample sequence. The stream is stopped and
closed on completion or SIGINT/SIGTERM/SIGHUP. Native open/stop/close calls do not
offer public timeouts; the runner should retain the process ID and enforce an
external bounded timeout with a forced-kill escalation if graceful shutdown stalls.
The runner's timeout must cover open + up to3s startup +8s active + shutdown; a
20s limit followed by forced termination after2s provides a bounded outer guard.
The helper logs before stop and close so a stall can be located. A timeout around
the host's ADB process alone does not guarantee the remote probe was terminated;
the runner must enforce the timeout on the phone and verify the exact probe PID
is gone. Abrupt process termination releases its audio resources.
The last queued silence can be discarded at the active-window deadline.

## Reading results

The first line of CLIENT config is the negotiated application stream format,
not the Bluetooth codec, hardware output format, or proof of a spatializer.
The probe logs app-written and endpoint-read frame counters, underrun-count delta
(`-1` means unavailable), stream state, and timestamp availability once per second
and just before stopping. There is no audio input or acoustic measurement.

With AAudio timestamp frame`P` at monotonic time`T`, current written-frame count`W`
and monotonic time`N`, the software queued-tail estimate in nanoseconds is
`T + (W-P)*1e9/48000 - N`. Raw frame/time/age values are printed alongside it.
Negative values or old timestamps indicate an estimate that should not be treated
as a valid positive queue delay. Timestamp unavailability soon after asynchronous
start is expected and does not trigger a fallback. None of these values measures
acoustic Bluetooth latency.

Independently capture AudioPolicy/AudioFlinger/spatializer state during the active
window and the current Bluetooth codec before/after. AUTO only requests policy
eligibility; NEVER excludes this stream. Compare those external observations to
establish actual spatializer activation and transport stability.

Public API semantics and API32 introduction of channel-mask/spatialization calls:
[official AAudio reference](https://developer.android.com/ndk/reference/group/audio).
The local NDK header defines5.1 as the BACK variant; channel-count-only requests
can drop upper channels, so this probe always requests and verifies a position mask.
