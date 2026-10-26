// SPDX-License-Identifier: GPL-3.0-or-later
#include "hal_adapter.h"
#include "encoder_interface.h"
#include <algorithm>
#include <atomic>
#include <cstring>

namespace eld {
namespace {
struct Bindings {
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
Bindings bindings{};
std::atomic<bool> prepared{false};
std::atomic<bool> reported_failure{false};
std::atomic<HalAdapterFailure> last_failure{HalAdapterFailure::None};
void* original_setup = nullptr;

void* provider(void** storage) {
    void* value = nullptr;
    std::memcpy(&value, storage, sizeof(value));
    return value;
}
bool software_ready() {
    if (!prepared.load() || bindings.transport() != 3 || !bindings.hal_enabled() ||
        bindings.hal_offloading()) return false;
    const auto active = provider(bindings.active);
    return active && active == provider(bindings.software);
}
bool failed(HalAdapterFailure reason) {
    last_failure.store(reason);
    if (!reported_failure.exchange(true)) encoder_fail_route();
    return false;
}
struct AudioConfiguration {
    // HIDL's own C1/D1/pcmConfig establish and destroy the platform object in this storage.
    // The pinned setup function allocates 32 bytes, aligned to at least 8.
    alignas(8) uint8_t storage[32]{};
    AudioConfiguration() { bindings.construct(storage); }
    ~AudioConfiguration() { bindings.destroy(storage); }
};
bool setup() {
    const auto stock = reinterpret_cast<bool (*)()>(original_setup);
    if (!prepared.load()) return stock && stock();
    void* const codec = bindings.current_codec();
    uint8_t info[kNote8CodecStorage]{};
    if (!note8_codec_info(codec, info, sizeof(info))) return stock && stock();
    if (reported_failure.load()) return false;
    if (note8_is_quarantined()) return failed(HalAdapterFailure::Quarantined);
    if (bindings.transport() != 3) return failed(HalAdapterFailure::UnsupportedTransport);
    if (!software_ready()) return failed(HalAdapterFailure::SoftwareUnavailable);
    void* const active = provider(bindings.active);
    const Note8CodecConfig input{kNote8EldSlot, 0, 2, 1, 2, 0, {0, 0, 0, 0}};
    const HalPcmParameters pcm{bindings.sample_rate(&input), bindings.channels(&input),
                               bindings.bits(&input), 0};
    if (pcm.sample_rate != 2 || pcm.channel_mode != 2 || pcm.bits_per_sample != 1)
        return failed(HalAdapterFailure::InvalidPcm);
    AudioConfiguration audio;
    bindings.set_pcm(audio.storage, &pcm);
    if (!software_ready() || provider(bindings.active) != active || bindings.current_codec() != codec)
        return failed(HalAdapterFailure::ProviderChanged);
    // This updates the real software provider with PCM only. No encoded codec is fabricated.
    const bool accepted = bindings.update(active, audio.storage);
    if (!software_ready() || provider(bindings.active) != active || bindings.current_codec() != codec)
        return failed(HalAdapterFailure::ProviderChanged);
    if (!accepted) return failed(HalAdapterFailure::UpdateRejected);
    last_failure.store(HalAdapterFailure::None);
    return true;
}
const Note8HookSpec hooks[] = {
#include "hal_adapter_hook_specs.inc"
};
const HalAdapterReadSpec reads[] = {
#include "hal_adapter_read_pins.inc"
};
bool prepare(const Bindings& value) {
    if (prepared.load() || !value.current_codec || !value.transport || !value.hal_enabled ||
        !value.hal_offloading || !value.sample_rate || !value.bits || !value.channels ||
        !value.construct || !value.destroy || !value.set_pcm || !value.update ||
        !value.active || !value.software) return false;
    bindings = value;
    prepared.store(true);
    return true;
}
}
const Note8HookSpec* hal_adapter_hook_specs(size_t* count) {
    if (count) *count = sizeof(hooks) / sizeof(hooks[0]);
    return hooks;
}
const HalAdapterReadSpec* hal_adapter_read_specs(size_t* count) {
    if (count) *count = sizeof(reads) / sizeof(reads[0]);
    return reads;
}
bool hal_adapter_prepare(uintptr_t image_base) {
    if (!image_base || prepared.load() || !note8_is_prepared()) return false;
    for (const auto& pin : reads)
        if (std::memcmp(reinterpret_cast<const void*>(image_base + pin.elf_va), pin.prologue,
                        std::min(pin.function_size, sizeof(pin.prologue))) != 0) return false;
    return prepare({
        reinterpret_cast<void* (*)()>(image_base + 0x39c8d4),
        reinterpret_cast<uint8_t (*)()>(image_base + 0x68c678),
        reinterpret_cast<bool (*)()>(image_base + 0x657afc),
        reinterpret_cast<bool (*)()>(image_base + 0x657b10),
        reinterpret_cast<uint32_t (*)(const Note8CodecConfig*)>(image_base + 0x65b5fc),
        reinterpret_cast<uint8_t (*)(const Note8CodecConfig*)>(image_base + 0x65b638),
        reinterpret_cast<uint8_t (*)(const Note8CodecConfig*)>(image_base + 0x65b65c),
        reinterpret_cast<void (*)(void*)>(image_base + 0xc6cd00),
        reinterpret_cast<void (*)(void*)>(image_base + 0xc6cd40),
        reinterpret_cast<void (*)(void*, const HalPcmParameters*)>(image_base + 0xc6ce00),
        reinterpret_cast<bool (*)(void*, const void*)>(image_base + 0x66155c),
        reinterpret_cast<void**>(image_base + kHalHidlActiveStorage),
        reinterpret_cast<void**>(image_base + kHalHidlSoftwareStorage)});
}
bool hal_adapter_software_ready() { return software_ready(); }
HalAdapterFailure hal_adapter_last_failure() { return last_failure.load(); }
#ifdef ELD_ADAPTER_TESTING
bool hal_adapter_prepare_for_test(const HalAdapterTestBindings& value) {
    return prepare({value.current_codec, value.transport, value.hal_enabled, value.hal_offloading,
                    value.sample_rate, value.bits, value.channels, value.construct, value.destroy,
                    value.set_pcm, value.update, value.active, value.software});
}
void hal_adapter_reset_for_test() {
    prepared.store(false);
    reported_failure.store(false);
    last_failure.store(HalAdapterFailure::None);
    bindings = {};
    original_setup = nullptr;
}
#endif
}
