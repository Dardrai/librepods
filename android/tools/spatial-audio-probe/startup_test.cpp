// Exercise the real stream loop against a delayed, partial-write AAudio endpoint.
#define _Nonnull
#define _Nullable
#define _Null_unspecified
#define __INTRODUCED_IN(api)
#include <aaudio/AAudio.h>
#include <algorithm>
#include <cassert>

static int64_t simulatedNow = 1000000000;
static int simulatedClock(clockid_t, timespec* value) {
    value->tv_sec = simulatedNow / 1000000000;
    value->tv_nsec = simulatedNow % 1000000000;
    return 0;
}
static int simulatedSleep(const timespec* duration, timespec*) {
    simulatedNow += int64_t(duration->tv_sec) * 1000000000 + duration->tv_nsec;
    return 0;
}
#define clock_gettime simulatedClock
#define nanosleep simulatedSleep
#define main unusedProbeMain
#include "probe.cpp"
#undef main
#undef nanosleep
#undef clock_gettime

struct AAudioStreamStruct {
    int64_t written = 0;
    int64_t start = 0;
    int64_t prefilled = 0;
    bool stopped = false;
    bool stalled = false;
    bool tones = false;
};

#define GETTER(name, type, value) extern "C" type name(AAudioStream*) { return value; }
GETTER(AAudioStream_getDeviceId, int32_t, 4791)
GETTER(AAudioStream_getSampleRate, int32_t, kRate)
GETTER(AAudioStream_getChannelCount, int32_t, kChannels)
GETTER(AAudioStream_getChannelMask, aaudio_channel_mask_t, AAUDIO_CHANNEL_5POINT1)
GETTER(AAudioStream_getFormat, aaudio_format_t, AAUDIO_FORMAT_PCM_FLOAT)
GETTER(AAudioStream_getSharingMode, aaudio_sharing_mode_t, AAUDIO_SHARING_MODE_SHARED)
GETTER(AAudioStream_getDirection, aaudio_direction_t, AAUDIO_DIRECTION_OUTPUT)
GETTER(AAudioStream_getUsage, aaudio_usage_t, AAUDIO_USAGE_MEDIA)
GETTER(AAudioStream_getContentType, aaudio_content_type_t, AAUDIO_CONTENT_TYPE_MOVIE)
GETTER(AAudioStream_getSpatializationBehavior, aaudio_spatialization_behavior_t, AAUDIO_SPATIALIZATION_BEHAVIOR_AUTO)
GETTER(AAudioStream_isContentSpatialized, bool, false)
GETTER(AAudioStream_getPerformanceMode, aaudio_performance_mode_t, AAUDIO_PERFORMANCE_MODE_NONE)
GETTER(AAudioStream_getFramesPerBurst, int32_t, 3615)
GETTER(AAudioStream_getBufferSizeInFrames, int32_t, 4000)
GETTER(AAudioStream_getBufferCapacityInFrames, int32_t, 7230)
GETTER(AAudioStream_getXRunCount, int32_t, 0)
#undef GETTER
extern "C" const char* AAudio_convertResultToText(aaudio_result_t) { return "simulated"; }
extern "C" int64_t AAudioStream_getFramesWritten(AAudioStream* stream) { return stream->written; }
extern "C" int64_t AAudioStream_getFramesRead(AAudioStream*) { return 0; }
extern "C" aaudio_result_t AAudioStream_getTimestamp(AAudioStream*, clockid_t, int64_t*, int64_t*) {
    return AAUDIO_ERROR_INVALID_STATE;
}
extern "C" aaudio_stream_state_t AAudioStream_getState(AAudioStream* stream) {
    if (!stream->start) return AAUDIO_STREAM_STATE_OPEN;
    return !stream->stalled && simulatedNow >= stream->start + 900000000 ?
        AAUDIO_STREAM_STATE_STARTED : AAUDIO_STREAM_STATE_STARTING;
}
extern "C" aaudio_result_t AAudioStream_requestStart(AAudioStream* stream) {
    stream->start = simulatedNow;
    stream->prefilled = stream->written;
    return AAUDIO_OK;
}
extern "C" aaudio_result_t AAudioStream_waitForStateChange(AAudioStream* stream,
        aaudio_stream_state_t, aaudio_stream_state_t* next, int64_t timeout) {
    assert(timeout > 0 && timeout <= 50000000);
    simulatedNow += timeout;
    *next = AAudioStream_getState(stream);
    return *next == AAUDIO_STREAM_STATE_STARTED ? AAUDIO_OK : AAUDIO_ERROR_TIMEOUT;
}
extern "C" aaudio_result_t AAudioStream_write(AAudioStream* stream, const void* input, int32_t frames, int64_t timeout) {
    assert(timeout >= 0 && timeout <= 50000000);
    int32_t accepted = std::min(frames, 137);
    if (!stream->start) {
        assert(timeout == 0);
        accepted = static_cast<int32_t>(std::min<int64_t>(accepted, 4000 - stream->written));
    } else {
        assert(AAudioStream_getState(stream) == AAUDIO_STREAM_STATE_STARTED);
        simulatedNow += int64_t(accepted) * 1000000000 / kRate;
    }
    const float* samples = static_cast<const float*>(input);
    for (int32_t i = 0; i < accepted; ++i) {
        float expected[kChannels];
        fillFrames(expected, stream->written + i, 1, stream->tones);
        for (int channel = 0; channel < kChannels; ++channel) {
            assert(samples[size_t(i) * kChannels + channel] == expected[channel]);
            if (!stream->start) assert(expected[channel] == 0);
        }
    }
    stream->written += accepted;
    return accepted;
}
extern "C" aaudio_result_t AAudioStream_requestStop(AAudioStream* stream) {
    stream->stopped = true;
    return AAUDIO_OK;
}

int main() {
    Options options;
    options.deviceId = 4791;
    for (bool tones : {false, true}) {
        AAudioStream stream;
        stream.tones = tones;
        options.tones = tones;
        const int64_t before = simulatedNow;
        assert(runStream(&stream, options) == 0);
        assert(stream.prefilled == 4000 && stream.written == kTotalFrames && stream.stopped);
        assert(simulatedNow - before >= kDurationNs + 900000000);
        assert(simulatedNow - before < kDurationNs + 905000000);
    }
    AAudioStream stalled;
    stalled.stalled = true;
    const int64_t before = simulatedNow;
    assert(runStream(&stalled, options) == 1);
    assert(stalled.written == 4000 && stalled.stopped);
    assert(simulatedNow - before == 3000000000LL);
    std::puts("Real stream loop: delayed STARTED, partial prefill/writes, exact pattern and startup timeout: passed");
}
