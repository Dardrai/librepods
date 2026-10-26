// SPDX-License-Identifier: GPL-3.0-or-later
#include "game_host.h"
#include <array>
#include <atomic>
#include <chrono>
#include <cstring>
#include <mutex>
#ifdef __ANDROID__
#include <time.h>
#endif

namespace eld {
namespace {
enum Hook : size_t { Delay, Start, Stop, HookCount };
void* originals[HookCount]{};
std::atomic<bool> prepared{false};
std::mutex state_mutex;
GameHostState state;
bool (*is_streaming)() = nullptr;
uint8_t (*sco_links)() = nullptr;
bool (*peer_sco)(const void*) = nullptr;
GameHostPeerAddress (*source_peer)() = nullptr;
uint64_t monotonic_ns() {
#ifdef __ANDROID__
    timespec value{};
    if (clock_gettime(CLOCK_BOOTTIME, &value) != 0) return 0;
    return static_cast<uint64_t>(value.tv_sec) * 1000000000ULL + value.tv_nsec;
#else
    return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count());
#endif
}
uint64_t (*clock_ns)() = &monotonic_ns;
bool equal_peer(const uint8_t* left, const uint8_t* right) {
    return left && right && std::memcmp(left, right, 6) == 0;
}
bool valid_peer(const uint8_t* peer, size_t size) {
    if (!peer || size != 6) return false;
    uint8_t any = 0, all = 0xff;
    for (size_t i = 0; i < 6; ++i) { any |= peer[i]; all &= peer[i]; }
    return any != 0 && all != 0xff;
}
GameHostState snapshot() {
    std::lock_guard<std::mutex> guard(state_mutex);
    return state;
}
bool same_owner(const GameHostState& previous) {
    return state.peer_valid && previous.peer_valid && state.generation == previous.generation &&
           equal_peer(state.peer, previous.peer);
}
bool native_sco(const GameHostState& owner) {
    // These read helpers run on native Bluetooth event callbacks, never on a JNI polling thread.
    return sco_links() != 0 || peer_sco(owner.peer);
}
void delay(uint8_t handle, const void* address, uint16_t tenths) {
    const auto owner = snapshot();
    const auto host = reinterpret_cast<void (*)(uint8_t, const void*, uint16_t)>(originals[Delay]);
    if (host) host(handle, address, tenths);
    if (!prepared.load() || !address) return;
    if (!owner.peer_valid || !equal_peer(owner.peer, static_cast<const uint8_t*>(address))) return;
    const auto now = clock_ns();
    const bool sco = native_sco(owner);
    const bool running = is_streaming();
    uint8_t active_peer[6]{};
    const bool matching_peer = game_host_current_peer(active_peer, sizeof(active_peer)) &&
                               equal_peer(active_peer, owner.peer);
    std::lock_guard<std::mutex> guard(state_mutex);
    if (!same_owner(owner)) return;
    state.avdt_handle = handle;
    state.delay_tenths = tenths;
    state.delay_valid = tenths != 0;
    state.delay_received_ns = now;
    ++state.delay_serial;
    state.native_sco_active = sco;
    state.sco_checked_ns = now;
    // A Delay Report establishes the peer, but cannot invent a fresh Start acceptance time.
    if (!running || !matching_peer) {
        state.stream_active = false;
        state.stream_started_ns = 0;
    }
}
void start() {
    const auto owner = snapshot();
    const auto host = reinterpret_cast<void (*)()>(originals[Start]);
    if (host) host();
    if (!prepared.load() || !owner.peer_valid) return;
    uint8_t active_peer[6]{};
    const bool running = is_streaming() && game_host_current_peer(active_peer, sizeof(active_peer)) &&
                         equal_peer(active_peer, owner.peer);
    const bool sco = native_sco(owner);
    const auto now = clock_ns();
    std::lock_guard<std::mutex> guard(state_mutex);
    if (!same_owner(owner)) return;
    if (running && !state.stream_active) state.stream_started_ns = now;
    if (!running) state.stream_started_ns = 0;
    state.stream_active = running;
    state.native_sco_active = sco;
    state.sco_checked_ns = now;
}
void stop() {
    const auto owner = snapshot();
    const auto host = reinterpret_cast<void (*)()>(originals[Stop]);
    if (host) host();
    if (!prepared.load() || !owner.peer_valid) return;
    const bool sco = native_sco(owner);
    const auto now = clock_ns();
    std::lock_guard<std::mutex> guard(state_mutex);
    if (!same_owner(owner)) return;
    state.stream_active = false;
    state.stream_started_ns = 0;
    state.delay_valid = false;
    state.native_sco_active = sco;
    state.sco_checked_ns = now;
}
const Note8HookSpec hooks[] = {
#include "game_host_hook_specs.inc"
};
static_assert(sizeof(hooks) / sizeof(hooks[0]) == HookCount);

bool prepare(bool (*streaming)(), uint8_t (*count)(), bool (*peer)(const void*),
             uint64_t (*now)(), GameHostPeerAddress (*active_peer)()) {
    if (!streaming || !count || !peer || !now) return false;
    std::lock_guard<std::mutex> guard(state_mutex);
    if (prepared.load()) return false;
    is_streaming = streaming;
    sco_links = count;
    peer_sco = peer;
    clock_ns = now;
    source_peer = active_peer;
    prepared.store(true);
    return true;
}
}
const Note8HookSpec* game_host_hook_specs(size_t* count) {
    if (count) *count = HookCount;
    return hooks;
}
bool game_host_prepare(uintptr_t image_base) {
    if (!image_base || prepared.load()) return false;
    struct Pin { uintptr_t va; size_t size; uint8_t bytes[32]; };
    const Pin pins[] = {
#include "game_host_read_pins.inc"
    };
    for (const auto& pin : pins)
        if (std::memcmp(reinterpret_cast<const void*>(image_base + pin.va), pin.bytes, pin.size) != 0)
            return false;
    return prepare(reinterpret_cast<bool (*)()>(image_base + pins[0].va),
                   reinterpret_cast<uint8_t (*)()>(image_base + pins[1].va),
                   reinterpret_cast<bool (*)(const void*)>(image_base + pins[2].va), &monotonic_ns,
                   reinterpret_cast<GameHostPeerAddress (*)()>(image_base + pins[3].va));
}
bool game_host_set_peer(const uint8_t* peer, size_t size, uint64_t generation,
                        bool connected, bool microphone_active) {
    if (!prepared.load() || !generation || (connected && !valid_peer(peer, size))) return false;
    std::lock_guard<std::mutex> guard(state_mutex);
    if (generation < state.generation) return false;
    if (generation == state.generation && connected &&
        (!state.peer_valid || !equal_peer(peer, state.peer))) return false;
    if (generation != state.generation || !connected) {
        state = {};
        state.generation = generation;
    }
    if (connected) {
        std::memcpy(state.peer, peer, 6);
        state.peer_valid = true;
    }
    state.microphone_active = microphone_active;
    return true;
}
GameHostState game_host_state() {
    auto result = snapshot();
    if (!prepared.load()) result.block = GameHostBlock::NotPrepared;
    else if (!result.peer_valid) result.block = GameHostBlock::NoPeer;
    else if (!result.stream_active || !result.stream_started_ns) result.block = GameHostBlock::NotStreaming;
    else if (result.microphone_active || result.native_sco_active) result.block = GameHostBlock::MicrophoneBusy;
    else if (!result.delay_valid) result.block = GameHostBlock::MissingDelay;
    else result.block = GameHostBlock::ControllerFlushUnavailable;
    return result;
}
bool game_host_current_peer(uint8_t* out, size_t size) {
    if (!prepared.load() || !source_peer || !out || size != 6) return false;
    // The pinned getter returns a trivial six-byte RawAddress in x0, not a C++ reference.
    const auto first = source_peer();
    const auto second = source_peer();
    if (!valid_peer(first.bytes, 6) || !equal_peer(first.bytes, second.bytes)) return false;
    std::memcpy(out, first.bytes, 6);
    return true;
}
#ifdef ELD_ADAPTER_TESTING
bool game_host_prepare_for_test(bool (*streaming)(), uint8_t (*count)(),
                                bool (*peer)(const void*), uint64_t (*now)(),
                                GameHostPeerAddress (*active_peer)()) {
    return prepare(streaming, count, peer, now, active_peer);
}
void game_host_reset_for_test() {
    std::lock_guard<std::mutex> guard(state_mutex);
    prepared.store(false);
    state = {};
    is_streaming = nullptr;
    sco_links = nullptr;
    peer_sco = nullptr;
    source_peer = nullptr;
    clock_ns = &monotonic_ns;
    for (auto& host : originals) host = nullptr;
}
#endif
}
