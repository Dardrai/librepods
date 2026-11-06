// SPDX-License-Identifier: GPL-3.0-or-later
#include "pixel17_packet_policy.h"
#include <algorithm>
#include <atomic>
#include <cstring>

namespace eld {
namespace {
uint16_t le16(const uint8_t* p) { return uint16_t(p[0]) | uint16_t(p[1]) << 8; }
bool peer_valid(const uint8_t* p) {
    uint8_t any=0, all=255;
    for(size_t i=0;i<6;++i) { any|=p[i]; all&=p[i]; }
    return any && all!=255;
}
Pixel17PacketPolicy policy;
Pixel17PacketBindings bindings;
std::atomic<bool> prepared{false};
void* command_original=nullptr;
void* event_original=nullptr;
thread_local bool sending=false;
void command_hook(const void* self,uint16_t handle,uint16_t mask) {
    if(prepared.load(std::memory_order_acquire)) policy.command(handle,mask,sending);
    reinterpret_cast<void (*)(const void*,uint16_t,uint16_t)>(command_original)(self,handle,mask);
}
void event_hook(void* self,const void* view) {
    if(prepared.load(std::memory_order_acquire) && view) {
        // Copy only the four relevant, bounded HCI events; retain no native view.
        const auto size=bindings.view_size(view);
        if(size>=2 && size<=13) {
            const auto code=bindings.view_byte(view,0);
            if(code==3 || code==5 || code==0x0f || code==0x1d) {
                uint8_t bytes[13]{};
                for(size_t i=0;i<size;++i) bytes[i]=bindings.view_byte(view,i);
                policy.event(bytes,size);
                if(bindings.changed) bindings.changed();
            }
        }
    }
    reinterpret_cast<void (*)(void*,const void*)>(event_original)(self,view);
}
}
Pixel17PacketPolicy::Link* Pixel17PacketPolicy::link(uint16_t handle) {
    for(auto& l:links_) if(l.epoch && l.handle==handle) return &l;
    return nullptr;
}
void Pixel17PacketPolicy::command(uint16_t handle,uint16_t mask,bool owned) {
    std::lock_guard<std::mutex> lock(mutex_);
    if(physical_ || (status_.dirty && !owned)) {
        poisoned_=true; status_.error=Pixel17HciStatus::UnexpectedStatus; status_.ready=false;
        return;
    }
    physical_=true; pending_handle_=handle; accepted_=false;
    owned_=owned && armed_ && owner_.acl_handle==handle && requested_==mask;
    if(owned && !owned_) { poisoned_=true; status_.error=Pixel17HciStatus::UnexpectedStatus; }
    if(owned_) armed_=false;
}
void Pixel17PacketPolicy::event(const uint8_t* p,size_t size) {
    if(!p || size<2 || size!=size_t(p[1])+2) return;
    std::lock_guard<std::mutex> lock(mutex_);
    if(p[0]==3 && size==13 && p[2]==0 && p[11]==1) {
        const auto handle=le16(p+3);
        if(handle>0xeff || !peer_valid(p+5) || next_epoch_==UINT64_MAX) return;
        auto* l=link(handle);
        if(!l) for(auto& slot:links_) if(!slot.epoch) { l=&slot; break; }
        if(!l) return;
        if(epoch_ && owner_.acl_handle==handle) {
            status_.error=Pixel17HciStatus::StaleOwner; status_.ready=false;
            // A new Connection Complete is proof that the old ACL no longer exists.
            status_.dirty=false; status_.pending=false;
        }
        *l={}; l->handle=handle; l->epoch=++next_epoch_;
        for(size_t i=0;i<6;++i) l->peer[i]=p[10-i];
        return;
    }
    if(p[0]==5 && size==6 && p[2]==0) {
        const auto handle=le16(p+3);
        if(auto* l=link(handle)) *l={};
        if(physical_ && pending_handle_==handle) physical_=false;
        if(epoch_ && owner_.acl_handle==handle) {
            status_.dirty=false; status_.pending=false; status_.ready=false;
            status_.error=Pixel17HciStatus::StaleOwner;
        }
        return;
    }
    if(p[0]==0x0f && size==6 && le16(p+4)==0x040f && physical_ && !poisoned_) {
        accepted_=p[2]==0;
        if(!accepted_) {
            physical_=false;
            if(owned_) {
                status_.pending=false; status_.ready=false;
                // A rejected command cannot have changed the captured mask.
                status_.dirty=status_.current!=status_.baseline;
                status_.error=Pixel17HciStatus::ControllerError;
            }
        }
        return;
    }
    if(p[0]!=0x1d || size!=7 || poisoned_) return;
    const auto handle=le16(p+3), mask=le16(p+5);
    auto* l=link(handle);
    if(!l) return;
    const bool completion=physical_ && pending_handle_==handle && accepted_;
    if(p[2]==0 && (!physical_ || completion)) {
        l->mask=mask; l->known=true;
        if(!physical_ && epoch_==l->epoch && status_.known && mask!=status_.current) {
            status_.current=mask; status_.dirty=mask!=status_.baseline;
            status_.ready=false; status_.error=Pixel17HciStatus::UnexpectedStatus;
        }
    }
    if(!completion) return;
    physical_=false;
    if(!owned_) return;
    status_.pending=false; status_.ready=false;
    if(l->epoch!=epoch_ || std::memcmp(l->peer,owner_.peer,6)) {
        status_.error=Pixel17HciStatus::StaleOwner; return;
    }
    if(p[2]) { status_.error=Pixel17HciStatus::ControllerError; return; }
    status_.current=mask; status_.dirty=mask!=status_.baseline;
    if(mask!=requested_) status_.error=Pixel17HciStatus::Malformed;
    else if(status_.error==Pixel17HciStatus::Ok) status_.ready=true;
}
bool Pixel17PacketPolicy::issue(uint16_t mask,uint64_t deadline,void (*send)(uint16_t,uint16_t),
                               std::unique_lock<std::mutex>& lock) {
    if(!send || poisoned_ || physical_) return false;
    requested_=mask; deadline_=deadline; armed_=true;
    status_.pending=true; status_.ready=false; status_.dirty=true;
    const auto handle=owner_.acl_handle;
    lock.unlock(); send(handle,mask); lock.lock();
    if(armed_) {
        // The validated native command observer must witness the exact submission.
        armed_=false; poisoned_=true; status_.error=Pixel17HciStatus::UnexpectedStatus;
        return false;
    }
    return true;
}
bool Pixel17PacketPolicy::begin(const Pixel17HciOwner& owner,uint64_t deadline,uint64_t now,
                               void (*send)(uint16_t,uint16_t)) {
    std::unique_lock<std::mutex> lock(mutex_);
    auto* l=link(owner.acl_handle);
    if(!l || !l->known || poisoned_ || physical_ || status_.dirty || status_.pending ||
       !owner.media_generation || !owner.encoder_generation || !peer_valid(owner.peer) ||
       std::memcmp(l->peer,owner.peer,6) || deadline<=now || deadline-now>1000000) return false;
    owner_=owner; epoch_=l->epoch; status_={};
    status_.known=true; status_.baseline=l->mask; status_.current=l->mask;
    // EDR exclusions have negative polarity: prohibit only 3-DH1/3/5, retaining
    // the controller-confirmed BR/2-Mbit permissions instead of assuming CC18.
    const auto target=uint16_t(l->mask|0x2204);
    if(target==l->mask) { status_.ready=true; return true; }
    return issue(target,deadline,send,lock);
}
bool Pixel17PacketPolicy::restore(const Pixel17HciOwner& owner,uint64_t deadline,uint64_t now,
                                 void (*send)(uint16_t,uint16_t)) {
    std::unique_lock<std::mutex> lock(mutex_);
    auto* l=link(owner.acl_handle);
    if(!status_.dirty) return true;
    if(!l || l->epoch!=epoch_ || !pixel17_same_hci_owner(owner_,owner) ||
       physical_ || status_.pending || deadline<=now || deadline-now>1000000) return false;
    return issue(status_.baseline,deadline,send,lock);
}
Pixel17PacketStatus Pixel17PacketPolicy::status(uint64_t now) {
    std::lock_guard<std::mutex> lock(mutex_);
    if(status_.pending && now>=deadline_) { status_.error=Pixel17HciStatus::Timeout; status_.ready=false; }
    return status_;
}
bool pixel17_packet_prepare(Pixel17PacketBindings b) {
    if(prepared.load() || !b.view_size || !b.view_byte || !b.send || !b.event_name ||
       (std::strcmp(b.event_name,kPixel17PacketEvent) && std::strcmp(b.event_name,kPixel17PacketEventRef))) return false;
    bindings=b; prepared.store(true,std::memory_order_release); return true;
}
const Pixel17WireHookSpec* pixel17_packet_hook_specs(size_t* count) {
    static const Pixel17WireHookSpec hooks[]={
        {kPixel17PacketCommand,reinterpret_cast<void*>(&command_hook),&command_original},
        {bindings.event_name,reinterpret_cast<void*>(&event_hook),&event_original}};
    if(count) *count=2;
    return hooks;
}
Pixel17PacketPolicy& pixel17_packet_policy() { return policy; }
void pixel17_packet_send(uint16_t handle,uint16_t mask) {
    sending=true; bindings.send(handle,mask); sending=false;
}
}
