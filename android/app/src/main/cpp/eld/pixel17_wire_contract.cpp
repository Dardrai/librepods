// SPDX-License-Identifier: GPL-3.0-or-later
#include "pixel17_wire_contract.h"
#include "pixel17_wire_hooks.h"
#include <cstring>

namespace eld {
namespace {
constexpr char generic_init[] = "_Z20A2DP_InitCodecConfig23btav_a2dp_codec_index_tP14AvdtpSepConfig29btav_a2dp_codec_sample_rate_t";
constexpr char vendor_init[] = "_Z26A2DP_VendorInitCodecConfig29btav_a2dp_codec_sample_rate_t23btav_a2dp_codec_index_tP14AvdtpSepConfig";
constexpr char aac_interface[] = "_Z27A2DP_GetEncoderInterfaceAacPKh";
constexpr char start[] = "_ZL38btif_a2dp_source_start_session_delayedRK10RawAddressNSt3__17promiseIvEE";
constexpr char timer[] = "_ZL35btif_a2dp_source_audio_handle_timerv";
constexpr char tx_start[] = "_ZL37btif_a2dp_source_audio_tx_start_eventv";
constexpr char cleanup[] = "_ZL38btif_a2dp_source_cleanup_codec_delayedv";
constexpr char tick[] = "_ZN9bluetooth6common29time_get_audio_server_tick_usEv";
constexpr char queue_size[] = "_Z18fixed_queue_lengthP13fixed_queue_t";
constexpr char read[] = "_ZL30btif_a2dp_source_read_callbackPhj";
constexpr char enqueue[] = "_ZL33btif_a2dp_source_enqueue_callbackP6BT_HDRmj";
constexpr char vendor_index[] = "_Z27A2DP_VendorSourceCodecIndexPKh";
constexpr char find_source[] = "_ZN10A2dpCodecs21findSourceCodecConfigEPKh";
constexpr char set_ota[] = "_ZN10A2dpCodecs17setCodecOtaConfigEPKhPK30tA2DP_ENCODER_INIT_PEER_PARAMSPhPbS6_S6_";
constexpr char queue_flush[] = "_Z17fixed_queue_flushP13fixed_queue_tPFvPvE";
constexpr char queue_enqueue[] = "_Z19fixed_queue_enqueueP13fixed_queue_tPv";
constexpr char queue_dequeue[] = "_Z23fixed_queue_try_dequeueP13fixed_queue_t";
constexpr char packet_free[] = "_Z8osi_freePv";
constexpr const char* encoder_functions[] = {
    "_Z21a2dp_aac_encoder_initPK30tA2DP_ENCODER_INIT_PEER_PARAMSP15A2dpCodecConfigPFjPhjEPFbP6BT_HDRmjE",
    "_Z24a2dp_aac_encoder_cleanupv", "_Z22a2dp_aac_feeding_resetv", "_Z22a2dp_aac_feeding_flushv",
    "_Z32a2dp_aac_get_encoder_interval_msv", "_Z33a2dp_aac_get_effective_frame_sizev",
    "_Z20a2dp_aac_send_framesm"
};
constexpr const char* contexts[] = {
    generic_init, vendor_init, aac_interface, start, timer, tx_start, cleanup, tick, queue_size, read, enqueue,
    encoder_functions[0], encoder_functions[1], encoder_functions[2], encoder_functions[3],
    encoder_functions[4], encoder_functions[5], encoder_functions[6],
    "_Z26A2DP_GetTrackSampleRateAacPKh", "_Z29A2DP_GetTrackBitsPerSampleAacPKh",
    "_Z28A2DP_GetTrackChannelCountAacPKh", "_Z23A2DP_GetPacketTimestampPKhS0_Pj",
    "_Z21A2DP_SourceCodecIndexPKh", "_ZN15A2dpCodecConfig21copyOutOtaCodecConfigEPh",
    vendor_index, find_source, set_ota, queue_flush, queue_enqueue, queue_dequeue, packet_free
};
uint32_t word(const uint8_t* p) { uint32_t value; std::memcpy(&value,p,4); return value; }
uint64_t wide(const uint8_t* p) { uint64_t value; std::memcpy(&value,p,8); return value; }
struct Code { ImageSymbol symbol{}; const uint8_t* bytes=nullptr; };
bool code(const SymbolImage& image,const char* name,Code* out) {
    return image.resolve(name,true,&out->symbol) && out->symbol.size>=4 &&
        out->symbol.size<=65536 && !(out->symbol.size&3) &&
        (out->bytes=image.bytes(out->symbol.address,out->symbol.size,5));
}
bool at(const Code& code,size_t offset,uint32_t value) {
    return offset<=code.symbol.size && 4<=code.symbol.size-offset && word(code.bytes+offset)==value;
}
bool has(const Code& code,uint32_t value) {
    for(size_t p=0;p<code.symbol.size;p+=4) if(at(code,p,value)) return true;
    return false;
}
template<size_t N> bool pattern(const Code& code,const uint32_t (&values)[N]) {
    for(size_t p=0;p+sizeof(values)<=code.symbol.size;p+=4) {
        size_t n=0; while(n<N && word(code.bytes+p+n*4)==values[n]) ++n;
        if(n==N) return true;
    }
    return false;
}
bool target(uint32_t instruction,uint64_t address,bool link,uint64_t* out) {
    if((instruction&0xfc000000)!=(link?0x94000000U:0x14000000U)) return false;
    const int64_t displacement=static_cast<int64_t>(static_cast<int32_t>((instruction&0x03ffffff)<<6))>>4;
    if(displacement<0 && address<static_cast<uint64_t>(-displacement)) return false;
    if(displacement>0 && static_cast<uint64_t>(displacement)>UINT64_MAX-address) return false;
    *out=address+displacement; return true;
}
bool call(const Code& from,uint64_t to,size_t* offset) {
    size_t count=0;
    for(size_t p=0;p<from.symbol.size;p+=4) { uint64_t address;
        if(target(word(from.bytes+p),from.symbol.address+p,true,&address) && address==to) {
            *offset=p; ++count;
        }
    }
    return count==1;
}
bool address(uint32_t instruction,uint64_t pc,unsigned reg,uint64_t* out) {
    // ADR: signed 21-bit PC-relative address, with no image-specific address embedded here.
    if((instruction&0x9f00001f)!=(0x10000000U|reg)) return false;
    const uint32_t immediate=((instruction>>29)&3)|((instruction>>3)&0x1ffffc);
    const int64_t delta=static_cast<int64_t>(static_cast<int32_t>(immediate<<11))>>11;
    if(delta<0 && pc<static_cast<uint64_t>(-delta)) return false;
    if(delta>0 && static_cast<uint64_t>(delta)>UINT64_MAX-pc) return false;
    *out=pc+delta; return true;
}
bool table_address(uint32_t adrp,uint32_t add,uint64_t pc,uint64_t* out) {
    if((adrp&0x9f00001f)!=0x90000000 || (add&0xffc003ff)!=0x91000000) return false;
    const uint32_t immediate=((adrp>>29)&3)|((adrp>>3)&0x1ffffc);
    const int64_t delta=(static_cast<int64_t>(static_cast<int32_t>(immediate<<11))>>11)*4096;
    const uint64_t page=pc&~uint64_t(4095), low=(add>>10)&4095;
    if(delta<0 && page<static_cast<uint64_t>(-delta)) return false;
    if(delta>0 && static_cast<uint64_t>(delta)>UINT64_MAX-page) return false;
    const uint64_t base=page+delta;
    if(low>UINT64_MAX-base) return false;
    *out=base+low; return true;
}
bool initializer(const SymbolImage& image) {
    Code generic,vendor,copy;
    if(!code(image,generic_init,&generic) || !code(image,vendor_init,&vendor) ||
       !code(image,"_ZN15A2dpCodecConfig21copyOutOtaCodecConfigEPh",&copy)) return false;
    // Incoming rate, SEP and index survive logging in the same callee-saved registers.
    const uint32_t keep_rate_and_sep[]={0x2a0203f4,0xaa0103f3};
    const uint32_t clear_tail[]={0x3902227f,0x3900b67f}; // SEP[136]=SEP[45]=0.
    // Native OTA copy is 32 bytes plus an overlapping 16-byte tail at +29: exactly 45 bytes.
    const uint32_t copy_ota[]={0xad4006a2,0x3cc1d2a0,0x3c81d280,0xad000682};
    if(!pattern(generic,keep_rate_and_sep) || !pattern(generic,clear_tail) ||
       !pattern(copy,copy_ota) || !has(generic,0x2a0003f5) || !has(generic,0x12000000)) return false;
    size_t p=0;
    // Native generic signature (index,SEP,rate) delegates to vendor (rate,index,SEP).
    if(!call(generic,vendor.symbol.address,&p) || p<12 ||
       !at(generic,p-12,0x2a1403e0) || !at(generic,p-8,0x2a1503e1) ||
       !at(generic,p-4,0xaa1303e2)) return false;
    // Slot 5 has no stock vendor implementation and follows the explicit false return.
    const uint32_t reject[]={0x2a1f03e0,0xd65f03c0};
    return pattern(vendor,reject) && at(vendor,0,0xd503245f) && at(vendor,4,0x7100143f);
}
bool encoder_table(const SymbolImage& image) {
    Code getter;
    if(!code(image,aac_interface,&getter)) return false;
    uint64_t entries[7];
    for(size_t i=0;i<7;++i) { Code function; if(!code(image,encoder_functions[i],&function)) return false;
        entries[i]=function.symbol.address;
    }
    size_t matches=0;
    for(size_t p=0;p+8<=getter.symbol.size;p+=4) {
        uint64_t address;
        if(!table_address(word(getter.bytes+p),word(getter.bytes+p+4),getter.symbol.address+p,&address)) continue;
        const auto* bytes=image.bytes(address,8*sizeof(uint64_t),4);
        if(!bytes) return false;
        for(size_t i=0;i<7;++i) if(wide(bytes+i*8)!=entries[i]) return false;
        if(wide(bytes+7*8)) return false;
        ++matches;
    }
    return matches==1;
}
bool source_lookup(const SymbolImage& image) {
    Code vendor,find,ota;
    if(!code(image,vendor_index,&vendor) || !code(image,find_source,&find) || !code(image,set_ota,&ota)) return false;
    size_t p=0;
    // Incoming source OTA selection calls the factory lookup. That lookup inlines the generic
    // dispatcher, then calls the vendor index directly: detouring only the global helper misses it.
    if(!call(ota,find.symbol.address,&p) || p<8 || p+8>ota.symbol.size || !at(ota,p-8,0xaa1303e0) ||
       !at(ota,p-4,0xaa1403e1) || (word(ota.bytes+p+4)&0xff00001f)!=0xb4000000) return false;
    if(!call(find,vendor.symbol.address,&p) || p<4 || p+12>find.symbol.size || !at(find,p-4,0xaa1403e0) ||
       !at(find,p+4,0x71004c1f) || (word(find.bytes+p+8)&0xff00001f)!=0x54000000) return false;
    // The stock vendor helper decodes the vendor word, returns a 32-bit source enum and uses
    // the same sentinel19 as the global helper. It is not the peer-source/sink decoder API.
    const uint32_t invalid[]={0x52800268,0x1a880120};
    return has(vendor,0xb8403013) && pattern(vendor,invalid);
}
bool enqueue_contract(const SymbolImage& image) {
    Code callback,flush,push,pop,free;
    if(!code(image,enqueue,&callback) || callback.symbol.size!=660 ||
       !code(image,queue_flush,&flush) || !code(image,queue_enqueue,&push) ||
       !code(image,queue_dequeue,&pop) || !code(image,packet_free,&free) ||
       free.symbol.size!=8 || !at(free,0,0xd503245f)) return false;
    uint64_t release;
    if(!target(word(free.bytes+4),free.symbol.address+4,false,&release) || !image.bytes(release,4,5)) return false;
    // This narrow ABI guard pins instruction roles and relative control flow, not linked addresses.
    // x19 holds the incoming packet; w25 snapshots tx_flush once and is retained until the return.
    if(!at(callback,0x30,0xaa0003f3) || !at(callback,0x44,0xaa0003f5) ||
       (word(callback.bytes+0x40)&0x9f00001f)!=0x90000008 ||
       (word(callback.bytes+0x48)&0xffc003ff)!=0x39400119 ||
       !at(callback,0x4c,0x7100073f) || !at(callback,0x264,0x52000320)) return false;
    // Derive the queue address from both paths. tx_flush is the byte immediately after its pointer.
    auto data_address=[&](size_t p,unsigned reg,uint64_t* out) {
        const uint32_t page=word(callback.bytes+p),add=word(callback.bytes+p+4);
        if((page&0x9f00001f)!=(0x90000000U|reg) ||
           (add&0xffc003ff)!=(0x91000000U|(reg<<5)|reg)) return false;
        return table_address(page&~31U,add&~0x3ffU,callback.symbol.address+p,out);
    };
    uint64_t queue_a,queue_b,free_callback,flag_page;
    if(!data_address(0x98,20,&queue_a) || !data_address(0xe4,26,&queue_b) || queue_a!=queue_b ||
       !data_address(0xac,1,&free_callback) || free_callback!=free.symbol.address ||
       !table_address(word(callback.bytes+0x40)&~31U,0x91000000,callback.symbol.address+0x40,&flag_page)) return false;
    const uint64_t flag_offset=(word(callback.bytes+0x48)>>10)&4095;
    if(queue_a>UINT64_MAX-8 || flag_page>UINT64_MAX-flag_offset || flag_page+flag_offset!=queue_a+8) return false;
    size_t p;
    if(!call(callback,flush.symbol.address,&p) || p!=0xc0 ||
       !call(callback,push.symbol.address,&p) || p!=0x250 ||
       !call(callback,pop.symbol.address,&p) || p!=0x1cc || !at(callback,0xbc,0xa917d688) ||
       !at(callback,0xb8,0xf9400280) || !at(callback,0xc4,0xaa1303e0)) return false;
    uint64_t freed_current,freed_backlog;
    if(!target(word(callback.bytes+0xc8),callback.symbol.address+0xc8,true,&freed_current) ||
       !target(word(callback.bytes+0x1e4),callback.symbol.address+0x1e4,true,&freed_backlog) ||
       freed_current!=release || freed_backlog!=release) return false;
    // No intervening instruction replaces the packet/flag, and normal enqueue still consumes x19.
    const uint32_t push_args[]={0xaa1303e1,0xf9400340,0xeb09029f,0x8b140108,0x9a898289,0xa914a748};
    for(size_t i=0;i<6;++i) if(!at(callback,0x238+i*4,push_args[i])) return false;
    const uint32_t epilogue[]={0xa94d4ff4,0xf9404bfb,0xa94c57f6,0xa94b5ff8,0xa94a67fa,
        0xa9487bfd,0x910383ff,0xf85f8e5e,0xd50323bf,0xd65f03c0};
    for(size_t i=0;i<10;++i) if(!at(callback,0x268+i*4,epilogue[i])) return false;
    // Enumerate every local branch: the flush path skips enqueue; every normal path reaches it.
    // No new early error return, indirect branch, or alternate false path is admitted.
    struct Branch { size_t at,to; uint32_t mask,kind; };
    constexpr Branch branches[]={
        {0x50,0xd0,0xff00001f,0x54000001},{0xcc,0x254,0xfc000000,0x14000000},
        {0xf4,0x234,0xff00001f,0x54000009},{0x1a0,0x1ec,0xff00001f,0xb4000000},
        {0x1ac,0x1bc,0xfc000000,0x14000000},{0x1b8,0x1f4,0xff00001f,0xb4000000},
        {0x1d0,0x1b0,0xff00001f,0xb4000000},{0x1e8,0x1b0,0xfc000000,0x14000000},
        {0x260,0x290,0xff00001f,0x54000001}};
    size_t seen=0,release_calls=0;
    for(size_t i=0;i<callback.symbol.size;i+=4) {
        const uint32_t ins=word(callback.bytes+i);
        uint64_t destination;
        if(target(ins,callback.symbol.address+i,true,&destination)) { if(destination==release) ++release_calls; continue; }
        const bool direct=(ins&0xfc000000)==0x14000000;
        const bool conditional=(ins&0xff000010)==0x54000000 || (ins&0x7e000000)==0x34000000;
        if(direct || conditional) {
            const Branch* expected=nullptr;
            for(const auto& branch:branches) if(branch.at==i) expected=&branch;
            if(!expected || (ins&expected->mask)!=expected->kind) return false;
            if(direct) {
                if(!target(ins,callback.symbol.address+i,false,&destination) || destination!=callback.symbol.address+expected->to) return false;
            } else {
                const int32_t delta=static_cast<int32_t>((ins&0x00ffffe0)<<8)>>11;
                if(static_cast<int64_t>(i)+delta!=static_cast<int64_t>(expected->to)) return false;
            }
            ++seen; continue;
        }
        if((ins&0x7e000000)==0x36000000 || (ins&0xfe000000)==0xd6000000) {
            if(i!=0x28c || ins!=0xd65f03c0) return false;
        }
        if(i>0x30 && i<0x264 && i!=0x48 && ((ins&31)==19 || (ins&31)==25)) return false;
        if(i>0x30 && i<0x264 && (ins&0x3a400000)==0x28400000 &&
           (((ins>>10)&31)==19 || ((ins>>10)&31)==25)) return false;
        const auto base=(ins>>5)&31;
        if(i>0x30 && i<0x264 && (base==19 || base==25) &&
           (((ins&0x3b200000)==0x38000000 && ((ins>>10)&1)) ||
            ((ins&0x3a000000)==0x28000000 && ((ins>>23)&1)))) return false;
        // The known callback has no exclusive/atomic operation with an additional status register.
        if(i>0x30 && i<0x264 && (ins&0x3f000000)==0x08000000) return false;
    }
    return seen==sizeof(branches)/sizeof(branches[0]) && release_calls==2;
}
bool consumers(const SymbolImage& image) {
    Code begin,pump,tx,end,time,size,pcm,output;
    if(!code(image,start,&begin) || !code(image,timer,&pump) || !code(image,tx_start,&tx) ||
       !code(image,cleanup,&end) || !code(image,tick,&time) || !code(image,queue_size,&size) ||
       !code(image,read,&pcm) || !code(image,enqueue,&output)) return false;
    size_t p=0;
    // Tick stays 64-bit in x20. Queue length stays size_t in x21.
    if(!call(pump,time.symbol.address,&p) || !at(pump,p+4,0xaa0003f4) ||
       !call(pump,size.symbol.address,&p) || !at(pump,p+12,0xaa0003f5)) return false;
    bool slots=false;
    for(size_t i=0;i+32<=pump.symbol.size;i+=4) {
        if(!at(pump,i,0xf9401d09) || (word(pump.bytes+i+4)&0xff00001f)!=0xb4000009 ||
           !at(pump,i+8,0xaa1503e0) || !at(pump,i+12,0xd63f0120) ||
           !at(pump,i+20,0xf9401908) || !at(pump,i+24,0xaa1403e0) || !at(pump,i+28,0xd63f0100)) continue;
        // A null slot7 skips exactly its call to the common reload/slot6 path.
        const uint32_t cb=word(pump.bytes+i+4);
        const int32_t displacement=static_cast<int32_t>((cb&0x00ffffe0)<<8)>>11;
        slots=displacement==16;
    }
    if(!slots) return false;
    // Slot0 receives native four-byte peer parameters, the codec, and the two exact callbacks.
    bool init=false;
    for(size_t i=0;i+32<=begin.symbol.size;i+=4) {
        uint64_t input,packet;
        if(!at(begin,i,0xf94002c8) || !at(begin,i+4,0xd503201f) ||
           !address(word(begin.bytes+i+8),begin.symbol.address+i+8,2,&input) || input!=pcm.symbol.address ||
           !at(begin,i+12,0xd503201f) ||
           !address(word(begin.bytes+i+16),begin.symbol.address+i+16,3,&packet) || packet!=output.symbol.address ||
           !at(begin,i+20,0x910073e0) || !at(begin,i+24,0xaa1503e1) || !at(begin,i+28,0xd63f0100)) continue;
        init=true;
    }
    const uint32_t peer_zero[]={0xb9001fff}; // The caller initializes exactly four bytes at sp+28.
    const uint32_t cleanup_slot[]={0xf9400508,0xd63f0100};
    const uint32_t reset_slot[]={0xf9400908,0xd63f0100};
    const uint32_t interval_slot[]={0xf9401108,0xd63f0100};
    const uint32_t timestamp[]={0xb9400028,0x52800020,0xb9000048};
    Code stamp,index;
    return init && pattern(begin,peer_zero) && pattern(end,cleanup_slot) && pattern(tx,reset_slot) &&
        pattern(tx,interval_slot) && code(image,"_Z23A2DP_GetPacketTimestampPKhS0_Pj",&stamp) &&
        pattern(stamp,timestamp) && code(image,"_Z21A2DP_SourceCodecIndexPKh",&index) &&
        has(index,0x52800260); // Exact invalid native source index 19.
}
}

const char* const* pixel17_wire_contract_symbols(size_t* count) {
    if(count) *count=sizeof(contexts)/sizeof(contexts[0]);
    return contexts;
}
bool pixel17_wire_contract(const SymbolImage& image) {
    // The exact mangled names encode source-only arguments, including new sample-rate initializer,
    // 32-bit read count, size_t enqueue frame count and 64-bit encoder clock. No sink hook is admitted.
    size_t count=0; const auto* hooks=pixel17_wire_hook_specs(&count);
    if(!hooks || count!=13) return false;
    for(size_t i=0;i<count;++i) { Code function;
        if(!code(image,hooks[i].symbol,&function)) return false;
    }
    for(const auto* name:contexts) { Code function; if(!code(image,name,&function)) return false; }
    if(!initializer(image) || !encoder_table(image) || !consumers(image) || !source_lookup(image) ||
       !enqueue_contract(image)) return false;
    for(const char* name:{"_Z23A2DP_GetTrackSampleRatePKh","_Z26A2DP_GetTrackBitsPerSamplePKh",
                         "_Z25A2DP_GetTrackChannelCountPKh"}) {
        Code parameter;
        if(!code(image,name,&parameter) || !has(parameter,0x12800000)) return false;
    }
    Code rate,bits,channels;
    // Return units are numeric Hz, signed integer bits/channel count; not native bit masks.
    return code(image,contexts[18],&rate) && has(rate,0x52977000) &&
        code(image,contexts[19],&bits) && has(bits,0x52800200) &&
        code(image,contexts[20],&channels) && has(channels,0x52800049);
}
}
