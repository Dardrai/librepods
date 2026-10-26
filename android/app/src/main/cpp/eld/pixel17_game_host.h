// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "pixel17_wire_hooks.h"
#include "symbol_image.h"
#include <cstddef>
#include <cstdint>

namespace eld {
inline constexpr char kPixel17GameOpen[] = "_Z17bta_av_str_openedP11tBTA_AV_SCBP12tBTA_AV_DATA";
inline constexpr char kPixel17GameStart[] = "_Z15bta_av_start_okP11tBTA_AV_SCBP12tBTA_AV_DATA";
inline constexpr char kPixel17GameStop[] = "_Z18bta_av_str_stoppedP11tBTA_AV_SCBP12tBTA_AV_DATA";
inline constexpr char kPixel17GameClose[] = "_Z21bta_av_co_audio_closehRK10RawAddress";
inline constexpr char kPixel17GameReconfigure[] = "_Z18bta_av_rcfg_str_okP11tBTA_AV_SCBP12tBTA_AV_DATA";
inline constexpr char kPixel17GameDelay[] = "_Z21bta_av_co_audio_delayhRK10RawAddresst";
inline constexpr char kPixel17GameSco[] = "_ZL20bta_av_sco_chg_cback20tBTA_SYS_CONN_STATUShhRK10RawAddress";
inline constexpr char kPixel17GameScb[] = "_Z18bta_av_hndl_to_scbt";
inline constexpr char kPixel17GameAvdtScb[] = "_Z15avdt_scb_by_hdlh";
inline constexpr char kPixel17GameCid[] = "_Z20AVDT_GetL2CapChannelh";
inline constexpr char kPixel17GameCidAcl[] = "_Z17L2CA_GetAclHandletPt";
inline constexpr char kPixel17GamePeerAcl[] = "_Z20BTM_GetHCIConnHandleRK10RawAddress13tBT_TRANSPORT";
inline constexpr char kPixel17GameScoCount[] = "_Z18BTM_GetNumScoLinksv";
inline constexpr char kPixel17GamePeerSco[] = "_Z23BTM_IsScoActiveByBdaddrRK10RawAddress";

struct Pixel17GameLayout {
    size_t media_cid = 0;
    size_t bta_handle = 0;
    size_t avdt_handle = 0;
    size_t co_started = 0;
    size_t peer = 0;
    size_t codec = 0;
    size_t avdt_reporting_mask = 0;
    size_t avdt_self_handle = 0;
};
// Every returned context must also match loaded executable memory before detouring.
bool pixel17_game_host_contract(const SymbolImage& image, Pixel17GameLayout* out);
const char* const* pixel17_game_host_context_names(size_t* count);

struct Pixel17GameEncoder {
    uint64_t generation = 0;
    uint8_t peer[6]{};
    bool active = false;
    bool failed = false;
};
struct Pixel17GameHostState {
    uint64_t media_generation = 0; // Native channel incarnation, never a Java connection generation.
    uint64_t encoder_generation = 0;
    uint64_t started_ns = 0; // CLOCK_BOOTTIME; only an accepted Start observation sets this.
    uint64_t delay_serial = 0;
    uint64_t delay_received_ns = 0;
    uint64_t observed_ns = 0;
    uint8_t peer[6]{};
    uint16_t media_cid = 0; // Local L2CAP media CID, not the peer's CID.
    uint16_t acl_handle = 0xffff;
    uint16_t delay_tenths = 0;
    uint8_t bta_handle = 0;
    uint8_t avdt_handle = 0; // Distinct from bta_handle and the on-wire SEP identifier.
    bool route_valid = false;
    bool streaming = false;
    bool encoder_ready = false;
    bool reporting = false;
    bool delay_valid = false;
    bool native_sco = true;
};
enum class Pixel17GameInvalidation : uint8_t {
    Stop, Close, Reconfigure, RouteChanged, EncoderChanged, Sco, InvalidRoute, WrongThread, Disabled
};
struct Pixel17GameBindings {
    // Platform readers execute only on the thread delivering the validated BTA callbacks.
    bool (*copy)(const void* source, size_t size, void* out) noexcept = nullptr;
    void* (*scb)(uint16_t bta_handle) noexcept = nullptr;
    void* (*avdt_scb)(uint8_t avdt_handle) noexcept = nullptr;
    uint16_t (*media_cid)(uint8_t avdt_handle) noexcept = nullptr;
    bool (*cid_acl)(uint16_t cid, uint16_t* handle) noexcept = nullptr;
    uint16_t (*peer_acl)(const void* peer, uint8_t transport) noexcept = nullptr;
    bool (*active_peer)(uint8_t* out, size_t size) noexcept = nullptr;
    uint8_t (*sco_count)() noexcept = nullptr;
    bool (*peer_sco)(const void* peer) noexcept = nullptr;
    bool (*encoder)(Pixel17GameEncoder* out) noexcept = nullptr;
    uint64_t (*now_ns)() noexcept = nullptr;
    // Called outside the snapshot mutex. Must be bounded; cancellation only, no waiting for a transition.
    void (*invalidated)(Pixel17GameInvalidation reason, const Pixel17GameHostState& previous) noexcept = nullptr;
};

bool pixel17_game_host_prepare(const Pixel17GameBindings& bindings, const Pixel17GameLayout& validated_layout);
const Pixel17WireHookSpec* pixel17_game_host_hook_specs(size_t* count);
// Dispatch this onto the same native Bluetooth event thread, not a JNI/Binder polling thread.
bool pixel17_game_host_refresh_on_event_thread();
// Pure thread-identity check for the dispatcher's HCI boundary; never latches an arbitrary caller.
bool pixel17_game_host_is_event_thread() noexcept;
// Pure immutable copy: no platform getters, host locks or callbacks are invoked here.
Pixel17GameHostState pixel17_game_host_snapshot();
// Safe from any thread; retires the current owner without calling platform readers.
void pixel17_game_host_disable();

#ifdef ELD_PIXEL17_GAME_HOST_TESTING
void pixel17_game_host_reset_for_test();
#endif
}
