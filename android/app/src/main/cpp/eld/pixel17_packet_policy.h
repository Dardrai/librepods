// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "pixel17_hci.h"
#include "pixel17_wire_hooks.h"
#include <array>
#include <mutex>

namespace eld {
inline constexpr char kPixel17PacketCommand[] = "_ZNK9bluetooth6legacy3hci13InterfaceImpl26ChangeConnectionPacketTypeEtt";
inline constexpr char kPixel17PacketEvent[] = "_ZN9bluetooth3hci8HciLayer4impl12on_hci_eventENS0_9EventViewE";
inline constexpr char kPixel17PacketEventRef[] = "_ZN9bluetooth3hci8HciLayer4impl12on_hci_eventERKNS0_9EventViewE";
struct Pixel17PacketStatus {
    uint16_t baseline = 0, current = 0;
    bool known = false, pending = false, dirty = false, ready = false;
    Pixel17HciStatus error = Pixel17HciStatus::Ok;
};
// Only scalar event data crosses from GD to BT main. A missing event retains the
// physical command fence; a timer cannot make a late event belong to another ACL.
class Pixel17PacketPolicy {
public:
    void command(uint16_t handle, uint16_t mask, bool owned);
    void event(const uint8_t*, size_t);
    bool begin(const Pixel17HciOwner&, uint64_t deadline_us, uint64_t now_us,
               void (*send)(uint16_t, uint16_t));
    bool restore(const Pixel17HciOwner&, uint64_t deadline_us, uint64_t now_us,
                 void (*send)(uint16_t, uint16_t));
    Pixel17PacketStatus status(uint64_t now_us);
private:
    struct Link {
        uint8_t peer[6]{};
        uint16_t handle = 0xffff, mask = 0;
        uint64_t epoch = 0;
        bool known = false;
    };
    std::mutex mutex_;
    std::array<Link, 16> links_{};
    uint64_t next_epoch_ = 0, epoch_ = 0, deadline_ = 0;
    Pixel17HciOwner owner_{};
    Pixel17PacketStatus status_{};
    uint16_t pending_handle_ = 0xffff, requested_ = 0;
    bool physical_ = false, owned_ = false, accepted_ = false, armed_ = false, poisoned_ = false;
    Link* link(uint16_t);
    bool issue(uint16_t mask, uint64_t deadline, void (*send)(uint16_t, uint16_t),
               std::unique_lock<std::mutex>&);
};
struct Pixel17PacketBindings {
    size_t (*view_size)(const void*) = nullptr;
    uint8_t (*view_byte)(const void*, size_t) = nullptr;
    void (*send)(uint16_t, uint16_t) = nullptr;
    void (*changed)() = nullptr;
    const char* event_name = nullptr;
};
struct Pixel17PacketSymbols {
    ImageSymbol command, event, allocate, enqueue;
    const char* event_name = nullptr;
};
bool pixel17_packet_contract(const SymbolImage&, Pixel17PacketSymbols*);
bool pixel17_packet_prepare(Pixel17PacketBindings);
const Pixel17WireHookSpec* pixel17_packet_hook_specs(size_t*);
Pixel17PacketPolicy& pixel17_packet_policy();
void pixel17_packet_send(uint16_t, uint16_t);
}
