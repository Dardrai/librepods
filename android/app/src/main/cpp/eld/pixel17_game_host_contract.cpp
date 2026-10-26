// SPDX-License-Identifier: GPL-3.0-or-later
#include "pixel17_game_host.h"
#include <cstring>

namespace eld {
namespace {
constexpr char delay_co[]="_Z15bta_av_delay_coP11tBTA_AV_SCBP12tBTA_AV_DATA";
constexpr char config_response[]="_Z26avdt_scb_hdl_setconfig_rspP8AvdtpScbP13tAVDT_SCB_EVT";
constexpr char delay_command[]="_Z26avdt_scb_hdl_delay_rpt_cmdP8AvdtpScbP13tAVDT_SCB_EVT";
constexpr char media_type[]="_Z20avdt_ad_type_to_tcidhP8AvdtpScb";
constexpr char find_cid[]="_Z20l2cu_find_ccb_by_cidP8tL2C_LCBt";
const char* const contexts[]={kPixel17GameOpen,kPixel17GameStart,kPixel17GameStop,kPixel17GameClose,
    kPixel17GameReconfigure,kPixel17GameDelay,kPixel17GameSco,kPixel17GameScb,kPixel17GameAvdtScb,
    kPixel17GameCid,kPixel17GameCidAcl,kPixel17GamePeerAcl,kPixel17GameScoCount,kPixel17GamePeerSco,
    delay_co,config_response,delay_command,media_type,find_cid};
struct Code { ImageSymbol symbol{}; const uint8_t* data=nullptr; };
uint32_t word(const uint8_t* p) { uint32_t value; std::memcpy(&value,p,4); return value; }
uint32_t at(const Code& c,size_t p) { return p+4<=c.symbol.size?word(c.data+p):0; }
bool code(const SymbolImage& image,const char* name,Code* out) {
    return image.resolve(name,true,&out->symbol) && out->symbol.size>=4 &&
        out->symbol.size<=65536 && !(out->symbol.size&3) &&
        (out->data=image.bytes(out->symbol.address,out->symbol.size,5));
}
bool load(uint32_t instruction,uint32_t opcode,unsigned rt,unsigned rn,size_t* offset,size_t scale=1) {
    if((instruction&0xffc003ff)!=(opcode|(rn<<5)|rt)) return false;
    *offset=((instruction>>10)&0xfff)*scale; return true;
}
bool add(uint32_t instruction,unsigned rd,unsigned rn,size_t* offset) {
    return load(instruction,0x91000000,rd,rn,offset);
}
bool contains(const Code& c,uint32_t value) {
    for(size_t p=0;p<c.symbol.size;p+=4) if(at(c,p)==value) return true;
    return false;
}
bool unique_add(const Code& c,unsigned rd,unsigned rn,size_t* out) {
    size_t count=0;
    for(size_t p=0;p<c.symbol.size;p+=4) { size_t value;
        if(add(at(c,p),rd,rn,&value)) { *out=value; ++count; }
    }
    return count==1;
}
bool call(const Code& c,uint64_t target,size_t* out) {
    size_t count=0;
    for(size_t p=0;p<c.symbol.size;p+=4) {
        const auto w=at(c,p); if((w&0xfc000000)!=0x94000000) continue;
        const int64_t delta=static_cast<int64_t>(static_cast<int32_t>((w&0x3ffffff)<<6))>>4;
        const auto address=c.symbol.address+p;
        if((delta<0 && address<static_cast<uint64_t>(-delta)) ||
            (delta>0 && address>UINT64_MAX-static_cast<uint64_t>(delta))) return false;
        if(address+delta==target) { *out=p; ++count; }
    }
    return count==1;
}
bool byte_field(const Code& c,uint32_t opcode,unsigned rt,unsigned rn,size_t value) {
    for(size_t p=0;p<c.symbol.size;p+=4) { size_t field;
        if(load(at(c,p),opcode,rt,rn,&field) && field==value) return true;
    }
    return false;
}
bool report_mask(const Code& c,size_t* field) {
    size_t count=0;
    for(size_t p=0;p+8<=c.symbol.size;p+=4) {
        size_t value;
        if(load(at(c,p),0x79400000,8,19,&value,2) &&
            (at(c,p+4)&0xfff8001f)==0x37400008) { *field=value; ++count; }
    }
    return count==1;
}
}
const char* const* pixel17_game_host_context_names(size_t* count) {
    if(count) *count=sizeof(contexts)/sizeof(contexts[0]);
    return contexts;
}
bool pixel17_game_host_contract(const SymbolImage& image,Pixel17GameLayout* out) {
    if(!out) return false;
    *out={};
    Code all[sizeof(contexts)/sizeof(contexts[0])];
    for(size_t i=0;i<sizeof(contexts)/sizeof(contexts[0]);++i) if(!code(image,contexts[i],&all[i])) return false;
    const auto& open=all[0]; const auto& start=all[1]; const auto& stop=all[2];
    const auto& reconfigure=all[4]; const auto& by_handle=all[7];
    const auto& cid=all[9]; const auto& cid_acl=all[10]; const auto& peer_acl=all[11];
    const auto& delay=all[14]; const auto& config=all[15]; const auto& avdt_delay=all[16];
    Pixel17GameLayout value;
    size_t p=0, other=0;
    if(!unique_add(open,20,0,&value.peer) || !unique_add(delay,21,0,&other) || other!=value.peer ||
        !call(open,cid.symbol.address,&p) || p<32 ||
        !load(at(open,p+4),0x79000000,0,19,&value.media_cid,2)) return false;
    size_t loads=0;
    for(size_t n=p-32;n<p;n+=4) if(load(at(open,n),0x39400000,0,19,&other)) {
        value.avdt_handle=other; ++loads;
    }
    if(loads!=1) return false;
    size_t rp=0;
    if(!call(reconfigure,cid.symbol.address,&rp) || rp<4 ||
        !load(at(reconfigure,rp-4),0x39400000,0,0,&other) || other!=value.avdt_handle) return false;
    bool cid_store=false;
    for(size_t n=rp+4;n<rp+24 && n<reconfigure.symbol.size;n+=4)
        if(load(at(reconfigure,n),0x79000000,0,19,&other,2) && other==value.media_cid) cid_store=true;
    if(!cid_store) return false;
    size_t handles=0;
    for(size_t n=0;n+12<=delay.symbol.size;n+=4)
        if(load(at(delay,n),0x39400000,0,19,&other) && at(delay,n+4)==0xf9402108 && at(delay,n+8)==0xd63f0100) {
            value.bta_handle=other; ++handles;
        }
    if(handles!=1 || value.bta_handle==value.avdt_handle) return false;
    size_t started_fields=0;
    for(size_t n=0;n+8<=stop.symbol.size;n+=4)
        if(load(at(stop,n),0x39400000,8,19,&other) &&
            (at(stop,n+4)&0x7f00001f)==0x34000008 &&
            byte_field(stop,0x39000000,31,19,other) && byte_field(start,0x39000000,25,19,other)) {
            value.co_started=other; ++started_fields;
        }
    if(started_fields!=1) return false;
    size_t codec_fields=0;
    for(size_t n=0;n+28<=start.symbol.size;n+=4)
        if(add(at(start,n),2,19,&other) && at(start,n+12)==0xaa1403e1 &&
            at(start,n+16)==0xf9401928 && at(start,n+24)==0xd63f0100) {
            value.codec=other; ++codec_fields;
        }
    if(codec_fields!=1 || !report_mask(avdt_delay,&value.avdt_reporting_mask) ||
        !report_mask(config,&other) || other!=value.avdt_reporting_mask) return false;
    size_t self_fields=0;
    for(size_t n=0;n<avdt_delay.symbol.size;n+=4)
        if(load(at(avdt_delay,n),0x39400000,0,0,&other)) { value.avdt_self_handle=other; ++self_fields; }
    if(self_fields!=1) return false;
    // Native getters, rather than reconstructed L2CAP/ACL containers, own CID and ACL lookup.
    size_t c=0;
    if(!call(cid,all[8].symbol.address,&c) || !call(cid,all[17].symbol.address,&c) || c<4 ||
        at(cid,c-4)!=0x52800020 || !call(cid_acl,all[18].symbol.address,&c) ||
        !contains(cid_acl,0x79000288) || !contains(cid_acl,0x52800020) ||
        !contains(peer_acl,0x529fffe0) || !contains(by_handle,0x12001408)) return false;
    // All scalar fields are bounded, non-overlapping and below this ABI's native object limit.
    const size_t fields[]={value.media_cid,value.bta_handle,value.avdt_handle,value.co_started,
        value.peer,value.codec,value.avdt_reporting_mask,value.avdt_self_handle};
    for(auto field:fields) if(!field || field>16384) return false;
    if(value.codec+45>value.media_cid || value.media_cid+2>value.bta_handle ||
        value.bta_handle>=value.avdt_handle || value.avdt_handle>=value.co_started ||
        value.co_started>=value.peer) return false;
    *out=value; return true;
}
}
