// SPDX-License-Identifier: GPL-3.0-or-later
#include "pixel17_packet_policy.h"
#include <cassert>
#include <cstring>
#include <vector>
using namespace eld;
namespace {
Pixel17PacketPolicy* radio=nullptr;
std::vector<uint16_t> sent;
void send(uint16_t handle,uint16_t mask) { sent.push_back(mask); radio->command(handle,mask,true); }
void event(Pixel17PacketPolicy& p,std::initializer_list<uint8_t> bytes) { p.event(bytes.begin(),bytes.size()); }
void connected(Pixel17PacketPolicy& p) { event(p,{3,11,0,0x34,0x02,6,5,4,3,2,1,1,0}); }
void changed(Pixel17PacketPolicy& p,uint16_t mask) {
    event(p,{0x1d,5,0,0x34,0x02,uint8_t(mask),uint8_t(mask>>8)});
}
void accepted(Pixel17PacketPolicy& p) { event(p,{0x0f,4,0,1,0x0f,4}); }
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
        const auto target=uint16_t(baseline|0x2204);
        if(baseline==target) { assert(sent.empty()); assert(p.status(2).ready); continue; }
        assert(sent==std::vector<uint16_t>{target});
        assert(p.status(2).pending && p.status(2).dirty && !p.status(2).ready);
        accepted(p); assert(p.status(2).pending && !p.status(2).ready);
        changed(p,target);
        assert(p.status(2).ready && p.status(2).current==target && p.status(2).baseline==baseline);
        assert(p.restore(owner(),200,2,send));
        assert(sent.back()==baseline && p.status(3).dirty);
        accepted(p); assert(p.status(3).dirty); changed(p,baseline);
        assert(!p.status(3).dirty && !p.status(3).pending && p.status(3).current==baseline);
    }
    {
        Pixel17PacketPolicy p; init(p);
        assert(p.begin(owner(),100,1,send)); accepted(p);
        assert(p.status(100).error==Pixel17HciStatus::Timeout);
        assert(!p.restore(owner(),200,101,send));
        assert(!p.begin(owner(),200,101,send));
        changed(p,0xee1c); // Late success still belongs to this physical request.
        assert(!p.status(102).ready && p.status(102).dirty);
        assert(p.restore(owner(),200,102,send)); accepted(p); changed(p,0xcc18);
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
        assert(!p.restore(replacement,200,2,send));
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
        assert(!p.restore(owner(),200,2,send));
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
}
