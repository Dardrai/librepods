// SPDX-License-Identifier: GPL-3.0-or-later
#include "pixel17_hci.h"
#include <array>
#include <cstring>
#include <mutex>
#include <new>
#include <utility>

namespace eld {
namespace {
constexpr uint16_t kRead = 0x0c27, kWrite = 0x0c28, kMaxSlots = 0x07ff;
void destroy_context(Pixel17Destroy destroy, void* context) noexcept {
    if(destroy) { try { destroy(context); } catch(...) {} }
}
bool valid_owner(const Pixel17HciOwner& owner) {
    const uint8_t empty[6]{};
    return std::memcmp(owner.peer, empty, 6) && owner.media_generation &&
        owner.encoder_generation && owner.acl_handle <= 0x0eff;
}
}

struct Pixel17MainDispatcher::State {
    struct Work {
        std::shared_ptr<State> core;
        uint64_t ticket = 0, deadline = 0;
        Pixel17Task task = nullptr;
        Pixel17Destroy destroy = nullptr;
        void* context = nullptr;
        bool cancelled = false, running = false, finished = false;
        ~Work() { destroy_context(destroy, context); }
    };
    struct NativeState {
        alignas(8) uint64_t native_base[4]{};
        std::shared_ptr<Work> work;
    };
    Pixel17MainBindings binding;
    mutable std::mutex mutex;
    std::array<std::weak_ptr<Work>, 8> pending{};
    uint64_t next = 0;
    bool closed = false;
    explicit State(Pixel17MainBindings b) : binding(b) {}
    static void invoke(void* opaque) noexcept {
        auto work = static_cast<NativeState*>(opaque)->work;
        auto core = work->core;
        {
            std::lock_guard<std::mutex> lock(core->mutex);
            if(core->closed || work->cancelled || work->running || work->finished ||
               core->binding.now(core->binding.clock_context) >= work->deadline) {
                work->cancelled = true;
                return;
            }
            work->running = true;
        }
        try { work->task(work->context); } catch(...) {}
        std::lock_guard<std::mutex> lock(core->mutex);
        work->running = false;
        work->finished = true;
    }
    static void destroy(const void* opaque) noexcept {
        delete static_cast<const NativeState*>(opaque);
    }
};

Pixel17MainDispatcher::Pixel17MainDispatcher(Pixel17MainBindings binding)
    : state_(std::make_shared<State>(binding)) {}
Pixel17MainDispatcher::~Pixel17MainDispatcher() {
    std::lock_guard<std::mutex> lock(state_->mutex);
    state_->closed = true;
    for(auto& slot : state_->pending) if(auto work = slot.lock()) work->cancelled = true;
}
uint64_t Pixel17MainDispatcher::post(Pixel17Task task, void* context,
                                    Pixel17Destroy destroy, uint64_t deadline) {
    auto core = state_;
    const auto& b = core->binding;
    if(!task || !b.main_thread || !b.construct || !b.post || !b.destroy_callback || !b.now) {
        destroy_context(destroy, context); return 0;
    }
    std::shared_ptr<State::Work> work;
    try { work = std::make_shared<State::Work>(); }
    catch(...) { destroy_context(destroy, context); return 0; }
    work->core = core; work->task = task; work->context = context;
    work->destroy = destroy; work->deadline = deadline;
    {
        std::lock_guard<std::mutex> lock(core->mutex);
        size_t free = core->pending.size();
        bool timed_out = false;
        const auto now = b.now(b.clock_context);
        for(size_t i = 0; i < core->pending.size(); ++i) {
            if(auto old = core->pending[i].lock()) {
                if(!old->finished && now >= old->deadline) {
                    old->cancelled = true; timed_out = true;
                }
            } else if(free == core->pending.size()) free = i;
        }
        // Lost native closures are bounded. Do not keep allocating after a deadline.
        if(core->closed || timed_out || now >= deadline || free == core->pending.size() ||
           core->next == UINT64_MAX) return 0;
        work->ticket = ++core->next;
        core->pending[free] = work;
    }
    auto* native = new(std::nothrow) State::NativeState;
    if(!native) return 0;
    native->work = work;
    b.construct(native, reinterpret_cast<void (*)()>(&State::invoke), &State::destroy);
    void* callback = native;
    bool accepted = false;
    try { accepted = b.post(b.main_thread, &callback); }
    catch(...) {
        std::lock_guard<std::mutex> lock(core->mutex);
        work->cancelled = true;
    }
    // DoInThread consumes/moves the pointer, even when it rejects the task. This
    // also handles mocks/compatible implementations which leave an owned pointer.
    if(callback) b.destroy_callback(&callback);
    if(!accepted) {
        std::lock_guard<std::mutex> lock(core->mutex);
        work->cancelled = true;
        return 0;
    }
    return work->ticket;
}
bool Pixel17MainDispatcher::cancel(uint64_t ticket) {
    if(!ticket) return false;
    std::lock_guard<std::mutex> lock(state_->mutex);
    for(auto& slot : state_->pending) if(auto work = slot.lock()) {
        if(work->ticket == ticket && !work->running && !work->finished) {
            work->cancelled = true; return true;
        }
    }
    return false;
}
void Pixel17MainDispatcher::poll() {
    std::lock_guard<std::mutex> lock(state_->mutex);
    if(!state_->binding.now) return;
    const auto now = state_->binding.now(state_->binding.clock_context);
    for(auto& slot : state_->pending) if(auto work = slot.lock())
        if(!work->finished && now >= work->deadline) work->cancelled = true;
}
size_t Pixel17MainDispatcher::outstanding() const {
    std::lock_guard<std::mutex> lock(state_->mutex);
    size_t count = 0;
    for(const auto& slot : state_->pending) if(!slot.expired()) ++count;
    return count;
}
bool Pixel17MainDispatcher::quarantined() const {
    std::lock_guard<std::mutex> lock(state_->mutex);
    if(!state_->binding.now) return true;
    const auto now = state_->binding.now(state_->binding.clock_context);
    for(const auto& slot : state_->pending) if(auto work = slot.lock())
        if(!work->finished && now >= work->deadline) return true;
    return false;
}

bool pixel17_same_hci_owner(const Pixel17HciOwner& a, const Pixel17HciOwner& b) {
    return !std::memcmp(a.peer,b.peer,6) && a.media_generation == b.media_generation &&
        a.encoder_generation == b.encoder_generation && a.acl_handle == b.acl_handle;
}
Pixel17HciResult pixel17_decode_flush_complete(const uint8_t* p, size_t size,
                                              uint16_t opcode, uint16_t handle) {
    Pixel17HciResult result;
    result.opcode = opcode;
    if(!p || (opcode != kRead && opcode != kWrite) || handle > 0x0eff ||
       size < 6 || size > 257 || p[0] != 0x0e || size != size_t(p[1]) + 2 ||
       (uint16_t(p[3]) | uint16_t(p[4]) << 8) != opcode) return result;
    result.controller_status = p[5];
    if(p[5]) { result.status = Pixel17HciStatus::ControllerError; return result; }
    if(size != (opcode == kRead ? 10U : 8U)) return result;
    result.handle = uint16_t(p[6]) | uint16_t(p[7]) << 8;
    if(result.handle != handle) return result;
    if(opcode == kRead) {
        result.slots = uint16_t(p[8]) | uint16_t(p[9]) << 8;
        if(result.slots > kMaxSlots) return result;
    }
    result.status = Pixel17HciStatus::Ok;
    return result;
}

struct Pixel17Hci::State : std::enable_shared_from_this<Pixel17Hci::State> {
    struct Request {
        std::shared_ptr<State> core;
        Pixel17HciOwner owner;
        Pixel17HciResult result;
        uint64_t deadline = 0;
        Pixel17HciComplete complete = nullptr;
        Pixel17Destroy destroy = nullptr;
        void* context = nullptr;
        bool native_done = false, logical_done = false, dispatch_failed = false;
    };
    struct Delivery { std::shared_ptr<State> core; std::shared_ptr<Request> request; };
    Pixel17HciBindings binding;
    std::shared_ptr<Pixel17MainDispatcher> main;
    mutable std::mutex mutex;
    std::shared_ptr<Request> pending;
    uint64_t next = 0;
    bool closed = false, abi_rejected = false;
    const void* unexpected_command = nullptr; // Opaque foreign ownership; never dereferenced or freed.
    State(Pixel17HciBindings b, std::shared_ptr<Pixel17MainDispatcher> m)
        : binding(b), main(std::move(m)) {}
    bool on_main() const { return binding.is_main && binding.is_main(binding.owner_context); }
    void finish(const std::shared_ptr<Request>& request, Pixel17HciStatus status) {
        Pixel17HciComplete complete = nullptr;
        Pixel17Destroy destroy = nullptr;
        void* context = nullptr;
        Pixel17HciResult result;
        bool reclaimed_late=false;
        {
            std::lock_guard<std::mutex> lock(mutex);
            if(pending != request) return;
            reclaimed_late=request->logical_done && request->native_done;
            if(!request->logical_done) {
                request->logical_done = true;
                request->result.status = status;
                result = request->result;
                complete = request->complete; destroy = request->destroy; context = request->context;
                request->complete = nullptr; request->destroy = nullptr; request->context = nullptr;
            }
            if(request->native_done) { pending.reset(); request->core.reset(); }
        }
        if(complete) { try { complete(context, result); } catch(...) {} }
        destroy_context(destroy, context);
        if(reclaimed_late && binding.reclaimed) {
            try { binding.reclaimed(binding.owner_context); } catch(...) {}
        }
    }
    void poll() {
        if(!on_main()) return;
        std::shared_ptr<Request> request;
        Pixel17HciResult result;
        bool native_done, logical_done, failed;
        {
            std::lock_guard<std::mutex> lock(mutex);
            request = pending;
            if(!request) return;
            result = request->result; native_done = request->native_done;
            logical_done = request->logical_done; failed = request->dispatch_failed;
        }
        if(logical_done) { if(native_done) finish(request,result.status); return; }
        if(binding.now(binding.clock_context) >= request->deadline)
            return finish(request,Pixel17HciStatus::Timeout);
        if(!binding.owner_current(binding.owner_context,request->owner))
            return finish(request,Pixel17HciStatus::StaleOwner);
        if(failed) return finish(request,Pixel17HciStatus::DispatchFailed);
        if(native_done) finish(request,result.status);
    }
    static void finish_task(void* opaque) noexcept {
        static_cast<Delivery*>(opaque)->core->poll();
    }
    static void delete_task(void* opaque) noexcept {
        delete static_cast<Delivery*>(opaque);
    }
    static void received(Request* raw, Pixel17HciResult result) noexcept {
        // pending owns raw until this native callback returns, even after timeout,
        // cancellation, facade destruction or an owner generation change.
        auto core = raw->core;
        std::shared_ptr<Request> request;
        {
            std::lock_guard<std::mutex> lock(core->mutex);
            request = core->pending;
            if(!request || request.get() != raw || request->native_done) return;
            result.serial = request->result.serial; result.submitted = true;
            request->result = result;
            request->native_done = true;
        }
        bool already_finished;
        { std::lock_guard<std::mutex> lock(core->mutex); already_finished = request->logical_done; }
        if(already_finished) { core->finish(request,result.status); return; }
        auto* task = new(std::nothrow) Delivery{core,request};
        const auto now=core->binding.now(core->binding.clock_context);
        const auto deadline=now>UINT64_MAX-1000000 ? UINT64_MAX : now+1000000;
        if(!task || !core->main->post(&finish_task,task,&delete_task,deadline)) {
            std::lock_guard<std::mutex> lock(core->mutex);
            request->dispatch_failed = true;
        }
        // A later explicit on-main poll can still reclaim a completion if its
        // delivery post failed. No platform-owned object or payload escapes here.
    }
    static void native_complete(const void* view, void* opaque) noexcept {
        auto* request = static_cast<Request*>(opaque);
        auto core = request->core;
        Pixel17HciResult result;
        result.opcode = request->result.opcode;
        try {
            const auto size = view ? core->binding.view_size(view) : 0;
            if(size >= 6 && size <= 10) {
                uint8_t event[10]{};
                for(size_t i = 0; i < size; ++i) event[i] = core->binding.view_byte(view,i);
                result = pixel17_decode_flush_complete(event,size,request->result.opcode,
                                                        request->owner.acl_handle);
            }
        } catch(...) {}
        received(request,result);
    }
    static void native_status(uint8_t status, void* command, void* opaque) noexcept {
        auto* request = static_cast<Request*>(opaque);
        auto core = request->core;
        // Admitted0c27/0c28 never choose this branch. Its foreign BT_HDR ownership
        // is therefore unproven: do not dereference/free it. Stop this backend so
        // at most one such opaque command can remain until Bluetooth exits.
        {
            std::lock_guard<std::mutex> lock(core->mutex);
            core->abi_rejected=true; core->unexpected_command=command;
        }
        Pixel17HciResult result;
        result.opcode = request->result.opcode; result.controller_status = status;
        result.status = Pixel17HciStatus::UnexpectedStatus;
        received(request,result);
    }
};

Pixel17Hci::Pixel17Hci(Pixel17HciBindings binding, std::shared_ptr<Pixel17MainDispatcher> main)
    : state_(std::make_shared<State>(binding,std::move(main))) {}
Pixel17Hci::~Pixel17Hci() {
    auto core = state_;
    std::shared_ptr<State::Request> request;
    {
        std::lock_guard<std::mutex> lock(core->mutex);
        core->closed = true; request = core->pending;
        if(request) request->complete = nullptr;
    }
    // Never call an owner callback or a completion on an arbitrary destructor
    // thread. Release caller-owned state, but retain native's pending raw pointer.
    if(request) core->finish(request,Pixel17HciStatus::Cancelled);
}
uint64_t Pixel17Hci::submit(const Pixel17HciOwner& owner,uint16_t opcode,uint16_t slots,
                           uint64_t deadline,Pixel17HciComplete complete,void* context,
                           Pixel17Destroy destroy) {
    auto core = state_; const auto& b = core->binding;
    if(!core->main || !b.transmit || !b.allocate || !b.release || !b.view_size ||
       !b.view_byte || !b.owner_current || !b.now || !core->on_main() ||
       !valid_owner(owner) || slots > kMaxSlots || !complete ||
       b.now(b.clock_context) >= deadline || deadline - b.now(b.clock_context) > 5000000 ||
       !b.owner_current(b.owner_context,owner)) {
        destroy_context(destroy,context); return 0;
    }
    core->poll();
    std::shared_ptr<State::Request> request;
    try { request = std::make_shared<State::Request>(); }
    catch(...) { destroy_context(destroy,context); return 0; }
    request->owner = owner; request->deadline = deadline;
    request->complete = complete; request->destroy = destroy; request->context = context;
    bool refused = false;
    {
        std::lock_guard<std::mutex> lock(core->mutex);
        if(core->closed || core->abi_rejected || core->pending || core->next == UINT64_MAX) {
            refused = true;
        } else {
            request->result.serial = ++core->next;
            request->result.opcode = opcode;
            request->core = core;
            core->pending = request;
        }
    }
    if(refused) { destroy_context(destroy,context); return 0; }
    // Native owns/frees this command after transmit; event/len/offset/layer fields
    // are the native 8-byte BT_HDR, followed by an HCI command without H4 prefix.
    auto* command = static_cast<uint8_t*>(b.allocate(15));
    if(!command) {
        { std::lock_guard<std::mutex> lock(core->mutex); request->native_done = true; }
        core->finish(request,Pixel17HciStatus::DispatchFailed);
        return request->result.serial;
    }
    std::memset(command,0,15);
    command[2] = opcode == kRead ? 5 : 7;
    command[8] = uint8_t(opcode); command[9] = uint8_t(opcode >> 8);
    command[10] = opcode == kRead ? 2 : 4;
    command[11] = uint8_t(owner.acl_handle); command[12] = uint8_t(owner.acl_handle >> 8);
    command[13] = uint8_t(slots); command[14] = uint8_t(slots >> 8);
    { std::lock_guard<std::mutex> lock(core->mutex); request->result.submitted = true; }
    b.transmit(command,&State::native_complete,&State::native_status,request.get());
    return request->result.serial;
}
uint64_t Pixel17Hci::read_on_main(const Pixel17HciOwner& owner,uint64_t deadline,
                                 Pixel17HciComplete complete,void* context,Pixel17Destroy destroy) {
    return submit(owner,kRead,0,deadline,complete,context,destroy);
}
uint64_t Pixel17Hci::write_on_main(const Pixel17HciOwner& owner,uint16_t slots,uint64_t deadline,
                                  Pixel17HciComplete complete,void* context,Pixel17Destroy destroy) {
    return submit(owner,kWrite,slots,deadline,complete,context,destroy);
}
bool Pixel17Hci::cancel_on_main(uint64_t serial) {
    auto core = state_; if(!core->on_main()) return false;
    std::shared_ptr<State::Request> request;
    { std::lock_guard<std::mutex> lock(core->mutex); request = core->pending; }
    if(!request || request->result.serial != serial || request->logical_done) return false;
    core->finish(request,Pixel17HciStatus::Cancelled); return true;
}
void Pixel17Hci::poll_on_main() { state_->poll(); }
bool Pixel17Hci::quarantined() const {
    std::lock_guard<std::mutex> lock(state_->mutex);
    return state_->abi_rejected || (state_->pending && (state_->pending->logical_done || state_->pending->dispatch_failed));
}
size_t Pixel17Hci::outstanding() const {
    std::lock_guard<std::mutex> lock(state_->mutex); return state_->pending ? 1 : 0;
}

bool Pixel17FlushBaseline::capture(const Pixel17HciOwner& owner,const Pixel17HciResult& read) {
    if(known_ || !valid_owner(owner) || read.status != Pixel17HciStatus::Ok ||
       read.opcode != kRead || read.handle != owner.acl_handle || read.slots > kMaxSlots) return false;
    owner_ = owner; baseline_ = read.slots; known_ = true; return true;
}
bool Pixel17FlushBaseline::will_write(const Pixel17HciOwner& owner,uint16_t slots) {
    if(!known_ || !pixel17_same_hci_owner(owner_,owner) || slots > kMaxSlots) return false;
    dirty_ = true; return true;
}
bool Pixel17FlushBaseline::observe(const Pixel17HciOwner& owner,const Pixel17HciResult& read) {
    if(!known_ || !pixel17_same_hci_owner(owner_,owner) || read.status != Pixel17HciStatus::Ok ||
       read.opcode != kRead || read.handle != owner.acl_handle || read.slots > kMaxSlots) return false;
    dirty_ = read.slots != baseline_; return true;
}
bool Pixel17FlushBaseline::restore_value(const Pixel17HciOwner& owner,uint16_t* slots) const {
    if(!slots || !known_ || !pixel17_same_hci_owner(owner_,owner)) return false;
    *slots = baseline_; return true;
}
}
