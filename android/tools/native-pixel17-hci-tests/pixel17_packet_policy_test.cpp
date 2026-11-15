// SPDX-License-Identifier: GPL-3.0-or-later
#include "pixel17_packet_policy.h"
#include <cassert>
#include <cstring>
#include <vector>
using namespace eld;
namespace {
constexpr bool keep_packet_baseline=ELD_KEEP_GAME_PACKET_BASELINE!=0;
Pixel17PacketPolicy* radio=nullptr;
std::vector<uint16_t> sent;
void send(uint16_t handle,uint16_t mask) { sent.push_back(mask); radio->command(handle,mask,true); }
void event(Pixel17PacketPolicy& p,std::initializer_list<uint8_t> bytes) { p.event(bytes.begin(),bytes.size()); }
void connected(Pixel17PacketPolicy& p) { event(p,{3,11,0,0x34,0x02,6,5,4,3,2,1,1,0}); }
void changed(Pixel17PacketPolicy& p,uint16_t mask) {
    event(p,{0x1d,5,0,0x34,0x02,uint8_t(mask),uint8_t(mask>>8)});
}
void accepted(Pixel17PacketPolicy& p) { event(p,{0x0f,4,0,1,0x0f,4}); }
void foreign_connected(Pixel17PacketPolicy& p) { event(p,{3,11,0,0x35,0x02,7,5,4,3,2,1,1,0}); }
void foreign_changed(Pixel17PacketPolicy& p) { event(p,{0x1d,5,0,0x35,0x02,0x18,0xcc}); }
Pixel17HciOwner owner() {
    Pixel17HciOwner o{{1,2,3,4,5,6},7,8,0x234}; return o;
}
void init(Pixel17PacketPolicy& p,uint16_t baseline=0xcc18) {
    radio=&p; sent.clear(); connected(p); changed(p,baseline);
}
}
int main() {
    {
        Pixel17PacketPolicy p; radio=&p;
        assert(!p.begin(owner(),100,1,send)); // No guessed baseline.
        connected(p); assert(!p.begin(owner(),100,1,send));
        changed(p,0xcc18); auto wrong=owner(); ++wrong.peer[0];
        assert(!p.begin(wrong,100,1,send));
        assert(!p.begin(owner(),1,1,send));
    }
    for(uint16_t baseline:{uint16_t(0xcc18),uint16_t(0xcc08),uint16_t(0xee1c)}) {
        Pixel17PacketPolicy p; init(p,baseline);
        assert(p.begin(owner(),100,1,send));
        const auto target=keep_packet_baseline ? baseline : uint16_t(baseline|0x2204);
        if(baseline==target) {
            const auto status=p.status(2);
            assert(sent.empty() && status.ready && status.known && status.error==Pixel17HciStatus::Ok);
            assert(status.current==baseline && status.baseline==baseline && !status.pending && !status.dirty);
            assert(p.restore(owner(),200,2,send)==Pixel17PacketRestoreResult::Accepted && sent.empty());
            continue;
        }
        assert(sent==std::vector<uint16_t>{target});
        assert(p.status(2).pending && p.status(2).dirty && !p.status(2).ready);
        accepted(p); assert(p.status(2).pending && !p.status(2).ready);
        changed(p,target);
        assert(p.status(2).ready && p.status(2).current==target && p.status(2).baseline==baseline);
        assert(p.restore(owner(),200,2,send)==Pixel17PacketRestoreResult::Accepted);
        assert(sent.back()==baseline && p.status(3).dirty);
        accepted(p); assert(p.status(3).dirty); changed(p,baseline);
        assert(!p.status(3).dirty && !p.status(3).pending && p.status(3).current==baseline);
    }
    if(keep_packet_baseline) {
        {
            Pixel17PacketPolicy p; init(p); foreign_connected(p);
            p.command(0x235,0xcc18,false);
            assert(!p.begin(owner(),100,1,send)); // No overlap with a handle-less status.
            foreign_changed(p);
            assert(!p.begin(owner(),100,1,send)); // Completion alone cannot release the fence.
            accepted(p); foreign_changed(p);
            assert(p.begin(owner(),100,1,send));
            assert(p.status(2).ready && p.status(2).current==0xcc18 && sent.empty());
        }
        {
            Pixel17PacketPolicy p; init(p); foreign_connected(p);
            p.command(0x235,0xcc18,false); p.command(0x236,0xcc18,false);
            accepted(p); foreign_changed(p);
            assert(!p.begin(owner(),100,1,send)); // Ambiguous submissions still fail closed.
            assert(p.status(2).error==Pixel17HciStatus::UnexpectedStatus && sent.empty());
        }
        {
            Pixel17PacketPolicy p; init(p);
            event(p,{5,4,0,0x34,2,0x13}); connected(p);
            assert(!p.begin(owner(),100,1,send)); // Reused handle needs a new observed baseline.
            changed(p,0xcc08); assert(p.begin(owner(),100,1,send));
            assert(p.status(2).baseline==0xcc08 && p.status(2).current==0xcc08 && sent.empty());
        }
        return 0;
    }
    {
        Pixel17PacketPolicy p; init(p);
        assert(p.begin(owner(),100,1,send)); accepted(p);
        assert(p.status(100).error==Pixel17HciStatus::Timeout);
        assert(p.restore(owner(),200,101,send)==Pixel17PacketRestoreResult::Refused);
        assert(!p.begin(owner(),200,101,send));
        changed(p,0xee1c); // Late success still belongs to this physical request.
        assert(!p.status(102).ready && p.status(102).dirty);
        assert(p.restore(owner(),200,102,send)==Pixel17PacketRestoreResult::Accepted); accepted(p); changed(p,0xcc18);
        assert(!p.status(103).pending && !p.status(103).dirty);
    }
    {
        Pixel17PacketPolicy p; init(p);
        assert(p.begin(owner(),100,1,send));
        event(p,{0x0f,4,0x0c,1,0x0f,4});
        assert(!p.status(2).pending && !p.status(2).dirty && !p.status(2).ready);
        assert(p.status(2).error==Pixel17HciStatus::ControllerError);
    }
    {
        Pixel17PacketPolicy p; init(p);
        assert(p.begin(owner(),100,1,send)); accepted(p); changed(p,0xcc18);
        assert(p.status(2).error==Pixel17HciStatus::Malformed && !p.status(2).ready);
    }
    {
        Pixel17PacketPolicy p; init(p);
        assert(p.begin(owner(),100,1,send)); accepted(p); changed(p,0xee1c);
        auto replacement=owner(); ++replacement.media_generation;
        assert(p.restore(replacement,200,2,send)==Pixel17PacketRestoreResult::Refused);
        event(p,{5,4,0,0x34,2,0x13});
        assert(!p.status(2).dirty && !p.status(2).ready);
        connected(p); // Same handle and peer, fresh ACL: no old baseline may survive.
        assert(!p.begin(replacement,200,2,send));
        changed(p,0xcc08); assert(p.begin(replacement,200,2,send));
        assert(p.status(2).baseline==0xcc08);
    }
    {
        Pixel17PacketPolicy p; init(p);
        assert(p.begin(owner(),100,1,send)); accepted(p); changed(p,0xee1c);
        p.command(0x234,0xcc18,false); // Competing platform policy cannot confirm ours.
        accepted(p); changed(p,0xcc18);
        assert(p.status(2).error==Pixel17HciStatus::UnexpectedStatus && !p.status(2).ready);
        assert(p.restore(owner(),200,2,send)==Pixel17PacketRestoreResult::Refused);
    }
    {
        Pixel17PacketPolicy p; init(p);
        const uint8_t bytes[]={0x1d,5,0,0x34,2,0x1c,0xee};
        for(size_t n=0;n<sizeof(bytes);++n) p.event(bytes,n);
        assert(p.begin(owner(),100,1,send));
        assert(p.status(2).baseline==0xcc18);
        // A completion on another handle and a status for another opcode do nothing.
        event(p,{0x0f,4,0,1,0x28,0x0c});
        event(p,{0x1d,5,0,0x35,2,0x1c,0xee});
        assert(p.status(2).pending);
    }
    for(int terminal=0;terminal<4;++terminal) {
        Pixel17PacketPolicy p; init(p); foreign_connected(p);
        assert(p.begin(owner(),100,1,send)); accepted(p); changed(p,0xee1c);
        p.command(0x235,0xcc18,false);
        assert(p.status(2).ready && p.status(2).error==Pixel17HciStatus::Ok);
        assert(p.restore(owner(),200,2,send)==Pixel17PacketRestoreResult::Deferred);
        assert(sent.size()==1 && p.status(2).dirty && !p.status(2).pending);
        // A foreign completion without Command Status cannot unlock our send.
        foreign_changed(p);
        assert(p.restore(owner(),200,2,send)==Pixel17PacketRestoreResult::Deferred);
        if(terminal==0) { accepted(p); foreign_changed(p); }
        else if(terminal==1) event(p,{0x0f,4,0x0c,1,0x0f,4});
        else if(terminal==2) { accepted(p); event(p,{5,4,0,0x35,2,0x13}); }
        else {
            event(p,{5,4,0,0x35,2,0x13});
            assert(p.restore(owner(),200,2,send)==Pixel17PacketRestoreResult::Deferred);
            accepted(p); // A disconnected ACL still owns its delayed status.
        }
        assert(p.status(2).current==0xee1c && p.status(2).baseline==0xcc18);
        assert(p.restore(owner(),200,2,send)==Pixel17PacketRestoreResult::Accepted);
        assert(sent==std::vector<uint16_t>({0xee1c,0xcc18}));
        foreign_changed(p); // Late foreign completion cannot confirm our restore.
        assert(p.status(3).pending && p.status(3).dirty);
        accepted(p); foreign_changed(p);
        assert(p.status(3).pending && p.status(3).dirty);
        changed(p,0xcc18);
        assert(!p.status(3).pending && !p.status(3).dirty && p.status(3).error==Pixel17HciStatus::Ok);
    }
    for(bool owned_first:{false,true}) {
        Pixel17PacketPolicy p; init(p); foreign_connected(p);
        assert(p.begin(owner(),100,1,send));
        if(!owned_first) {
            accepted(p); changed(p,0xee1c);
            p.command(0x235,0xcc18,false);
        }
        // Either overlap loses the association of the handle-less status.
        p.command(owned_first?0x235:0x236,0xcc18,false);
        accepted(p); foreign_changed(p); changed(p,0xcc18);
        assert(p.status(2).error==Pixel17HciStatus::UnexpectedStatus && !p.status(2).ready);
        assert(p.restore(owner(),200,2,send)==Pixel17PacketRestoreResult::Refused);
        assert(sent.size()==1);
    }
}
