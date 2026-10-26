// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "note8_adapter.h"
#include <cstddef>
#include <cstdint>

namespace eld {
struct HalAdapterReadSpec {
    const char* symbol;
    uintptr_t elf_va;
    size_t function_size;
    uint8_t prologue[32];
};
struct HalPcmParameters {
    uint32_t sample_rate;
    uint8_t channel_mode;
    uint8_t bits_per_sample;
    uint16_t padding;
};
static_assert(sizeof(HalPcmParameters) == 8);
static_assert(offsetof(HalPcmParameters, bits_per_sample) == 5);
enum class HalAdapterFailure : uint8_t {
    None, Quarantined, UnsupportedTransport, SoftwareUnavailable, InvalidPcm,
    ProviderChanged, UpdateRejected
};

bool hal_adapter_prepare(uintptr_t image_base);
bool hal_adapter_software_ready();
const Note8HookSpec* hal_adapter_hook_specs(size_t* count);
const HalAdapterReadSpec* hal_adapter_read_specs(size_t* count);
HalAdapterFailure hal_adapter_last_failure();
inline constexpr uintptr_t kHalHidlActiveStorage = 0xd29090;
inline constexpr uintptr_t kHalHidlSoftwareStorage = 0xd29098;

#ifdef ELD_ADAPTER_TESTING
struct HalAdapterTestBindings {
    void* (*current_codec)();
    uint8_t (*transport)();
    bool (*hal_enabled)();
    bool (*hal_offloading)();
    uint32_t (*sample_rate)(const Note8CodecConfig*);
    uint8_t (*bits)(const Note8CodecConfig*);
    uint8_t (*channels)(const Note8CodecConfig*);
    void (*construct)(void*);
    void (*destroy)(void*);
    void (*set_pcm)(void*, const HalPcmParameters*);
    bool (*update)(void*, const void*);
    void** active;
    void** software;
};
bool hal_adapter_prepare_for_test(const HalAdapterTestBindings& bindings);
void hal_adapter_reset_for_test();
#endif
}
