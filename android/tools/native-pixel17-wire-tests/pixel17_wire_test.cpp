// SPDX-License-Identifier: GPL-3.0-or-later
#include "pixel17_wire_hooks.h"
#include "wire.h"
#include <array>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <set>
#include <string>

namespace {
using namespace eld;
bool active = false, quarantined = false;
size_t forwarded = 0;
const uint8_t* seen_info = nullptr;
const uint8_t* seen_other = nullptr;
const void* seen_buffer = nullptr;
uint16_t seen_frames = 0;
bool seen_protection = false;
int32_t seen_rate = 0, seen_index = 0;
EncoderInterface ours{}, stock{};
bool is_active() { return active; }
bool is_quarantined() { return quarantined; }
const EncoderInterface* encoder() { return &ours; }
bool other_active() { return false; }
const char stock_name[] = "Stock pointer";

void check(bool value, const char* message = "Pixel wire assertion failed") {
    if (!value) { std::fprintf(stderr, "%s\n", message); std::abort(); }
}
bool host_valid(const uint8_t* info) { ++forwarded; seen_info = info; return true; }
bool host_rtp(bool protection, const uint8_t* info) {
    ++forwarded; seen_info = info; seen_protection = protection; return false;
}
const char* host_name(const uint8_t* info) { ++forwarded; seen_info = info; return stock_name; }
bool host_equals(const uint8_t* left, const uint8_t* right) {
    ++forwarded; seen_info = left; seen_other = right; return true;
}
int host_rate(const uint8_t* info) { ++forwarded; seen_info = info; return 44100; }
int host_bits(const uint8_t* info) { ++forwarded; seen_info = info; return 24; }
int host_channels(const uint8_t* info) { ++forwarded; seen_info = info; return 1; }
bool host_timestamp(const uint8_t* info, const uint8_t* bytes, uint32_t* out) {
    ++forwarded; seen_info = info; seen_buffer = bytes;
    if (out) *out = 0xfedcba98;
    return true;
}
bool host_header(const uint8_t* info, PacketHeader* packet, uint16_t frames) {
    ++forwarded; seen_info = info; seen_buffer = packet; seen_frames = frames; return true;
}
const EncoderInterface* host_encoder(const uint8_t* info) { ++forwarded; seen_info = info; return &stock; }
int32_t host_index(const uint8_t* info) { ++forwarded; seen_info = info; return 7; }
int32_t host_vendor_index(const uint8_t* info) { ++forwarded; seen_info = info; return -37; }
bool host_init(int32_t rate, int32_t index, void* config) {
    ++forwarded; seen_rate = rate; seen_index = index; seen_buffer = config; return true;
}

const Pixel17WireHookSpec& spec(const char* symbol) {
    size_t count = 0;
    const auto hooks = pixel17_wire_hook_specs(&count);
    for (size_t i = 0; i < count; ++i) if (std::strcmp(hooks[i].symbol, symbol) == 0) return hooks[i];
    std::abort();
}
template<class Fn> Fn hook(const char* symbol) { return reinterpret_cast<Fn>(spec(symbol).replacement); }
template<class Fn> void bind(const char* symbol, Fn callback) {
    *spec(symbol).original = reinterpret_cast<void*>(callback);
}

constexpr char valid_symbol[] = "_Z29A2DP_IsVendorSourceCodecValidPKh";
constexpr char rtp_symbol[] = "_Z18A2DP_UsesRtpHeaderbPKh";
constexpr char name_symbol[] = "_Z14A2DP_CodecNamePKh";
constexpr char equals_symbol[] = "_Z20A2DP_CodecTypeEqualsPKhS0_";
constexpr char rate_symbol[] = "_Z23A2DP_GetTrackSampleRatePKh";
constexpr char bits_symbol[] = "_Z26A2DP_GetTrackBitsPerSamplePKh";
constexpr char channels_symbol[] = "_Z25A2DP_GetTrackChannelCountPKh";
constexpr char timestamp_symbol[] = "_Z23A2DP_GetPacketTimestampPKhS0_Pj";
constexpr char header_symbol[] = "_Z21A2DP_BuildCodecHeaderPKhP6BT_HDRt";
constexpr char encoder_symbol[] = "_Z24A2DP_GetEncoderInterfacePKh";
constexpr char index_symbol[] = "_Z21A2DP_SourceCodecIndexPKh";
constexpr char vendor_index_symbol[] = "_Z27A2DP_VendorSourceCodecIndexPKh";
constexpr char init_symbol[] = "_Z26A2DP_VendorInitCodecConfig29btav_a2dp_codec_sample_rate_t23btav_a2dp_codec_index_tP14AvdtpSepConfig";
using Valid = bool (*)(const uint8_t*);
using Rtp = bool (*)(bool, const uint8_t*);
using Name = const char* (*)(const uint8_t*);
using Equals = bool (*)(const uint8_t*, const uint8_t*);
using Parameter = int (*)(const uint8_t*);
using Timestamp = bool (*)(const uint8_t*, const uint8_t*, uint32_t*);
using Header = bool (*)(const uint8_t*, PacketHeader*, uint16_t);
using Encoder = const EncoderInterface* (*)(const uint8_t*);
using Init = bool (*)(int32_t, int32_t, void*);

void install() {
    pixel17_wire_reset_for_test();
    active = quarantined = false;
    forwarded = 0;
    check(!pixel17_wire_prepare({}));
    check(!pixel17_wire_prepare({is_active, is_quarantined, encoder, 6}));
    check(pixel17_wire_prepare({is_active, is_quarantined, encoder, 5}));
    check(!pixel17_wire_ready());
    bind(valid_symbol, host_valid); bind(rtp_symbol, host_rtp); bind(name_symbol, host_name);
    bind(equals_symbol, host_equals); bind(rate_symbol, host_rate); bind(bits_symbol, host_bits);
    bind(channels_symbol, host_channels); bind(timestamp_symbol, host_timestamp);
    bind(header_symbol, host_header); bind(encoder_symbol, host_encoder); bind(index_symbol, host_index);
    bind(vendor_index_symbol, host_vendor_index);
    bind(init_symbol, host_init);
    check(pixel17_wire_ready());
    check(pixel17_wire_prepare({is_active, is_quarantined, encoder, 5}));
    check(!pixel17_wire_prepare({other_active, is_quarantined, encoder, 5}));
}
std::array<uint8_t, kPixel17WireStorage> cie() {
    std::array<uint8_t, kPixel17WireStorage> result{};
    check(make_configuration(result.data(), result.size()));
    return result;
}

void descriptors() {
    size_t count = 0;
    const auto hooks = pixel17_wire_hook_specs(&count);
    check(count == 13 && pixel17_wire_hook_specs(nullptr) == hooks);
    std::set<std::string> names;
    std::set<void**> storage;
    for (size_t i = 0; i < count; ++i) {
        check(hooks[i].symbol && hooks[i].replacement && hooks[i].original);
        check(names.insert(hooks[i].symbol).second && storage.insert(hooks[i].original).second);
        check(std::strstr(hooks[i].symbol, "CodecIndexStr") == nullptr);
        check(std::strstr(hooks[i].symbol, "PeerSource") == nullptr);
    }
}

void forwarding() {
    install();
    auto other = cie();
    other[3] = 0x4f;
    auto apple = cie();
    PacketHeader packet{3, 99, 12, 7};
    const auto saved_packet = packet;
    uint32_t stamp = 0;
    uint8_t bytes[4] = {1, 2, 3, 4};
    for (bool armed : {false, true}) {
        active = armed;
        const auto* info = armed ? other.data() : apple.data();
        check(hook<Valid>(valid_symbol)(info) && seen_info == info);
        check(!hook<Rtp>(rtp_symbol)(true, info) && seen_info == info && seen_protection);
        check(hook<Name>(name_symbol)(info) == stock_name && seen_info == info);
        check(hook<Equals>(equals_symbol)(info, other.data()) && seen_info == info && seen_other == other.data());
        check(hook<Parameter>(rate_symbol)(info) == 44100);
        check(hook<Parameter>(bits_symbol)(info) == 24);
        check(hook<Parameter>(channels_symbol)(info) == 1);
        check(hook<Timestamp>(timestamp_symbol)(info, bytes, &stamp) && stamp == 0xfedcba98 && seen_buffer == bytes);
        check(hook<Header>(header_symbol)(info, &packet, 42) && seen_frames == 42 && seen_buffer == &packet);
        check(std::memcmp(&saved_packet, &packet, sizeof(packet)) == 0);
        check(hook<Encoder>(encoder_symbol)(info) == &stock);
        check(hook<Parameter>(index_symbol)(info) == 7);
        check(hook<Parameter>(vendor_index_symbol)(info) == -37 && seen_info == info);
        check(hook<Init>(init_symbol)(8, 7, bytes) && seen_rate == 8 && seen_index == 7 && seen_buffer == bytes);
    }
    check(forwarded == 26);
    // Unknown Apple codec IDs are unrelated and retain stock behavior, including null arguments.
    active = true;
    other = cie(); other[7] = 2;
    check(hook<Encoder>(encoder_symbol)(other.data()) == &stock);
    check(hook<Valid>(valid_symbol)(nullptr) && seen_info == nullptr);
    check(hook<Parameter>(vendor_index_symbol)(nullptr) == -37 && seen_info == nullptr);
    check(hook<Parameter>(vendor_index_symbol)(other.data()) == -37 && seen_info == other.data());
}

void own_codec() {
    install(); active = true;
    auto info = cie();
    auto slower = cie(); check(make_configuration(slower.data(), slower.size(), 128000));
    check(hook<Valid>(valid_symbol)(info.data()));
    check(hook<Rtp>(rtp_symbol)(false, info.data()));
    check(std::strcmp(hook<Name>(name_symbol)(info.data()), "Apple AAC-ELD") == 0);
    check(hook<Equals>(equals_symbol)(info.data(), slower.data()));
    auto other = info; other[3] = 0x4f;
    check(!hook<Equals>(equals_symbol)(info.data(), other.data()));
    check(!hook<Equals>(equals_symbol)(nullptr, info.data()));
    check(hook<Parameter>(rate_symbol)(info.data()) == 48000);
    check(hook<Parameter>(bits_symbol)(info.data()) == 16);
    check(hook<Parameter>(channels_symbol)(info.data()) == 2);
    check(hook<Parameter>(index_symbol)(info.data()) == 5);
    check(hook<Parameter>(vendor_index_symbol)(info.data()) == 5);
    check(hook<Encoder>(encoder_symbol)(info.data()) == &ours);
    uint32_t original_stamp = 0x12345678, stamp = 0;
    std::array<uint8_t, 6> bytes{};
    std::memcpy(bytes.data() + 1, &original_stamp, sizeof(original_stamp));
    check(hook<Timestamp>(timestamp_symbol)(info.data(), bytes.data() + 1, &stamp) && stamp == original_stamp);
    check(!hook<Timestamp>(timestamp_symbol)(info.data(), nullptr, &stamp));
    check(!hook<Timestamp>(timestamp_symbol)(info.data(), bytes.data(), nullptr));
    PacketHeader packet{0, 324, 32, 1};
    const auto saved = packet;
    check(hook<Header>(header_symbol)(info.data(), &packet, 1));
    check(std::memcmp(&packet, &saved, sizeof(packet)) == 0);
    check(!hook<Header>(header_symbol)(info.data(), nullptr, 1));
    check(!hook<Header>(header_symbol)(info.data(), &packet, 2));
    for (PacketHeader bad : {PacketHeader{0, 4, 32, 1}, PacketHeader{0, 326, 32, 1},
                            PacketHeader{0, 5, 0, 1}, PacketHeader{0, 5, 32, 2},
                            PacketHeader{0, 324, UINT16_MAX, 1}})
        check(!hook<Header>(header_symbol)(info.data(), &bad, 1));
    check(forwarded == 0);
}

void incoming_ota_vendor_dispatch() {
    install(); active = true;
    // The incoming AirPods SetConfiguration reported C2 before the direct vendor helper was
    // hooked. The actual native factory caller bypasses the generic SourceCodecIndex helper.
    std::array<uint8_t, kPixel17WireStorage> incoming{};
    const uint8_t captured[] = {0x10,0,0xff,0x4c,0,0,0,1,0x80,0,0x80,0,0x84,0,0x83,0xe8,0};
    std::memcpy(incoming.data(), captured, sizeof(captured));
    check(valid_configuration(incoming.data(), incoming.size()));
    check(hook<Parameter>(vendor_index_symbol)(incoming.data()) == 5);
    check(forwarded == 0);
    incoming[7] = 2; // Another Apple codec must retain this helper's exact original behavior.
    check(hook<Parameter>(vendor_index_symbol)(incoming.data()) == -37 && seen_info == incoming.data());
    check(forwarded == 1);
}

void malformed_and_quarantine() {
    install(); active = true;
    for (size_t offset : {size_t(0), size_t(1), size_t(9), size_t(10), size_t(12), size_t(14)}) {
        auto invalid = cie(); invalid[offset] = 0;
        if (offset == 1 || offset == 9) invalid[offset] = 1;
        uint32_t stamp = 0xaabbccdd;
        uint8_t bytes[4]{};
        check(!hook<Valid>(valid_symbol)(invalid.data()));
        check(!hook<Rtp>(rtp_symbol)(false, invalid.data()));
        check(hook<Parameter>(rate_symbol)(invalid.data()) == -1);
        check(hook<Parameter>(index_symbol)(invalid.data()) == kPixel17InvalidCodecIndex);
        check(hook<Parameter>(vendor_index_symbol)(invalid.data()) == kPixel17InvalidCodecIndex);
        check(!hook<Encoder>(encoder_symbol)(invalid.data()));
        check(!hook<Timestamp>(timestamp_symbol)(invalid.data(), bytes, &stamp) && stamp == 0xaabbccdd);
    }
    auto info = cie();
    quarantined = true;
    check(!hook<Valid>(valid_symbol)(info.data()));
    check(!hook<Encoder>(encoder_symbol)(info.data()));
    check(hook<Parameter>(index_symbol)(info.data()) == 19);
    check(hook<Parameter>(vendor_index_symbol)(info.data()) == 19);
    auto unrelated = info; unrelated[3] = 0x4f;
    check(hook<Encoder>(encoder_symbol)(unrelated.data()) == &stock);
    check(forwarded == 1);
}

void bounded_sep_prefix() {
    install(); active = true;
    for (int rate : {0, 2, 1, 3}) {
        std::array<uint8_t, 160> sep; sep.fill(0x5a);
        const bool accepted = hook<Init>(init_symbol)(rate, 5, sep.data());
        check(accepted == (rate == 0 || rate == 2));
        if (accepted) check(valid_configuration(sep.data(), 45));
        else for (size_t i = 0; i < 45; ++i) check(sep[i] == 0);
        for (size_t i = 45; i < sep.size(); ++i) check(sep[i] == 0x5a);
    }
    check(!hook<Init>(init_symbol)(2, 5, nullptr));
    quarantined = true;
    std::array<uint8_t, 160> sep; sep.fill(0x5a);
    check(!hook<Init>(init_symbol)(2, 5, sep.data()));
    for (size_t i = 0; i < 45; ++i) check(sep[i] == 0);
    for (size_t i = 45; i < sep.size(); ++i) check(sep[i] == 0x5a);
    check(forwarded == 0);
}
}

int main() {
    descriptors(); forwarding(); own_codec(); incoming_ota_vendor_dispatch(); malformed_and_quarantine(); bounded_sep_prefix();
    std::puts("PASS Pixel wire descriptors, stock forwarding, own ABI, incoming vendor OTA dispatch, malformed/quarantine, SEP bounds");
}
