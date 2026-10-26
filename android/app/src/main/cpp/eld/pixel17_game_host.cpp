// SPDX-License-Identifier: GPL-3.0-or-later
#include "pixel17_game_host.h"
#include "wire.h"
#include <array>
#include <atomic>
#include <cstring>
#include <mutex>
#include <thread>

namespace eld {
namespace {
enum Hook : size_t { Open, Start, Stop, Close, Reconfigure, Delay, Sco, Count };
void* originals[Count]{};
Pixel17GameBindings host{};
Pixel17GameLayout layout{};
std::atomic<bool> prepared{false};
std::mutex mutex;
Pixel17GameHostState state{};
const void* state_scb = nullptr;
uint64_t revision = 0, next_generation = 0, next_delay_serial = 0;
std::thread::id event_thread;

template<class T> T original(Hook hook) { return reinterpret_cast<T>(originals[hook]); }
bool equal_peer(const uint8_t* a, const uint8_t* b) { return !std::memcmp(a,b,6); }
bool valid_peer(const uint8_t* peer) {
    uint8_t any=0, all=0xff;
    for(size_t i=0;i<6;++i) { any|=peer[i]; all&=peer[i]; }
    return any && all!=0xff;
}
bool valid_acl(uint16_t handle) { return handle<=0x0eff; }
bool read(const void* source,size_t offset,void* out,size_t size) {
    const auto address=reinterpret_cast<uintptr_t>(source);
    return source && offset<=UINTPTR_MAX-address && size<=UINTPTR_MAX-address-offset &&
        host.copy(reinterpret_cast<const void*>(address+offset),size,out);
}
template<class T> bool read(const void* source,size_t offset,T* out) {
    return read(source,offset,out,sizeof(*out));
}
bool valid_layout(const Pixel17GameLayout& value) {
    const size_t offsets[]={value.media_cid,value.bta_handle,value.avdt_handle,value.co_started,
        value.peer,value.codec,value.avdt_reporting_mask,value.avdt_self_handle};
    for(auto offset:offsets) if(!offset || offset>16384) return false;
    return !(value.media_cid&1) && !(value.avdt_reporting_mask&1) &&
        value.bta_handle!=value.avdt_handle && value.peer!=value.codec &&
        value.co_started!=value.bta_handle && value.co_started!=value.avdt_handle;
}
void notify(Pixel17GameInvalidation reason,const Pixel17GameHostState& previous) {
    if(previous.media_generation) host.invalidated(reason,previous);
}
bool enter(bool latch=true) {
    if(!prepared.load(std::memory_order_acquire)) return false;
    Pixel17GameHostState previous;
    bool wrong=false;
    {
        std::lock_guard<std::mutex> lock(mutex);
        if(!prepared.load(std::memory_order_relaxed)) return false;
        const auto thread=std::this_thread::get_id();
        if(event_thread==std::thread::id{}) {
            if(!latch) return false;
            event_thread=thread;
        }
        if(event_thread==thread) return true;
        previous=state;
        state.route_valid=false; state.streaming=false; state.started_ns=0;
        ++revision; wrong=true;
    }
    if(wrong) notify(Pixel17GameInvalidation::WrongThread,previous);
    return false;
}
uint64_t fence() { std::lock_guard<std::mutex> lock(mutex); return revision; }
bool same_channel(const Pixel17GameHostState& a,const Pixel17GameHostState& b,const void* scb) {
    // A pre-Open report has no media CID yet. It belongs to this exact native SCB/AVDT/ACL.
    return state_scb==scb && a.media_generation && equal_peer(a.peer,b.peer) &&
        a.bta_handle==b.bta_handle && a.avdt_handle==b.avdt_handle && a.acl_handle==b.acl_handle &&
        (!a.media_cid || a.media_cid==b.media_cid);
}
struct Candidate { Pixel17GameHostState value; bool co_started=false; bool actual_peer=false; };
bool collect(const void* scb,Candidate* out) {
    Candidate result;
    uint8_t started=0, avdt_handle=0;
    uint16_t reporting=0;
    std::array<uint8_t,45> codec{};
    if(!scb || !read(scb,layout.bta_handle,&result.value.bta_handle) ||
        !result.value.bta_handle || host.scb(result.value.bta_handle)!=scb ||
        !read(scb,layout.avdt_handle,&result.value.avdt_handle) || !result.value.avdt_handle ||
        !read(scb,layout.media_cid,&result.value.media_cid) ||
        !read(scb,layout.peer,result.value.peer,6) || !valid_peer(result.value.peer) ||
        !read(scb,layout.co_started,&started) || started>1 ||
        !read(scb,layout.codec,codec.data(),codec.size()) || !valid_configuration(codec.data(),codec.size())) return false;
    const void* avdt=host.avdt_scb(result.value.avdt_handle);
    if(!avdt || !read(avdt,layout.avdt_self_handle,&avdt_handle) || avdt_handle!=result.value.avdt_handle ||
        !read(avdt,layout.avdt_reporting_mask,&reporting)) return false;
    result.value.reporting=(reporting&0x100)!=0;
    result.value.acl_handle=host.peer_acl(result.value.peer,1); // BR/EDR only.
    if(!valid_acl(result.value.acl_handle)) return false;
    const auto actual_cid=host.media_cid(result.value.avdt_handle);
    if(result.value.media_cid!=actual_cid) return false;
    if(actual_cid) {
        uint16_t acl=0xffff;
        if(actual_cid<0x40 || actual_cid==0xffff || !host.cid_acl(actual_cid,&acl) ||
            !valid_acl(acl) || acl!=result.value.acl_handle) return false;
    }
    uint8_t active[6]{};
    result.actual_peer=host.active_peer(active,6) && valid_peer(active) && equal_peer(active,result.value.peer);
    Pixel17GameEncoder encoder;
    if(host.encoder(&encoder)) {
        result.value.encoder_generation=encoder.generation;
        result.value.encoder_ready=encoder.generation && encoder.active && !encoder.failed &&
            equal_peer(encoder.peer,result.value.peer);
    }
    result.value.native_sco=host.sco_count()!=0 || host.peer_sco(result.value.peer);
    result.value.observed_ns=host.now_ns();
    if(!result.value.observed_ns) return false;
    result.value.route_valid=actual_cid && result.actual_peer && result.value.encoder_ready;
    result.co_started=started!=0;
    *out=result;
    return true;
}
bool observe(const void* scb,uint64_t expected,bool accepted_start,bool has_delay=false,uint16_t delay=0,
             uint8_t expected_bta=0,const uint8_t* expected_peer=nullptr) {
    Candidate next;
    if(!collect(scb,&next)) {
        Pixel17GameHostState previous;
        {
            std::lock_guard<std::mutex> lock(mutex);
            if(revision!=expected || state_scb!=scb || !prepared.load()) return false;
            previous=state; state.route_valid=false; state.streaming=false; state.started_ns=0; ++revision;
        }
        notify(Pixel17GameInvalidation::InvalidRoute,previous);
        return false;
    }
    if((expected_bta && expected_bta!=next.value.bta_handle) ||
        (expected_peer && !equal_peer(expected_peer,next.value.peer))) return false;
    Pixel17GameHostState previous;
    Pixel17GameInvalidation reason=Pixel17GameInvalidation::RouteChanged;
    bool invalidate=false;
    {
        std::lock_guard<std::mutex> lock(mutex);
        if(revision!=expected || !prepared.load()) return false;
        // An unrelated pre-Open peer cannot displace the actual selected route.
        if(state.route_valid && !next.actual_peer && !equal_peer(state.peer,next.value.peer)) return false;
        previous=state;
        const bool same=same_channel(state,next.value,scb);
        if(!same) {
            if(next_generation==UINT64_MAX) return false;
            next.value.media_generation=++next_generation;
            invalidate=previous.media_generation!=0;
        } else {
            next.value.media_generation=state.media_generation;
            next.value.started_ns=state.started_ns;
            next.value.streaming=state.streaming && next.co_started && next.actual_peer;
            next.value.delay_serial=state.delay_serial;
            next.value.delay_received_ns=state.delay_received_ns;
            next.value.delay_tenths=state.delay_tenths;
            next.value.delay_valid=state.delay_valid && next.value.reporting;
            if(state.encoder_generation && next.value.encoder_generation!=state.encoder_generation) {
                invalidate=true; reason=Pixel17GameInvalidation::EncoderChanged;
            }
            if(state.route_valid && !next.value.route_valid) {
                invalidate=true; reason=Pixel17GameInvalidation::InvalidRoute;
                next.value.started_ns=0; next.value.streaming=false;
            }
        }
        if(accepted_start && next.co_started && next.actual_peer) {
            if(!next.value.streaming || !next.value.started_ns) next.value.started_ns=next.value.observed_ns;
            next.value.streaming=true;
        }
        if(!next.co_started || !next.actual_peer) { next.value.streaming=false; next.value.started_ns=0; }
        if(has_delay) {
            if(next_delay_serial==UINT64_MAX) return false;
            next.value.delay_serial=++next_delay_serial;
            next.value.delay_received_ns=next.value.observed_ns;
            next.value.delay_tenths=delay;
            next.value.delay_valid=next.value.reporting && delay!=0 && delay!=0xffff;
        }
        if(!state.native_sco && next.value.native_sco) { invalidate=true; reason=Pixel17GameInvalidation::Sco; }
        state=next.value; state_scb=scb; ++revision;
    }
    if(invalidate) notify(reason,previous);
    return true;
}
void retire(Pixel17GameInvalidation reason,const void* scb=nullptr,uint8_t bta=0,const void* address=nullptr) {
    Pixel17GameHostState previous;
    {
        std::lock_guard<std::mutex> lock(mutex);
        if((scb && state_scb!=scb) || (bta && state.bta_handle!=bta) ||
            (address && !equal_peer(state.peer,static_cast<const uint8_t*>(address)))) return;
        previous=state;
        if(reason==Pixel17GameInvalidation::Stop || reason==Pixel17GameInvalidation::Sco) {
            state.streaming=false; state.started_ns=0;
            if(reason==Pixel17GameInvalidation::Sco) state.native_sco=true;
        } else {
            state={}; state_scb=nullptr;
            if(next_generation!=UINT64_MAX) state.media_generation=++next_generation;
        }
        ++revision;
    }
    notify(reason,previous);
}
void opened(void* scb,void* data) {
    const bool ready=enter(); const auto before=fence();
    const auto next=original<void (*)(void*,void*)>(Open); if(next) next(scb,data);
    if(ready && enter()) observe(scb,before,false);
}
void started(void* scb,void* data) {
    const bool ready=enter(); const auto before=fence();
    const auto next=original<void (*)(void*,void*)>(Start); if(next) next(scb,data);
    if(ready && enter()) observe(scb,before,true);
}
void stopped(void* scb,void* data) {
    if(enter()) retire(Pixel17GameInvalidation::Stop,scb);
    const auto next=original<void (*)(void*,void*)>(Stop); if(next) next(scb,data);
}
void closed(uint8_t bta,const void* peer) {
    // Copy the callback argument while it is owned by the native caller, before original reset.
    uint8_t saved[6]{};
    if(enter() && peer && host.copy(peer,6,saved)) retire(Pixel17GameInvalidation::Close,nullptr,bta,saved);
    const auto next=original<void (*)(uint8_t,const void*)>(Close); if(next) next(bta,peer);
}
void reconfigured(void* scb,void* data) {
    const bool ready=enter();
    if(ready) retire(Pixel17GameInvalidation::Reconfigure,scb);
    const auto before=fence();
    const auto next=original<void (*)(void*,void*)>(Reconfigure); if(next) next(scb,data);
    if(ready && enter()) observe(scb,before,false);
}
void delayed(uint8_t bta,const void* peer,uint16_t tenths) {
    const bool ready=enter(); const auto before=fence();
    uint8_t saved[6]{}; const bool valid=ready && peer && host.copy(peer,6,saved) && valid_peer(saved);
    const auto next=original<void (*)(uint8_t,const void*,uint16_t)>(Delay); if(next) next(bta,peer,tenths);
    if(valid && enter()) observe(host.scb(bta),before,false,true,tenths,bta,saved);
}
void sco_changed(uint32_t status,uint8_t id,uint8_t app,const void* peer) {
    const bool ready=enter();
    if(ready) retire(Pixel17GameInvalidation::Sco);
    const auto next=original<void (*)(uint32_t,uint8_t,uint8_t,const void*)>(Sco);
    if(next) next(status,id,app,peer);
    if(ready) pixel17_game_host_refresh_on_event_thread();
}
const Pixel17WireHookSpec hooks[]={
    {kPixel17GameOpen,reinterpret_cast<void*>(opened),&originals[Open]},
    {kPixel17GameStart,reinterpret_cast<void*>(started),&originals[Start]},
    {kPixel17GameStop,reinterpret_cast<void*>(stopped),&originals[Stop]},
    {kPixel17GameClose,reinterpret_cast<void*>(closed),&originals[Close]},
    {kPixel17GameReconfigure,reinterpret_cast<void*>(reconfigured),&originals[Reconfigure]},
    {kPixel17GameDelay,reinterpret_cast<void*>(delayed),&originals[Delay]},
    {kPixel17GameSco,reinterpret_cast<void*>(sco_changed),&originals[Sco]},
};
}
bool pixel17_game_host_prepare(const Pixel17GameBindings& bindings,const Pixel17GameLayout& validated_layout) {
    std::lock_guard<std::mutex> lock(mutex);
    if(prepared.load() || !valid_layout(validated_layout) || !bindings.copy || !bindings.scb ||
        !bindings.avdt_scb || !bindings.media_cid || !bindings.cid_acl || !bindings.peer_acl ||
        !bindings.active_peer || !bindings.sco_count || !bindings.peer_sco || !bindings.encoder ||
        !bindings.now_ns || !bindings.invalidated) return false;
    host=bindings; layout=validated_layout;
    prepared.store(true,std::memory_order_release); return true;
}
const Pixel17WireHookSpec* pixel17_game_host_hook_specs(size_t* count) {
    if(count) *count=Count;
    return hooks;
}
bool pixel17_game_host_refresh_on_event_thread() {
    if(!enter(false)) return false;
    const void* scb; uint64_t before;
    { std::lock_guard<std::mutex> lock(mutex); scb=state_scb; before=revision; }
    return scb && observe(scb,before,false);
}
bool pixel17_game_host_is_event_thread() noexcept {
    std::lock_guard<std::mutex> lock(mutex);
    return prepared.load(std::memory_order_relaxed) && event_thread!=std::thread::id{} &&
        event_thread==std::this_thread::get_id();
}
Pixel17GameHostState pixel17_game_host_snapshot() {
    std::lock_guard<std::mutex> lock(mutex); return state;
}
void pixel17_game_host_disable() {
    if(!prepared.exchange(false,std::memory_order_acq_rel)) return;
    retire(Pixel17GameInvalidation::Disabled);
}
#ifdef ELD_PIXEL17_GAME_HOST_TESTING
void pixel17_game_host_reset_for_test() {
    std::lock_guard<std::mutex> lock(mutex);
    prepared.store(false); host={}; layout={}; state={}; state_scb=nullptr;
    revision=next_generation=next_delay_serial=0; event_thread={};
    for(auto& value:originals) value=nullptr;
}
#endif
}
