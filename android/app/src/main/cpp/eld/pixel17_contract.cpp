// SPDX-License-Identifier: GPL-3.0-or-later
#include "pixel17_contract.h"
#include <cstring>

namespace eld {
namespace {
constexpr char peer_init[] = "_ZN11BtaAvCoPeer4InitERKNSt3__16vectorI24btav_a2dp_codec_config_tNS0_9allocatorIS2_EEEE29btav_a2dp_codec_sample_rate_t";
constexpr char peer_reset[] = "_ZN11BtaAvCoPeer5ResetEh";
constexpr char audio_close[] = "_Z21bta_av_co_audio_closehRK10RawAddress";
constexpr char registry_ctor[] = "_ZN10A2dpCodecsC1ERKNSt3__16vectorI24btav_a2dp_codec_config_tNS0_9allocatorIS2_EEEE";
constexpr char registry_dtor[] = "_ZN10A2dpCodecsD1Ev";
constexpr char default_sbc[] = "_Z24A2DP_InitDefaultCodecSbcPh";
constexpr const char* lifecycle_contexts[] = {
    peer_init, peer_reset, audio_close, registry_ctor, registry_dtor, default_sbc
};
uint32_t word(const uint8_t* p) { uint32_t v; std::memcpy(&v,p,4); return v; }
bool code(const SymbolImage& image,const char* name,ImageSymbol* symbol,const uint8_t** bytes) {
    return image.resolve(name,true,symbol) && symbol->size>=4 && symbol->size<=65536 &&
        symbol->size%4==0 && (*bytes=image.bytes(symbol->address,symbol->size,5));
}
bool branch(uint32_t ins,uint64_t at,uint64_t* target) {
    if((ins&0xfc000000)!=0x94000000) return false;
    const int64_t displacement=static_cast<int64_t>(static_cast<int32_t>((ins&0x03ffffff)<<6))>>4;
    if(displacement<0 && at<static_cast<uint64_t>(-displacement)) return false;
    if(displacement>=0 && at>UINT64_MAX-static_cast<uint64_t>(displacement)) return false;
    *target=at+displacement; return true;
}
size_t calls(const uint8_t* bytes,const ImageSymbol& from,uint64_t to,size_t* first=nullptr,size_t* last=nullptr) {
    size_t count=0;
    for(size_t p=0;p<from.size;p+=4) { uint64_t target;
        if(branch(word(bytes+p),from.address+p,&target) && target==to) {
            if(!count && first) *first=p;
            if(last) *last=p;
            ++count;
        }
    }
    return count;
}
bool contains(const uint8_t* bytes,size_t size,const uint32_t* pattern,size_t count) {
    if(!count || count>size/4) return false;
    for(size_t p=0;p+count*4<=size;p+=4) {
        size_t n=0; while(n<count && word(bytes+p+n*4)==pattern[n]) ++n;
        if(n==count) return true;
    }
    return false;
}
bool lifecycle(const SymbolImage& image,const ImageSymbol& init,uint64_t allocation) {
    ImageSymbol peer,reset,close,ctor,dtor,sbc,release,mutex_ctor;
    const uint8_t *p,*r,*c,*n,*d,*s,*f,*m;
    if(!code(image,peer_init,&peer,&p) || peer.size!=112 || !code(image,peer_reset,&reset,&r) ||
       !code(image,audio_close,&close,&c) || !code(image,registry_ctor,&ctor,&n) ||
       !code(image,registry_dtor,&dtor,&d) || !code(image,default_sbc,&sbc,&s) ||
       !code(image,kPixel17Release,&release,&f) ||
       !code(image,"_ZNSt3__115recursive_mutexC1Ev",&mutex_ctor,&m)) return false;
    // The registry constructor constructs its recursive mutex at incoming this, not this+8.
    size_t at=0;
    if(calls(n,ctor,mutex_ctor.address,&at)!=1 || at<8 || word(n+at-8)!=0xaa0103f4 ||
       word(n+at-4)!=0xaa0003f3) return false;
    // Peer::Init owns the replacement lifecycle: reset, allocate 0xc0, construct, publish,
    // initialize, then only write the peer's default OTA buffer. Calls remain relocatable.
    const uint32_t roles[]={
        0xd503233f,0xf800865e,0xa9bd7bfd,0xa90157f6,0xa9024ff4,0x910003fd,
        0x395f9008,0xaa0103f5,0x2a0203f3,0xaa0003f4,0x2a0803e1,0,
        0x52801800,0,0xaa1503e1,0xaa0003f6,0,0xaa1603e0,0x2a1303e1,
        0xf903f696,0,0x911ec280,0xa9424ff4,0xa94157f6,0xa8c37bfd,
        0xf85f8e5e,0xd50323bf,0
    };
    for(size_t i=0;i<sizeof(roles)/sizeof(roles[0]);++i)
        if(roles[i] && word(p+i*4)!=roles[i]) return false;
    const uint64_t targets[]={reset.address,allocation,ctor.address,init.address};
    const size_t sites[]={0x2c,0x34,0x40,0x50};
    for(size_t i=0;i<4;++i) { uint64_t target;
        if(!branch(word(p+sites[i]),peer.address+sites[i],&target) || target!=targets[i] ||
           calls(p,peer,target)!=1) return false;
    }
    uint64_t tail;
    const uint32_t jump=word(p+0x6c);
    if((jump&0xfc000000)!=0x14000000 ||
       !branch(jump|0x80000000U,peer.address+0x6c,&tail) || tail!=sbc.address) return false;
    // Reset deletes the old registry before clearing peer+0x7e8. Host map destruction
    // dispatches the owned codec's deleting destructor, which retires readers first.
    size_t destroyed=0,deleted=0;
    const uint32_t old_registry=0xf943f414;
    if(!contains(r,reset.size,&old_registry,1) ||
       calls(r,reset,dtor.address,&destroyed)!=1 || destroyed<8 || destroyed+24>reset.size ||
       calls(r,reset,release.address,&deleted)!=1 || deleted!=destroyed+12 ||
       word(r+destroyed-4)!=0xaa1403e0 || word(r+destroyed+4)!=0xaa1403e0 ||
       word(r+destroyed+8)!=0x52801801 || word(r+destroyed+16)!=0xf903f67f) return false;
    // The sole normal peer-close call passes retained priorities and sample rate. The
    // new registry is already published before init, so the adapter must retain its own
    // recursive lock across original init and single-registry registration.
    if(calls(c,close,peer.address,&at)!=1 || at<12 || word(c+at-12)!=0xf9400109 ||
       word(c+at-8)!=0xb9400d02 || word(c+at-4)!=0x9100a121) return false;
    return true;
}
}
const char* const* pixel17_lifecycle_context_names(size_t* count) {
    if(count) *count=sizeof(lifecycle_contexts)/sizeof(lifecycle_contexts[0]);
    return lifecycle_contexts;
}
bool pixel17_registry_mutex_contract(const uint8_t* p,size_t size,uint64_t address,
                                    uint64_t lock,uint64_t unlock) {
    if(!p || size<12 || size%4 || size>65536 || size>UINT64_MAX-address || lock==unlock) return false;
    const ImageSymbol init{address,size};
    size_t locked=0,unlocked=0;
    const uint32_t save_self=0xaa0003f3, pass_self=0xaa1303e0;
    if(calls(p,init,lock,&locked)!=1 || calls(p,init,unlock,&unlocked)!=1 ||
       locked<4 || unlocked<locked+4 || word(p+locked-4)!=pass_self ||
       !contains(p,locked,&save_self,1)) return false;
    // The shipped function computes its original boolean result between setting x0 and
    // unlocking. Those three flag/result operations preserve x0 exactly.
    const uint32_t result_before_unlock[]={pass_self,0xf100011f,0xfa401924,0x1a9f07f4};
    return word(p+unlocked-4)==pass_self || (unlocked>=sizeof(result_before_unlock) &&
        contains(p+unlocked-sizeof(result_before_unlock),sizeof(result_before_unlock),result_before_unlock,4));
}
bool pixel17_active_peer_contract(const uint8_t* p,size_t size) {
    // Entire leaf function: no receiver, call, branch or write. Only relocation immediates vary.
    if(!p || size!=28 || word(p)!=0xd503245f || (word(p+4)&0x9f00001f)!=0x90000008 ||
       (word(p+8)&0xffc003ff)!=0x91000108) return false;
    const uint32_t tail[]={0x79400909,0xb9400108,0xaa098100,0xd65f03c0};
    return contains(p+12,16,tail,4);
}
bool pixel17_source_list_contract(const uint8_t* p,size_t size,uint64_t address) {
    if(!p || size<12 || size%4 || size>65536 || size>UINT64_MAX-address) return false;
    // CMP w23,#7; STR x28,[x0,#0x10]; B.HI sink. Both destinations then splice the
    // same allocated node into the corresponding list. +0x227 is not used here.
    const uint32_t source[]={0xf9403e68,0xd10103a3,0xaa1603e1,0xa9005808,0xf9000500,0xf9003e60};
    const uint32_t sink[]={0xf9404a68,0xf94013e1,0xd10103a3,0xa9000408,0xf9000500,0xf9004a60};
    size_t matches=0;
    for(size_t at=0;at+12+sizeof(source)<=size;at+=4) {
        const auto conditional=word(p+at+8);
        if(word(p+at)!=0x71001eff || word(p+at+4)!=0xf900081c ||
            (conditional&0xff00001f)!=0x54000008) continue;
        const int64_t displacement=static_cast<int64_t>(static_cast<int32_t>((conditional&0x00ffffe0)<<8))>>11;
        const int64_t destination=static_cast<int64_t>(at+8)+displacement;
        if(destination<0 || static_cast<uint64_t>(destination)>size || sizeof(sink)>size-destination) continue;
        bool same=true;
        for(size_t i=0;i<sizeof(source)/sizeof(source[0]);++i)
            same=same && word(p+at+12+i*4)==source[i] && word(p+destination+i*4)==sink[i];
        if(same) ++matches;
    }
    return matches==1;
}
bool pixel17_contract(const SymbolImage& image,Pixel17Contract* out) {
    if(!out) return false;
    *out={};
    ImageSymbol init,cache,ctor,lock,unlock,allocation,peer,base;
    const uint8_t *a,*b,*c,*l,*u,*n,*p,*q;
    if(!code(image,kPixel17Init,&init,&a) || !code(image,kPixel17CacheInit,&cache,&b) ||
       !code(image,kPixel17AacCtor,&ctor,&c) || !code(image,kPixel17Lock,&lock,&l) ||
       !code(image,kPixel17Unlock,&unlock,&u) || !code(image,kPixel17Allocate,&allocation,&n) ||
       !code(image,kPixel17ActivePeer,&peer,&p) || !pixel17_active_peer_contract(p,peer.size) ||
       !code(image,"_ZN15A2dpCodecConfigC2E23btav_a2dp_codec_index_tN9bluetooth4a2dp7CodecIdERKNSt3__112basic_stringIcNS4_11char_traitsIcEENS4_9allocatorIcEEEE26btav_a2dp_codec_priority_t",&base,&q)) return false;
    // The nothrow wrapper forwards only x0 to the platform allocation function; it never reads its tag.
    if(allocation.size!=48 || word(n)!=0xd503233f || word(n+4)!=0xa9bf7bfd ||
       word(n+8)!=0x910003fd || !branch(word(n+12),allocation.address+12,&out->raw_allocate) ||
       word(n+16)!=0xa8c17bfd || word(n+20)!=0xd50323bf || word(n+24)!=0xd65f03c0) return false;
    size_t ctor_at=0;
    if(calls(a,init,ctor.address,&ctor_at)!=1 || ctor_at<16 || word(a+ctor_at-16)!=0x52804500) return false;
    uint64_t alloc_call;
    if(!branch(word(a+ctor_at-12),init.address+ctor_at-12,&alloc_call) || alloc_call!=out->raw_allocate) return false;
    // Constructor and five native records: numeric index, distinct 64-bit CodecId and 64-byte stride.
    const uint32_t identity[]={0xb9003274,0xf9001e77};
    const uint32_t current[]={0xad030260,0xad040260,0x290c2274};
    const uint32_t other[]={0xad050260,0x290b5668,0xad060260,0xad070260,0x29142274};
    const uint32_t source[]={0x39089e75};
    if(!contains(q,base.size,identity,2) || !contains(q,base.size,current,3) ||
       !contains(q,base.size,other,5) || !contains(c,ctor.size,source,1)) return false;
    size_t first=0,last=0,lock_at=0,unlock_at=0;
    out->registry_count=calls(b,cache,init.address,&first,&last);
    // Each observed registry is initialized once under the outer recursive mutex. Reject a changed cohort.
    if(out->registry_count!=6 || calls(b,cache,lock.address,&lock_at)!=1 ||
       calls(b,cache,unlock.address,&unlock_at)!=1 || lock_at>=first || unlock_at<=last) return false;
    if(!pixel17_registry_mutex_contract(a,init.size,init.address,lock.address,unlock.address) ||
       !lifecycle(image,init,out->raw_allocate)) return false;
    // Registry containers are independently walked and checked before mutation. Confirm this init's offsets.
    const uint32_t containers[]={0x9100e279,0x91014278,0x91024269,0x9101e276,0x9101a268};
    if(!contains(a,init.size,containers,5) || !pixel17_source_list_contract(a,init.size,init.address)) return false;
    // Stock AIDL chooses software PCM when neither provider offload nor standard codec conversion accepts it.
    ImageSymbol setup,hal,pcm,support; const uint8_t *s,*h,*m,*v;
    if(!code(image,"_ZN9bluetooth5audio4aidl4a2dp11setup_codecERKNS0_4a2dp24ahal_codec_configurationE",&setup,&s) ||
       !code(image,"_ZN9bluetooth5audio4aidl4a2dp5codec24getHalCodecConfigurationERKNS0_4a2dp24ahal_codec_configurationEPN4aidl7android8hardware9bluetooth5audio18CodecConfigurationE",&hal,&h) ||
       !code(image,"_ZN9bluetooth5audio4aidl4a2dp5codec22getHalPcmConfigurationERKNS0_4a2dp24ahal_codec_configurationEPN4aidl7android8hardware9bluetooth5audio16PcmConfigurationE",&pcm,&m) ||
       !code(image,"_ZNK9bluetooth5audio4aidl4a2dp12ProviderInfo13SupportsCodecE23btav_a2dp_codec_index_t",&support,&v)) return false;
    size_t provider_at=0,hal_at=0,pcm_at=0;
    if(calls(s,setup,support.address,&provider_at)!=1 || calls(s,setup,hal.address,&hal_at)!=1 ||
       calls(s,setup,pcm.address,&pcm_at)!=1 || provider_at>=hal_at || hal_at>=pcm_at) return false;
    // Exact numeric-dispatch shape excludes slot 5. Addresses of its local targets remain relocatable.
    const uint32_t zero_and_index[]={0x2a1f03e8,0xb9404009,0x71000d3f};
    if(!contains(h,hal.size,zero_and_index,3)) return false;
    bool rejected_five=false;
    for(size_t i=0;i+16<=hal.size;i+=4) {
        if(word(h+i)!=0x7100113f || (word(h+i+4)&0xff00001f)!=0x54000000 ||
           word(h+i+8)!=0x7100193f || (word(h+i+12)&0xff00001f)!=0x54000001) continue;
        const int64_t displacement=static_cast<int64_t>(static_cast<int32_t>((word(h+i+12)&0x00ffffe0)<<8))>>11;
        const int64_t destination=static_cast<int64_t>(i+12)+displacement;
        if(destination<4 || static_cast<uint64_t>(destination+20)>hal.size) continue;
        // Canary loads/compare leave w8 untouched; return is its low bit (zero on this path).
        const auto* tail=h+destination;
        rejected_five=word(tail)==0xf9401669 && word(tail+4)==0xf85f83aa &&
            word(tail+8)==0xeb0a013f && (word(tail+12)&0xff00001f)==0x54000001 &&
            word(tail+16)==0x12000100;
    }
    return rejected_five;
}
}
