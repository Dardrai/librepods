// SPDX-License-Identifier: GPL-3.0-or-later
#include "pixel17_hci.h"
#include <array>
#include <cassert>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <memory>
#include <thread>
#include <vector>
using namespace eld;
namespace {
uint64_t clock_us = 10;
uint64_t now(void*) { return clock_us; }
struct NativeBase { uint64_t refs; void (*invoke)(); void (*destroy)(const void*); void* query; };
static_assert(sizeof(NativeBase) == 32);
struct Loop {
    bool accept = true, consume_rejection = false, synchronous = false;
    std::deque<void*> tasks;
    static void construct(void* p,void (*invoke)(),void (*destroy)(const void*)) {
        auto* base = static_cast<NativeBase*>(p);
        base->refs = 1; base->invoke = invoke; base->destroy = destroy;
    }
    static void destroy(void* storage) {
        auto*& pointer = *static_cast<void**>(storage);
        if(pointer) { auto* base = static_cast<NativeBase*>(pointer); pointer = nullptr; base->destroy(base); }
    }
    static bool post(void* opaque,void* storage) {
        auto* loop = static_cast<Loop*>(opaque);
        auto*& pointer = *static_cast<void**>(storage);
        if(!loop->accept) {
            if(loop->consume_rejection) destroy(storage);
            return false;
        }
        loop->tasks.push_back(pointer); pointer = nullptr;
        if(loop->synchronous) loop->run();
        return true;
    }
    void run() {
        while(!tasks.empty()) {
            void* p = tasks.front(); tasks.pop_front();
            auto* base = static_cast<NativeBase*>(p);
            reinterpret_cast<void (*)(void*)>(base->invoke)(base);
            destroy(&p);
        }
    }
    std::shared_ptr<Pixel17MainDispatcher> dispatcher() {
        return std::make_shared<Pixel17MainDispatcher>(Pixel17MainBindings{
            this,construct,post,destroy,now,nullptr});
    }
};
struct Counter { int called = 0, destroyed = 0; };
void task(void* p) { ++static_cast<Counter*>(p)->called; }
void destroy(void* p) { ++static_cast<Counter*>(p)->destroyed; }
Pixel17HciOwner owner() { Pixel17HciOwner p{{1,2,3,4,5,6},3,7,0x123}; return p; }
struct Radio {
    static Radio* self;
    bool is_main = true, current = true, allocation_fails = false;
    size_t view_reads = 0, transmissions = 0;
    void (*complete)(const void*,void*) = nullptr;
    void (*status)(uint8_t,void*,void*) = nullptr;
    void* context = nullptr;
    std::vector<uint8_t> command;
    struct View { std::vector<uint8_t> bytes; };
    static void* allocate(size_t size) { return self->allocation_fails ? nullptr : std::malloc(size); }
    static void transmit(const void* command,void (*complete)(const void*,void*),
                         void (*status)(uint8_t,void*,void*),void* context) {
        const auto* bytes = static_cast<const uint8_t*>(command);
        ++self->transmissions;
        self->command.assign(bytes,bytes+15);
        self->complete = complete; self->status = status; self->context = context;
        std::free(const_cast<void*>(command));
    }
    static size_t size(const void* p) { return static_cast<const View*>(p)->bytes.size(); }
    static uint8_t byte(const void* p,size_t index) {
        const auto& bytes = static_cast<const View*>(p)->bytes;
        assert(index < bytes.size()); ++self->view_reads; return bytes[index];
    }
    static bool main(void* p) { return static_cast<Radio*>(p)->is_main; }
    static bool owned(void* p,const Pixel17HciOwner& requested) {
        auto* radio = static_cast<Radio*>(p);
        assert(radio->is_main);
        return radio->current && pixel17_same_hci_owner(requested,owner());
    }
    Pixel17HciBindings bindings() {
        self = this;
        return {transmit,allocate,std::free,size,byte,main,owned,this,now,nullptr};
    }
    void reply(std::vector<uint8_t> event) {
        auto callback = complete; auto* ctx = context;
        complete = nullptr; context = nullptr;
        is_main = false;
        View view{std::move(event)}; callback(&view,ctx);
        // Native view has already died when the main queue is dispatched.
        is_main = true;
    }
};
Radio* Radio::self = nullptr;
std::vector<uint8_t> read_event(uint16_t slots = 0,uint16_t handle = 0x123) {
    return {0x0e,8,1,0x27,0x0c,0,uint8_t(handle),uint8_t(handle>>8),uint8_t(slots),uint8_t(slots>>8)};
}
std::vector<uint8_t> write_event() { return {0x0e,6,1,0x28,0x0c,0,0x23,1}; }
struct Result { Counter life; Pixel17HciResult result; };
void completed(void* p,const Pixel17HciResult& result) {
    auto* got = static_cast<Result*>(p); ++got->life.called; got->result = result;
}
void result_destroy(void* p) { ++static_cast<Result*>(p)->life.destroyed; }
void dispatcher_tests() {
    Loop loop; auto main = loop.dispatcher(); Counter a;
    assert(main->post(task,&a,destroy)); assert(a.called == 0 && a.destroyed == 0);
    loop.run(); assert(a.called == 1 && a.destroyed == 1 && !main->outstanding());
    loop.accept = false;
    for(bool consumed : {false,true}) {
        loop.consume_rejection = consumed; Counter b;
        assert(!main->post(task,&b,destroy)); assert(!b.called && b.destroyed == 1);
    }
    loop.accept = true; Counter cancelled;
    const auto ticket = main->post(task,&cancelled,destroy);
    assert(main->cancel(ticket) && main->cancel(ticket)); loop.run();
    assert(!cancelled.called && cancelled.destroyed == 1 && !main->cancel(ticket));
    Counter expired; assert(main->post(task,&expired,destroy,clock_us+5));
    clock_us += 5; main->poll(); assert(main->quarantined());
    Counter refused; assert(!main->post(task,&refused,destroy)); assert(refused.destroyed == 1);
    loop.run(); assert(!expired.called && expired.destroyed == 1 && !main->quarantined());
    Counter capped[9];
    for(size_t i=0;i<8;++i) assert(main->post(task,&capped[i],destroy));
    assert(!main->post(task,&capped[8],destroy)); assert(capped[8].destroyed == 1);
    loop.run();
    for(size_t i=0;i<8;++i) assert(capped[i].called == 1 && capped[i].destroyed == 1);
    loop.synchronous = true; Counter sync;
    assert(main->post(task,&sync,destroy)); assert(sync.called == 1 && sync.destroyed == 1);
    loop.synchronous = false; Counter closed;
    assert(main->post(task,&closed,destroy)); main.reset(); loop.run();
    assert(!closed.called && closed.destroyed == 1);
}
void decoder_tests() {
    auto good = read_event();
    assert(pixel17_decode_flush_complete(good.data(),good.size(),0xc27,0x123).status == Pixel17HciStatus::Ok);
    for(size_t n=0;n<good.size();++n)
        assert(pixel17_decode_flush_complete(good.data(),n,0xc27,0x123).status != Pixel17HciStatus::Ok);
    for(size_t index : {0U,1U,3U,4U,6U,7U,9U}) {
        auto bad=good; bad[index] ^= 0x80;
        assert(pixel17_decode_flush_complete(bad.data(),bad.size(),0xc27,0x123).status != Pixel17HciStatus::Ok);
    }
    good.push_back(0); good[1]++;
    assert(pixel17_decode_flush_complete(good.data(),good.size(),0xc27,0x123).status != Pixel17HciStatus::Ok);
    auto error = std::vector<uint8_t>{0xe,4,1,0x27,0xc,0xc};
    assert(pixel17_decode_flush_complete(error.data(),error.size(),0xc27,0x123).status == Pixel17HciStatus::ControllerError);
    auto write = write_event();
    assert(pixel17_decode_flush_complete(write.data(),write.size(),0xc28,0x123).status == Pixel17HciStatus::Ok);
    auto max=read_event(0x7ff);
    assert(pixel17_decode_flush_complete(max.data(),max.size(),0xc27,0x123).status == Pixel17HciStatus::Ok);
}
void hci_tests() {
    Loop loop; auto main = loop.dispatcher(); Radio radio; Pixel17Hci hci(radio.bindings(),main);
    Result read; const auto serial = hci.read_on_main(owner(),clock_us+1000,completed,&read,result_destroy);
    assert(serial && hci.outstanding() == 1);
    assert((radio.command == std::vector<uint8_t>{0,0,5,0,0,0,0,0,0x27,0xc,2,0x23,1,0,0}));
    Result busy; assert(!hci.read_on_main(owner(),clock_us+1000,completed,&busy,result_destroy));
    assert(!busy.life.called && busy.life.destroyed == 1);
    radio.reply(read_event(0)); assert(!read.life.called); loop.run();
    assert(read.life.called == 1 && read.life.destroyed == 1 && read.result.serial == serial);
    assert(read.result.status == Pixel17HciStatus::Ok && read.result.submitted && !hci.outstanding());
    Pixel17FlushBaseline baseline; assert(baseline.capture(owner(),read.result));
    assert(!baseline.capture(owner(),read.result)); uint16_t original=0xffff;
    assert(baseline.restore_value(owner(),&original) && original == 0);
    assert(baseline.will_write(owner(),96) && baseline.needs_restore());
    Result write; assert(hci.write_on_main(owner(),96,clock_us+1000,completed,&write,result_destroy));
    assert((radio.command == std::vector<uint8_t>{0,0,7,0,0,0,0,0,0x28,0xc,4,0x23,1,96,0}));
    radio.reply(write_event()); loop.run();
    assert(write.result.status == Pixel17HciStatus::Ok && baseline.needs_restore());
    assert(!baseline.observe(owner(),write.result));
    assert(baseline.observe(owner(),read.result) && !baseline.needs_restore());
    auto stale_owner=owner(); ++stale_owner.media_generation;
    assert(!baseline.will_write(stale_owner,96) && !baseline.restore_value(stale_owner,&original));
    Result stale; assert(hci.read_on_main(owner(),clock_us+1000,completed,&stale,result_destroy));
    radio.reply(read_event()); radio.current = false; loop.run();
    assert(stale.result.status == Pixel17HciStatus::StaleOwner && stale.life.called == 1);
    radio.current = true;
    Result late; assert(hci.read_on_main(owner(),clock_us+100,completed,&late,result_destroy));
    clock_us += 100; hci.poll_on_main(); assert(hci.quarantined());
    assert(late.result.status == Pixel17HciStatus::Timeout && late.life.called == 1 && late.life.destroyed == 1);
    Result refused; assert(!hci.read_on_main(owner(),clock_us+1000,completed,&refused,result_destroy));
    radio.reply(read_event()); loop.run(); assert(!hci.outstanding() && !hci.quarantined() && late.life.called == 1);
    Result cancelled; const auto cancelled_id=hci.read_on_main(owner(),clock_us+1000,completed,&cancelled,result_destroy);
    assert(hci.cancel_on_main(cancelled_id) && !hci.cancel_on_main(cancelled_id));
    assert(cancelled.result.status == Pixel17HciStatus::Cancelled && cancelled.life.destroyed == 1);
    radio.reply(read_event()); loop.run(); assert(cancelled.life.called == 1 && !hci.outstanding());
    Result failed_post; assert(hci.read_on_main(owner(),clock_us+1000,completed,&failed_post,result_destroy));
    loop.accept = false; radio.reply(read_event()); assert(hci.quarantined()); hci.poll_on_main();
    assert(failed_post.result.status == Pixel17HciStatus::DispatchFailed && !hci.outstanding()); loop.accept = true;
    Result wrong_thread; radio.is_main = false;
    assert(!hci.read_on_main(owner(),clock_us+1000,completed,&wrong_thread,result_destroy)); radio.is_main = true;
    assert(!wrong_thread.life.called && wrong_thread.life.destroyed == 1);
    Result allocation; radio.allocation_fails = true;
    assert(hci.read_on_main(owner(),clock_us+1000,completed,&allocation,result_destroy));
    assert(allocation.result.status == Pixel17HciStatus::DispatchFailed && !allocation.result.submitted && !hci.outstanding());
    radio.allocation_fails = false;
    Result destroyed;
    { Pixel17Hci temporary(radio.bindings(),main);
      assert(temporary.read_on_main(owner(),clock_us+1000,completed,&destroyed,result_destroy)); }
    assert(!destroyed.life.called && destroyed.life.destroyed == 1);
    radio.reply(read_event()); loop.run(); assert(!destroyed.life.called && destroyed.life.destroyed == 1);
    Result malformed; assert(hci.read_on_main(owner(),clock_us+1000,completed,&malformed,result_destroy));
    radio.reply({}); loop.run(); assert(malformed.result.status == Pixel17HciStatus::Malformed);
    Result oversized; assert(hci.read_on_main(owner(),clock_us+1000,completed,&oversized,result_destroy));
    const auto before=radio.view_reads; radio.reply(std::vector<uint8_t>(257)); loop.run();
    assert(oversized.result.status == Pixel17HciStatus::Malformed && radio.view_reads == before);
    Result unexpected;
    assert(hci.read_on_main(owner(),clock_us+1000,completed,&unexpected,result_destroy));
    uint8_t foreign_command[15]{}; // Not heap-owned; the backend must not free/dereference it.
    radio.status(0,foreign_command,radio.context); radio.context=nullptr; radio.complete=nullptr; loop.run();
    assert(unexpected.result.status == Pixel17HciStatus::UnexpectedStatus && unexpected.life.called == 1);
    assert(hci.quarantined() && !hci.outstanding());
    Result rejected_abi;
    assert(!hci.read_on_main(owner(),clock_us+1000,completed,&rejected_abi,result_destroy));
    assert(!rejected_abi.life.called && rejected_abi.life.destroyed == 1);
}
}
#ifndef ELD_HCI_TEST_HELPERS_ONLY
int main() { dispatcher_tests(); decoder_tests(); hci_tests(); }
#endif
