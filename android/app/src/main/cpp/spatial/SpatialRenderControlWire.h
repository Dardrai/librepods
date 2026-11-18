#pragma once
#include "transport/receiver/PoseStoreWire.h"
#include <algorithm>
#include <array>
#include <cstring>

namespace dolby_pose::spatial_control {
namespace wire=dolby_pose::wire;
constexpr uint32_t kVersion=1, kCommandIndex=0x6001ff06, kStatusIndex=0xe001ff07;
constexpr size_t kCommandBytes=88, kStatusBytes=264;
constexpr char kCommandName[]="librepods.dolby.spatial-control";
constexpr char kStatusName[]="librepods.dolby.spatial-status";
using Nonce=std::array<uint8_t,16>;
using CommandFrame=std::array<uint8_t,kCommandBytes>;
using StatusFrame=std::array<uint8_t,kStatusBytes>;
enum class Opcode:uint32_t { Acquire=1,Set=2,Release=3 };
enum class Result:uint32_t { None=0,Accepted=1,Malformed=2,Unauthorized=3,Replay=4,ForeignOwner=5,Unsupported=6,ScopeChanged=7 };
enum Capability:uint32_t { Eac3Rendering=1,Ac4Rendering=2,TrackingGate=4,NativeModeReadback=8,OutputCommitReadback=16 };
constexpr uint32_t kCapabilities=31;
enum Choice:uint32_t { Spatial=1,Tracking=2 };
enum StatusFlag:uint32_t { Available=1,Owned=2,DesiredKnown=4,Rendered=8,OutputCommitted=16,
    NativeVerified=32,MatchesDesired=64,PosePresent=128 };
enum class Codec:uint32_t { None=0,Eac3=1,Ac4=2 };
struct Scope {
    uint64_t generation=0;
    uint32_t routeId=0;
    wire::Mac mac{};
    bool operator==(const Scope& other) const { return generation==other.generation && routeId==other.routeId && mac==other.mac; }
};
struct Command {
    Opcode opcode=Opcode::Acquire;
    Nonce nonce{};
    uint64_t controlGeneration=0,sequence=0,choiceEpoch=0;
    Scope scope;
    uint32_t choices=Spatial|Tracking;
    Result result=Result::None;
    uint32_t requiredCapabilities=kCapabilities;
};
struct Status {
    uint32_t flags=Available,capabilities=0,ownerPid=0;
    Result result=Result::None;
    Nonce nonce{};
    uint64_t controlGeneration=0,sequence=0,choiceEpoch=0;
    Scope scope;
    uint32_t desired=Spatial|Tracking;
    Codec codec=Codec::None;
    uint64_t appliedControlGeneration=0,appliedChoiceEpoch=0,decoderInstance=0,decoderEpoch=0,
        renderedBoottimeNs=0,committedBoottimeNs=0,renderSerial=0;
    int64_t inputPtsUs=0,outputPtsUs=0;
    uint32_t outputFrames=0,actual=0;
    std::array<int32_t,3> nativeMod{};
    int32_t nativeSource=0,nativeMaximum=0,pregain=0,systemGain=0;
    uint32_t nativeDax=0,failure=0;
};
inline bool nonzero(const Nonce& n) { uint8_t v=0;for(auto b:n)v|=b;return v!=0; }
inline bool valid(const Scope& s) {
    return wire::positiveLong(s.generation) && s.routeId>0 && s.routeId<=INT32_MAX &&
        std::any_of(s.mac.begin(),s.mac.end(),[](auto b){return b!=0;}) &&
        std::any_of(s.mac.begin(),s.mac.end(),[](auto b){return b!=255;});
}
inline bool choicesValid(uint32_t c) { return !(c&~3u) && (!(c&Tracking) || (c&Spatial)); }
inline bool header(wire::Span s,size_t bytes,uint32_t index) {
    return s.data && s.size==bytes && wire::read32(s.data)==bytes && wire::read32(s.data+4)==index;
}
inline CommandFrame encode(const Command& c) {
    CommandFrame out{};auto*p=out.data();wire::write32(p,kCommandBytes);wire::write32(p+4,kCommandIndex);p+=8;
    wire::write32(p,kVersion);wire::write32(p+4,uint32_t(c.opcode));std::memcpy(p+8,c.nonce.data(),16);
    wire::write64(p+24,c.controlGeneration);wire::write64(p+32,c.sequence);wire::write64(p+40,c.choiceEpoch);
    wire::write64(p+48,c.scope.generation);wire::write32(p+56,c.scope.routeId);wire::write32(p+60,c.choices);
    std::memcpy(p+64,c.scope.mac.data(),6);wire::write32(p+72,uint32_t(c.result));wire::write32(p+76,c.requiredCapabilities);return out;
}
inline bool decode(wire::Span s,Command*out,bool response=false) {
    if(!out||!header(s,kCommandBytes,kCommandIndex))return false;const auto*p=s.data+8;
    if(wire::read32(p)!=kVersion||wire::read32(p+4)<1||wire::read32(p+4)>3||p[70]||p[71]||
        wire::read32(p+72)>uint32_t(Result::ScopeChanged)||(!response&&wire::read32(p+72)))return false;
    Command c;c.opcode=Opcode(wire::read32(p+4));std::memcpy(c.nonce.data(),p+8,16);
    c.controlGeneration=wire::read64(p+24);c.sequence=wire::read64(p+32);c.choiceEpoch=wire::read64(p+40);
    c.scope.generation=wire::read64(p+48);c.scope.routeId=wire::read32(p+56);c.choices=wire::read32(p+60);
    std::memcpy(c.scope.mac.data(),p+64,6);c.result=Result(wire::read32(p+72));c.requiredCapabilities=wire::read32(p+76);
    if(!nonzero(c.nonce)||!wire::positiveLong(c.controlGeneration)||!wire::positiveLong(c.sequence)||
        !wire::positiveLong(c.choiceEpoch)||!valid(c.scope)||!choicesValid(c.choices)||
        !c.requiredCapabilities||(c.requiredCapabilities&~kCapabilities))return false;
    *out=c;return true;
}
inline StatusFrame encode(const Status&s) {
    StatusFrame out{};auto*p=out.data();wire::write32(p,kStatusBytes);wire::write32(p+4,kStatusIndex);p+=8;
    wire::write32(p,kVersion);wire::write32(p+4,s.flags);wire::write32(p+8,uint32_t(s.result));wire::write32(p+12,s.capabilities);
    wire::write32(p+16,s.ownerPid);wire::write32(p+20,s.scope.routeId);std::memcpy(p+24,s.nonce.data(),16);
    wire::write64(p+40,s.controlGeneration);wire::write64(p+48,s.sequence);wire::write64(p+56,s.choiceEpoch);
    wire::write64(p+64,s.scope.generation);wire::write32(p+72,s.desired);wire::write32(p+76,uint32_t(s.codec));
    std::memcpy(p+80,s.scope.mac.data(),6);
    const uint64_t words[]={s.appliedControlGeneration,s.appliedChoiceEpoch,s.decoderInstance,s.decoderEpoch,
        s.renderedBoottimeNs,s.committedBoottimeNs,uint64_t(s.inputPtsUs),uint64_t(s.outputPtsUs),s.renderSerial};
    for(size_t i=0;i<9;++i)wire::write64(p+88+8*i,words[i]);
    wire::write32(p+160,s.outputFrames);wire::write32(p+164,s.actual);
    for(size_t i=0;i<3;++i)wire::write32(p+168+4*i,uint32_t(s.nativeMod[i]));
    wire::write32(p+180,uint32_t(s.nativeSource));wire::write32(p+184,uint32_t(s.nativeMaximum));
    wire::write32(p+188,s.nativeDax);wire::write32(p+192,uint32_t(s.pregain));wire::write32(p+196,uint32_t(s.systemGain));
    wire::write32(p+200,s.failure);return out;
}
inline bool decode(wire::Span span,Status*out) {
    if(!out||!header(span,kStatusBytes,kStatusIndex))return false;const auto*p=span.data+8;
    if(wire::read32(p)!=kVersion||(wire::read32(p+4)&~255u)||!(wire::read32(p+4)&Available)||
        wire::read32(p+8)>uint32_t(Result::ScopeChanged)||(wire::read32(p+12)&~kCapabilities)||
        wire::read32(p+76)>2||p[86]||p[87])return false;
    for(size_t i=204;i<256;++i)if(p[i])return false;
    Status s;s.flags=wire::read32(p+4);s.result=Result(wire::read32(p+8));s.capabilities=wire::read32(p+12);
    s.ownerPid=wire::read32(p+16);s.scope.routeId=wire::read32(p+20);std::memcpy(s.nonce.data(),p+24,16);
    s.controlGeneration=wire::read64(p+40);s.sequence=wire::read64(p+48);s.choiceEpoch=wire::read64(p+56);
    s.scope.generation=wire::read64(p+64);s.desired=wire::read32(p+72);s.codec=Codec(wire::read32(p+76));std::memcpy(s.scope.mac.data(),p+80,6);
    uint64_t*words[]={&s.appliedControlGeneration,&s.appliedChoiceEpoch,&s.decoderInstance,&s.decoderEpoch,
        &s.renderedBoottimeNs,&s.committedBoottimeNs};for(size_t i=0;i<6;++i)*words[i]=wire::read64(p+88+8*i);
    auto signed64=[](const uint8_t*q){const auto v=wire::read64(q);int64_t out;std::memcpy(&out,&v,8);return out;};
    s.inputPtsUs=signed64(p+136);s.outputPtsUs=signed64(p+144);s.renderSerial=wire::read64(p+152);
    s.outputFrames=wire::read32(p+160);s.actual=wire::read32(p+164);
    auto signed32=[](const uint8_t*q){const auto v=wire::read32(q);int32_t out;std::memcpy(&out,&v,4);return out;};
    for(size_t i=0;i<3;++i)s.nativeMod[i]=signed32(p+168+4*i);
    s.nativeSource=signed32(p+180);s.nativeMaximum=signed32(p+184);s.nativeDax=wire::read32(p+188);
    s.pregain=signed32(p+192);s.systemGain=signed32(p+196);s.failure=wire::read32(p+200);
    if(!choicesValid(s.desired)||!choicesValid(s.actual)||s.nativeDax>1)return false;
    if(s.flags&Owned) {
        if(!s.ownerPid||s.ownerPid>INT32_MAX||!nonzero(s.nonce)||!wire::positiveLong(s.controlGeneration)||
            !wire::positiveLong(s.sequence)||!wire::positiveLong(s.choiceEpoch)||!valid(s.scope))return false;
    } else if((s.flags&(DesiredKnown|Rendered|OutputCommitted|NativeVerified|MatchesDesired|PosePresent))||
        s.ownerPid||nonzero(s.nonce)||s.controlGeneration||s.sequence||s.choiceEpoch||s.scope.generation||s.scope.routeId||
        std::any_of(s.scope.mac.begin(),s.scope.mac.end(),[](auto b){return b!=0;}))return false;
    if(s.flags&Rendered) {
        if(!wire::positiveLong(s.appliedControlGeneration)||!wire::positiveLong(s.appliedChoiceEpoch)||
            !wire::positiveLong(s.decoderInstance)||!wire::positiveLong(s.decoderEpoch)||
            !wire::positiveLong(s.renderedBoottimeNs)||!wire::positiveLong(s.renderSerial)||s.codec==Codec::None)return false;
    } else if(s.flags&(OutputCommitted|NativeVerified|MatchesDesired|PosePresent))return false;
    if(s.flags&OutputCommitted)if(!s.outputFrames||!wire::positiveLong(s.committedBoottimeNs)||s.committedBoottimeNs<s.renderedBoottimeNs)return false;
    if(s.flags&PosePresent)if(!(s.actual&Tracking))return false;
    if(s.flags&MatchesDesired)if(s.appliedControlGeneration!=s.controlGeneration||s.appliedChoiceEpoch!=s.choiceEpoch||s.actual!=s.desired)return false;
    *out=s;return true;
}
} // namespace dolby_pose::spatial_control
