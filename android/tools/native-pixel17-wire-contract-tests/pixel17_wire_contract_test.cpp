// SPDX-License-Identifier: GPL-3.0-or-later
#include "pixel17_wire_contract.h"
#include "pixel17_wire_hooks.h"
#include <algorithm>
#include <cassert>
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>
#include <map>
#include <string>
#include <vector>

namespace {
void put(std::vector<uint8_t>& b,size_t at,uint64_t value,size_t count) {
    for(size_t i=0;i<count;++i) b.at(at+i)=value>>(8*i);
}
constexpr char generic[]="_Z20A2DP_InitCodecConfig23btav_a2dp_codec_index_tP14AvdtpSepConfig29btav_a2dp_codec_sample_rate_t";
constexpr char vendor[]="_Z26A2DP_VendorInitCodecConfig29btav_a2dp_codec_sample_rate_t23btav_a2dp_codec_index_tP14AvdtpSepConfig";
constexpr char timer[]="_ZL35btif_a2dp_source_audio_handle_timerv";
constexpr char start[]="_ZL38btif_a2dp_source_start_session_delayedRK10RawAddressNSt3__17promiseIvEE";
constexpr char tick[]="_ZN9bluetooth6common29time_get_audio_server_tick_usEv";
constexpr char queue[]="_Z18fixed_queue_lengthP13fixed_queue_t";
constexpr char read[]="_ZL30btif_a2dp_source_read_callbackPhj";
constexpr char enqueue[]="_ZL33btif_a2dp_source_enqueue_callbackP6BT_HDRmj";
constexpr char getter[]="_Z27A2DP_GetEncoderInterfaceAacPKh";
constexpr char vendor_index[]="_Z27A2DP_VendorSourceCodecIndexPKh";
constexpr char find_source[]="_ZN10A2dpCodecs21findSourceCodecConfigEPKh";
constexpr char set_ota[]="_ZN10A2dpCodecs17setCodecOtaConfigEPKhPK30tA2DP_ENCODER_INIT_PEER_PARAMSPhPbS6_S6_";
constexpr char flush[]="_Z17fixed_queue_flushP13fixed_queue_tPFvPvE";
constexpr char push[]="_Z19fixed_queue_enqueueP13fixed_queue_tPv";
constexpr char pop[]="_Z23fixed_queue_try_dequeueP13fixed_queue_t";
constexpr char packet_free[]="_Z8osi_freePv";
constexpr const char* functions[]={
    "_Z21a2dp_aac_encoder_initPK30tA2DP_ENCODER_INIT_PEER_PARAMSP15A2dpCodecConfigPFjPhjEPFbP6BT_HDRmjE",
    "_Z24a2dp_aac_encoder_cleanupv","_Z22a2dp_aac_feeding_resetv","_Z22a2dp_aac_feeding_flushv",
    "_Z32a2dp_aac_get_encoder_interval_msv","_Z33a2dp_aac_get_effective_frame_sizev","_Z20a2dp_aac_send_framesm"};
struct Fixture {
    std::vector<uint8_t> bytes=std::vector<uint8_t>(50000);
    std::map<std::string,uint64_t> symbols;
    static size_t offset(uint64_t va) { return static_cast<size_t>(va-0x1000+256); }
    void words(const char* name,std::initializer_list<uint32_t> code) {
        size_t p=offset(symbols.at(name)); for(auto word:code) { put(bytes,p,word,4); p+=4; }
    }
    uint32_t bl(const char* from,size_t p,const char* to) {
        const auto distance=static_cast<int64_t>(symbols.at(to))-static_cast<int64_t>(symbols.at(from)+p);
        assert(!(distance&3)); return 0x94000000|((distance/4)&0x3ffffff);
    }
    uint32_t adr(const char* from,size_t p,const char* to,unsigned reg) {
        const auto delta=static_cast<int64_t>(symbols.at(to))-static_cast<int64_t>(symbols.at(from)+p);
        const uint32_t imm=static_cast<uint32_t>(delta)&0x1fffff;
        return 0x10000000|reg|((imm&3)<<29)|((imm&0x1ffffc)<<3);
    }
    Fixture() {
        size_t count=0; const auto* names=eld::pixel17_wire_contract_symbols(&count);
        std::vector<std::string> all(names,names+count);
        const auto* hooks=eld::pixel17_wire_hook_specs(&count);
        for(size_t i=0;i<count;++i) all.emplace_back(hooks[i].symbol);
        std::sort(all.begin(),all.end()); all.erase(std::unique(all.begin(),all.end()),all.end());
        std::memcpy(bytes.data(),"\177ELF\2\1\1",7);
        put(bytes,16,3,2); put(bytes,18,183,2); put(bytes,20,1,4); put(bytes,32,64,8);
        put(bytes,40,48000,8); put(bytes,52,64,2); put(bytes,54,56,2); put(bytes,56,1,2);
        put(bytes,58,64,2); put(bytes,60,5,2); put(bytes,62,1,2);
        put(bytes,64,1,4); put(bytes,68,5,4); put(bytes,72,256,8); put(bytes,80,0x1000,8);
        put(bytes,96,24576,8); put(bytes,104,24576,8);
        constexpr char sections[]="\0.shstrtab\0.strtab\0.symtab\0.text\0";
        std::memcpy(bytes.data()+32768,sections,sizeof(sections));
        size_t string=33025,entry=40024; uint64_t va=0x1000;
        for(const auto& name:all) {
            const auto location=name==enqueue?0x6200:va;
            symbols[name]=location; put(bytes,entry,string-33024,4); bytes.at(entry+4)=0x12;
            put(bytes,entry+6,4,2); put(bytes,entry+8,location,8);
            put(bytes,entry+16,name==enqueue?660:name==packet_free?8:128,8);
            std::memcpy(bytes.data()+string,name.c_str(),name.size()+1); string+=name.size()+1; entry+=24;
            put(bytes,offset(va),0xd65f03c0,4); va+=256;
        }
        auto section=[&](size_t i,uint32_t name,uint32_t type,size_t at,size_t size) {
            const size_t p=48000+i*64; put(bytes,p,name,4); put(bytes,p+4,type,4);
            put(bytes,p+24,at,8); put(bytes,p+32,size,8);
        };
        section(1,1,3,32768,sizeof(sections)); section(2,11,3,33024,string-33024);
        section(3,19,2,40000,entry-40000); put(bytes,48000+3*64+40,2,4);
        put(bytes,48000+3*64+44,1,4); put(bytes,48000+3*64+56,24,8);
        section(4,27,1,256,24576); put(bytes,48000+4*64+8,6,8); put(bytes,48000+4*64+16,0x1000,8);
        words(generic,{0x2a0203f4,0xaa0103f3,0x2a0003f5,0x3902227f,0x3900b67f,
            0x2a1403e0,0x2a1503e1,0xaa1303e2,bl(generic,32,vendor),0x12000000});
        words(vendor,{0xd503245f,0x7100143f,0x2a1f03e0,0xd65f03c0});
        words("_ZN15A2dpCodecConfig21copyOutOtaCodecConfigEPh",{0xad4006a2,0x3cc1d2a0,0x3c81d280,0xad000682});
        const int64_t pages=(0x6000-static_cast<int64_t>(symbols.at(getter)&~uint64_t(4095)))/4096;
        const uint32_t imm=static_cast<uint32_t>(pages)&0x1fffff;
        words(getter,{0x90000000|((imm&3)<<29)|((imm&0x1ffffc)<<3),0x91000000,0xd65f03c0});
        for(size_t i=0;i<7;++i) put(bytes,offset(0x6000)+i*8,symbols.at(functions[i]),8);
        words(timer,{bl(timer,0,tick),0xaa0003f4,bl(timer,8,queue),0xd503201f,0xd503201f,0xaa0003f5,
            0xf9401d09,0xb4000089,0xaa1503e0,0xd63f0120,0xf9433308,0xf9401908,0xaa1403e0,0xd63f0100});
        words(start,{0xb9001fff,0xf94002c8,0xd503201f,adr(start,12,read,2),0xd503201f,
            adr(start,20,enqueue,3),0x910073e0,0xaa1503e1,0xd63f0100});
        words("_ZL38btif_a2dp_source_cleanup_codec_delayedv",{0xf9400508,0xd63f0100});
        words("_ZL37btif_a2dp_source_audio_tx_start_eventv",{0xf9400908,0xd63f0100,0xf9401108,0xd63f0100});
        words("_Z23A2DP_GetPacketTimestampPKhS0_Pj",{0xb9400028,0x52800020,0xb9000048});
        words("_Z21A2DP_SourceCodecIndexPKh",{0x52800260});
        for(auto name:{"_Z23A2DP_GetTrackSampleRatePKh","_Z26A2DP_GetTrackBitsPerSamplePKh","_Z25A2DP_GetTrackChannelCountPKh"}) words(name,{0x12800000});
        words("_Z26A2DP_GetTrackSampleRateAacPKh",{0x52977000});
        words("_Z29A2DP_GetTrackBitsPerSampleAacPKh",{0x52800200});
        words("_Z28A2DP_GetTrackChannelCountAacPKh",{0x52800049});
        words(vendor_index,{0xb8403013,0x52800268,0x1a880120});
        words(find_source,{0xaa1403e0,bl(find_source,4,vendor_index),0x71004c1f,0x54000000});
        words(set_ota,{0xaa1303e0,0xaa1403e1,bl(set_ota,8,find_source),0xb4000000});
        for(size_t p=0;p<660;p+=4) put(bytes,offset(symbols.at(enqueue))+p,0xd503201f,4);
        auto instruction=[&](size_t p,uint32_t ins) { put(bytes,offset(symbols.at(enqueue))+p,ins,4); };
        auto page_address=[&](size_t p,uint64_t target,unsigned reg) {
            const auto pc=symbols.at(enqueue)+p;
            const auto delta=(static_cast<int64_t>(target&~uint64_t(4095))-static_cast<int64_t>(pc&~uint64_t(4095)))/4096;
            const auto imm=static_cast<uint32_t>(delta)&0x1fffff;
            instruction(p,0x90000000|reg|((imm&3)<<29)|((imm&0x1ffffc)<<3));
            instruction(p+4,0x91000000|(reg<<5)|reg|((target&4095)<<10));
        };
        // Synthetic flow uses a different queue/global/link address than the shipped library.
        page_address(0x40,0x7000,8); instruction(0x44,0xaa0003f5);
        instruction(0x48,0x39400119|(8<<10)); instruction(0x4c,0x7100073f);
        instruction(0x30,0xaa0003f3);
        page_address(0x98,0x7000,20); page_address(0xe4,0x7000,26);
        page_address(0xac,symbols.at(packet_free),1);
        instruction(0xb8,0xf9400280); instruction(0xbc,0xa917d688);
        instruction(0xc0,bl(enqueue,0xc0,flush)); instruction(0xc4,0xaa1303e0);
        // osi_free is a BTI + relative tail call to the same free implementation used by callback.
        put(bytes,offset(symbols.at(packet_free)),0xd503245f,4);
        const uint64_t release=symbols.at(read);
        put(bytes,offset(symbols.at(packet_free))+4,
            0x14000000|((static_cast<int64_t>(release)-static_cast<int64_t>(symbols.at(packet_free)+4))/4&0x3ffffff),4);
        instruction(0xc8,bl(enqueue,0xc8,read)); instruction(0x1cc,bl(enqueue,0x1cc,pop));
        instruction(0x1e4,bl(enqueue,0x1e4,read)); instruction(0x250,bl(enqueue,0x250,push));
        instruction(0x290,bl(enqueue,0x290,tick));
        const uint32_t arguments[]={0xaa1303e1,0xf9400340,0xeb09029f,0x8b140108,0x9a898289,0xa914a748};
        for(size_t i=0;i<6;++i) instruction(0x238+i*4,arguments[i]);
        instruction(0x264,0x52000320);
        const uint32_t ending[]={0xa94d4ff4,0xf9404bfb,0xa94c57f6,0xa94b5ff8,0xa94a67fa,
            0xa9487bfd,0x910383ff,0xf85f8e5e,0xd50323bf,0xd65f03c0};
        for(size_t i=0;i<10;++i) instruction(0x268+i*4,ending[i]);
        auto conditional=[&](size_t p,size_t to,uint32_t kind) {
            instruction(p,kind|((static_cast<int32_t>(to)-static_cast<int32_t>(p))/4&0x7ffff)<<5);
        };
        auto jump=[&](size_t p,size_t to) {
            instruction(p,0x14000000|((static_cast<int32_t>(to)-static_cast<int32_t>(p))/4&0x3ffffff));
        };
        conditional(0x50,0xd0,0x54000001); jump(0xcc,0x254); conditional(0xf4,0x234,0x54000009);
        conditional(0x1a0,0x1ec,0xb4000000); jump(0x1ac,0x1bc); conditional(0x1b8,0x1f4,0xb4000000);
        conditional(0x1d0,0x1b0,0xb4000000); jump(0x1e8,0x1b0); conditional(0x260,0x290,0x54000001);
    }
};
bool accepted(const std::vector<uint8_t>& bytes) {
    eld::SymbolImage image; assert(image.open(bytes.data(),bytes.size())); return eld::pixel17_wire_contract(image);
}
void synthetic() {
    Fixture good; assert(accepted(good.bytes));
    auto rejects=[&](const char* name,size_t p,uint32_t value) {
        auto bytes=good.bytes; put(bytes,Fixture::offset(good.symbols.at(name))+p,value,4); assert(!accepted(bytes));
    };
    rejects(generic,12,0x3902267f); // Changed SEP tail offset.
    rejects(generic,16,0x3900ba7f); // Changed 45-byte codec_info bound.
    rejects(generic,24,0x2a1403e1); // Index/rate swapped.
    rejects(generic,36,0x12000400); // Result is no longer boolean.
    rejects(timer,4,0x2a0003f4); // Truncated tick.
    rejects(timer,20,0x2a0003f5); // Truncated size_t queue length.
    rejects(timer,28,0xb40000a9); // Null queue callback no longer skips safely.
    rejects(timer,44,0xf9401508); // Wrong send_frames slot.
    rejects(start,0,0xf9000fff); // Peer parameter storage changed.
    rejects(start,12,good.adr(start,12,enqueue,2)); // Wrong read callback.
    rejects("_Z26A2DP_GetTrackSampleRateAacPKh",0,0x52800040); // Bit mask instead of Hertz.
    rejects("_Z21A2DP_SourceCodecIndexPKh",0,0x52800160); // Legacy invalid sentinel.
    rejects("_ZN15A2dpCodecConfig21copyOutOtaCodecConfigEPh",8,0x3c81e280); // Changed output extent.
    rejects(find_source,4,good.bl(find_source,4,"_Z21A2DP_SourceCodecIndexPKh")); // Direct vendor dispatch changed.
    rejects(find_source,8,0x71002c1f); // Different source sentinel.
    rejects(set_ota,4,0xaa1303e1); // Wrong incoming codec argument.
    rejects(vendor_index,4,0x52800168); // Legacy vendor sentinel.
    rejects(enqueue,0x48,0x39400119|(9<<10)); // Flush flag is not adjacent to queue pointer.
    rejects(enqueue,0x4c,0x71000b3f); // False now means a different flag value.
    rejects(enqueue,0x50,0x54000400); // Inverted/changed flush branch.
    rejects(enqueue,0xc0,good.bl(enqueue,0xc0,push)); // Flush no longer owns/drains the queue.
    rejects(enqueue,0xc4,0xaa1403e0); // Frees frame count instead of current packet.
    rejects(enqueue,0xc8,good.bl(enqueue,0xc8,tick)); // Current packet is not freed.
    rejects(enqueue,0xcc,0x14000001); // Flush falls through into normal enqueue.
    rejects(enqueue,0x220,0x52800019); // Flag overwritten before return.
    rejects(enqueue,0x224,0xaa0003f3); // Incoming packet overwritten before consumption.
    rejects(enqueue,0x228,0xd65f03c0); // Additional early false return.
    rejects(enqueue,0x22c,0xb4000020); // Additional conditional false/error path.
    rejects(enqueue,0x238,0xaa1403e1); // Queue receives another pointer.
    rejects(enqueue,0x250,good.bl(enqueue,0x250,flush)); // Nonflush no longer enqueues current packet.
    rejects(enqueue,0x264,0x2a1f03e0); // Always false.
    rejects(enqueue,0x268,0x52800000); // Return value overwritten in epilogue.
    rejects(enqueue,0x22c,0xf8008728); // Post-index store mutates the preserved flag base.
    rejects(enqueue,0x22c,0x88197c08); // New exclusive-store status overwrites w25.
    for(size_t i=0;i<8;++i) {
        auto bytes=good.bytes; put(bytes,Fixture::offset(0x6000)+i*8,0x1111,8); assert(!accepted(bytes));
    }
    eld::SymbolImage empty; assert(!eld::pixel17_wire_contract(empty));
    std::cout<<"Synthetic wire ABI, enqueue ownership and 42 targeted mutations passed\n";
}
void shipped(const char* path) {
    std::ifstream input(path,std::ios::binary);
    std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(input)),{});
    assert(accepted(bytes));
    eld::SymbolImage image; assert(image.open(bytes.data(),bytes.size()));
    for(auto name:{generic,vendor,getter,start,timer,"_Z21A2DP_SourceCodecIndexPKh",vendor_index,find_source,set_ota,enqueue}) {
        eld::ImageSymbol symbol; assert(image.resolve(name,true,&symbol));
        const auto* p=image.bytes(symbol.address,symbol.size,5); assert(p);
        auto altered=bytes; std::memset(altered.data()+(p-bytes.data()),0,static_cast<size_t>(symbol.size));
        assert(!accepted(altered));
    }
    std::cout<<"Private shipped SDK37 wire contract and changed-function rejection passed\n";
}
}
int main(int argc,char** argv) { synthetic(); if(argc==2) shipped(argv[1]); }
