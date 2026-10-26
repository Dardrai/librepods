// SPDX-License-Identifier: MIT
#include "owned_fdk_encoder.h"
#include "aacdecoder_lib.h"
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <vector>

#define CHECK(condition) do { if (!(condition)) { \
    std::fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #condition); std::abort(); \
} } while (false)

namespace {
constexpr size_t RATE = 48000, FRAME = 480, SECTIONS = sizeof(EldOwnedPcm) == 4 ? 9 : 8;
constexpr double PI = 3.14159265358979323846;
using Pcm = std::vector<EldOwnedPcm>;

double tone(size_t frame, double frequency) {
    return std::sin(2 * PI * frequency * static_cast<double>(frame) / RATE);
}

Pcm source() {
    Pcm pcm(SECTIONS * RATE * 2);
    uint32_t random = 7;
    for (size_t i = 0; i < SECTIONS * RATE; ++i) {
        random ^= random << 13; random ^= random >> 17; random ^= random << 5;
        const double noise = (static_cast<double>(random) / 4294967295.0 - .5) * .36;
        double left = 0, right = 0;
        switch (i / RATE) {
            case 0: case 1: case 6: case 7: case 8: {
                // The same two tones at four levels expose gain changes and compression.
                const double level = i / RATE == 0 ? .25 : i / RATE == 1 ? .0001 : i / RATE == 6 ? .025 : i / RATE == 7 ? .0025 : .00001;
                left = level * tone(i, 997); right = level * tone(i, 2111);
                break;
            }
            case 2:
                left = .08 * (tone(i, 233) + tone(i, 1997) + tone(i, 8101) + tone(i, 15001));
                right = .08 * (tone(i, 557) + tone(i, 3299) + tone(i, 11113) + tone(i, 17501));
                break;
            case 3: left = noise; right = noise * .7 + .05 * tone(i, 331); break;
            case 4: {
                const double envelope = std::exp(-static_cast<double>(i % 2400) / 120);
                left = .65 * envelope * tone(i, 641); right = .65 * envelope * tone(i, 1741);
                break;
            }
            case 5:
                left = .49 * (tone(i, 997) + tone(i, 4001));
                right = .49 * (tone(i, 2111) + tone(i, 7001));
                break;
        }
        pcm[i * 2] = static_cast<EldOwnedPcm>(left * std::numeric_limits<EldOwnedPcm>::max());
        pcm[i * 2 + 1] = static_cast<EldOwnedPcm>(right * std::numeric_limits<EldOwnedPcm>::max());
    }
    return pcm;
}

size_t delay(const Pcm& input, const Pcm& output) {
    size_t best = 0;
    double best_error = std::numeric_limits<double>::max();
    // A sparse deterministic noise window gives an unambiguous sample alignment.
    for (size_t lag = 0; lag <= FRAME * 2; ++lag) {
        double error = 0;
        for (size_t i = 3 * RATE + 12000; i < 3 * RATE + 16096; i += 7) {
            const double difference = static_cast<double>(input[i * 2]) - output[(i + lag) * 2];
            error += difference * difference;
        }
        if (error < best_error) { best_error = error; best = lag; }
    }
    return best;
}

struct Measurement { double gain_db, snr_db, crest_change_db; };
Measurement measure(const Pcm& input, const Pcm& output, size_t lag, size_t section, size_t channel) {
    double reference_power = 0, decoded_power = 0, error = 0, reference_peak = 0, decoded_peak = 0;
    for (size_t i = section * RATE + RATE / 4; i < section * RATE + 3 * RATE / 4; ++i) {
        const double reference = input[i * 2 + channel], decoded = output[(i + lag) * 2 + channel];
        reference_power += reference * reference; decoded_power += decoded * decoded;
        error += (decoded - reference) * (decoded - reference);
        reference_peak = std::max(reference_peak, std::abs(reference));
        decoded_peak = std::max(decoded_peak, std::abs(decoded));
    }
    const double gain = 10 * std::log10(decoded_power / reference_power);
    return {gain, 10 * std::log10(reference_power / error),
            20 * std::log10(decoded_peak / reference_peak) - gain};
}

double tone_amplitude(const Pcm& pcm, size_t lag, size_t section, size_t channel, double frequency) {
    double sine = 0, cosine = 0;
    for (size_t i = section * RATE + RATE / 4; i < section * RATE + 3 * RATE / 4; ++i) {
        const double phase = 2 * PI * frequency * static_cast<double>(i) / RATE;
        const double value = pcm[(i + lag) * 2 + channel];
        sine += value * std::sin(phase); cosine += value * std::cos(phase);
    }
    return 4 * std::hypot(sine, cosine) / RATE;
}
}

int main() {
    const auto input = source();
    const auto api = eld_owned_fdk_api();
    EldFdkEncoder* encoder = nullptr;
    EldFdkConfig config{};
    CHECK(eld_fdk_open(&api, 256000, &encoder, &config) == ELD_FDK_OK);
    CHECK(config.sample_rate == RATE && config.channels == 2 && config.frame_samples == FRAME);
    CHECK(config.pcm_sample_bytes == sizeof(EldOwnedPcm) && config.max_au_bytes == 321);
    auto decoder = aacDecoder_Open(TT_MP4_RAW, 1);
    CHECK(decoder);
    UCHAR* asc = config.asc;
    const UINT asc_size = sizeof(config.asc);
    CHECK(aacDecoder_ConfigRaw(decoder, &asc, &asc_size) == AAC_DEC_OK);
    Pcm output;
    output.reserve(input.size());
    std::vector<double> encode_times;
    size_t largest_au = 0;
    for (size_t frame = 0; frame < input.size() / 2; frame += FRAME) {
        std::array<uint8_t, 321> au{};
        size_t bytes = 0;
        const auto start = std::chrono::steady_clock::now();
        CHECK(eld_owned_fdk_encode(encoder, input.data() + frame * 2, FRAME, au.data(), au.size(), &bytes) == ELD_FDK_OK);
        encode_times.push_back(std::chrono::duration<double, std::micro>(std::chrono::steady_clock::now() - start).count());
        CHECK(bytes <= config.max_au_bytes);
        largest_au = std::max(largest_au, bytes);
        if (bytes == 0) continue;
        UCHAR* packet = au.data();
        const UINT size = static_cast<UINT>(bytes);
        UINT remaining = size;
        CHECK(aacDecoder_Fill(decoder, &packet, &size, &remaining) == AAC_DEC_OK && remaining == 0);
        std::array<INT_PCM, FRAME * 2> pcm{};
        CHECK(aacDecoder_DecodeFrame(decoder, pcm.data(), static_cast<INT>(pcm.size()), 0) == AAC_DEC_OK);
        const auto* info = aacDecoder_GetStreamInfo(decoder);
        CHECK(info->sampleRate == RATE && info->numChannels == 2 && info->frameSize == FRAME);
        CHECK(std::none_of(pcm.begin(), pcm.end(), [](INT_PCM value) { return value == std::numeric_limits<INT_PCM>::max() || value == std::numeric_limits<INT_PCM>::min(); }));
        output.insert(output.end(), pcm.begin(), pcm.end());
    }
    CHECK(output.size() == input.size());
    const auto lag = delay(input, output);
    CHECK(lag == config.delay_samples && lag <= FRAME);
    for (size_t channel = 0; channel < 2; ++channel) {
        const auto reference = measure(input, output, lag, 0, channel);
        CHECK(std::abs(reference.gain_db) < .15 && reference.snr_db > 48);
        CHECK(std::abs(reference.crest_change_db) < .3);
        for (size_t section : {size_t{1}, size_t{6}, size_t{7}}) {
            const auto quieter = measure(input, output, lag, section, channel);
            CHECK(std::abs(quieter.gain_db) < .3);
            CHECK(std::abs(quieter.gain_db - reference.gain_db) < .3);
        }
        if constexpr (sizeof(EldOwnedPcm) == 4) {
            // Both input tones are below half a signed-16 LSB. Any S16 truncation
            // before the encoder erases this whole section, unlike genuine Q31.
            const auto quiet = measure(input, output, lag, 8, channel);
            std::printf("sub-S16 channel %zu: gain %.3f dB, SNR %.2f dB\n", channel, quiet.gain_db, quiet.snr_db);
            // At -100 dBFS the lossy codec's own noise is measurable. Require
            // preserved level and a resolved waveform, not high-resolution SNR.
            CHECK(std::isfinite(quiet.gain_db) && std::abs(quiet.gain_db) < .5 && quiet.snr_db > 12);
            const auto begin = input.begin() + 8 * RATE * 2;
            CHECK(std::all_of(begin, input.end(), [](auto value) { return std::abs(static_cast<double>(value)) < 32768; }));
        }
        const auto peaks = measure(input, output, lag, 5, channel);
        CHECK(std::abs(peaks.gain_db) < .15 && peaks.snr_db > 45);
        CHECK(std::abs(peaks.crest_change_db) < .3);
        const auto transients = measure(input, output, lag, 4, channel);
        CHECK(transients.snr_db > 22 && std::abs(transients.crest_change_db) < 1);
        const double wanted = tone_amplitude(output, lag, 0, channel, channel == 0 ? 997 : 2111);
        const double wrong = tone_amplitude(output, lag, 0, channel, channel == 0 ? 2111 : 997);
        CHECK(wrong / wanted < .001);
        std::printf("channel %zu: tone SNR %.2f dB, gain %.3f dB, peak crest change %.3f dB\n",
                    channel, reference.snr_db, reference.gain_db, peaks.crest_change_db);
    }
    for (const double frequency : {557.0, 3299.0, 11113.0, 17501.0}) {
        const double gain = 20 * std::log10(tone_amplitude(output, lag, 2, 1, frequency) /
                                           tone_amplitude(input, 0, 2, 1, frequency));
        CHECK(std::abs(gain) < .15);
        std::printf("%.0f Hz: gain %.3f dB\n", frequency, gain);
    }
    double total_time = 0;
    for (double time : encode_times) total_time += time;
    std::sort(encode_times.begin(), encode_times.end());
    std::printf("%zu frames: encode mean %.2f us, p99 %.2f us, max %.2f us; host timing only\n",
                encode_times.size(), total_time / static_cast<double>(encode_times.size()),
                encode_times[encode_times.size() * 99 / 100], encode_times.back());
    std::printf("PCM%zu: alignment %zu frames, maximum AU %zu bytes (bound %u)\n",
                sizeof(EldOwnedPcm) * 8, lag, largest_au, config.max_au_bytes);
    aacDecoder_Close(decoder);
    eld_fdk_close(&encoder);
}
