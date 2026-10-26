// SPDX-License-Identifier: GPL-3.0-or-later
#include "pixel17_vtable_contract.h"
#include "pixel17_contract.h"
#include <array>
#include <cstring>
#include <limits>

namespace eld {
namespace {
const char* const contexts[]={
    kPixel17AacCtor, kPixel17Init, kPixel17Release,
    "_ZN15A2dpCodecConfigD2Ev",
    "_ZNK22A2dpCodecConfigAacBase15getTrackBitRateEv",
    "_ZN22A2dpCodecConfigAacBase14setCodecConfigEPKhbPh",
    "_ZN22A2dpCodecConfigAacBase24setPeerCodecCapabilitiesEPKh",
    "_ZN24A2dpCodecConfigAacSource16debug_codec_dumpEi",
    "_ZN10A2dpCodecs14setCodecConfigEPKhbPhb",
    "_ZN10A2dpCodecs28setPeerSinkCodecCapabilitiesEPKh",
    "_ZN10A2dpCodecs21findSourceCodecConfigEPKh",
    "_ZN15A2dpCodecConfig18setCodecUserConfigERK24btav_a2dp_codec_config_tS2_PK30tA2DP_ENCODER_INIT_PEER_PARAMSPKhbPhPbS9_S9_",
    "_ZN10A2dpCodecsD1Ev",
    "_ZN10A2dpCodecs16debug_codec_dumpEi",
    "_ZL38btif_a2dp_source_start_session_delayedRK10RawAddressNSt3__17promiseIvEE",
    "_Z15bta_av_start_okP11tBTA_AV_SCBP12tBTA_AV_DATA"
};
uint32_t word(const uint8_t* p) { uint32_t value; std::memcpy(&value,p,4); return value; }
uint64_t wide(const uint8_t* p) { uint64_t value; std::memcpy(&value,p,8); return value; }
bool function(const SymbolImage& image,const char* name,ImageSymbol* symbol,const uint8_t** bytes) {
    return image.resolve(name,true,symbol) && symbol->size>=4 && symbol->size<=65536 &&
        symbol->size%4==0 && (*bytes=image.bytes(symbol->address,symbol->size,5));
}
bool function_at(const SymbolImage& image,uint64_t address,ImageSymbol* symbol,const uint8_t** bytes) {
    return image.resolve_address(address,true,symbol) && symbol->size>=4 && symbol->size<=65536 &&
        symbol->size%4==0 && (*bytes=image.bytes(address,symbol->size,5));
}
bool branch(uint32_t ins,uint64_t at,bool link,uint64_t* target) {
    if((ins&0xfc000000)!=(link?0x94000000u:0x14000000u)) return false;
    const int64_t displacement=static_cast<int64_t>(static_cast<int32_t>((ins&0x03ffffff)<<6))>>4;
    if(displacement<0 && at<static_cast<uint64_t>(-displacement)) return false;
    if(displacement>=0 && at>UINT64_MAX-static_cast<uint64_t>(displacement)) return false;
    *target=at+displacement; return true;
}
template<size_t N> size_t occurrences(const uint8_t* bytes,size_t size,const std::array<uint32_t,N>& pattern) {
    size_t matches=0;
    for(size_t p=0;p+N*4<=size;p+=4) {
        size_t n=0; while(n<N && word(bytes+p+n*4)==pattern[n]) ++n;
        if(n==N) ++matches;
    }
    return matches;
}
bool leaf(const SymbolImage& image,uint64_t address,bool value) {
    ImageSymbol symbol; const uint8_t* bytes;
    return function_at(image,address,&symbol,&bytes) && symbol.size==12 &&
        word(bytes)==0xd503245f && word(bytes+4)==(value?0x52800020u:0x2a1f03e0u) &&
        word(bytes+8)==0xd65f03c0;
}
bool named_target(const SymbolImage& image,const char* name,uint64_t address) {
    ImageSymbol symbol; const uint8_t* bytes;
    return function(image,name,&symbol,&bytes) && symbol.address==address;
}
template<size_t N> bool lookup_dispatch(const SymbolImage& image,const char* name,
        const std::array<uint32_t,N>& after_cbz) {
    ImageSymbol symbol,lookup; const uint8_t *bytes,*lookup_bytes;
    if(!function(image,name,&symbol,&bytes) ||
        !function(image,"_ZN10A2dpCodecs21findSourceCodecConfigEPKh",&lookup,&lookup_bytes)) return false;
    size_t matches=0;
    for(size_t p=0;p+(N+2)*4<=symbol.size;p+=4) {
        uint64_t target;
        if(!branch(word(bytes+p),symbol.address+p,true,&target) || target!=lookup.address ||
            (word(bytes+p+4)&0xff00001f)!=0xb4000000) continue; // CBZ x0.
        size_t n=0; while(n<N && word(bytes+p+8+n*4)==after_cbz[n]) ++n;
        if(n==N) ++matches;
    }
    return matches==1;
}
bool destructor_contract(const SymbolImage& image,uint64_t nondeleting,uint64_t deleting) {
    ImageSymbol base,release,first,second; const uint8_t *base_bytes,*release_bytes,*a,*b;
    if(!function(image,"_ZN15A2dpCodecConfigD2Ev",&base,&base_bytes) ||
       !function(image,kPixel17Release,&release,&release_bytes) ||
       !function_at(image,nondeleting,&first,&a) || !function_at(image,deleting,&second,&b) ||
       first.size!=8 || second.size!=56 || word(a)!=0xd503245f) return false;
    uint64_t target;
    if(!branch(word(a+4),first.address+4,false,&target) || target!=base.address) return false;
    const std::array<uint32_t,6> prefix{0xd503233f,0xf800865e,0xa9be7bfd,0xf9000bf3,0x910003fd,0xaa0003f3};
    const std::array<uint32_t,6> tail{0xaa1303e0,0x52804501,0xf9400bf3,0xa8c27bfd,0xf85f8e5e,0xd50323bf};
    if(occurrences(b,24,prefix)!=1 || !branch(word(b+24),second.address+24,true,&target) || target!=base.address ||
       occurrences(b+28,24,tail)!=1 || !branch(word(b+52),second.address+52,false,&target) || target!=release.address)
        return false;
    return true;
}
bool consumers(const SymbolImage& image) {
    // The complete return-to-dispatch spans contain no vcall CFI/range/type check. A changed
    // instrumented call site must fail closed before an NDK-owned vtable can be installed.
    const std::array<uint32_t,9> set_config{0xf9400008,0x12000302,0xaa1703e1,0xaa1603e3,
        0xaa0003f5,0xf9401508,0xd63f0100,0x72001c1f,0x1a9f17f6};
    const std::array<uint32_t,5> set_peer{0xf9400008,0xaa1403e1,0xf9401908,0xd63f0100,0x2a0003f4};
    if(!lookup_dispatch(image,"_ZN10A2dpCodecs14setCodecConfigEPKhbPhb",set_config) ||
       !lookup_dispatch(image,"_ZN10A2dpCodecs28setPeerSinkCodecCapabilitiesEPKh",set_peer)) return false;
    ImageSymbol user,destruct,init,debug,start,marker; const uint8_t *u,*d,*i,*g,*s,*m;
    if(!function(image,"_ZN15A2dpCodecConfig18setCodecUserConfigERK24btav_a2dp_codec_config_tS2_PK30tA2DP_ENCODER_INIT_PEER_PARAMSPKhbPhPbS9_S9_",&user,&u) ||
       !function(image,"_ZN10A2dpCodecsD1Ev",&destruct,&d) || !function(image,kPixel17Init,&init,&i) ||
       !function(image,"_ZN10A2dpCodecs16debug_codec_dumpEi",&debug,&g) ||
       !function(image,"_ZL38btif_a2dp_source_start_session_delayedRK10RawAddressNSt3__17promiseIvEE",&start,&s) ||
       !function(image,"_Z15bta_av_start_okP11tBTA_AV_SCBP12tBTA_AV_DATA",&marker,&m)) return false;
    const std::array<uint32_t,5> destruction{0xf94016a0,0xb4000080,0xf9400008,0xf9400508,0xd63f0100};
    const std::array<uint32_t,4> initialization{0xf9400388,0xaa1c03e0,0xf9401d08,0xd63f0100};
    const std::array<uint32_t,5> debugging{0xf9400aa0,0x2a1403e1,0xf9400008,0xf9402108,0xd63f0100};
    const std::array<uint32_t,5> rate{0xaa1503e0,0xf94002a8,0xf9400908,0xd63f0100,0x6f00e400};
    const std::array<uint32_t,5> mark{0xf9400008,0xf9400d08,0xd63f0100,0x12000008,0x391b1668};
    const std::array<uint32_t,20> user_dispatch{
        0xf9400268,0xad0287e0,0xad4a0262,0xad0383e2,0xad410742,0xad400343,
        0xad0a0662,0xad090263,0xad4b0261,0xad410f22,0xad401325,0xad0083e1,
        0xad4c0660,0xad0c0e62,0xad0b1265,0xf9401508,0xad0187e0,0xd63f0100,
        0x2a0003fb,0x72001c1f};
    return occurrences(d,destruct.size,destruction)==2 && occurrences(i,init.size,initialization)==1 &&
        occurrences(g,debug.size,debugging)==1 && occurrences(s,start.size,rate)==1 &&
        occurrences(m,marker.size,mark)==1 && occurrences(u,user.size,user_dispatch)==1;
}
}

const char* const* pixel17_vtable_context_names(size_t* count) {
    if(count) *count=sizeof(contexts)/sizeof(contexts[0]);
    return contexts;
}

bool pixel17_vtable_address_point(const SymbolImage& image,uint64_t* out) {
    if(!out) return false;
    *out=0;
    ImageSymbol ctor; const uint8_t* bytes;
    if(!function(image,kPixel17AacCtor,&ctor,&bytes)) return false;
    size_t stores=0;
    for(size_t p=0;p+12<=ctor.size;p+=4) {
        const auto page=word(bytes+p),add=word(bytes+p+4),store=word(bytes+p+8);
        const uint32_t reg=page&31;
        if((page&0x9f000000)!=0x90000000 || (reg!=8 && reg!=9) ||
           (add&0xffc003ff)!=(0x91000000u|(reg<<5)|reg) || store!=(0xf9000260u|reg)) continue;
        const uint32_t immediate=((page>>5)&0x7ffff)<<2 | ((page>>29)&3);
        const int64_t displacement=static_cast<int64_t>(static_cast<int32_t>(immediate<<11))*2;
        const auto at=(ctor.address+p)&~uint64_t{0xfff};
        if(displacement<0 && at<static_cast<uint64_t>(-displacement)) return false;
        if(displacement>=0 && at>UINT64_MAX-static_cast<uint64_t>(displacement)) return false;
        const auto target=at+displacement;
        const uint64_t low=(add>>10)&0xfff;
        if(target>UINT64_MAX-low) return false;
        *out=target+low; ++stores;
    }
    return stores==2 && *out;
}

bool pixel17_vtable_contract(const SymbolImage& image,uintptr_t base,uintptr_t runtime_table,
        const uintptr_t* words,size_t count) {
    if(!base || !words || count!=11 || runtime_table<base || runtime_table-base<16) return false;
    const uint64_t table=runtime_table-base;
    uint64_t derived;
    if(!pixel17_vtable_address_point(image,&derived) || derived!=table) return false;
    // The adjacent zero metadata pair bounds this complete nine-entry source vtable. A new
    // tenth virtual entry or changed RTTI/layout must be reviewed instead of silently cloned.
    const auto* file=image.bytes(table-16,13*8,4);
    if(!file || wide(file) || wide(file+8) || wide(file+11*8) || wide(file+12*8) || words[0] || words[1]) return false;
    std::array<uint64_t,9> targets{};
    for(size_t slot=0;slot<targets.size();++slot) {
        targets[slot]=wide(file+(slot+2)*8);
        if(targets[slot]>UINTPTR_MAX-base || words[slot+2]!=base+targets[slot]) return false;
        ImageSymbol symbol; const uint8_t* bytes;
        if(!function_at(image,targets[slot],&symbol,&bytes)) return false;
    }
    return destructor_contract(image,targets[0],targets[1]) &&
        named_target(image,"_ZNK22A2dpCodecConfigAacBase15getTrackBitRateEv",targets[2]) &&
        leaf(image,targets[3],true) && leaf(image,targets[4],false) &&
        named_target(image,"_ZN22A2dpCodecConfigAacBase14setCodecConfigEPKhbPh",targets[5]) &&
        named_target(image,"_ZN22A2dpCodecConfigAacBase24setPeerCodecCapabilitiesEPKh",targets[6]) &&
        leaf(image,targets[7],true) &&
        named_target(image,"_ZN24A2dpCodecConfigAacSource16debug_codec_dumpEi",targets[8]) && consumers(image);
}
}
