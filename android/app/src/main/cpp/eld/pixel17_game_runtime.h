// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "pixel17_hci.h"
#include "pixel17_game_host.h"
#include "encoder_interface.h"

namespace eld {
enum class Pixel17GameOperation : uint32_t { Read = 1, Roundtrip = 2, Prepare = 3 };
enum class Pixel17GamePhase : uint32_t {
    Unsupported, Idle, Refreshing, Reading, Writing, Verifying,
    Restoring, VerifyingRestore, Complete, Failed, Quarantined, Holding
};
struct Pixel17GameStatus {
    uint32_t version = 1;
    Pixel17GamePhase phase = Pixel17GamePhase::Unsupported;
    Pixel17GameOperation operation = Pixel17GameOperation::Read;
    uint64_t serial = 0, lease_owner = 0;
    Pixel17HciOwner owner{};
    Pixel17GameHostState host{};
    Pixel17HciStatus error = Pixel17HciStatus::Ok;
    uint64_t deadline_ns = 0;
    uint16_t baseline_slots = 0, current_slots = 0;
    uint16_t last_opcode = 0, completed_handle = 0xffff;
    uint8_t controller_status = 0xff;
    bool supported = false, busy = false, baseline_known = false;
    bool current_known = false, restore_pending = false, cancelled = false;
    // A verified96 hold is distinct from an in-flight HCI operation. Only an
    // authenticated heartbeat for this exact lease+serial renews the3s grace.
    bool holding = false, clock_pending = false;
    uint64_t lease_deadline_ns = 0, clock_request_serial = 0;
    uint64_t clock_observed_ns = 0;
    EncoderClockStatus clock{};
};
// Prepare once, after every native function/data context and host layout has
// passed admission, before installing observer hooks. Failure affects Game only.
bool pixel17_game_runtime_prepare(Pixel17MainBindings main, Pixel17HciBindings hci);
bool pixel17_game_refresh();
// Returns a new monotonic native serial, or0 on refusal. Querying status never
// reads platform state. All admission/command steps execute on native BT main.
uint64_t pixel17_game_submit(Pixel17GameOperation, uint64_t lease_owner,
                             const Pixel17HciOwner& expected, uint64_t deadline_boottime_ns);
// These never select a fresh peer/channel. Restore remains allowed after expiry
// only for its retained original baseline and exact native channel ownership.
bool pixel17_game_heartbeat(uint64_t lease_owner, uint64_t native_serial);
bool pixel17_game_restore(uint64_t lease_owner, uint64_t native_serial);
// Begin returns an asynchronous request token, not the encoder clock's serial.
// The snapshot links that token to actual clock serial/generation after main
// admission. Cancel also invalidates a queued begin, and never cancels another owner.
uint64_t pixel17_game_clock_begin(uint64_t lease_owner, uint64_t native_serial,
                                  const Pixel17HciOwner& expected,
                                  uint32_t interval_tenths, uint32_t drift_ms);
bool pixel17_game_clock_cancel(uint64_t lease_owner, uint64_t native_serial);
void pixel17_game_cancel_owner(uint64_t lease_owner);
Pixel17GameStatus pixel17_game_status();
// Called by validated host observers outside the host mutex. Cancellation only;
// same-channel restoration is dispatched to main by the independent watchdog.
void pixel17_game_invalidated(Pixel17GameInvalidation, const Pixel17GameHostState&) noexcept;
void pixel17_game_runtime_disable();
// Called only after an accepted, non-primer encoder callback and outside its
// mutex. It may schedule one bounded main-thread retry; it never adjusts cadence.
void pixel17_game_clock_opportunity();
}
