// SPDX-License-Identifier: GPL-3.0-or-later
#include "pixel17_packet_policy.h"
#include <cstring>
#include <initializer_list>

namespace eld {
namespace {
uint32_t word(const uint8_t* p) { uint32_t w; std::memcpy(&w,p,4); return w; }
bool at(const uint8_t* p,size_t offset,std::initializer_list<uint32_t> words) {
    for(auto expected:words) { if(word(p+offset)!=expected) return false; offset+=4; }
    return true;
}
bool branch(const SymbolImage& image,const ImageSymbol& from,const uint8_t* p,size_t offset,
            bool call,const char* name,ImageSymbol* out) {
    const auto w=word(p+offset);
    if((w&0xfc000000)!=(call?0x94000000U:0x14000000U) || !image.resolve(name,true,out)) return false;
    const auto delta=int64_t(int32_t((w&0x03ffffff)<<6))>>4;
    const auto pc=from.address+offset;
    return !(delta<0 && pc<uint64_t(-delta)) && out->address==pc+delta &&
        out->size && out->size<=65536 && image.bytes(out->address,out->size,5);
}
}
bool pixel17_packet_contract(const SymbolImage& image,Pixel17PacketSymbols* out) {
    if(!out) return false;
    *out={}; Pixel17PacketSymbols s;
    if(!image.resolve(kPixel17PacketCommand,true,&s.command) || s.command.size!=112) return false;
    const auto* command=image.bytes(s.command.address,s.command.size,5);
    // Entire leaf apart from relocated calls: ignores x0, builds native BT_HDR,
    // and transfers it to the platform's normal command/status ownership path.
    if(!command || !at(command,0,{0xd503233f,0xf800865e,0xa9be7bfd,0xa9014ff4,0x910003fd,
        0x52805280,0x2a0203f3,0x2a0103f4}) ||
       !at(command,36,{0xaa0003e1,0x528000e8,0x2a1f03e0,0xb8002028,0x528081e8,0x79001028,
        0x52800088,0x39002828,0x53087e88,0x39002c34,0x39003028,0x53087e68,0x39003433,
        0x39003828,0xa9414ff4,0xa8c27bfd,0xf85f8e5e,0xd50323bf}) ||
       !branch(image,s.command,command,32,true,"_Z10osi_mallocm",&s.allocate) ||
       !branch(image,s.command,command,108,false,"_Z17btu_hcif_send_cmdhPK6BT_HDR",&s.enqueue)) return false;
    if(image.resolve(kPixel17PacketEventRef,true,&s.event)) {
        if(s.event.size!=0x33d4) return false;
        const auto* event=image.bytes(s.event.address,s.event.size,5);
        if(!event || !at(event,0,{0xd503233f,0xf800865e,0xd10703ff}) ||
           !at(event,44,{0xaa0103f4,0xaa0003f3}) ||
           !at(event,64,{0x39406028,0x7100051f,0x54000140})) return false;
        s.event_name=kPixel17PacketEventRef;
    } else {
        if(!image.resolve(kPixel17PacketEvent,true,&s.event) || s.event.size!=0x4ee4) return false;
        const auto* event=image.bytes(s.event.address,s.event.size,5);
        // The old by-value nontrivial view is also passed indirectly in x1.
        if(!event || !at(event,0,{0xd503233f,0xf800865e,0xa9ba7bfd}) ||
           !at(event,44,{0xaa0103f4,0xaa0003f3}) ||
           !at(event,64,{0x39406028,0x7100051f,0x54000140})) return false;
        s.event_name=kPixel17PacketEvent;
    }
    *out=s; return true;
}
}
