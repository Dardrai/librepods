// SPDX-License-Identifier: MIT
#include "owned_fdk_encoder.h"
#include <algorithm>
#include <array>
#include <cstdio>
#include <cstdlib>
#include <thread>
#include <vector>

#define CHECK(condition) do { if (!(condition)) { \
    std::fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #condition); std::abort(); \
} } while (false)

namespace {
constexpr size_t FRAMES = 480;
constexpr size_t SAMPLES = FRAMES * 2;
constexpr uint8_t SENTINEL = 0xa5;
using Pcm = std::array<int16_t, SAMPLES>;
using Packets = std::vector<std::vector<uint8_t>>;

Pcm pcm_at(unsigned index) {
    Pcm pcm{};
    for (size_t i = 0; i < pcm.size(); ++i)
        pcm[i] = static_cast<int16_t>(static_cast<int>((i * 137 + index * 547) & 8191) - 4096);
    return pcm;
}

struct Owner {
    EldFdkEncoder* encoder = nullptr;
    EldFdkConfig config{};
    explicit Owner(uint32_t bitrate) {
        const auto api = eld_owned_fdk_api();
        CHECK(eld_fdk_open(&api, bitrate, &encoder, &config) == ELD_FDK_OK);
        CHECK(encoder);
        CHECK(config.sample_rate == 48000 && config.channels == 2);
        CHECK(config.frame_samples == FRAMES && config.pcm_sample_bytes == sizeof(int16_t));
        CHECK(config.bitrate == bitrate && config.max_au_bytes == (bitrate + 799) / 800 + 1);
        const std::array<uint8_t, 4> asc{0xf8, 0xe6, 0x50, 0};
        CHECK(std::equal(asc.begin(), asc.end(), config.asc));
    }
    Owner(const Owner&) = delete;
    Owner& operator=(const Owner&) = delete;
    ~Owner() { eld_fdk_close(&encoder); }
    std::vector<uint8_t> encode(unsigned index) {
        const auto pcm = pcm_at(index);
        std::array<uint8_t, 512> bytes{};
        bytes.fill(SENTINEL);
        size_t count = 999;
        CHECK(eld_fdk_encode(encoder, pcm.data(), FRAMES, bytes.data(), config.max_au_bytes, &count) == ELD_FDK_OK);
        CHECK(count <= config.max_au_bytes);
        CHECK(std::all_of(bytes.begin() + count, bytes.end(), [](uint8_t value) { return value == SENTINEL; }));
        return {bytes.begin(), bytes.begin() + count};
    }
    Packets stream(unsigned start, unsigned count) {
        Packets packets;
        packets.reserve(count);
        for (unsigned i = start; i < start + count; ++i) packets.push_back(encode(i));
        return packets;
    }
};

void encode_rates() {
    for (uint32_t bitrate : {128000u, 192000u, 256000u}) {
        Owner owner(bitrate);
        const auto packets = owner.stream(0, 64);
        CHECK(std::any_of(packets.begin(), packets.end(), [](const auto& bytes) { return !bytes.empty(); }));
        CHECK(eld_fdk_reset(owner.encoder) == ELD_FDK_OK);
        CHECK(owner.stream(0, 64) == packets);
    }
}

void invalid_input_preserves_owner() {
    auto api = eld_owned_fdk_api();
    EldFdkEncoder* absent = nullptr;
    EldFdkConfig config{};
    for (uint32_t invalid : {0u, 127000u, 128001u, 257000u}) {
        CHECK(eld_fdk_open(&api, invalid, &absent, &config) == ELD_FDK_INVALID);
        CHECK(!absent);
    }
    CHECK(eld_fdk_open(nullptr, 192000, &absent, &config) == ELD_FDK_INVALID);
    CHECK(api.open(nullptr, 1, 2) != 0);
    CHECK(api.info(nullptr, nullptr) != 0);
    CHECK(api.close(nullptr) != 0);
    Owner owner(192000), reference(192000);
    CHECK(eld_fdk_open(&api, 192000, &owner.encoder, &config) == ELD_FDK_INVALID);
    const auto pcm = pcm_at(0);
    std::array<uint8_t, 512> output{};
    output.fill(SENTINEL);
    size_t bytes = 999;
    CHECK(eld_fdk_encode(owner.encoder, pcm.data(), FRAMES - 1, output.data(), output.size(), &bytes) == ELD_FDK_INVALID);
    CHECK(bytes == 0);
    CHECK(eld_fdk_encode(owner.encoder, pcm.data(), FRAMES, output.data(), owner.config.max_au_bytes - 1, &bytes) == ELD_FDK_INVALID);
    CHECK(bytes == 0);
    CHECK(eld_fdk_encode(owner.encoder, nullptr, FRAMES, output.data(), output.size(), &bytes) == ELD_FDK_INVALID);
    CHECK(bytes == 0);
    CHECK(std::all_of(output.begin(), output.end(), [](uint8_t value) { return value == SENTINEL; }));
    const auto before = owner.config;
    CHECK(eld_fdk_update(owner.encoder, 127000, &owner.config) == ELD_FDK_INVALID);
    CHECK(owner.config.bitrate == before.bitrate && owner.config.max_au_bytes == before.max_au_bytes);
    CHECK(owner.stream(0, 8) == reference.stream(0, 8));
    eld_fdk_close(&owner.encoder);
    CHECK(!owner.encoder);
    eld_fdk_close(&owner.encoder);
    eld_fdk_close(nullptr);
}

void update_and_independent_owners() {
    Owner changing(128000), unchanged(256000), reference(256000);
    CHECK(unchanged.stream(0, 8) == reference.stream(0, 8));
    CHECK(eld_fdk_update(changing.encoder, 192000, &changing.config) == ELD_FDK_OK);
    CHECK(changing.config.bitrate == 192000 && changing.config.max_au_bytes == 241);
    Owner replacement_reference(192000);
    CHECK(changing.stream(0, 8) == replacement_reference.stream(0, 8));
    CHECK(eld_fdk_reset(changing.encoder) == ELD_FDK_OK);
    CHECK(unchanged.stream(8, 8) == reference.stream(8, 8));
    eld_fdk_close(&changing.encoder);
    CHECK(unchanged.stream(16, 8) == reference.stream(16, 8));
}

void concurrent_owners() {
    Owner first(128000), second(256000), first_reference(128000), second_reference(256000);
    Packets first_packets, second_packets;
    std::thread a([&] { first_packets = first.stream(0, 64); });
    std::thread b([&] { second_packets = second.stream(100, 64); });
    a.join();
    b.join();
    CHECK(first_packets == first_reference.stream(0, 64));
    CHECK(second_packets == second_reference.stream(100, 64));
}
}

int main() {
    encode_rates();
    invalid_input_preserves_owner();
    update_and_independent_owners();
    concurrent_owners();
    std::puts("Owned S16 AAC-ELD: 3 rates, AU bounds, reset, invalid inputs and independent concurrent owners passed");
}
