// SPDX-License-Identifier: GPL-3.0-or-later
#include "pixel17_game_runtime.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <mutex>
#include <new>
#include <thread>
#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace eld {
namespace {
std::atomic<bool> opportunity_pending{false};
struct Runtime {
    struct Start { Runtime* runtime; uint64_t serial; bool ran=false; };
    struct Tick { Runtime* runtime; uint64_t serial; bool ran=false; };
    struct ClockBegin {
        Runtime* runtime; uint64_t serial, lease, request, epoch;
        Pixel17HciOwner owner; uint32_t interval, drift; bool ran=false;
        uint64_t deadline_ns=0;
    };
    std::mutex mutex;
    std::condition_variable wake;
    Pixel17GameStatus status;
    Pixel17MainBindings clock;
    std::shared_ptr<Pixel17MainDispatcher> main;
    std::unique_ptr<Pixel17Hci> hci;
    Pixel17FlushBaseline baseline;
    uint64_t next = 0, hci_serial = 0, command_epoch = 0, monitor_deadline_ns = 0;
    uint64_t next_clock_request = 0, clock_epoch = 0, owned_clock = 0;
    bool tick_queued = false, restore_attempted = false, finishing = false;
    bool graceful_restore = false;
    bool clock_starting = false;
    bool clock_waiting = false, clock_retry_queued = false;
    ClockBegin waiting_clock{};
    uint32_t clock_attempts = 0;
    bool late_reclaimed = false, late_verification_attempted = false;
    Pixel17GamePhase command_phase = Pixel17GamePhase::Idle;
    uint16_t original_cid = 0;
    uint64_t now_ns() const {
        const auto us=clock.now(clock.clock_context);
        return us>UINT64_MAX/1000 ? UINT64_MAX : us*1000;
    }
    static uint64_t grace(uint64_t now) {
        return now>UINT64_MAX-3000000000ULL ? UINT64_MAX : now+3000000000ULL;
    }
    // These helpers deliberately call the encoder outside the runtime mutex.
    void sample_clock() {
        const auto actual=encoder_clock_status();
        const auto now=now_ns();
        std::lock_guard<std::mutex> lock(mutex);
        if(owned_clock && actual.serial==owned_clock) {
            status.clock=actual; status.clock_observed_ns=now;
        }
    }
    uint64_t detach_clock_locked() {
        if(clock_epoch!=UINT64_MAX) ++clock_epoch;
        // An already-entered encoder begin must publish or cancel its returned
        // serial before callers can trust nominal cadence for microphone use.
        status.clock_pending=clock_starting;
        clock_waiting=false; opportunity_pending.store(false,std::memory_order_release);
        const auto serial=owned_clock; owned_clock=0;
        return serial;
    }
    void cancel_clock(uint64_t serial) {
        if(!serial) return;
        encoder_clock_cancel(serial);
        const auto actual=encoder_clock_status();
        const auto now=now_ns();
        std::lock_guard<std::mutex> lock(mutex);
        if(status.clock.serial==serial && actual.serial==serial) {
            status.clock=actual; status.clock_observed_ns=now;
        }
    }
    bool end_for(uint64_t lease,uint64_t serial,Pixel17HciStatus error,bool graceful=false,bool held_only=false) {
        uint64_t clock_serial;
        {
            std::lock_guard<std::mutex> lock(mutex);
            if(!lease || !serial || status.lease_owner!=lease || status.serial!=serial ||
               (!status.busy && !status.holding && !status.restore_pending && !status.clock_pending)) return false;
            // A validation begun while held can race an explicit Restore. Abort
            // only if that same hold is still live under this lock; never relabel
            // successful graceful cleanup as an ownership/lease failure.
            if(held_only && (!status.holding || status.cancelled)) return false;
            status.cancelled=true; status.holding=false;
            graceful_restore=graceful && status.error==Pixel17HciStatus::Ok;
            if(status.error==Pixel17HciStatus::Ok && !graceful) status.error=error;
            clock_serial=detach_clock_locked();
            status.busy=status.restore_pending || hci->outstanding();
            if(!status.busy) status.phase=graceful_restore ? Pixel17GamePhase::Complete : Pixel17GamePhase::Failed;
        }
        cancel_clock(clock_serial);
        wake.notify_one();
        return true;
    }
    static bool on_main(void*) { return pixel17_game_host_is_event_thread(); }
    static bool owner_current(void* opaque,const Pixel17HciOwner& requested) {
        auto& r=*static_cast<Runtime*>(opaque);
        if(!pixel17_game_host_is_event_thread() || !pixel17_game_host_refresh_on_event_thread()) return false;
        const auto host=pixel17_game_host_snapshot();
        bool restoring,admitted; uint16_t cid;
        { std::lock_guard<std::mutex> lock(r.mutex);
          restoring=r.status.restore_pending && (r.command_phase==Pixel17GamePhase::Restoring ||
              r.command_phase==Pixel17GamePhase::VerifyingRestore);
          admitted=!r.status.cancelled && r.now_ns()<(r.status.holding ? r.status.lease_deadline_ns : r.status.deadline_ns);
          cid=r.original_cid; r.status.host=host; }
        const bool same=!std::memcmp(requested.peer,host.peer,6) &&
            requested.media_generation==host.media_generation && requested.acl_handle==host.acl_handle &&
            host.media_cid==cid && cid>=0x40 && host.acl_handle<=0xeff;
        // Controller cleanup belongs to the original native media channel/ACL.
        // An encoder reset or SCO invalidates Game admission, not its restore duty.
        return same && (restoring || (admitted && requested.encoder_generation==host.encoder_generation &&
            host.route_valid && host.encoder_ready && !host.native_sco));
    }
    void publish_failure(Pixel17HciStatus error) {
        uint64_t lease=0,serial=0;
        {
            std::lock_guard<std::mutex> lock(mutex);
            if(status.holding) { lease=status.lease_owner; serial=status.serial; }
            else {
                if(status.error==Pixel17HciStatus::Ok) status.error=error;
                status.restore_pending=baseline.needs_restore();
                status.phase=status.restore_pending ? Pixel17GamePhase::Quarantined : Pixel17GamePhase::Failed;
                status.busy=status.restore_pending && (hci->outstanding() || !restore_attempted);
            }
        }
        if(serial) end_for(lease,serial,error,false,true);
    }
    void command(Pixel17GamePhase phase,bool write,uint16_t slots=0) {
        Pixel17HciOwner owner; uint64_t deadline,epoch; bool cancelled;
        {
            std::lock_guard<std::mutex> lock(mutex);
            status.phase=phase; command_phase=phase; owner=status.owner;
            epoch=++command_epoch;
            const bool cleanup=phase==Pixel17GamePhase::Restoring || phase==Pixel17GamePhase::VerifyingRestore;
            cancelled=!cleanup && (status.cancelled || now_ns()>=status.deadline_ns);
            if(cancelled && status.error==Pixel17HciStatus::Ok) status.error=Pixel17HciStatus::Cancelled;
            // Cleanup has its own bounded command deadline after the app's lease
            // expires. It never renews the Game lease or selects another channel.
            const auto now=clock.now(clock.clock_context);
            const auto command_limit=now>UINT64_MAX-1000000 ? UINT64_MAX : now+1000000;
            deadline=(phase==Pixel17GamePhase::Restoring || phase==Pixel17GamePhase::VerifyingRestore)
                ? command_limit : std::min(status.deadline_ns/1000,command_limit);
        }
        if(cancelled) { restore(); return; }
        const auto serial=write ? hci->write_on_main(owner,slots,deadline,completed,this,nullptr)
                                : hci->read_on_main(owner,deadline,completed,this,nullptr);
        { std::lock_guard<std::mutex> lock(mutex);
          if(command_epoch==epoch && hci->outstanding()) hci_serial=serial; }
        if(!serial) publish_failure(hci->quarantined()?Pixel17HciStatus::Timeout:Pixel17HciStatus::StaleOwner);
    }
    void restore() {
        Pixel17HciOwner owner; uint16_t value;
        {
            std::lock_guard<std::mutex> lock(mutex);
            status.restore_pending=baseline.needs_restore(); owner=status.owner;
            finishing=true;
            if(!status.restore_pending) {
                status.busy=false;
                status.phase=status.error==Pixel17HciStatus::Ok && (!status.cancelled || graceful_restore)
                    ? Pixel17GamePhase::Complete : Pixel17GamePhase::Failed;
                return;
            }
            // An unreclaimed native callback retains its own raw context. Wait
            // for it; never overlap another command or free it on a timer.
            if(hci->outstanding()) { status.phase=Pixel17GamePhase::Quarantined; return; }
            if(restore_attempted) { status.phase=Pixel17GamePhase::Quarantined; status.busy=false; return; }
            if(!baseline.restore_value(owner,&value)) { status.phase=Pixel17GamePhase::Quarantined; status.busy=false; return; }
            restore_attempted=true;
        }
        command(Pixel17GamePhase::Restoring,true,value);
    }
    static void completed(void* opaque,const Pixel17HciResult& result) {
        auto& r=*static_cast<Runtime*>(opaque);
        Pixel17GamePhase phase; Pixel17GameOperation operation; Pixel17HciOwner owner;
        bool cancelled;
        {
            std::lock_guard<std::mutex> lock(r.mutex);
            r.hci_serial=0; phase=r.command_phase; operation=r.status.operation;
            owner=r.status.owner; cancelled=r.status.cancelled;
            r.status.last_opcode=result.opcode; r.status.completed_handle=result.handle;
            r.status.controller_status=result.controller_status;
            // A timeout can change phase to Quarantined while native HCI still
            // owns the request; this logical callback is delivered only once.
            if(result.status!=Pixel17HciStatus::Ok && r.status.error==Pixel17HciStatus::Ok &&
               !(r.graceful_restore && result.status==Pixel17HciStatus::Cancelled)) {
                r.status.error=result.status;
#ifdef __ANDROID__
                __android_log_print(ANDROID_LOG_WARN,"LibrePodsGame",
                    "native HCI failed serial=%llu phase=%u error=%u opcode=0x%04x status=0x%02x handle=0x%04x submitted=%d",
                    static_cast<unsigned long long>(r.status.serial),static_cast<unsigned>(phase),
                    static_cast<unsigned>(result.status),result.opcode,result.controller_status,result.handle,result.submitted);
#endif
            }
        }
        if(result.status!=Pixel17HciStatus::Ok) { r.restore(); return; }
        if(result.opcode==0xc27) {
            std::lock_guard<std::mutex> lock(r.mutex);
            r.status.current_slots=result.slots; r.status.current_known=true;
        }
        if(phase==Pixel17GamePhase::Reading) {
            bool captured;
            {
                std::lock_guard<std::mutex> lock(r.mutex);
                captured=r.baseline.capture(owner,result);
                if(captured) { r.status.baseline_known=true; r.status.baseline_slots=result.slots; }
                else if(r.status.error==Pixel17HciStatus::Ok) r.status.error=Pixel17HciStatus::Malformed;
            }
            if(!captured) { r.restore(); return; }
            if(operation==Pixel17GameOperation::Read || cancelled) { r.restore(); return; }
            bool marked;
            {
                std::lock_guard<std::mutex> lock(r.mutex);
                marked=r.baseline.will_write(owner,96);
                if(!marked && r.status.error==Pixel17HciStatus::Ok) { r.status.error=Pixel17HciStatus::Malformed; }
                r.status.restore_pending=r.baseline.needs_restore();
            }
            if(!marked) { r.restore(); return; }
            r.command(Pixel17GamePhase::Writing,true,96); return;
        }
        if(phase==Pixel17GamePhase::Writing) {
            if(cancelled) r.restore(); else r.command(Pixel17GamePhase::Verifying,false);
            return;
        }
        if(phase==Pixel17GamePhase::Verifying) {
            bool held=false;
            { std::lock_guard<std::mutex> lock(r.mutex);
              if(result.slots!=96 && r.status.error==Pixel17HciStatus::Ok) r.status.error=Pixel17HciStatus::Malformed;
              if(operation==Pixel17GameOperation::Prepare && result.slots==96 && !r.status.cancelled &&
                 r.status.error==Pixel17HciStatus::Ok && r.now_ns()<r.status.deadline_ns) {
                  // The captured controller baseline may already be96. Exact
                  // readback then discharges write uncertainty without inventing
                  // a different "normal" timeout or forcing an unnecessary reset.
                  r.baseline.observe(owner,result);
                  r.status.restore_pending=r.baseline.needs_restore();
                  r.status.holding=true; r.status.busy=false; r.status.phase=Pixel17GamePhase::Holding;
                  r.status.lease_deadline_ns=grace(r.now_ns()); held=true;
              } }
            if(held) { r.wake.notify_one(); return; }
            r.restore(); return;
        }
        if(phase==Pixel17GamePhase::Restoring) {
            r.command(Pixel17GamePhase::VerifyingRestore,false); return;
        }
        if(phase==Pixel17GamePhase::VerifyingRestore) {
            {
                std::lock_guard<std::mutex> lock(r.mutex);
                if((!r.baseline.observe(owner,result) || r.baseline.needs_restore()) && r.status.error==Pixel17HciStatus::Ok)
                    r.status.error=Pixel17HciStatus::Malformed;
                r.status.restore_pending=r.baseline.needs_restore();
            }
            r.restore(); return;
        }
        r.publish_failure(Pixel17HciStatus::Malformed);
    }
    void tick() {
        if(!pixel17_game_host_is_event_thread()) { publish_failure(Pixel17HciStatus::DispatchFailed); return; }
        hci->poll_on_main();
        Pixel17HciOwner held_owner; uint64_t held_lease=0,held_serial=0;
        { std::lock_guard<std::mutex> lock(mutex);
          if(status.holding) { held_owner=status.owner; held_lease=status.lease_owner; held_serial=status.serial; } }
        if(held_serial) {
            if(owner_current(this,held_owner)) return;
            end_for(held_lease,held_serial,Pixel17HciStatus::StaleOwner,false,true);
        }
        bool reclaimed,verify_late;
        {
            std::lock_guard<std::mutex> lock(mutex);
            reclaimed=late_reclaimed; late_reclaimed=false;
            verify_late=reclaimed && restore_attempted && !late_verification_attempted;
            if(verify_late) late_verification_attempted=true;
        }
        if(reclaimed && !hci->outstanding()) {
            if(verify_late) command(Pixel17GamePhase::VerifyingRestore,false);
            else restore();
            return;
        }
        bool busy,cancelled,dirty,cleanup; uint64_t deadline,serial;
        {
            std::lock_guard<std::mutex> lock(mutex);
            busy=status.busy; cancelled=status.cancelled; dirty=status.restore_pending;
            cleanup=finishing;
            deadline=status.deadline_ns; serial=hci_serial;
            if(busy && now_ns()>=deadline && !finishing && !cancelled) {
                status.cancelled=true;
                if(status.error==Pixel17HciStatus::Ok) status.error=Pixel17HciStatus::Timeout;
                cancelled=true;
            }
        }
        if(!busy) return;
        if(cancelled && serial && !cleanup) hci->cancel_on_main(serial);
        if(dirty && !hci->outstanding()) restore();
        else if(cancelled && !dirty && !hci->outstanding()) restore();
    }
    static void tick_task(void* opaque) {
        auto& tick=*static_cast<Tick*>(opaque); tick.ran=true; tick.runtime->tick();
    }
    static void tick_destroy(void* opaque) {
        std::unique_ptr<Tick> tick(static_cast<Tick*>(opaque));
        auto& r=*tick->runtime;
        bool current; uint64_t lease;
        { std::lock_guard<std::mutex> lock(r.mutex);
          r.tick_queued=false; current=r.status.serial==tick->serial && (r.status.busy || r.status.holding);
          lease=r.status.lease_owner; }
        if(!tick->ran && current) r.end_for(lease,tick->serial,Pixel17HciStatus::DispatchFailed);
    }
    void queue_tick() {
        uint64_t serial;
        { std::lock_guard<std::mutex> lock(mutex);
          if((!status.busy && !status.holding) || tick_queued) return;
          tick_queued=true; serial=status.serial; }
        auto* tick=new(std::nothrow) Tick{this,serial};
        if(!tick) {
            { std::lock_guard<std::mutex> lock(mutex); tick_queued=false; }
            publish_failure(Pixel17HciStatus::DispatchFailed); return;
        }
        const auto now=clock.now(clock.clock_context);
        if(!main->post(tick_task,tick,tick_destroy,now+1000000)) publish_failure(Pixel17HciStatus::DispatchFailed);
    }
    void monitor() {
        // The watchdog may update scalar state and cancel queued work while BT
        // main is stalled. It never invokes a platform getter or HCI off-main.
        main->poll();
        sample_clock();
        const bool stalled=main->quarantined();
        const bool physical=hci->outstanding()!=0;
        const auto now=now_ns();
        uint64_t expired_lease=0,expired_serial=0;
        {
            std::lock_guard<std::mutex> lock(mutex);
            if(status.holding && (stalled || now>=status.lease_deadline_ns)) {
                expired_lease=status.lease_owner; expired_serial=status.serial;
            }
        }
        if(expired_serial) end_for(expired_lease,expired_serial,
                                   stalled ? Pixel17HciStatus::DispatchFailed : Pixel17HciStatus::Timeout,false,true);
        std::lock_guard<std::mutex> lock(mutex);
        if(clock_waiting && now>=waiting_clock.deadline_ns) {
            detach_clock_locked();
#ifdef __ANDROID__
            __android_log_print(ANDROID_LOG_WARN,"LibrePodsGame","clock admission deadline expired request=%llu attempts=%u",
                static_cast<unsigned long long>(status.clock_request_serial),clock_attempts);
#endif
        }
        if(!status.busy) return;
        if(stalled || (!status.cancelled && !finishing && now>=status.deadline_ns)) {
            status.cancelled=true;
            if(status.error==Pixel17HciStatus::Ok)
                status.error=now>=status.deadline_ns ? Pixel17HciStatus::Timeout : Pixel17HciStatus::DispatchFailed;
        }
        if(!status.cancelled && status.phase!=Pixel17GamePhase::Quarantined) return;
        if(!status.restore_pending && !physical) {
            status.busy=false; status.phase=Pixel17GamePhase::Failed; return;
        }
        status.phase=Pixel17GamePhase::Quarantined;
        if(!monitor_deadline_ns) monitor_deadline_ns=now>UINT64_MAX-2000000000ULL ? UINT64_MAX : now+2000000000ULL;
        // Keep the raw native request and restore obligation, but stop active
        // polling after this finite recovery window. Its late reclaim signal can
        // wake one bounded same-owner cleanup/verification attempt afterward.
        if(now>=monitor_deadline_ns) status.busy=false;
    }
    static void reclaimed(void* opaque) {
        auto& r=*static_cast<Runtime*>(opaque);
        {
            std::lock_guard<std::mutex> lock(r.mutex);
            if(!r.status.restore_pending || (r.restore_attempted && r.late_verification_attempted)) return;
            r.late_reclaimed=true; r.status.busy=true; r.status.phase=Pixel17GamePhase::Quarantined;
            r.monitor_deadline_ns=r.now_ns()+2000000000ULL;
        }
        r.wake.notify_one();
    }
    static void start_task(void* opaque) {
        auto& start=*static_cast<Start*>(opaque); start.ran=true;
        auto& r=*start.runtime;
        Pixel17HciOwner owner; bool cancelled;
        { std::lock_guard<std::mutex> lock(r.mutex);
          if(r.status.serial!=start.serial || !r.status.busy || r.status.phase!=Pixel17GamePhase::Reading) return;
          owner=r.status.owner; cancelled=r.status.cancelled; }
        if(cancelled) { r.restore(); return; }
        if(!owner_current(&r,owner)) { r.publish_failure(Pixel17HciStatus::StaleOwner); return; }
        r.command(Pixel17GamePhase::Reading,false);
    }
    static void delete_start(void* opaque) {
        std::unique_ptr<Start> start(static_cast<Start*>(opaque));
        if(start->ran) return;
        auto& r=*start->runtime;
        bool current;
        { std::lock_guard<std::mutex> lock(r.mutex);
          current=r.status.serial==start->serial && r.status.busy; }
        if(current) r.publish_failure(Pixel17HciStatus::DispatchFailed);
    }
    static void refresh_task(void* opaque) {
        auto& r=*static_cast<Runtime*>(opaque);
        if(pixel17_game_host_is_event_thread()) pixel17_game_host_refresh_on_event_thread();
        const auto host=pixel17_game_host_snapshot();
        std::lock_guard<std::mutex> lock(r.mutex); r.status.host=host;
    }
    static void clock_task(void* opaque) {
        auto& task=*static_cast<ClockBegin*>(opaque); task.ran=true;
        auto& r=*task.runtime;
        {
            std::lock_guard<std::mutex> lock(r.mutex);
            if(task.epoch!=r.clock_epoch || task.request!=r.status.clock_request_serial ||
               task.serial!=r.status.serial || task.lease!=r.status.lease_owner ||
               !r.status.clock_pending || !r.status.holding || r.status.cancelled) return;
            r.clock_retry_queued=false; r.clock_waiting=false;
            opportunity_pending.store(false,std::memory_order_release);
            ++r.clock_attempts;
        }
        const bool current=owner_current(&r,task.owner);
        bool valid;
        {
            std::lock_guard<std::mutex> lock(r.mutex);
            valid=current && task.epoch==r.clock_epoch && r.status.holding && !r.status.cancelled &&
                r.now_ns()<r.status.lease_deadline_ns && r.now_ns()<task.deadline_ns && r.status.host.streaming;
            if(valid) r.clock_starting=true;
        }
        EncoderClockAdmission admission;
        const auto serial=valid ? encoder_clock_begin_checked(task.owner.peer,6,task.owner.encoder_generation,
                                                              task.interval,task.drift,&admission) : 0;
        const auto actual=encoder_clock_status();
        bool cancelled=false;
        {
            std::lock_guard<std::mutex> lock(r.mutex);
            cancelled=task.epoch!=r.clock_epoch || task.request!=r.status.clock_request_serial ||
                task.serial!=r.status.serial || !r.status.holding || r.status.cancelled ||
                r.now_ns()>=r.status.lease_deadline_ns || r.now_ns()>=task.deadline_ns;
            if(!cancelled) {
                r.status.clock_pending=false; r.clock_starting=false;
                if(serial && actual.serial==serial) {
                    r.owned_clock=serial; r.status.clock=actual; r.status.clock_observed_ns=r.now_ns();
                } else if(valid && (admission.reason==EncoderClockRejection::MediaAge ||
                          admission.reason==EncoderClockRejection::DueDebt || admission.reason==EncoderClockRejection::QueueFull)) {
                    r.status.clock_pending=true; r.clock_waiting=true; r.waiting_clock=task;
                    opportunity_pending.store(true,std::memory_order_release);
                }
            }
#ifdef __ANDROID__
            if(r.clock_attempts==1 || serial || !r.clock_waiting)
                __android_log_print(serial ? ANDROID_LOG_INFO : ANDROID_LOG_WARN,"LibrePodsGame",
                    "clock admission request=%llu attempt=%u owner_ready=%d reason=%u queue=%zu due_lag_us=%llu media_age_us=%llu admitted_serial=%llu waiting=%d",
                    static_cast<unsigned long long>(task.request),r.clock_attempts,valid,static_cast<unsigned>(admission.reason),
                    admission.queued,static_cast<unsigned long long>(admission.due_lag_us),
                    static_cast<unsigned long long>(admission.media_age_us),static_cast<unsigned long long>(serial),r.clock_waiting);
#endif
        }
        if(cancelled) {
            if(serial) encoder_clock_cancel(serial);
            const auto cancelled_clock=encoder_clock_status();
            std::lock_guard<std::mutex> lock(r.mutex);
            if(task.serial==r.status.serial && task.request==r.status.clock_request_serial) {
                r.clock_starting=false; r.status.clock_pending=false;
                if(serial && cancelled_clock.serial==serial) {
                    r.status.clock=cancelled_clock; r.status.clock_observed_ns=r.now_ns();
                }
            }
        }
    }
    static void delete_clock(void* opaque) {
        std::unique_ptr<ClockBegin> task(static_cast<ClockBegin*>(opaque));
        if(task->ran) return;
        auto& r=*task->runtime;
        std::lock_guard<std::mutex> lock(r.mutex);
        if(task->epoch==r.clock_epoch && task->request==r.status.clock_request_serial) {
            r.clock_retry_queued=false;
            r.detach_clock_locked();
        }
    }
    void opportunity() {
        ClockBegin request;
        {
            std::lock_guard<std::mutex> lock(mutex);
            if(!clock_waiting || clock_retry_queued || clock_starting || !status.holding || status.cancelled ||
               now_ns()>=waiting_clock.deadline_ns || now_ns()>=status.lease_deadline_ns) return;
            request=waiting_clock; request.ran=false;
            clock_retry_queued=true;
        }
        auto* task=new(std::nothrow) ClockBegin(request);
        if(!task) {
            std::lock_guard<std::mutex> lock(mutex);
            if(request.epoch==clock_epoch) { clock_retry_queued=false; detach_clock_locked(); }
            return;
        }
        main->post(clock_task,task,delete_clock,request.deadline_ns/1000);
    }
};
std::mutex setup_mutex;
// One bounded process-lifetime native owner. It deliberately survives app/Binder
// death, as do platform closures containing its addresses. Native code is not
// unloadable while these contexts or a controller restore obligation survive.
Runtime* runtime=nullptr;
Runtime* get() { std::lock_guard<std::mutex> lock(setup_mutex); return runtime; }
}
bool pixel17_game_runtime_prepare(Pixel17MainBindings main,Pixel17HciBindings hci) {
    std::lock_guard<std::mutex> lock(setup_mutex);
    if(runtime || !main.now || !main.main_thread || !main.construct || !main.post ||
       !main.destroy_callback || !hci.transmit || !hci.allocate || !hci.release ||
       !hci.view_size || !hci.view_byte || !hci.now) return false;
    try {
        auto candidate=std::make_unique<Runtime>();
        candidate->clock=main; candidate->main=std::make_shared<Pixel17MainDispatcher>(main);
        hci.is_main=Runtime::on_main; hci.owner_current=Runtime::owner_current;
        hci.owner_context=candidate.get();
        hci.reclaimed=Runtime::reclaimed;
        candidate->hci=std::make_unique<Pixel17Hci>(hci,candidate->main);
        candidate->status.supported=true; candidate->status.phase=Pixel17GamePhase::Idle;
#ifndef ELD_PIXEL17_GAME_RUNTIME_TESTING
        // The helper app may die during a write. This watchdog remains in the
        // Bluetooth owner and dispatches bounded cleanup without app polling.
        auto* r=candidate.get();
        std::thread([r] {
            for(;;) {
                { std::unique_lock<std::mutex> lock(r->mutex);
                  r->wake.wait(lock,[r]{ return r->status.busy || r->status.holding; }); }
                r->monitor(); r->queue_tick();
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
            }
        }).detach();
#endif
        runtime=candidate.release(); return true;
    } catch(...) { return false; }
}
bool pixel17_game_refresh() {
    auto* r=get();
    return r && r->main->post(Runtime::refresh_task,r,nullptr,r->clock.now(r->clock.clock_context)+1000000);
}
uint64_t pixel17_game_submit(Pixel17GameOperation operation,uint64_t lease,
                             const Pixel17HciOwner& expected,uint64_t deadline) {
    auto* r=get(); if(!r || !lease || (operation!=Pixel17GameOperation::Read &&
        operation!=Pixel17GameOperation::Roundtrip && operation!=Pixel17GameOperation::Prepare)) return 0;
    const auto now=r->now_ns();
    if(deadline<=now || deadline-now>6000000000ULL) return 0;
    const auto host=pixel17_game_host_snapshot();
    uint64_t serial;
    {
        std::lock_guard<std::mutex> lock(r->mutex);
        if(!r->status.supported || r->status.busy || r->status.holding || r->status.restore_pending ||
           r->status.clock_pending || r->clock_starting ||
           r->hci->outstanding() || r->next==UINT64_MAX) return 0;
        serial=++r->next;
        r->status={}; r->status.supported=true; r->status.busy=true;
        r->status.phase=Pixel17GamePhase::Reading; r->status.operation=operation;
        r->status.serial=serial; r->status.lease_owner=lease; r->status.owner=expected;
        r->status.deadline_ns=deadline; r->status.host=host;
        r->original_cid=host.media_cid; r->baseline={};
        r->restore_attempted=false; r->finishing=false; r->hci_serial=0;
        r->monitor_deadline_ns=0; r->late_reclaimed=false; r->late_verification_attempted=false;
        r->graceful_restore=false;
    }
    auto* start=new(std::nothrow) Runtime::Start{r,serial};
    if(!start || !r->main->post(Runtime::start_task,start,Runtime::delete_start,deadline/1000)) {
        r->publish_failure(Pixel17HciStatus::DispatchFailed); return 0;
    }
    r->wake.notify_one(); return serial;
}
bool pixel17_game_heartbeat(uint64_t lease,uint64_t serial) {
    auto* r=get(); if(!r || !lease || !serial) return false;
    bool expired=false;
    const bool stalled=r->main->quarantined();
    {
        std::lock_guard<std::mutex> lock(r->mutex);
        if(r->status.lease_owner!=lease || r->status.serial!=serial ||
           !r->status.holding || r->status.cancelled) return false;
        const auto now=r->now_ns();
        expired=stalled || now>=r->status.lease_deadline_ns;
        if(!expired) r->status.lease_deadline_ns=Runtime::grace(now);
    }
    if(expired) r->end_for(lease,serial,stalled ? Pixel17HciStatus::DispatchFailed : Pixel17HciStatus::Timeout,false,true);
    return !expired;
}
bool pixel17_game_restore(uint64_t lease,uint64_t serial) {
    auto* r=get(); if(!r || !r->end_for(lease,serial,Pixel17HciStatus::Ok,true)) return false;
    r->queue_tick(); return true;
}
uint64_t pixel17_game_clock_begin(uint64_t lease,uint64_t serial,const Pixel17HciOwner& expected,
                                  uint32_t interval,uint32_t drift) {
    auto* r=get();
    if(!r || !lease || !serial || (interval!=9 && interval!=11) || !drift || drift>500 ||
       r->main->quarantined()) return 0;
    const auto actual=encoder_clock_status();
    if(actual.phase==EncoderClockPhase::Armed || actual.phase==EncoderClockPhase::Active) return 0;
    uint64_t request,epoch,deadline;
    {
        std::lock_guard<std::mutex> lock(r->mutex);
        if(r->status.lease_owner!=lease || r->status.serial!=serial || !r->status.holding ||
           r->status.cancelled || r->status.busy || r->status.clock_pending ||
           !pixel17_same_hci_owner(expected,r->status.owner) || r->now_ns()>=r->status.lease_deadline_ns ||
           r->clock_epoch==UINT64_MAX || r->next_clock_request==UINT64_MAX) return 0;
        request=++r->next_clock_request; epoch=++r->clock_epoch;
        r->clock_attempts=0; r->clock_waiting=false; r->clock_retry_queued=false;
        r->status.clock_request_serial=request; r->status.clock_pending=true;
        r->owned_clock=0;
        r->status.clock={}; r->status.clock_observed_ns=0;
        deadline=std::min(r->status.lease_deadline_ns/1000,r->clock.now(r->clock.clock_context)+1000000);
    }
    auto* task=new(std::nothrow) Runtime::ClockBegin{r,serial,lease,request,epoch,expected,interval,drift};
    if(!task) {
        std::lock_guard<std::mutex> lock(r->mutex);
        if(r->clock_epoch==epoch) r->status.clock_pending=false;
        return 0;
    }
    task->deadline_ns=deadline*1000;
    if(!r->main->post(Runtime::clock_task,task,Runtime::delete_clock,deadline)) return 0;
    return request;
}
bool pixel17_game_clock_cancel(uint64_t lease,uint64_t serial) {
    auto* r=get(); if(!r || !lease || !serial) return false;
    uint64_t clock;
    {
        std::lock_guard<std::mutex> lock(r->mutex);
        if(r->status.lease_owner!=lease || r->status.serial!=serial) return false;
        clock=r->detach_clock_locked();
    }
    r->cancel_clock(clock); return true;
}
void pixel17_game_cancel_owner(uint64_t lease) {
    auto* r=get(); if(!r || !lease) return;
    uint64_t serial;
    { std::lock_guard<std::mutex> lock(r->mutex); serial=r->status.serial; }
    if(r->end_for(lease,serial,Pixel17HciStatus::Cancelled)) r->queue_tick();
}
Pixel17GameStatus pixel17_game_status() {
    auto* r=get(); if(!r) return {};
    std::lock_guard<std::mutex> lock(r->mutex); return r->status;
}
void pixel17_game_invalidated(Pixel17GameInvalidation,const Pixel17GameHostState& previous) noexcept {
    auto* r=get(); if(!r) return;
    uint64_t lease,serial;
    { std::lock_guard<std::mutex> lock(r->mutex);
      if((!r->status.busy && !r->status.holding && !r->status.restore_pending) ||
          r->status.owner.media_generation!=previous.media_generation ||
          r->status.owner.acl_handle!=previous.acl_handle || std::memcmp(r->status.owner.peer,previous.peer,6)) return;
      lease=r->status.lease_owner; serial=r->status.serial; }
    r->end_for(lease,serial,Pixel17HciStatus::StaleOwner);
}
void pixel17_game_runtime_disable() {
    auto* r=get(); if(!r) return;
    uint64_t lease,serial;
    { std::lock_guard<std::mutex> lock(r->mutex);
      r->status.supported=false;
      lease=r->status.lease_owner; serial=r->status.serial; }
    if(r->end_for(lease,serial,Pixel17HciStatus::Cancelled)) r->queue_tick();
}
void pixel17_game_clock_opportunity() {
    if(!opportunity_pending.load(std::memory_order_acquire)) return;
    if(auto* r=get()) r->opportunity();
}
#ifdef ELD_PIXEL17_GAME_RUNTIME_TESTING
void pixel17_game_runtime_tick_for_test() { if(auto* r=get()) r->queue_tick(); }
void pixel17_game_runtime_monitor_for_test() { if(auto* r=get()) { r->monitor(); r->queue_tick(); } }
#endif
}
