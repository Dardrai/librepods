// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cstddef>
#include <cstdint>

namespace eld {
#if defined(ELD_PRIVATE_WIDE_PCM) && ELD_PRIVATE_WIDE_PCM
inline constexpr uint32_t kPixel17PcmBits = 32, kPixel17PcmMask = 4;
#else
inline constexpr uint32_t kPixel17PcmBits = 16, kPixel17PcmMask = 1;
#endif
inline constexpr size_t kPixel17CodecObjectSize = 0x228;
inline constexpr size_t kPixel17CodecStorage = 45;
inline constexpr size_t kPixel17CodecVtableEntries = 9;
inline constexpr uint64_t kPixel17EldCodecId = 0x8001004cffULL;

enum class Pixel17NegotiationReject : uint8_t {
    None, Arguments, Quarantined, PeerCapabilities, PeerConfiguration, User, Audio, Count
};
struct Pixel17CodecHintSnapshot {
    int32_t type = 0, priority = 0;
    uint32_t rate = 0, bits = 0, channels = 0;
    uint8_t specific_nonzero = 0;
};
struct Pixel17NegotiationStatus {
    // Counters cover this owned codec object's lifetime, including feasibility checks.
    uint64_t config_calls = 0, peer_calls = 0;
    uint64_t rejected[static_cast<size_t>(Pixel17NegotiationReject::Count)]{};
    Pixel17NegotiationReject last_reason = Pixel17NegotiationReject::None;
    uint64_t last_boottime_ns = 0;
    bool last_peer_update = false, last_capability = false, hints_known = false;
    int32_t source_index = 0, source_priority = 0;
    Pixel17CodecHintSnapshot user{}, audio{};
    size_t peer_bytes = 0;
    uint8_t peer[kPixel17CodecStorage]{};
};

// The final eight bytes are not CodecId. Their meaning is unknown; preserve them on reads/copies.
struct Pixel17NativeConfig {
    int32_t codec_type;
    int32_t codec_priority;
    uint32_t sample_rate;
    uint32_t bits_per_sample;
    uint32_t channel_mode;
    uint32_t padding;
    int64_t codec_specific[4];
    uint64_t opaque_tail;
};
static_assert(sizeof(Pixel17NativeConfig) == 64);
static_assert(offsetof(Pixel17NativeConfig, codec_specific) == 24);
static_assert(offsetof(Pixel17NativeConfig, opaque_tail) == 56);

struct Pixel17PeerParams {
    bool is_peer_edr;
    bool peer_supports_3mbps;
    uint16_t peer_mtu;
};
static_assert(sizeof(Pixel17PeerParams) == 4);

// The caller validates all bindings and the complete object/vtable ABI before preparation.
// The original vtable address point must also have its two readable ABI metadata words.
struct Pixel17CodecBindings {
    void* (*allocate)(size_t);
    void (*release)(void*, size_t);
    void (*aac_source_construct)(void*, int32_t priority);
    const uintptr_t* aac_source_vtable;
    void (*lock)(void*);
    void (*unlock)(void*);
    void* (*assign_short)(void* host_string, const char*, size_t);
    // Diagnostics hold an object lease, without holding its codec or registry mutex.
    void (*debug)(const void* codec, int fd) = nullptr;
    // The first64 bytes retain their layout; newer native records have16 opaque trailing bytes.
    size_t config_size = 64;
};

bool pixel17_codec_prepare(const Pixel17CodecBindings& bindings);
// Registration, collision checks, priority policy and publication belong to the caller.
// Only the caller's independently validated local source index may be passed here.
void* pixel17_codec_create(int32_t source_index, int32_t priority);
bool pixel17_codec_owns(const void* codec);
size_t pixel17_codec_active_objects();
void pixel17_codec_quarantine();

bool pixel17_codec_info(const void* codec, uint8_t* out, size_t size);
bool pixel17_codec_get_config(const void* codec, Pixel17NativeConfig* out);
// Copies passive diagnostics without taking the host codec lock or invoking host callbacks.
Pixel17NegotiationStatus pixel17_codec_negotiation_status(const void* codec);
const char* pixel17_negotiation_reject_name(Pixel17NegotiationReject reason);
// Explicit-size entry points are also used by the native ABI callbacks with their proven 45-byte arrays.
// Status is an eight-bit A2DP result: zero succeeds; nonzero fails.
uint8_t pixel17_codec_set_config(void* codec, const uint8_t* peer, size_t peer_size,
                                bool capability, uint8_t* out, size_t out_size);
bool pixel17_codec_set_peer(void* codec, const uint8_t* peer, size_t peer_size);
bool pixel17_copy_config(const void* bytes, size_t size, Pixel17NativeConfig* out);
bool pixel17_peer_params(const void* bytes, size_t size, Pixel17PeerParams* out);
}
