// SPDX-License-Identifier: GPL-3.0-or-later
#include "pixel17_game_host.h"
#include "wire.h"
#include <array>
#include <cassert>
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <thread>
#include <vector>
using namespace eld;
namespace {
constexpr Pixel17GameLayout layout{0x6a8,0x6b0,0x6b4,0x6c6,0x6e8,0x5f4,0x142,0x204};
std::array<uint8_t,2048> scb{},other_scb{};
std::array<uint8_t,1024> avdt{};
uint8_t peer[6]{1,2,3,4,5,6},other_peer[6]{6,5,4,3,2,1};
uint8_t active[6]{},encoder_peer[6]{};
uint16_t cid=0,acl=11,cid_handle=11;
uint64_t now=1000000000,encoder_generation=9;
bool encoder_active=true,encoder_failed=false,peer_sco=false,readable=true;
uint8_t sco_count=0;
size_t reads=0,forwards[7]{};
std::vector<Pixel17GameInvalidation> invalidations;
void (*during_original)()=nullptr;
bool throw_original=false;
template<class T> void put(std::array<uint8_t,2048>& bytes,size_t at,T value) { std::memcpy(bytes.data()+at,&value,sizeof(value)); }
void set_cid(uint16_t value) { cid=value; put(scb,layout.media_cid,value); }
bool contains(const void* p,size_t n,const void* base,size_t size) {
    const auto a=reinterpret_cast<uintptr_t>(p),b=reinterpret_cast<uintptr_t>(base);
    return a>=b && a-b<=size && n<=size-(a-b);
}
bool copy(const void* p,size_t n,void* out) noexcept {
    ++reads;
    if(!readable || !(contains(p,n,scb.data(),scb.size()) || contains(p,n,other_scb.data(),other_scb.size()) ||
        contains(p,n,avdt.data(),avdt.size()) || contains(p,n,peer,6) || contains(p,n,other_peer,6))) return false;
    std::memcpy(out,p,n); return true;
}
void* find_scb(uint16_t handle) noexcept { return handle==0x41?scb.data():handle==0x42?other_scb.data():nullptr; }
void* find_avdt(uint8_t handle) noexcept { return handle==6?avdt.data():nullptr; }
uint16_t media_cid(uint8_t handle) noexcept { return handle==6?cid:0; }
bool cid_acl(uint16_t value,uint16_t* out) noexcept { if(!value || value!=cid) return false; *out=cid_handle; return true; }
uint16_t peer_acl(const void* p,uint8_t transport) noexcept { return transport==1 && !std::memcmp(p,peer,6)?acl:0xffff; }
bool active_peer(uint8_t* out,size_t n) noexcept { if(n!=6) return false; std::memcpy(out,active,6); return true; }
uint8_t sco_links() noexcept { return sco_count; }
bool peer_has_sco(const void*) noexcept { return peer_sco; }
bool encoder(Pixel17GameEncoder* out) noexcept {
    out->generation=encoder_generation; out->active=encoder_active; out->failed=encoder_failed;
    std::memcpy(out->peer,encoder_peer,6); return true;
}
uint64_t clock_ns() noexcept { return now; }
void invalidated(Pixel17GameInvalidation reason,const Pixel17GameHostState&) noexcept {
    invalidations.push_back(reason);
    (void)pixel17_game_host_snapshot(); // Callback is outside the state mutex.
}
Pixel17GameBindings bindings() {
    return {copy,find_scb,find_avdt,media_cid,cid_acl,peer_acl,active_peer,sco_links,peer_has_sco,encoder,clock_ns,invalidated};
}
void forwarded(size_t index) {
    ++forwards[index];
    if(during_original) { const auto f=during_original; during_original=nullptr; f(); }
    if(throw_original) throw std::runtime_error("original failure");
}
void open_original(void* p,void* data) { assert(p==scb.data() && data==reinterpret_cast<void*>(0x123)); forwarded(0); }
void start_original(void* p,void* data) {
    assert(p==scb.data() && data==reinterpret_cast<void*>(0x123)); forwarded(1); put(scb,layout.co_started,uint8_t{1});
}
void stop_original(void* p,void* data) {
    assert(p==scb.data() && data==reinterpret_cast<void*>(0x123)); forwarded(2); put(scb,layout.co_started,uint8_t{0});
}
void close_original(uint8_t,const void*) { forwarded(3); }
void reconfigure_original(void* p,void* data) { assert(p==scb.data() && data==reinterpret_cast<void*>(0x123)); forwarded(4); }
void delay_original(uint8_t,const void*,uint16_t) { forwarded(5); }
void sco_original(uint32_t status,uint8_t id,uint8_t app,const void* p) {
    assert(status==7 && id==8 && app==9 && p==other_peer); forwarded(6);
}
const Pixel17WireHookSpec* hook(size_t index) {
    size_t n;const auto* hooks=pixel17_game_host_hook_specs(&n);assert(n==7 && index<n);return hooks+index;
}
void event(size_t index) { reinterpret_cast<void (*)(void*,void*)>(hook(index)->replacement)(scb.data(),reinterpret_cast<void*>(0x123)); }
void delay(uint16_t value,uint8_t bta=0x41,const void* address=peer) {
    reinterpret_cast<void (*)(uint8_t,const void*,uint16_t)>(hook(5)->replacement)(bta,address,value);
}
void close(uint8_t bta=0x41,const void* address=peer) {
    reinterpret_cast<void (*)(uint8_t,const void*)>(hook(3)->replacement)(bta,address);
}
void sco() { reinterpret_cast<void (*)(uint32_t,uint8_t,uint8_t,const void*)>(hook(6)->replacement)(7,8,9,other_peer); }
void setup() {
    pixel17_game_host_reset_for_test(); scb.fill(0); other_scb.fill(0);avdt.fill(0);
    std::memcpy(active,peer,6);std::memcpy(encoder_peer,peer,6);std::memcpy(scb.data()+layout.peer,peer,6);
    put(scb,layout.bta_handle,uint8_t{0x41});put(scb,layout.avdt_handle,uint8_t{6});
    assert(make_configuration(scb.data()+layout.codec,45));
    uint16_t mask=0x100;std::memcpy(avdt.data()+layout.avdt_reporting_mask,&mask,2);avdt[layout.avdt_self_handle]=6;
    cid=0;acl=cid_handle=11;now=1000000000;encoder_generation=9;
    encoder_active=true;encoder_failed=false;peer_sco=false;readable=true;sco_count=0;reads=0;
    invalidations.clear();std::memset(forwards,0,sizeof(forwards));during_original=nullptr;throw_original=false;
    assert(pixel17_game_host_prepare(bindings(),layout));
    void* originals[]={reinterpret_cast<void*>(open_original),reinterpret_cast<void*>(start_original),reinterpret_cast<void*>(stop_original),
        reinterpret_cast<void*>(close_original),reinterpret_cast<void*>(reconfigure_original),reinterpret_cast<void*>(delay_original),reinterpret_cast<void*>(sco_original)};
    for(size_t i=0;i<7;++i) *hook(i)->original=originals[i];
}
void playing() { set_cid(0x43);event(0);event(1);assert(pixel17_game_host_snapshot().streaming); }
void preparation() {
    pixel17_game_host_reset_for_test();auto b=bindings();b.cid_acl=nullptr;assert(!pixel17_game_host_prepare(b,layout));
    auto bad=layout;bad.peer=0;assert(!pixel17_game_host_prepare(bindings(),bad));
    assert(pixel17_game_host_prepare(bindings(),layout));assert(!pixel17_game_host_prepare(bindings(),layout));
    assert(!pixel17_game_host_is_event_thread());
    assert(!pixel17_game_host_refresh_on_event_thread());
}
void initial_delay() {
    setup();delay(1500);auto before=pixel17_game_host_snapshot();
    assert(!before.route_valid && !before.streaming && before.started_ns==0 && before.delay_valid && before.delay_serial);
    set_cid(0x43);now+=1000000;event(0);auto opened=pixel17_game_host_snapshot();
    assert(opened.route_valid && !opened.streaming && !opened.started_ns && opened.reporting);
    assert(opened.media_generation==before.media_generation && opened.delay_serial==before.delay_serial);
    now+=1000000;event(1);auto started=pixel17_game_host_snapshot();
    assert(started.streaming && started.started_ns==now && started.bta_handle==0x41 && started.avdt_handle==6);
    assert(started.media_cid==0x43 && started.acl_handle==11 && started.encoder_generation==9);
    now+=1000000;event(1);assert(pixel17_game_host_snapshot().started_ns==started.started_ns);
}
void fresh_delay() {
    setup();playing();delay(1500);auto a=pixel17_game_host_snapshot();now+=5;delay(1500);auto b=pixel17_game_host_snapshot();
    assert(b.delay_serial>a.delay_serial && b.delay_received_ns>a.delay_received_ns && b.started_ns==a.started_ns);
    delay(1500,0x41,other_peer);assert(pixel17_game_host_snapshot().delay_serial==b.delay_serial);
    delay(0xffff);assert(!pixel17_game_host_snapshot().delay_valid);
    delay(0);assert(!pixel17_game_host_snapshot().delay_valid);
    uint16_t no_report=0;std::memcpy(avdt.data()+layout.avdt_reporting_mask,&no_report,2);delay(1500);
    assert(!pixel17_game_host_snapshot().reporting && !pixel17_game_host_snapshot().delay_valid);
    assert(forwards[5]==6);
}
void stop_restart() {
    setup();playing();delay(1500);const auto before=pixel17_game_host_snapshot();event(2);auto stopped=pixel17_game_host_snapshot();
    assert(!stopped.streaming && !stopped.started_ns && stopped.route_valid && stopped.media_generation==before.media_generation);
    assert(stopped.delay_serial==before.delay_serial && stopped.delay_valid && invalidations.back()==Pixel17GameInvalidation::Stop);
    now+=1000000;event(1);assert(pixel17_game_host_snapshot().started_ns==now);
}
void close_reset() {
    setup();playing();delay(1500);auto before=pixel17_game_host_snapshot();close(0x42,other_peer);
    assert(pixel17_game_host_snapshot().media_generation==before.media_generation);
    close();auto closed=pixel17_game_host_snapshot();assert(!closed.route_valid && !closed.delay_valid && !closed.started_ns);
    assert(closed.media_generation>before.media_generation);
    set_cid(0x44);event(0);auto replacement=pixel17_game_host_snapshot();
    assert(replacement.media_generation>closed.media_generation && replacement.media_cid==0x44 && !replacement.delay_valid);
    delay(1500);assert(pixel17_game_host_snapshot().delay_valid);
}
void late_and_reentry() {
    setup();playing();delay(1500);const auto before=pixel17_game_host_snapshot();
    during_original=[] {close();set_cid(0x44);event(0);};delay(900);
    const auto after=pixel17_game_host_snapshot();assert(after.media_generation>before.media_generation && !after.delay_valid);
    during_original=[] {pixel17_game_host_disable();};event(1);
    assert(!pixel17_game_host_snapshot().route_valid);
}
void failures_and_forwarding() {
    setup();playing();throw_original=true;
    try {event(4);assert(false);} catch(const std::runtime_error&) {}
    assert(!pixel17_game_host_snapshot().route_valid && forwards[4]==1);
    throw_original=false;set_cid(0x44);event(0);event(1);
    cid_handle=12;assert(!pixel17_game_host_refresh_on_event_thread());assert(!pixel17_game_host_snapshot().route_valid);
    cid_handle=11;assert(pixel17_game_host_refresh_on_event_thread());assert(!pixel17_game_host_snapshot().started_ns);
    event(1);avdt[layout.avdt_self_handle]=7;assert(!pixel17_game_host_refresh_on_event_thread());
    assert(!pixel17_game_host_snapshot().route_valid);
}
void sco_and_encoder() {
    setup();playing();delay(1500);sco_count=1;sco();auto blocked=pixel17_game_host_snapshot();
    assert(blocked.native_sco && !blocked.started_ns && !blocked.streaming);
    sco_count=0;sco();assert(!pixel17_game_host_snapshot().native_sco && !pixel17_game_host_snapshot().started_ns);
    event(1);++encoder_generation;assert(pixel17_game_host_refresh_on_event_thread());
    assert(pixel17_game_host_snapshot().encoder_generation==10 && invalidations.back()==Pixel17GameInvalidation::EncoderChanged);
    encoder_failed=true;assert(pixel17_game_host_refresh_on_event_thread());assert(!pixel17_game_host_snapshot().encoder_ready);
    assert(!pixel17_game_host_snapshot().route_valid);
}
void thread_and_snapshot() {
    setup();playing();auto before=reads;assert(pixel17_game_host_is_event_thread());
    std::thread reader([] {assert(!pixel17_game_host_is_event_thread());for(int i=0;i<1000;++i) assert(pixel17_game_host_snapshot().media_cid==0x43);});reader.join();
    assert(reads==before);
    std::thread wrong([] {assert(!pixel17_game_host_refresh_on_event_thread());});wrong.join();
    assert(reads==before && !pixel17_game_host_snapshot().route_valid && invalidations.back()==Pixel17GameInvalidation::WrongThread);
    event(1);assert(pixel17_game_host_snapshot().streaming);
    readable=false;assert(!pixel17_game_host_refresh_on_event_thread());assert(!pixel17_game_host_snapshot().route_valid);
}
void real_contract(const char* path) {
    std::ifstream input(path,std::ios::binary);assert(input);
    std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(input)),{});SymbolImage image;
    assert(image.open(bytes.data(),bytes.size()));Pixel17GameLayout found;
    assert(pixel17_game_host_contract(image,&found));
    assert(found.media_cid==layout.media_cid && found.bta_handle==layout.bta_handle && found.avdt_handle==layout.avdt_handle &&
        found.co_started==layout.co_started && found.peer==layout.peer && found.codec==layout.codec &&
        found.avdt_reporting_mask==layout.avdt_reporting_mask && found.avdt_self_handle==layout.avdt_self_handle);
    // Mutate actual role-bearing instructions, keeping symbols and other code valid.
    const std::pair<const char*,uint32_t> mutations[]={
        {kPixel17GameOpen,0x790d5260},{kPixel17GameOpen,0x395ad260},{kPixel17GameStart,0x9117d262},
        {kPixel17GameStop,0x395b1a68},{kPixel17GameDelay,0x14000000},
        {"_Z15bta_av_delay_coP11tBTA_AV_SCBP12tBTA_AV_DATA",0x395ac260},
        {"_Z26avdt_scb_hdl_delay_rpt_cmdP8AvdtpScbP13tAVDT_SCB_EVT",0x79428668}};
    size_t tested=0;
    for(const auto& mutation:mutations) {
        if(mutation.second==0x14000000) continue; // Named wrapper has no field-reading role.
        ImageSymbol symbol;assert(image.resolve(mutation.first,true,&symbol));const auto* code=image.bytes(symbol.address,symbol.size,5);assert(code);
        bool changed=false;
        for(size_t p=0;p+4<=symbol.size;p+=4) {
            uint32_t word;std::memcpy(&word,code+p,4);if(word!=mutation.second) continue;
            auto altered=bytes;const auto offset=static_cast<size_t>(code-bytes.data())+p;altered[offset]^=0x20;
            SymbolImage bad;assert(bad.open(altered.data(),altered.size()));Pixel17GameLayout rejected;
            assert(!pixel17_game_host_contract(bad,&rejected));++tested;changed=true;break;
        }
        assert(changed);
    }
    assert(tested==6);std::cout<<"real ELF contract + "<<tested<<" role mutations passed\n";
}
}
int main(int argc,char** argv) {
    preparation();initial_delay();fresh_delay();stop_restart();close_reset();late_and_reentry();
    failures_and_forwarding();sco_and_encoder();thread_and_snapshot();
    if(argc==2) real_contract(argv[1]);
    pixel17_game_host_reset_for_test();std::cout<<"nine game-host groups passed\n";
}
