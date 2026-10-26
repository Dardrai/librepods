// SPDX-License-Identifier: GPL-3.0-or-later
#include "game_host.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>

namespace {
using namespace eld;
constexpr uint8_t first[] = {1, 2, 3, 4, 5, 6};
constexpr uint8_t second[] = {6, 5, 4, 3, 2, 1};
uint64_t now = 1000000000;
bool running = false, sco = false, switch_start = false, switch_delay = false;
bool rotate_peer = false, empty_peer = false;
size_t forwarded_start = 0, forwarded_stop = 0, forwarded_delay = 0, sco_reads = 0;
size_t peer_reads = 0;
void check(bool value) {
    if (!value) { std::fputs("game host check failed\n", stderr); std::abort(); }
}
bool streaming() { return running; }
uint8_t sco_count() { ++sco_reads; return sco ? 1 : 0; }
bool peer_sco(const void*) { ++sco_reads; return sco; }
uint64_t clock_ns() { return now; }
GameHostPeerAddress active_peer() {
    GameHostPeerAddress result{};
    const auto* source = rotate_peer && (++peer_reads % 2 == 0) ? second : first;
    if (!empty_peer) std::memcpy(result.bytes, source, 6);
    return result;
}
void host_start() {
    ++forwarded_start; running = true;
    if (switch_start) check(game_host_set_peer(second, 6, 11, true, false));
}
void host_stop() { ++forwarded_stop; running = false; }
void host_delay(uint8_t, const void*, uint16_t) {
    ++forwarded_delay;
    if (switch_delay) check(game_host_set_peer(first, 6, 12, true, false));
}
const Note8HookSpec* find(const char* name) {
    size_t count;
    const auto* specs = game_host_hook_specs(&count);
    for (size_t i = 0; i < count; ++i)
        if (std::strstr(specs[i].symbol, name)) return specs + i;
    std::abort();
}
template<class Fn> Fn hook(const char* name) { return reinterpret_cast<Fn>(find(name)->replacement); }
void prepare() {
    game_host_reset_for_test();
    running = sco = switch_start = switch_delay = rotate_peer = empty_peer = false;
    peer_reads = 0;
    check(game_host_state().block == GameHostBlock::NotPrepared);
    check(!game_host_prepare_for_test(nullptr, &sco_count, &peer_sco, &clock_ns));
    check(game_host_prepare_for_test(&streaming, &sco_count, &peer_sco, &clock_ns, &active_peer));
    size_t count;
    const auto* specs = game_host_hook_specs(&count);
    check(count == 3);
    for (size_t i = 0; i < count; ++i) {
        check(specs[i].function_size > 32);
        for (size_t j = 0; j < i; ++j) check(specs[i].elf_va != specs[j].elf_va);
    }
    *find("audio_delay")->original = reinterpret_cast<void*>(&host_delay);
    *find("start_event")->original = reinterpret_cast<void*>(&host_start);
    *find("stop_event")->original = reinterpret_cast<void*>(&host_stop);
    check(game_host_state().block == GameHostBlock::NoPeer);
}
using DelayFn = void (*)(uint8_t, const void*, uint16_t);
void source_and_delay() {
    prepare();
    check(!game_host_set_peer(first, 6, 0, true, false));
    check(!game_host_set_peer(first, 5, 10, true, false));
    uint8_t invalid[6]{};
    check(!game_host_set_peer(invalid, 6, 10, true, false));
    check(game_host_set_peer(first, 6, 10, true, false));
    check(game_host_state().block == GameHostBlock::NotStreaming);
    const auto start = hook<void (*)()>("start_event");
    const auto delay = hook<DelayFn>("audio_delay");
    now = 2000000000;
    start();
    auto state = game_host_state();
    check(state.stream_active && state.stream_started_ns == now);
    check(state.block == GameHostBlock::MissingDelay);
    delay(4, second, 1500);
    check(game_host_state().delay_serial == 0);
    now = 3000000000;
    delay(4, first, 1500);
    state = game_host_state();
    check(state.delay_serial == 1 && state.delay_tenths == 1500 && state.avdt_handle == 4);
    check(state.delay_received_ns == now && state.sco_checked_ns == now);
    check(!state.controller_flush_supported && state.block == GameHostBlock::ControllerFlushUnavailable);
    const auto reads = sco_reads;
    game_host_state(); game_host_state();
    check(sco_reads == reads); // JNI polling never touches native stack-owned SCO data.
    now = 4000000000;
    start();
    check(game_host_state().stream_started_ns == 2000000000);
    check(game_host_set_peer(first, 6, 10, true, true));
    check(game_host_state().block == GameHostBlock::MicrophoneBusy);
    check(game_host_set_peer(first, 6, 10, true, false));
    sco = true;
    delay(4, first, 1500);
    check(game_host_state().block == GameHostBlock::MicrophoneBusy);
    hook<void (*)()>("stop_event")();
    state = game_host_state();
    check(!state.stream_active && !state.stream_started_ns && !state.delay_valid);
    check(forwarded_start >= 2 && forwarded_stop >= 1 && forwarded_delay >= 3);
}
void generation_and_interrupted_callbacks() {
    prepare();
    const auto start = hook<void (*)()>("start_event");
    const auto delay = hook<DelayFn>("audio_delay");
    check(game_host_set_peer(first, 6, 10, true, false));
    switch_start = true;
    start();
    auto state = game_host_state();
    check(state.generation == 11 && !state.stream_active && state.stream_started_ns == 0);
    check(std::memcmp(state.peer, second, 6) == 0);
    check(!game_host_set_peer(first, 6, 10, true, false));
    check(!game_host_set_peer(first, 6, 11, true, false));
    switch_start = false;
    check(game_host_set_peer(first, 6, 12, true, false));
    check(game_host_set_peer(first, 6, 12, false, false));
    check(!game_host_set_peer(first, 6, 12, true, false));
    check(game_host_set_peer(first, 6, 13, true, false));
    // Use the same address across a reconnect: an old report cannot become the new session's report.
    switch_delay = true;
    // The host callback chooses generation12; use a fresh low-numbered setup for this control.
    game_host_reset_for_test();
    check(game_host_prepare_for_test(&streaming, &sco_count, &peer_sco, &clock_ns));
    *find("audio_delay")->original = reinterpret_cast<void*>(&host_delay);
    check(game_host_set_peer(first, 6, 11, true, false));
    delay(4, first, 1500);
    state = game_host_state();
    check(state.generation == 12 && !state.delay_valid && state.delay_serial == 0);
}
void actual_peer_is_not_the_java_selection() {
    prepare();
    check(game_host_set_peer(second, 6, 20, true, false));
    uint8_t address[6]{};
    check(game_host_current_peer(address, sizeof(address)));
    check(std::memcmp(address, first, 6) == 0); // Native peer wins over the Java-selected second pair.
    hook<void (*)()>("start_event")();
    check(!game_host_state().stream_active && game_host_state().stream_started_ns == 0);
    check(!game_host_current_peer(address, 5));
    rotate_peer = true;
    std::memset(address, 0x5a, sizeof(address));
    check(!game_host_current_peer(address, sizeof(address)) && address[0] == 0x5a);
    rotate_peer = false; empty_peer = true;
    check(!game_host_current_peer(address, sizeof(address)) && address[0] == 0x5a);
}
}
int main() {
    source_and_delay();
    generation_and_interrupted_callbacks();
    actual_peer_is_not_the_java_selection();
    std::puts("Game host checks passed: stock forwarding, native timing, peer ownership, stale generations, SCO/mic gates, native peer readback, explicit flush limitation.");
}
