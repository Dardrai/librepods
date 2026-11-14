#include "probe_contract.h"

#include <aaudio/AAudio.h>
#include <array>
#include <cassert>
#include <cerrno>
#include <csignal>
#include <cstdio>
#include <time.h>

using namespace spatial_audio_probe;

static volatile sig_atomic_t stopRequested = 0;
static void stop(int) { stopRequested = 1; }

static int64_t nowNs() {
    timespec value{};
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0) return -1;
    return int64_t(value.tv_sec) * 1000000000 + value.tv_nsec;
}

static bool checked(aaudio_result_t result, const char* operation) {
    if (result >= 0) return true;
    std::fprintf(stderr, "%s: %s (%d)\n", operation, AAudio_convertResultToText(result), result);
    return false;
}

static bool matches(AAudioStream* stream, const Options& options) {
    return AAudioStream_getDeviceId(stream) == options.deviceId &&
           AAudioStream_getSampleRate(stream) == kRate &&
           AAudioStream_getChannelCount(stream) == kChannels &&
           AAudioStream_getChannelMask(stream) == AAUDIO_CHANNEL_5POINT1 &&
           AAudioStream_getFormat(stream) == AAUDIO_FORMAT_PCM_FLOAT &&
           AAudioStream_getSharingMode(stream) == AAUDIO_SHARING_MODE_SHARED &&
           AAudioStream_getDirection(stream) == AAUDIO_DIRECTION_OUTPUT &&
           AAudioStream_getUsage(stream) == AAUDIO_USAGE_MEDIA &&
           AAudioStream_getContentType(stream) == AAUDIO_CONTENT_TYPE_MOVIE &&
           AAudioStream_getSpatializationBehavior(stream) == (options.baseline ?
               AAUDIO_SPATIALIZATION_BEHAVIOR_NEVER : AAUDIO_SPATIALIZATION_BEHAVIOR_AUTO) &&
           !AAudioStream_isContentSpatialized(stream);
}

static void printConfig(AAudioStream* stream) {
    std::printf("CLIENT config: device=%d rate=%d channels=%d mask=0x%x format=%d shared_mode=%d "
                "performance_mode=%d usage=%d content_type=%d spatialization=%d already_spatialized=%d "
                "burst_frames=%d buffer_frames=%d capacity_frames=%d\n",
                AAudioStream_getDeviceId(stream), AAudioStream_getSampleRate(stream),
                AAudioStream_getChannelCount(stream), AAudioStream_getChannelMask(stream),
                AAudioStream_getFormat(stream), AAudioStream_getSharingMode(stream),
                AAudioStream_getPerformanceMode(stream), AAudioStream_getUsage(stream),
                AAudioStream_getContentType(stream), AAudioStream_getSpatializationBehavior(stream),
                AAudioStream_isContentSpatialized(stream), AAudioStream_getFramesPerBurst(stream),
                AAudioStream_getBufferSizeInFrames(stream), AAudioStream_getBufferCapacityInFrames(stream));
    std::fflush(stdout);
}

static void printMetrics(AAudioStream* stream, int32_t firstXruns) {
    const int64_t written = AAudioStream_getFramesWritten(stream);
    const int64_t read = AAudioStream_getFramesRead(stream);
    const int32_t xruns = AAudioStream_getXRunCount(stream);
    int64_t position = 0, timestamp = 0;
    const aaudio_result_t result = AAudioStream_getTimestamp(stream, CLOCK_MONOTONIC, &position, &timestamp);
    const int64_t now = nowNs();
    std::printf("METRICS frames_written=%lld frames_read=%lld underruns=%d underrun_delta=%lld state=%d ",
                static_cast<long long>(written), static_cast<long long>(read), xruns,
                firstXruns >= 0 && xruns >= firstXruns ? static_cast<long long>(xruns) - firstXruns : -1,
                AAudioStream_getState(stream));
    if (result == AAUDIO_OK && now >= 0) {
        const double delayMs = (double(timestamp - now) + double(written - position) * 1e9 / kRate) / 1e6;
        std::printf("timestamp_frame=%lld timestamp_ns=%lld timestamp_age_ms=%.3f software_queue_delay_ms=%.3f nonnegative_estimate=%d\n",
                    static_cast<long long>(position), static_cast<long long>(timestamp),
                    double(now - timestamp) / 1e6, delayMs, int(delayMs >= 0));
    } else {
        std::printf("timestamp_unavailable=%s (%d)\n", AAudio_convertResultToText(result), result);
    }
    std::fflush(stdout);
}

static int runStream(AAudioStream* stream, const Options& options) {
    printConfig(stream);
    if (!matches(stream, options)) {
        std::fprintf(stderr, "Requested route/format/attributes were not negotiated; no audio started.\n");
        return 1;
    }
    const int32_t firstXruns = AAudioStream_getXRunCount(stream);
    const int64_t prepare = nowNs();
    if (prepare < 0) { std::perror("clock_gettime"); return 1; }
    const int64_t startupDeadline = prepare + 3000000000LL;
    const int32_t capacity = AAudioStream_getBufferCapacityInFrames(stream);
    if (capacity <= 0) { std::fprintf(stderr, "Invalid output buffer capacity.\n"); return 1; }
    const int32_t prefillTarget = capacity < kRate ? capacity : kRate;
    int64_t queued = 0;
    std::array<float, kBlockFrames * kChannels> block{};
    while (!stopRequested && queued < prefillTarget) {
        const int64_t now = nowNs();
        if (now < 0) { std::perror("clock_gettime"); return 1; }
        if (now >= startupDeadline || !matches(stream, options)) {
            std::fprintf(stderr, "Prefill deadline or stream-contract check failed; no audio started.\n");
            return 1;
        }
        const int32_t frames = static_cast<int32_t>(prefillTarget - queued < kBlockFrames ? prefillTarget - queued : kBlockFrames);
        // Prefill belongs to the pattern's first second, which is silent in both modes.
        assert(queued + frames <= kRate);
        fillFrames(block.data(), queued, frames, options.tones);
        const aaudio_result_t written = AAudioStream_write(stream, block.data(), frames, 0);
        if (!checked(written, "AAudioStream_write prefill") || written > frames) return 1;
        if (written == 0) break;
        queued += written;
    }
    std::printf("STARTUP prefilled_frames=%lld target_frames=%d\n", static_cast<long long>(queued), prefillTarget);
    std::fflush(stdout);
    if (stopRequested) return 0;
    if (queued == 0) { std::fprintf(stderr, "Output accepted no silent prefill; no audio started.\n"); return 1; }
    const int64_t beforeStart = nowNs();
    if (beforeStart < 0) { std::perror("clock_gettime"); return 1; }
    if (beforeStart >= startupDeadline) { std::fprintf(stderr, "Startup budget expired before requestStart.\n"); return 1; }
    if (!checked(AAudioStream_requestStart(stream), "AAudioStream_requestStart")) return 1;
    bool failed = false;
    aaudio_stream_state_t state = AAudioStream_getState(stream);
    while (!stopRequested && state != AAUDIO_STREAM_STATE_STARTED) {
        const int64_t now = nowNs();
        if (now < 0) { std::perror("clock_gettime"); failed = true; break; }
        if (now >= startupDeadline || state != AAUDIO_STREAM_STATE_STARTING || !matches(stream, options)) {
            std::fprintf(stderr, "STARTED transition deadline/state/contract check failed: state=%d\n", state);
            failed = true; break;
        }
        const int64_t timeout = startupDeadline - now < 50000000 ? startupDeadline - now : 50000000;
        const aaudio_result_t waited = AAudioStream_waitForStateChange(stream, state, &state, timeout);
        if (waited != AAUDIO_OK && waited != AAUDIO_ERROR_TIMEOUT) {
            checked(waited, "AAudioStream_waitForStateChange"); failed = true; break;
        }
    }
    if (failed || stopRequested) {
        if (!checked(AAudioStream_requestStop(stream), "AAudioStream_requestStop after startup")) failed = true;
        return failed ? 1 : 0;
    }
    const int64_t start = nowNs();
    if (start < 0) {
        std::perror("clock_gettime");
        checked(AAudioStream_requestStop(stream), "AAudioStream_requestStop after clock error");
        return 1;
    }
    std::printf("STARTED startup_elapsed_ms=%.3f active_seconds=8 prefilled_frames=%lld\n",
                double(start - prepare) / 1e6, static_cast<long long>(queued));
    std::fflush(stdout);
    const int64_t deadline = start + kDurationNs;
    int64_t nextMetrics = start;
    while (!stopRequested) {
        const int64_t now = nowNs();
        if (now < 0) { std::perror("clock_gettime"); failed = true; break; }
        if (now >= deadline) break;
        if (!matches(stream, options)) {
            printConfig(stream);
            std::fprintf(stderr, "Route or stream contract changed; stopping without fallback.\n");
            failed = true; break;
        }
        if (now >= nextMetrics) {
            printMetrics(stream, firstXruns);
            nextMetrics = now + 1000000000;
        }
        if (queued < kTotalFrames) {
            const int32_t frames = static_cast<int32_t>(kTotalFrames - queued < kBlockFrames ? kTotalFrames - queued : kBlockFrames);
            fillFrames(block.data(), queued, frames, options.tones);
            const int64_t writeStart = nowNs();
            if (writeStart < 0) { std::perror("clock_gettime"); failed = true; break; }
            if (stopRequested || writeStart >= deadline) break;
            const int64_t timeout = deadline - writeStart < 50000000 ? deadline - writeStart : 50000000;
            const aaudio_result_t written = AAudioStream_write(stream, block.data(), frames, timeout);
            if (!checked(written, "AAudioStream_write") || written > frames) { failed = true; break; }
            queued += written;
            if (written > 0) continue;
        }
        const int64_t remaining = deadline - nowNs();
        if (remaining <= 0) break;
        timespec pause{0, static_cast<long>(remaining < 5000000 ? remaining : 5000000)};
        nanosleep(&pause, nullptr);
    }
    printMetrics(stream, firstXruns);
    std::printf("Completed active window: queued_frames=%lld max_frames=%lld elapsed_ms=%.3f signal=%d\n",
                static_cast<long long>(queued), static_cast<long long>(kTotalFrames),
                double(nowNs() - start) / 1e6, int(stopRequested));
    if (!stopRequested && queued < kTotalFrames) {
        std::fprintf(stderr, "The active-window deadline ended before the complete input pattern was accepted.\n");
        failed = true;
    }
    std::puts("Stopping AAudio stream; runner timeout remains responsible for native-call stalls.");
    std::fflush(stdout);
    if (!checked(AAudioStream_requestStop(stream), "AAudioStream_requestStop")) failed = true;
    return failed ? 1 : 0;
}

int main(int argc, char** argv) {
    Options options;
    if (!parseOptions(argc, argv, &options)) {
        std::fprintf(stderr, "Usage: %s --self-check | --silent DEVICE_ID [--baseline] | --tones DEVICE_ID [--baseline]\n"
                     "No default action. Tones require a separate listening cue; silent mode writes zeros only.\n", argv[0]);
        return 2;
    }
    if (options.selfCheck) { selfCheck(); return 0; }
    std::signal(SIGINT, stop);
    std::signal(SIGTERM, stop);
    std::signal(SIGHUP, stop);
    std::printf("Requested: device=%d pattern=%s seconds=8 peak=%.3f spatialization=%s\n",
                options.deviceId, options.tones ? "sequential_tones" : "silence", options.tones ? kPeak : 0,
                options.baseline ? "NEVER" : "AUTO");
    std::puts("Timestamp queue estimates are software diagnostics, not acoustic latency or codec-format evidence.");
    std::fflush(stdout);
    AAudioStreamBuilder* builder = nullptr;
    if (!checked(AAudio_createStreamBuilder(&builder), "AAudio_createStreamBuilder")) return 1;
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setSampleRate(builder, kRate);
    AAudioStreamBuilder_setChannelMask(builder, AAUDIO_CHANNEL_5POINT1);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setDeviceId(builder, options.deviceId);
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MOVIE);
    AAudioStreamBuilder_setSpatializationBehavior(builder, options.baseline ?
        AAUDIO_SPATIALIZATION_BEHAVIOR_NEVER : AAUDIO_SPATIALIZATION_BEHAVIOR_AUTO);
    AAudioStreamBuilder_setIsContentSpatialized(builder, false);
    AAudioStream* stream = nullptr;
    const aaudio_result_t opened = AAudioStreamBuilder_openStream(builder, &stream);
    const bool deleted = checked(AAudioStreamBuilder_delete(builder), "AAudioStreamBuilder_delete");
    if (!checked(opened, "AAudioStreamBuilder_openStream")) {
        if (stream) checked(AAudioStream_close(stream), "AAudioStream_close");
        return 1;
    }
    const int result = deleted && !stopRequested ? runStream(stream, options) : 1;
    std::puts("Closing AAudio stream.");
    std::fflush(stdout);
    const bool closed = checked(AAudioStream_close(stream), "AAudioStream_close");
    if (closed) std::puts("Closed AAudio stream; no global audio settings were changed.");
    return closed ? result : 1;
}
