// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cstddef>
#include <cstdint>

namespace eld {
inline constexpr int32_t kNote8EldSlot = 5;
inline constexpr size_t kNote8CodecStorage = 20;
inline constexpr char kNote8BluetoothSha256[] =
    "d965f16cba9b9f103d657844eae618ac13b915581da36a4d7720995229e114a7";
inline constexpr char kNote8BluetoothBuildId[] = "1bfdd3e2638b93ad5d86f78d3981c5f6";

struct Note8CodecConfig {
    int32_t codec_type;
    int32_t codec_priority;
    uint32_t sample_rate;
    uint32_t bits_per_sample;
    uint32_t channel_mode;
    uint32_t padding;
    int64_t codec_specific[4];
};
static_assert(sizeof(Note8CodecConfig) == 56);
static_assert(offsetof(Note8CodecConfig, codec_specific) == 24);

struct Note8PeerParams {
    bool is_peer_edr;
    bool peer_supports_3mbps;
    uint16_t peer_mtu;
};
static_assert(sizeof(Note8PeerParams) == 4);

struct Note8HookSpec {
    const char* symbol;
    uintptr_t elf_va;
    size_t function_size;
    uint8_t prologue[32];
    void* replacement;
    void** original;
};
using Note8MutexFn = void (*)(void*);

// The caller checks the complete image identity and mapping bounds before this call.
bool note8_prepare(uintptr_t image_base, Note8MutexFn lock, Note8MutexFn unlock);
const Note8HookSpec* note8_hook_specs(size_t* count);
bool note8_set_enabled(bool enabled);
void note8_quarantine();
bool note8_is_quarantined();
bool note8_is_prepared();
size_t note8_active_objects();

// These accept only objects created by this adapter, never arbitrary C++ objects.
bool note8_codec_info(const void* codec, uint8_t* out, size_t size);
bool note8_peer_params(const void* opaque, Note8PeerParams* out);

#ifdef ELD_ADAPTER_TESTING
bool note8_prepare_for_test(const uintptr_t* aac_vtable, Note8MutexFn lock,
                            Note8MutexFn unlock);
bool note8_owns_for_test(const void* object);
void note8_reset_for_test();
#endif
}
