// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cstddef>
#include <cstdint>
#include <memory>
#include "symbol_image.h"

namespace eld {
using Pixel17Task = void (*)(void*);
using Pixel17Destroy = void (*)(void*);
using Pixel17Now = uint64_t (*)(void*);

// Native libc++/base objects never cross this interface. callback_storage is one
// native OnceCallback pointer; construct initializes its 32-byte native BindState.
struct Pixel17MainBindings {
    void* main_thread = nullptr;
    void (*construct)(void*, void (*)(), void (*)(const void*)) = nullptr;
    bool (*post)(void* main_thread, void* callback_storage) = nullptr;
    void (*destroy_callback)(void* callback_storage) = nullptr;
    Pixel17Now now = nullptr;
    void* clock_context = nullptr;
};

class Pixel17MainDispatcher {
public:
    explicit Pixel17MainDispatcher(Pixel17MainBindings);
    ~Pixel17MainDispatcher();
    Pixel17MainDispatcher(const Pixel17MainDispatcher&) = delete;
    Pixel17MainDispatcher& operator=(const Pixel17MainDispatcher&) = delete;
    // Always consumes context. destroy is called exactly once, including failed
    // posts. Cancellation prevents a task not already running; it cannot retract
    // a running task. Native destruction may be delayed by the platform queue.
    uint64_t post(Pixel17Task, void* context, Pixel17Destroy,
                  uint64_t deadline_us = UINT64_MAX);
    bool cancel(uint64_t ticket);
    void poll();
    size_t outstanding() const;
    bool quarantined() const;
private:
    struct State;
    std::shared_ptr<State> state_;
};

struct Pixel17HciOwner {
    uint8_t peer[6]{};
    uint64_t media_generation = 0;
    uint64_t encoder_generation = 0;
    uint16_t acl_handle = 0xffff;
};
bool pixel17_same_hci_owner(const Pixel17HciOwner&, const Pixel17HciOwner&);

enum class Pixel17HciStatus : uint8_t {
    Ok, Malformed, ControllerError, StaleOwner, Timeout, Cancelled,
    DispatchFailed, UnexpectedStatus
};
struct Pixel17HciResult {
    Pixel17HciStatus status = Pixel17HciStatus::Malformed;
    uint64_t serial = 0;
    uint16_t opcode = 0;
    uint16_t handle = 0xffff;
    uint16_t slots = 0;
    uint8_t controller_status = 0xff;
    bool submitted = false;
};
using Pixel17HciComplete = void (*)(void*, const Pixel17HciResult&);

struct Pixel17HciBindings {
    void (*transmit)(const void* command,
                     void (*complete)(const void* borrowed_view, void*),
                     void (*status)(uint8_t, void* command, void*), void*) = nullptr;
    void* (*allocate)(size_t) = nullptr;
    void (*release)(void*) = nullptr;
    // These invoke the native PacketView members on the borrowed native view.
    // No view, container, iterator or backing fragment is retained by this module.
    size_t (*view_size)(const void*) = nullptr;
    uint8_t (*view_byte)(const void*, size_t) = nullptr;
    bool (*is_main)(void*) = nullptr;
    bool (*owner_current)(void*, const Pixel17HciOwner&) = nullptr;
    void* owner_context = nullptr;
    Pixel17Now now = nullptr;
    void* clock_context = nullptr;
    // Optional scalar wake signal, possibly on the native callback thread. No
    // platform reads here. Fired after reclaiming an already logically ended HCI
    // request, so its owner can resume same-channel cleanup without polling forever.
    void (*reclaimed)(void* owner_context) = nullptr;
};

// Fixed READ_AUTO_FLUSH_TIMEOUT / WRITE_AUTO_FLUSH_TIMEOUT only. All public
// operations below must run on the actual BT main thread (is_main enforces this).
// One physical command may be outstanding. Timeout/cancel does NOT free a context
// still held by native HCI: the backend quarantines until the late callback arrives.
// Native binding code and owner_context must live until all outstanding native
// closures complete, or until the Bluetooth process exits. Do not unload hooks.
class Pixel17Hci {
public:
    Pixel17Hci(Pixel17HciBindings, std::shared_ptr<Pixel17MainDispatcher>);
    ~Pixel17Hci();
    Pixel17Hci(const Pixel17Hci&) = delete;
    Pixel17Hci& operator=(const Pixel17Hci&) = delete;
    // Returns zero on refusal, consumes/destroys context without calling complete.
    // Accepted completion is delivered once on main, including timeout/cancel.
    uint64_t read_on_main(const Pixel17HciOwner&, uint64_t deadline_us,
                          Pixel17HciComplete, void*, Pixel17Destroy);
    uint64_t write_on_main(const Pixel17HciOwner&, uint16_t slots,
                           uint64_t deadline_us, Pixel17HciComplete, void*, Pixel17Destroy);
    bool cancel_on_main(uint64_t serial);
    void poll_on_main();
    bool quarantined() const;
    size_t outstanding() const;
private:
    struct State;
    std::shared_ptr<State> state_;
    uint64_t submit(const Pixel17HciOwner&, uint16_t, uint16_t, uint64_t,
                    Pixel17HciComplete, void*, Pixel17Destroy);
};

// Pure validation, also used by completion callbacks. A successful read is exactly
// 10 event bytes, a successful write exactly 8; no H4 type byte is present.
Pixel17HciResult pixel17_decode_flush_complete(const uint8_t*, size_t,
                                              uint16_t opcode, uint16_t handle);

// Controller baseline is learned from a successful READ, never assumed to be 320.
// Mark uncertainty BEFORE a write can be submitted. Only a subsequent exact READ
// of the captured value discharges the restore obligation; WRITE ACK alone cannot.
class Pixel17FlushBaseline {
public:
    bool capture(const Pixel17HciOwner&, const Pixel17HciResult& read);
    bool will_write(const Pixel17HciOwner&, uint16_t slots);
    bool observe(const Pixel17HciOwner&, const Pixel17HciResult& read);
    bool restore_value(const Pixel17HciOwner&, uint16_t* slots) const;
    bool needs_restore() const { return dirty_; }
    bool known() const { return known_; }
private:
    Pixel17HciOwner owner_{};
    uint16_t baseline_ = 0;
    bool known_ = false, dirty_ = false;
};

// Resolved file virtual addresses, not callable pointers. Runtime must validate
// each complete symbol against loaded memory and apply its verified load bias.
struct Pixel17HciSymbols {
    ImageSymbol construct, post, destroy_callback, main_wrapper;
    ImageSymbol transmit, complete_adapter, view_byte, view_payload, view_size;
    uint64_t main_thread = 0;
};
bool pixel17_hci_contract(const SymbolImage&, Pixel17HciSymbols*);
}
