// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "note8_adapter.h"
#include <cstddef>
#include <cstdint>

namespace eld {
enum class GameHostBlock : uint8_t {
    None, NotPrepared, NoPeer, NotStreaming, MicrophoneBusy, MissingDelay,
    ControllerFlushUnavailable
};
struct GameHostPeerAddress { uint8_t bytes[6]; };
static_assert(sizeof(GameHostPeerAddress) == 6 && alignof(GameHostPeerAddress) == 1);
struct GameHostState {
    uint64_t generation = 0;
    uint64_t stream_started_ns = 0;
    uint64_t delay_received_ns = 0;
    uint64_t delay_serial = 0;
    uint64_t sco_checked_ns = 0;
    uint8_t peer[6]{};
    uint16_t delay_tenths = 0;
    uint8_t avdt_handle = 0;
    bool peer_valid = false;
    bool stream_active = false;
    bool microphone_active = false;
    bool native_sco_active = true;
    bool delay_valid = false;
    bool controller_flush_supported = false;
    GameHostBlock block = GameHostBlock::NotPrepared;
};

// Android times use CLOCK_BOOTTIME, matching SystemClock.elapsedRealtimeNanos().

bool game_host_prepare(uintptr_t image_base);
const Note8HookSpec* game_host_hook_specs(size_t* count);
// The authenticated app/Bluetooth bridge owns this generation; zero is never a session.
bool game_host_set_peer(const uint8_t* peer, size_t size, uint64_t generation,
                        bool connected, bool microphone_active);
GameHostState game_host_state();
// Read on the native encoder's initialization path; no Java-selected peer is substituted.
bool game_host_current_peer(uint8_t* out, size_t size);

#ifdef ELD_ADAPTER_TESTING
bool game_host_prepare_for_test(bool (*streaming)(), uint8_t (*sco_count)(),
                                bool (*peer_sco)(const void*), uint64_t (*now_ns)(),
                                GameHostPeerAddress (*active_peer)() = nullptr);
void game_host_reset_for_test();
#endif
}
