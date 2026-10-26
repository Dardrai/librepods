// SPDX-License-Identifier: GPL-3.0-or-later
#include "pixel17_hci.h"
#include <cstring>
#include <initializer_list>

namespace eld {
namespace {
uint32_t word(const uint8_t* p) { uint32_t v; std::memcpy(&v,p,4); return v; }
bool code(const SymbolImage& image,const char* name,size_t size,ImageSymbol* symbol,const uint8_t** p) {
    return image.resolve(name,true,symbol) && symbol->size == size &&
        (*p = image.bytes(symbol->address,symbol->size,5));
}
bool at(const uint8_t* p,size_t size,size_t offset,std::initializer_list<uint32_t> words) {
    if(offset > size || words.size() > (size-offset)/4) return false;
    for(auto expected : words) { if(word(p+offset) != expected) return false; offset += 4; }
    return true;
}
bool branch(uint32_t ins,uint64_t pc,uint64_t* target,bool link = true) {
    if((ins & 0xfc000000) != (link ? 0x94000000U : 0x14000000U)) return false;
    const auto delta = int64_t(int32_t((ins&0x03ffffff)<<6)) >> 4;
    if((delta < 0 && pc < uint64_t(-delta)) || (delta >= 0 && pc > UINT64_MAX-uint64_t(delta))) return false;
    *target = pc + delta; return true;
}
bool calls(const uint8_t* p,const ImageSymbol& from,size_t offset,uint64_t target) {
    uint64_t actual;
    return offset < from.size && branch(word(p+offset),from.address+offset,&actual) && actual == target;
}
bool derived(const SymbolImage& image,const uint8_t* p,const ImageSymbol& from,size_t offset,ImageSymbol* target) {
    uint64_t address;
    return offset < from.size && branch(word(p+offset),from.address+offset,&address) &&
        image.resolve_address(address,true,target) && target->size && target->size <= 65536 &&
        image.bytes(target->address,target->size,5);
}
bool page_address(uint32_t adrp,uint32_t add,uint64_t pc,uint64_t* result) {
    if((adrp&0x9f00001f) != 0x90000000 || (add&0xffc003ff) != 0x91000000) return false;
    const auto immediate = ((adrp>>29)&3) | ((adrp>>5)&0x7ffff)<<2;
    const auto delta = (int64_t(int32_t(immediate<<11)) >> 11) * 4096;
    const auto page = pc & ~uint64_t(4095);
    if((delta < 0 && page < uint64_t(-delta)) || (delta >= 0 && page > UINT64_MAX-uint64_t(delta))) return false;
    const auto base = page + delta;
    const auto low = (add>>10)&4095;
    if(base > UINT64_MAX-low) return false;
    *result = base+low; return true;
}
}
bool pixel17_hci_contract(const SymbolImage& image,Pixel17HciSymbols* out) {
    if(!out) return false;
    *out = {};
    Pixel17HciSymbols found;
    const uint8_t *ctor,*post,*dtor,*main,*tx,*complete,*byte,*payload,*size;
    if(!code(image,"_ZN4base8internal13BindStateBaseC2EPFvvEPFvPKS1_E",32,&found.construct,&ctor) ||
       !code(image,"_ZN9bluetooth6common17MessageLoopThread10DoInThreadEN4base12OnceCallbackIFvvEEE",136,&found.post,&post) ||
       !code(image,"_ZN4base8internal12CallbackBaseD2Ev",96,&found.destroy_callback,&dtor) ||
       !code(image,"_Z17do_in_main_threadN4base12OnceCallbackIFvvEEE",240,&found.main_wrapper,&main) ||
       !code(image,"_ZL16transmit_commandPK6BT_HDRPFvN9bluetooth3hci19CommandCompleteViewEPvEPFvhPS_S5_ES5_",1028,&found.transmit,&tx) ||
       !code(image,"_ZN3cppL31OnTransmitPacketCommandCompleteEPFvN9bluetooth3hci19CommandCompleteViewEPvES3_S2_",572,&found.complete_adapter,&complete) ||
       !code(image,"_ZNK9bluetooth6packet10PacketViewILb1EEixEm",144,&found.view_byte,&byte) ||
       !code(image,"_ZNK9bluetooth3hci19CommandCompleteView10GetPayloadEv",368,&found.view_payload,&payload)) return false;
    // BindStateBase is native refcount32 + padding, invoke, destroy, cancellation
    // query: exactly32 bytes. This is the only base layout our extension uses.
    if(!at(ctor,32,0,{0xd503245f,0x52800028,0xa9008801,0xb9000008,0xd503201f}) ||
       (word(ctor+20)&0x9f00001f) != 0x10000008 ||
       !at(ctor,32,24,{0xf9000c08,0xd65f03c0})) return false;
    // Native callback destructor decrements the native refcount and invokes the
    // stored destructor at+16 with the same base pointer, not an NDK delete.
    if(!at(dtor,96,20,{0xf9400013,0xb40001b3,0x12800000,0xaa1303e1}) ||
       !at(dtor,96,40,{0x7100041f,0x54000101,0xf9400a70,0xaa1303e0}) ||
       !at(dtor,96,72,{0xd61f0200})) return false;
    // DoInThread takes an indirect8-byte callback and clears its caller's pointer.
    // Its result is bool w0, unlike do_in_main_thread's hidden aggregate result.
    if(!at(post,136,36,{0xf9400028,0xf900003f,0x910003e1,0xf90003e8,0x910023e8}) ||
       !at(post,136,60,{0x394043f4,0x7100069f,0x54000061,0x910023e0}) ||
       !calls(post,found.post,76,found.destroy_callback.address) ||
       !calls(post,found.post,84,found.destroy_callback.address) || word(post+104) != 0x52000280) return false;
    // Derive the actual main MessageLoopThread from the named wrapper, not a
    // process-global address pin or the distinct btif/JNI message loop.
    if(!at(main,240,48,{0xf9400008,0xf900001f}) ||
       !page_address(word(main+56),word(main+60),found.main_wrapper.address+56,&found.main_thread) ||
       !at(main,240,64,{0xf90003e8}) || !calls(main,found.main_wrapper,68,found.post.address) ||
       !calls(main,found.main_wrapper,80,found.destroy_callback.address) || !found.main_thread) return false;
    // The main object is in zero-filled native BSS, so it has no file byte range.
    // Runtime must check its PT_LOAD/mapped writable bounds after applying bias.
    // Native command ABI reads BT_HDR len/offset and copies command parameters
    // before enqueueing. Our fixed opcodes follow its complete-command path.
    if(!at(tx,1028,52,{0x79400408,0xaa0003f3,0xf100091f}) ||
       !at(tx,1028,68,{0x79400a69,0xaa0103f6,0xd1000d01,0x910083e8,0xaa0303f4,0xaa0203f5}) ||
       !at(tx,1028,92,{0x8b090269,0xf90013ff,0x79401138,0x91002d20,0x79005bf8}) ||
       !at(tx,1028,0x144,{0x7131791f}) ||
       !at(tx,1028,0x1d4,{0x71314d1f}) ||
       !at(tx,1028,0x328,{0xa9025b08,0xf9001b14,0xf90027ff,0xa93d5fb8}) ||
       !calls(tx,found.transmit,0x318,found.construct.address) ||
       !calls(tx,found.transmit,0x338,found.destroy_callback.address) ||
       !at(tx,1028,0x33c,{0xf94002a8,0x9100c3e1,0xd100c3a2,0xaa1503e0,0xf9400908,0xd63f0100}) ||
       !at(tx,1028,0x374,{0xaa1303e0})) return false;
    // The callback gets a BORROWED temporary native CommandCompleteView* in x0
    // and the original context in x1. Native destroys backing fragments afterward.
    if(!at(complete,572,44,{0xaa0003f3,0xaa0203e0}) ||
       !at(complete,572,56,{0xaa0203f5,0xaa0103f4}) ||
       !at(complete,572,0x19c,{0x394062a8,0xf9400aa9,0x910143e0,0xaa1403e1,0x3901a3e8}) ||
       !at(complete,572,0x1b8,{0xf90033e9,0xf9002be8,0xd63f0260,0xf9402ff3}) ||
       !at(complete,572,0x1d0,{0xf9002be8,0xb4000173})) return false;
    // PacketView::size is an ICF alias. Derive its role from GetPayload's first
    // call, then require the complete12-byte leaf; never resolve a perfetto alias.
    if(!at(payload,368,40,{0x39406009}) || !derived(image,payload,found.view_payload,56,&found.view_size) ||
       found.view_size.size != 12 || !(size=image.bytes(found.view_size.address,12,5)) ||
       !at(size,12,0,{0xd503245f,0xf9400800,0xd65f03c0}) ||
       !at(byte,144,20,{0xf9400808,0xeb08003f,0x540002a2,0xf9400414}) ||
       !at(byte,144,84,{0x91002280,0xaa1303e1,0xa9414ff4,0xa8c27bfd,0xf85f8e5e,0xd50323bf})) return false;
    *out = found; return true;
}
}
