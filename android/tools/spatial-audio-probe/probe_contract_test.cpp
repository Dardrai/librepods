#include "probe_contract.h"

#include <array>
#include <cassert>
#include <cstdio>

#ifdef NDEBUG
#error Build this probe with assertions enabled.
#endif

namespace spatial_audio_probe {

void selfCheck() {
    Options options;
    const char* self[] = {"probe", "--self-check"};
    assert(parseOptions(2, self, &options) && options.selfCheck && options.deviceId == 0);
    for (const char* mode : {"--silent", "--tones"}) {
        const char* basic[] = {"probe", mode, "4791"};
        assert(parseOptions(3, basic, &options));
        assert(options.deviceId == 4791 && !options.baseline && !options.selfCheck);
        assert(options.tones == (std::strcmp(mode, "--tones") == 0));
        const char* baseline[] = {"probe", mode, "2147483647", "--baseline"};
        assert(parseOptions(4, baseline, &options) && options.baseline && options.deviceId == 2147483647);
    }
    const char* empty[] = {"probe"};
    assert(!parseOptions(1, empty, &options));
    const char* badMode[] = {"probe", "--play", "4791"};
    assert(!parseOptions(3, badMode, &options));
    const char* badFlag[] = {"probe", "--tones", "4791", "--force"};
    assert(!parseOptions(4, badFlag, &options));
    for (const char* id : {"", "0", "-1", "+1", " 1", "1 ", "1x", "2147483648", "99999999999999999999"}) {
        const char* invalid[] = {"probe", "--silent", id};
        assert(!parseOptions(3, invalid, &options));
    }
    std::array<float, kBlockFrames * kChannels> block{};
    std::array<double, kChannels> energy{};
    for (int64_t first = 0; first < kTotalFrames; first += kBlockFrames) {
        fillFrames(block.data(), first, kBlockFrames, false);
        for (float value : block) assert(value == 0);
        fillFrames(block.data(), first, kBlockFrames, true);
        for (int32_t frame = 0; frame < kBlockFrames; ++frame) {
            const int64_t position = first + frame;
            const int active = position >= kRate && position < int64_t(kRate) * 7 ? int(position / kRate) - 1 : -1;
            for (int channel = 0; channel < kChannels; ++channel) {
                const float value = block[size_t(frame) * kChannels + channel];
                assert(std::isfinite(value) && std::abs(value) <= kPeak);
                if (channel != active) assert(value == 0);
                if (position % kRate == 0 || position % kRate == kRate - 1) assert(value == 0);
                energy[channel] += double(value) * value;
            }
        }
    }
    for (double value : energy) {
        const double normalized = value / (kRate * double(kPeak) * kPeak);
        assert(normalized > 0.48 && normalized < 0.5);
    }
    std::array<float, kFadeFrames * kChannels> fade{};
    fillFrames(fade.data(), kRate, kFadeFrames, true);
    for (int frame = 0; frame < kFadeFrames; ++frame) {
        assert(std::abs(fade[size_t(frame) * kChannels]) <= kPeak * float(frame) / kFadeFrames + 0.000000001F);
    }
    std::puts("Spatial audio CLI, 8-second silent/tone pattern, channel isolation, peak and fade bounds: passed");
}

}  // namespace spatial_audio_probe

#ifdef SPATIAL_PROBE_HOST_TEST
int main() { spatial_audio_probe::selfCheck(); }
#endif
