// SPDX-License-Identifier: GPL-3.0-or-later
#include "hal_adapter.h"
#include "wire.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>

namespace {
using namespace eld;
int codec_object = 0, software_object = 0, hardware_object = 0;
void* active = nullptr;
void* software = nullptr;
bool owned = false, quarantined = false, hal_enabled = true, offloading = false;
bool accepted = true, invalid_pcm = false, change_in_setter = false, change_in_update = false;
uint8_t transport = 3;
size_t failures = 0, stock_calls = 0, constructed = 0, destroyed = 0, updates = 0;
HalPcmParameters last_pcm{};
void check(bool value) {
    if (!value) { std::fputs("HAL adapter check failed\n", stderr); std::abort(); }
}
void* current_codec() { return &codec_object; }
uint8_t get_transport() { return transport; }
bool enabled() { return hal_enabled; }
bool is_offloading() { return offloading; }
uint32_t rate(const Note8CodecConfig* value) {
    check(value->sample_rate == 2 && value->codec_type == 5);
    return invalid_pcm ? 0 : 2;
}
uint8_t bits(const Note8CodecConfig* value) { check(value->bits_per_sample == 1); return 1; }
uint8_t channels(const Note8CodecConfig* value) { check(value->channel_mode == 2); return 2; }
void construct(void* memory) { ++constructed; std::memset(memory, 0xa5, 32); }
void destroy(void* memory) {
    check(static_cast<const uint8_t*>(memory)[31] == 0xa5);
    ++destroyed;
}
void set_pcm(void* memory, const HalPcmParameters* pcm) {
    check(static_cast<const uint8_t*>(memory)[31] == 0xa5);
    last_pcm = *pcm;
    std::memcpy(memory, pcm, sizeof(*pcm));
    if (change_in_setter) active = &hardware_object;
}
bool update(void* provider, const void* memory) {
    ++updates;
    check(provider == &software_object);
    check(std::memcmp(memory, &last_pcm, sizeof(last_pcm)) == 0);
    if (change_in_update) active = &hardware_object;
    return accepted;
}
bool stock() { ++stock_calls; return true; }
bool (*setup)() = nullptr;
HalAdapterTestBindings test_bindings() {
    return {&current_codec, &get_transport, &enabled, &is_offloading, &rate, &bits, &channels,
            &construct, &destroy, &set_pcm, &update, &active, &software};
}
void reset(bool providers_ready = true) {
    hal_adapter_reset_for_test();
    owned = quarantined = offloading = invalid_pcm = change_in_setter = change_in_update = false;
    hal_enabled = accepted = true;
    transport = 3;
    failures = stock_calls = constructed = destroyed = updates = 0;
    active = software = providers_ready ? &software_object : nullptr;
    size_t count;
    const auto specs = hal_adapter_hook_specs(&count);
    check(count == 1 && specs[0].elf_va == 0x6578d0 && specs[0].function_size == 52);
    *specs[0].original = reinterpret_cast<void*>(&stock);
    setup = reinterpret_cast<bool (*)()>(specs[0].replacement);
    check(setup() && stock_calls == 1);
    check(hal_adapter_prepare_for_test(test_bindings()));
    stock_calls = 0;
}
void startup_and_stock() {
    reset(false);
    check(!hal_adapter_software_ready() && failures == 0);
    active = software = &software_object;
    check(hal_adapter_software_ready());
    check(setup() && stock_calls == 1 && constructed == 0 && failures == 0);
    transport = 2;
    check(setup() && stock_calls == 2 && constructed == 0 && failures == 0);
}
void software_pcm_only() {
    reset(); owned = true;
    check(setup());
    check(last_pcm.sample_rate == 2 && last_pcm.channel_mode == 2 && last_pcm.bits_per_sample == 1);
    check(last_pcm.padding == 0);
    check(stock_calls == 0 && constructed == 1 && destroyed == 1 && updates == 1 && failures == 0);
    check(hal_adapter_last_failure() == HalAdapterFailure::None);
}
void unsupported_and_hardware_fail_closed() {
    reset(); owned = true; transport = 2;
    check(!setup() && hal_adapter_last_failure() == HalAdapterFailure::UnsupportedTransport);
    check(failures == 1 && stock_calls == 0 && updates == 0 && constructed == 0);
    transport = 3;
    check(!setup() && failures == 1); // A failed route cannot silently retry before quarantine arrives.
    reset(); owned = true; active = &hardware_object; offloading = true;
    check(!setup() && hal_adapter_last_failure() == HalAdapterFailure::SoftwareUnavailable);
    check(active == &hardware_object && offloading && failures == 1 && constructed == 0);
    reset(false); owned = true;
    check(!setup() && hal_adapter_last_failure() == HalAdapterFailure::SoftwareUnavailable);
}
void provider_failure_and_cleanup() {
    reset(); owned = true; invalid_pcm = true;
    check(!setup() && hal_adapter_last_failure() == HalAdapterFailure::InvalidPcm);
    check(constructed == 0 && failures == 1);
    reset(); owned = true; accepted = false;
    check(!setup() && hal_adapter_last_failure() == HalAdapterFailure::UpdateRejected);
    check(constructed == 1 && destroyed == 1 && updates == 1 && failures == 1);
    reset(); owned = true; change_in_setter = true;
    check(!setup() && hal_adapter_last_failure() == HalAdapterFailure::ProviderChanged);
    check(constructed == 1 && destroyed == 1 && updates == 0 && failures == 1);
    reset(); owned = true; change_in_update = true;
    check(!setup() && hal_adapter_last_failure() == HalAdapterFailure::ProviderChanged);
    check(constructed == 1 && destroyed == 1 && updates == 1 && failures == 1);
    reset(); owned = true; quarantined = true;
    check(!setup() && hal_adapter_last_failure() == HalAdapterFailure::Quarantined);
    check(constructed == 0 && updates == 0 && failures == 1);
}
}
namespace eld {
bool note8_is_prepared() { return true; }
bool note8_is_quarantined() { return quarantined; }
bool note8_codec_info(const void* codec, uint8_t* out, size_t size) {
    return owned && codec == &codec_object && make_configuration(out, size);
}
void encoder_fail_route() { ++failures; }
}
int main() {
    startup_and_stock();
    software_pcm_only();
    unsupported_and_hardware_fail_closed();
    provider_failure_and_cleanup();
    std::puts("HAL adapter checks passed: deferred startup, stock forwarding, typed software PCM, transport/offload rejection, provider failure cleanup.");
}
