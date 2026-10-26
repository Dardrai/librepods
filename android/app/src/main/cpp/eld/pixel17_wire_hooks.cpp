// SPDX-License-Identifier: GPL-3.0-or-later
#include "pixel17_wire_hooks.h"
#include "pixel17_codec.h"
#include "wire.h"
#include <array>
#include <atomic>
#include <cstring>
#include <mutex>
#if defined(__ANDROID__) && defined(__aarch64__) && defined(ELD_PRIVATE_OWNED_FDK) && defined(LIBREPODS_NATIVE_TRACE) && LIBREPODS_NATIVE_TRACE
#include <android/log.h>
#include <cstdio>
#endif

namespace eld {
namespace {
enum Hook : size_t { Valid, Rtp, Name, TypeEquals, Rate, Bits, Channels,
    Timestamp, Header, Encoder, SourceIndex, VendorSourceIndex, InitVendor, HookCount };
void* originals[HookCount]{};
Pixel17WireBindings host{};
std::atomic<bool> prepared{false};
std::mutex preparation;
static_assert(kPixel17WireStorage == kPixel17CodecStorage);

template<class Fn> Fn original(Hook hook) { return reinterpret_cast<Fn>(originals[hook]); }
bool handling() {
    return prepared.load(std::memory_order_acquire) && host.active() && pixel17_wire_ready();
}
bool candidate(const uint8_t* info) {
    // These proven host APIs receive codec_info[45], even if its internal length is malformed.
    static constexpr uint8_t vendor[] = {0xff, 0x4c, 0, 0, 0, 1, 0x80};
    return handling() && info && std::memcmp(info + 2, vendor, sizeof(vendor)) == 0;
}
bool compatible(const uint8_t* info) {
    std::array<uint8_t, kPixel17WireStorage> selected{};
    return !host.quarantined() && is_apple(info, kPixel17WireStorage) &&
        info[9] == 0 && info[11] == 0 && info[13] == 0 && select_configuration(info, kPixel17WireStorage,
        selected.data(), selected.size());
}
bool configured(const uint8_t* info) {
    return !host.quarantined() && valid_configuration(info, kPixel17WireStorage);
}

bool source_valid(const uint8_t* info) {
    if (candidate(info)) return compatible(info);
    const auto next = original<bool (*)(const uint8_t*)>(Valid);
    return next && next(info);
}
bool uses_rtp(bool protection, const uint8_t* info) {
    if (candidate(info)) return compatible(info);
    const auto next = original<bool (*)(bool, const uint8_t*)>(Rtp);
    return next && next(protection, info);
}
const char* name(const uint8_t* info) {
    if (candidate(info)) return is_apple(info, kPixel17WireStorage) ? "Apple AAC-ELD" : "UNKNOWN";
    const auto next = original<const char* (*)(const uint8_t*)>(Name);
    return next ? next(info) : "UNKNOWN";
}
bool type_equals(const uint8_t* left, const uint8_t* right) {
    if (candidate(left) || candidate(right))
        return candidate(left) && candidate(right) && compatible(left) && compatible(right);
    const auto next = original<bool (*)(const uint8_t*, const uint8_t*)>(TypeEquals);
    return next && next(left, right);
}
template<Hook hook, int value> int parameter(const uint8_t* info) {
    if (candidate(info)) return compatible(info) ? value : -1;
    const auto next = original<int (*)(const uint8_t*)>(hook);
    return next ? next(info) : -1;
}
bool timestamp(const uint8_t* info, const uint8_t* bytes, uint32_t* out) {
    if (candidate(info)) {
        if (!bytes || !out || !configured(info)) return false;
        // Matches the platform AAC timestamp ABI: native-endian uint32 at the data prefix.
        std::memcpy(out, bytes, sizeof(*out));
        return true;
    }
    const auto next = original<bool (*)(const uint8_t*, const uint8_t*, uint32_t*)>(Timestamp);
    return next && next(info, bytes, out);
}
bool build_header(const uint8_t* info, PacketHeader* packet, uint16_t frames) {
    if (candidate(info)) {
        if (!packet || frames != 1 || !configured(info)) return false;
        PacketHeader header;
        std::memcpy(&header, packet, sizeof(header));
        return header.layer_specific == 1 && header.offset >= sizeof(uint32_t) &&
            header.len > kAuHeaderSize && header.len <= kAuHeaderSize + max_au_bytes(bitrate(info, kPixel17WireStorage)) &&
            static_cast<uint32_t>(header.offset) + header.len <= UINT16_MAX;
    }
    const auto next = original<bool (*)(const uint8_t*, PacketHeader*, uint16_t)>(Header);
    return next && next(info, packet, frames);
}
const EncoderInterface* encoder(const uint8_t* info) {
    if (candidate(info)) return configured(info) ? host.encoder() : nullptr;
    const auto next = original<const EncoderInterface* (*)(const uint8_t*)>(Encoder);
    return next ? next(info) : nullptr;
}
template<Hook hook> int32_t source_index(const uint8_t* info) {
    const auto next = original<int32_t (*)(const uint8_t*)>(hook);
    const int32_t result = candidate(info)
        ? (compatible(info) ? host.source_index : kPixel17InvalidCodecIndex)
        : (next ? next(info) : kPixel17InvalidCodecIndex);
#if defined(__ANDROID__) && defined(__aarch64__) && defined(ELD_PRIVATE_OWNED_FDK) && defined(LIBREPODS_NATIVE_TRACE) && LIBREPODS_NATIVE_TRACE
    // Bounded control-path evidence for the private prototype; never trace media callbacks.
    static std::atomic<unsigned> accepted{0}, rejected{0};
    if (is_apple(info, kPixel17WireStorage) &&
        (result == 5 ? accepted.fetch_add(1) < 1 : rejected.fetch_add(1) < 8)) {
        char cie[2 * kCodecInfoSize + 1]{};
        for (size_t i = 0; i < kCodecInfoSize; ++i)
            std::snprintf(cie + 2 * i, 3, "%02x", info[i]);
        const bool ready = prepared.load(std::memory_order_acquire);
        __android_log_print(ANDROID_LOG_INFO, "LibrePodsELD",
            "Pixel17: source lookup hook=%u result=%d prepared=%d active=%d ready=%d quarantined=%d cie=%s",
            static_cast<unsigned>(hook), result, ready, ready && host.active(),
            pixel17_wire_ready(), ready && host.quarantined(), cie);
    }
#endif
    return result;
}
bool init_vendor(int32_t sample_rate, int32_t index, void* sep) {
    if (handling() && index == host.source_index) {
        if (!sep) return false;
        std::array<uint8_t, kPixel17WireStorage> capabilities{};
        const bool accepted = !host.quarantined() && (sample_rate == 0 || sample_rate == 2) &&
            make_capabilities(capabilities.data(), capabilities.size());
        // The stock global wrapper initializes the later SEP fields before calling this vendor API.
        std::memcpy(sep, capabilities.data(), capabilities.size());
        return accepted;
    }
    const auto next = original<bool (*)(int32_t, int32_t, void*)>(InitVendor);
    return next && next(sample_rate, index, sep);
}

const Pixel17WireHookSpec hooks[] = {
    {"_Z29A2DP_IsVendorSourceCodecValidPKh", reinterpret_cast<void*>(&source_valid), &originals[Valid]},
    {"_Z18A2DP_UsesRtpHeaderbPKh", reinterpret_cast<void*>(&uses_rtp), &originals[Rtp]},
    {"_Z14A2DP_CodecNamePKh", reinterpret_cast<void*>(&name), &originals[Name]},
    {"_Z20A2DP_CodecTypeEqualsPKhS0_", reinterpret_cast<void*>(&type_equals), &originals[TypeEquals]},
    {"_Z23A2DP_GetTrackSampleRatePKh", reinterpret_cast<void*>(&parameter<Rate, 48000>), &originals[Rate]},
    {"_Z26A2DP_GetTrackBitsPerSamplePKh", reinterpret_cast<void*>(&parameter<Bits, kPixel17PcmBits>), &originals[Bits]},
    {"_Z25A2DP_GetTrackChannelCountPKh", reinterpret_cast<void*>(&parameter<Channels, 2>), &originals[Channels]},
    {"_Z23A2DP_GetPacketTimestampPKhS0_Pj", reinterpret_cast<void*>(&timestamp), &originals[Timestamp]},
    {"_Z21A2DP_BuildCodecHeaderPKhP6BT_HDRt", reinterpret_cast<void*>(&build_header), &originals[Header]},
    {"_Z24A2DP_GetEncoderInterfacePKh", reinterpret_cast<void*>(&encoder), &originals[Encoder]},
    {"_Z21A2DP_SourceCodecIndexPKh", reinterpret_cast<void*>(&source_index<SourceIndex>), &originals[SourceIndex]},
    // Native findSourceCodecConfig inlines the generic dispatcher and calls this vendor helper.
    {"_Z27A2DP_VendorSourceCodecIndexPKh", reinterpret_cast<void*>(&source_index<VendorSourceIndex>), &originals[VendorSourceIndex]},
    {"_Z26A2DP_VendorInitCodecConfig29btav_a2dp_codec_sample_rate_t23btav_a2dp_codec_index_tP14AvdtpSepConfig",
        reinterpret_cast<void*>(&init_vendor), &originals[InitVendor]},
};
static_assert(sizeof(hooks) / sizeof(hooks[0]) == HookCount);
}

bool pixel17_wire_prepare(const Pixel17WireBindings& bindings) {
    if (!bindings.active || !bindings.quarantined || !bindings.encoder || bindings.source_index != 5)
        return false;
    std::lock_guard<std::mutex> lock(preparation);
    if (prepared.load()) return host.active == bindings.active && host.quarantined == bindings.quarantined &&
        host.encoder == bindings.encoder && host.source_index == bindings.source_index;
    host = bindings;
    prepared.store(true, std::memory_order_release);
    return true;
}
const Pixel17WireHookSpec* pixel17_wire_hook_specs(size_t* count) {
    if (count) *count = HookCount;
    return hooks;
}
bool pixel17_wire_ready() {
    if (!prepared.load(std::memory_order_acquire)) return false;
    for (auto trampoline : originals) if (!trampoline) return false;
    return true;
}

#ifdef ELD_PIXEL17_WIRE_TESTING
void pixel17_wire_reset_for_test() {
    std::lock_guard<std::mutex> lock(preparation);
    prepared.store(false);
    host = {};
    for (auto& trampoline : originals) trampoline = nullptr;
}
#endif
}
