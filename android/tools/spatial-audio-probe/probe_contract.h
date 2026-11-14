#pragma once

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>

namespace spatial_audio_probe {

constexpr int32_t kRate = 48000;
constexpr int32_t kChannels = 6;
constexpr int32_t kBlockFrames = 480;
constexpr int64_t kTotalFrames = int64_t(kRate) * 8;
constexpr int64_t kDurationNs = 8000000000LL;
constexpr int32_t kFadeFrames = kRate / 100;
constexpr float kPeak = 0.03F;
constexpr double kPi = 3.14159265358979323846;

struct Options {
    int32_t deviceId = 0;
    bool tones = false;
    bool baseline = false;
    bool selfCheck = false;
};

inline bool parseOptions(int argc, const char* const* argv, Options* result) {
    if (!argv || !result) return false;
    Options parsed;
    if (argc == 2 && std::strcmp(argv[1], "--self-check") == 0) {
        parsed.selfCheck = true;
    } else {
        if ((argc != 3 && argc != 4) ||
            (std::strcmp(argv[1], "--silent") != 0 && std::strcmp(argv[1], "--tones") != 0)) return false;
        if (argc == 4 && std::strcmp(argv[3], "--baseline") != 0) return false;
        if (!argv[2][0]) return false;
        for (const char* digit = argv[2]; *digit; ++digit) {
            if (*digit < '0' || *digit > '9') return false;
            const int value = *digit - '0';
            if (parsed.deviceId > (std::numeric_limits<int32_t>::max() - value) / 10) return false;
            parsed.deviceId = parsed.deviceId * 10 + value;
        }
        if (parsed.deviceId <= 0) return false;
        parsed.tones = std::strcmp(argv[1], "--tones") == 0;
        parsed.baseline = argc == 4;
    }
    *result = parsed;
    return true;
}

inline void fillFrames(float* output, int64_t first, int32_t frames, bool tones) {
    for (int32_t i = 0; i < frames; ++i) {
        float* frame = output + size_t(i) * kChannels;
        for (int32_t channel = 0; channel < kChannels; ++channel) frame[channel] = 0;
        const int64_t position = first + i;
        if (!tones || position < kRate || position >= int64_t(kRate) * 7) continue;
        const int32_t channel = static_cast<int32_t>(position / kRate) - 1;
        const int32_t local = static_cast<int32_t>(position % kRate);
        const int32_t edge = local < kRate - 1 - local ? local : kRate - 1 - local;
        const double envelope = edge < kFadeFrames ? double(edge) / kFadeFrames : 1.0;
        const double frequency = channel == 3 ? 80.0 : 440.0;
        frame[channel] = static_cast<float>(kPeak * envelope * std::sin(2 * kPi * frequency * local / kRate));
    }
}

void selfCheck();

}  // namespace spatial_audio_probe
