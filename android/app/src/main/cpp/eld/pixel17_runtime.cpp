// SPDX-License-Identifier: GPL-3.0-or-later
#include "pixel17_runtime.h"
#include "pixel17_codec.h"
#include "pixel17_contract.h"
#include "pixel17_registry.h"
#include "pixel17_wire_hooks.h"
#include "pixel17_cohort.h"
#include "pixel17_vtable_contract.h"
#include "pixel17_wire_contract.h"
#include "pixel17_hci.h"
#include "pixel17_game_host.h"
#include "pixel17_game_runtime.h"
#include "encoder_interface.h"
#include "hook_transaction.h"
#include <atomic>
#include <cstring>
#include <new>
#if defined(__ANDROID__) && defined(__aarch64__) && defined(ELD_PRIVATE_OWNED_FDK)
#include "owned_fdk_encoder.h"
#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <link.h>
#include <stdio.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <memory>
#include <time.h>
#endif

namespace eld {
namespace {
std::atomic<bool> prepared{false}, enabled{false}, poisoned{false}, published{false};
std::atomic<bool> initial_slot_free{false};
#if defined(__ANDROID__) && defined(__aarch64__) && defined(ELD_PRIVATE_OWNED_FDK)
constexpr bool kPixel17CatchUpExperiment = true;
void log(const char* text) { __android_log_print(ANDROID_LOG_INFO,"LibrePodsELD","Pixel17: %s",text); }
struct Loaded {
    uintptr_t base=0;
    const Elf64_Phdr* phdr=nullptr;
    size_t count=0;
    const char* path=nullptr;
    unsigned matches=0;
};
const char* basename(const char* p) { const auto* slash=p?std::strrchr(p,'/'):nullptr; return slash?slash+1:(p?p:""); }
int find(dl_phdr_info* info,size_t,void* opaque) {
    if(std::strcmp(basename(info->dlpi_name),"libbluetooth_jni.so")) return 0;
    auto& found=*static_cast<Loaded*>(opaque);
    if(++found.matches==1) { found.base=info->dlpi_addr; found.phdr=info->dlpi_phdr;
        found.count=info->dlpi_phnum; found.path=info->dlpi_name; }
    return 0;
}
bool permissions(uintptr_t address,size_t bytes,unsigned flags,bool exact=false) {
    // Android heap pointers retain a TBI tag; /proc maps contain virtual addresses.
    // Normalize this numeric lookup only. Every access and deallocation keeps its original tag.
    address&=UINT64_C(0x00ffffffffffffff);
    if(!address || !bytes || bytes>UINTPTR_MAX-address) return false;
    FILE* file=fopen("/proc/self/maps","r"); if(!file) return false;
    char line[1024],mode[5]; unsigned long long begin,end; bool ok=false;
    while(fgets(line,sizeof(line),file)) if(sscanf(line,"%llx-%llx %4s",&begin,&end,mode)==3 &&
        address>=begin && address+bytes<=end) {
        ok=mode[0]=='r' && (!(flags&2) || mode[1]=='w') && (!(flags&1) || mode[2]=='x') &&
            (!exact || ((mode[1]=='w')==bool(flags&2) && (mode[2]=='x')==bool(flags&1)));
        break;
    }
    fclose(file); return ok;
}
bool readable(const void* p,size_t bytes) {
    const auto address=reinterpret_cast<uintptr_t>(p);
    const bool valid=permissions(address,bytes,4);
    static std::atomic<bool> reported_tag{false};
    if((address>>56) && !reported_tag.exchange(true)) __android_log_print(ANDROID_LOG_INFO,"LibrePodsELD",
        "Pixel17: tagged host range tag=%u bytes=%zu mapped=%d (pointer retained)",
        static_cast<unsigned>(address>>56),bytes,valid);
    return valid;
}
struct Image {
    Loaded loaded{};
    const uint8_t* data=nullptr;
    size_t size=0;
    SymbolImage symbols;
    ~Image() { if(data) munmap(const_cast<uint8_t*>(data),size); }
    bool open(void* module) {
        dl_iterate_phdr(find,&loaded);
        if(loaded.matches!=1 || !loaded.base || !loaded.path || loaded.path[0]!='/' ||
           !loaded.phdr || !loaded.count || loaded.count>128) return false;
        int fd=::open(loaded.path,O_RDONLY|O_CLOEXEC); if(fd<0) return false;
        struct stat status{};
        if(fstat(fd,&status) || !S_ISREG(status.st_mode) || status.st_size<64 || status.st_size>128*1024*1024) {
            close(fd); return false;
        }
        size=static_cast<size_t>(status.st_size);
        void* mapping=mmap(nullptr,size,PROT_READ,MAP_PRIVATE,fd,0); close(fd);
        if(mapping==MAP_FAILED) return false;
        data=static_cast<const uint8_t*>(mapping);
        if(!symbols.open(data,size)) return false;
        const auto* header=reinterpret_cast<const Elf64_Ehdr*>(data);
        if(header->e_phnum!=loaded.count || header->e_phoff>size ||
           loaded.count*sizeof(Elf64_Phdr)>size-header->e_phoff ||
           std::memcmp(data+header->e_phoff,loaded.phdr,loaded.count*sizeof(Elf64_Phdr))) return false;
        const void* anchor=dlsym(module,"JNI_OnLoad"); Dl_info origin{}; ImageSymbol symbol;
        return anchor && dladdr(anchor,&origin) && reinterpret_cast<uintptr_t>(origin.dli_fbase)==loaded.base &&
            symbols.resolve("JNI_OnLoad",true,&symbol) && reinterpret_cast<uintptr_t>(anchor)-loaded.base==symbol.address;
    }
    void* function(const char* name,ImageSymbol* found=nullptr) const {
        ImageSymbol symbol;
        if(!symbols.resolve(name,true,&symbol)) return nullptr;
        auto* target=function(symbol);
        if(target && found) *found=symbol;
        return target;
    }
    void* function(const ImageSymbol& symbol) const {
        if(symbol.size<4 || symbol.size>65536 ||
           symbol.address>UINTPTR_MAX-loaded.base || symbol.size>UINTPTR_MAX-loaded.base-symbol.address) return nullptr;
        const auto* bytes=symbols.bytes(symbol.address,symbol.size,5);
        auto* target=reinterpret_cast<void*>(loaded.base+symbol.address);
        if(!bytes || !permissions(reinterpret_cast<uintptr_t>(target),symbol.size,5,true) ||
           std::memcmp(target,bytes,symbol.size)) return nullptr;
        return target;
    }
    void* writable(uint64_t address,size_t bytes) const {
        if(!address || !bytes || address>UINTPTR_MAX-loaded.base ||
           bytes>UINTPTR_MAX-loaded.base-address) return nullptr;
        bool mapped=false;
        for(size_t i=0;i<loaded.count;++i) {
            const auto& p=loaded.phdr[i];
            if(p.p_type==PT_LOAD && (p.p_flags&(PF_R|PF_W|PF_X))==(PF_R|PF_W) &&
               address>=p.p_vaddr && address-p.p_vaddr<=p.p_memsz && bytes<=p.p_memsz-(address-p.p_vaddr))
                mapped=true;
        }
        auto* target=reinterpret_cast<void*>(loaded.base+address);
        return mapped && permissions(reinterpret_cast<uintptr_t>(target),bytes,6,true) ? target : nullptr;
    }
    bool vtable(const uintptr_t* table) const {
        auto va=reinterpret_cast<uintptr_t>(table);
        if(va<loaded.base+2*sizeof(uintptr_t) || !permissions(va-16,11*sizeof(uintptr_t),4,true) ||
           !symbols.bytes(va-loaded.base-16,11*sizeof(uintptr_t),4)) return false;
        for(size_t i=0;i<9;++i) {
            if(table[i]<loaded.base) return false;
            ImageSymbol symbol;
            if(!symbols.resolve_address(table[i]-loaded.base,true,&symbol) || symbol.size>65536 ||
               !permissions(table[i],symbol.size,5,true)) return false;
            const auto* bytes=symbols.bytes(symbol.address,symbol.size,5);
            if(!bytes || std::memcmp(reinterpret_cast<const void*>(table[i]),bytes,symbol.size)) return false;
        }
        return true;
    }
};
using Allocate=void* (*)(size_t,const std::nothrow_t&);
Allocate native_allocate=nullptr;
void (*native_release)(void*,size_t)=nullptr;
void* (*packet_allocate)(size_t)=nullptr;
void (*packet_release)(void*)=nullptr;
void (*construct)(void*,int32_t)=nullptr;
bool (*provider)(int32_t)=nullptr;
struct Peer { uint8_t bytes[6]; };
Peer (*active_peer)(void*)=nullptr;
void (*on_registered)()=nullptr;
std::unique_ptr<Pixel17Cohort> cohort;
Pixel17RegistryBindings registries{};
void* allocate(size_t size) { return native_allocate(size,std::nothrow); }
void release(void* p,size_t size) { native_release(p,size); }
bool admissible() {
    return prepared.load(std::memory_order_acquire) && enabled.load() && initial_slot_free.load() && !poisoned.load() &&
        encoder_ready() && pixel17_wire_ready() && provider && !provider(5);
}
bool wire_active() { return prepared.load(std::memory_order_acquire); }
bool quarantined() { return poisoned.load() || !enabled.load(); }
const EncoderInterface* selected_encoder() {
    // Provider policy is checked on selection, never on the 100 Hz packet/header path.
    if(quarantined() || !provider || provider(5)) {
        pixel17_runtime_quarantine("encoder selection or provider admission"); return nullptr;
    }
    return encoder_interface();
}
bool actual_peer(uint8_t* out,size_t size) {
    if(!out || size!=6 || !active_peer) return false;
    const Peer peer=active_peer(nullptr); // Complete leaf contract proves the receiver is unused.
    uint8_t any=0; for(auto v:peer.bytes) any|=v;
    if(!any) return false;
    std::memcpy(out,peer.bytes,6); return true;
}
uint64_t boottime_ns() noexcept {
    timespec now{};
    if(clock_gettime(CLOCK_BOOTTIME,&now) || now.tv_sec<0 || now.tv_nsec<0 || now.tv_nsec>=1000000000 ||
       uint64_t(now.tv_sec)>(UINT64_MAX-uint64_t(now.tv_nsec))/1000000000) return 0;
    return uint64_t(now.tv_sec)*1000000000+uint64_t(now.tv_nsec);
}
uint64_t boottime_us() { return boottime_ns()/1000; }
uint64_t game_now(void*) { return boottime_us(); }
bool game_copy(const void* source,size_t bytes,void* out) noexcept {
    if(!out || !readable(source,bytes)) return false;
    std::memcpy(out,source,bytes); return true;
}
bool game_peer(uint8_t* out,size_t size) noexcept { return actual_peer(out,size); }
bool game_encoder(Pixel17GameEncoder* out) noexcept {
    if(!out) return false;
    const auto status=encoder_status();
    out->generation=status.generation; out->active=status.active; out->failed=status.failed;
    std::memcpy(out->peer,status.peer,6); return true;
}
bool prepare_game(const Image& image,RuntimeHook install,RuntimeUnhook remove) {
    Pixel17HciSymbols symbols; Pixel17GameLayout layout;
    if(!pixel17_hci_contract(image.symbols,&symbols) || !pixel17_game_host_contract(image.symbols,&layout)) return false;
    const ImageSymbol complete_contexts[]={symbols.construct,symbols.post,symbols.destroy_callback,
        symbols.main_wrapper,symbols.transmit,symbols.complete_adapter,symbols.view_byte,
        symbols.view_payload,symbols.view_size};
    for(const auto& context:complete_contexts) if(!image.function(context)) return false;
    size_t context_count=0;
    const auto* contexts=pixel17_game_host_context_names(&context_count);
    if(!contexts || !context_count || context_count>32) return false;
    for(size_t i=0;i<context_count;++i) if(!image.function(contexts[i])) return false;
    auto* main_thread=image.writable(symbols.main_thread,32);
    if(!main_thread) return false;
    Pixel17GameBindings host;
    host.copy=game_copy;
    host.scb=reinterpret_cast<decltype(host.scb)>(image.function(kPixel17GameScb));
    host.avdt_scb=reinterpret_cast<decltype(host.avdt_scb)>(image.function(kPixel17GameAvdtScb));
    host.media_cid=reinterpret_cast<decltype(host.media_cid)>(image.function(kPixel17GameCid));
    host.cid_acl=reinterpret_cast<decltype(host.cid_acl)>(image.function(kPixel17GameCidAcl));
    host.peer_acl=reinterpret_cast<decltype(host.peer_acl)>(image.function(kPixel17GamePeerAcl));
    host.active_peer=game_peer;
    host.sco_count=reinterpret_cast<decltype(host.sco_count)>(image.function(kPixel17GameScoCount));
    host.peer_sco=reinterpret_cast<decltype(host.peer_sco)>(image.function(kPixel17GamePeerSco));
    host.encoder=game_encoder; host.now_ns=boottime_ns; host.invalidated=pixel17_game_invalidated;
    Pixel17MainBindings main;
    main.main_thread=main_thread; main.now=game_now;
    main.construct=reinterpret_cast<decltype(main.construct)>(image.function(symbols.construct));
    main.post=reinterpret_cast<decltype(main.post)>(image.function(symbols.post));
    main.destroy_callback=reinterpret_cast<decltype(main.destroy_callback)>(image.function(symbols.destroy_callback));
    Pixel17HciBindings hci;
    hci.transmit=reinterpret_cast<decltype(hci.transmit)>(image.function(symbols.transmit));
    hci.allocate=packet_allocate; hci.release=packet_release; hci.now=game_now;
    hci.view_size=reinterpret_cast<decltype(hci.view_size)>(image.function(symbols.view_size));
    hci.view_byte=reinterpret_cast<decltype(hci.view_byte)>(image.function(symbols.view_byte));
    if(!pixel17_game_host_prepare(host,layout) || !pixel17_game_runtime_prepare(main,hci)) return false;
    size_t count=0; const auto* specs=pixel17_game_host_hook_specs(&count);
    if(!specs || !count || count>7) { pixel17_game_runtime_disable(); pixel17_game_host_disable(); return false; }
    ResolvedHook hooks[7]{};
    for(size_t i=0;i<count;++i) {
        auto* target=image.function(specs[i].symbol);
        if(!target) { pixel17_game_runtime_disable(); pixel17_game_host_disable(); return false; }
        hooks[i]={target,specs[i].replacement,specs[i].original};
    }
    // Game is an independent, optional hook transaction. A rejected Game boundary
    // never poisons the working ELD codec or changes its audio route.
    if(!hook_transaction(hooks,count,install,remove)) {
        pixel17_game_runtime_disable(); pixel17_game_host_disable(); return false;
    }
    return true;
}
bool peer_mtu(const void* opaque,uint16_t* out) {
    Pixel17PeerParams value{};
    if(!out || !opaque || !readable(opaque,sizeof(value)) || !pixel17_peer_params(opaque,sizeof(value),&value)) return false;
    *out=value.peer_mtu; return true;
}
void encoder_failed(uint64_t) { pixel17_runtime_quarantine("encoder callback"); }
bool admitted(void*) noexcept { return admissible(); }
void lock_registry(void*,void* registry) { registries.lock(registry); }
void unlock_registry(void*,void* registry) { registries.unlock(registry); }
Pixel17RegistryResult commit(void*,void* const* members,size_t count,int32_t index,int32_t priority,
                              Pixel17RegistryReceipt* receipts) noexcept {
    try {
        const auto result=pixel17_registry_register_many(members,count,index,priority,registries,receipts);
        __android_log_print(ANDROID_LOG_INFO,"LibrePodsELD","Pixel17: registry transaction result=%u count=%zu",
            static_cast<unsigned>(result),count);
        for(size_t i=0;i<count && i<8;++i) __android_log_print(ANDROID_LOG_INFO,"LibrePodsELD",
            "Pixel17: registry[%zu] result=%u before=%zu/%zu/%zu/%zu after=%zu/%zu/%zu/%zu",i,
            static_cast<unsigned>(receipts[i].result),receipts[i].before.active,receipts[i].before.disabled,
            receipts[i].before.source,receipts[i].before.sink,receipts[i].after.active,receipts[i].after.disabled,
            receipts[i].after.source,receipts[i].after.sink);
        if(result==Pixel17RegistryResult::Invalid) for(size_t i=0;i<count && i<8;++i) {
            Pixel17RegistryDiagnostic diagnostic{};
            pixel17_registry_diagnose(members[i],registries,&diagnostic);
            __android_log_print(ANDROID_LOG_ERROR,"LibrePodsELD",
                "Pixel17: registry[%zu] issue=%s container=%u counts=%zu/%zu/%zu/%zu visited=%zu/%zu index=%d/%d priority=%d tail_byte=%u",
                i,pixel17_registry_issue_name(diagnostic.issue),static_cast<unsigned>(diagnostic.container),
                diagnostic.counts.active,diagnostic.counts.disabled,diagnostic.counts.source,diagnostic.counts.sink,
                diagnostic.tree_entries,diagnostic.list_entries,diagnostic.map_index,diagnostic.object_index,
                diagnostic.priority,diagnostic.tail_byte);
        }
        return result;
    }
    catch(...) { return Pixel17RegistryResult::AllocationFailed; }
}
void completed(void*,const Pixel17CohortResult& result) noexcept {
    if(result.issue!=Pixel17CohortIssue::None || !result.committed || !admissible()) {
        pixel17_runtime_quarantine("registry completion admission"); return;
    }
    published.store(true,std::memory_order_release);
    log("six native registries committed; codec identity published");
    if(on_registered) on_registered();
}
// These two trampolines keep all host C++ container arguments opaque.
void* original_cache=nullptr;
void* original_init=nullptr;
void forward_cache(void* self,const void* configs,int32_t rate,void* addresses) {
    reinterpret_cast<void (*)(void*,const void*,int32_t,void*)>(original_cache)(self,configs,rate,addresses);
}
bool forward_init(void* self,int32_t rate) { return reinterpret_cast<bool (*)(void*,int32_t)>(original_init)(self,rate); }
void invalidated(void*,Pixel17CohortIssue issue,bool committed) noexcept {
    __android_log_print(ANDROID_LOG_ERROR,"LibrePodsELD","Pixel17: cohort rejected issue=%u committed=%d",
        static_cast<unsigned>(issue),committed);
    pixel17_runtime_quarantine("registry cohort invalidated");
}
void cache_init(void* self,const void* configs,int32_t rate,void* addresses) {
    cohort->cache_init(self,configs,rate,addresses);
}
bool codec_init(void* self,int32_t rate) { return cohort->registry_init(self,rate); }
template<class T> T field(const void* object,size_t offset) {
    T value; std::memcpy(&value,static_cast<const uint8_t*>(object)+offset,sizeof(value)); return value;
}
const uintptr_t* probe(const Image& image) {
    void* object=allocate(kPixel17CodecObjectSize); if(!object) return nullptr;
    try { construct(object,10001); } catch(...) { release(object,kPixel17CodecObjectSize); return nullptr; }
    const auto* table=field<const uintptr_t*>(object,0);
    // Never dispatch a destructor until its entire address point and targets are validated.
    if(!image.vtable(table) || !pixel17_vtable_contract(image.symbols,image.loaded.base,
            reinterpret_cast<uintptr_t>(table),table-2,11)) {
        log("AAC probe vtable ABI rejected; bounded probe retained until process exit"); return nullptr;
    }
    bool valid=field<int32_t>(object,0x30)==1 && field<uint64_t>(object,0x38)==2 &&
        field<uint8_t>(object,0x40)==6 && !std::memcmp(static_cast<uint8_t*>(object)+0x41,"AAC\0",4) &&
        field<int32_t>(object,0x58)==10001 && field<int32_t>(object,0x5c)==10001 &&
        field<uint8_t>(object,0x227)==1;
    for(size_t offset:{size_t(0x60),size_t(0xa0),size_t(0xe0),size_t(0x120),size_t(0x160)})
        valid=valid && field<int32_t>(object,offset)==1;
    reinterpret_cast<void (*)(void*)>(table[1])(object);
    return valid?table:nullptr;
}
void debug_codec(const void* codec,int fd) {
    if(fd<0) return;
    Pixel17NativeConfig config{};
    if(!pixel17_codec_get_config(codec,&config)) return;
    const auto status=encoder_status();
    const auto clock=encoder_clock_status(false); // A diagnostics read must not advance/cancel an adjustment.
    dprintf(fd,"\nApple AAC-ELD source: index=%d priority=%d rate_mask=%u bits_mask=%u channels_mask=%u\n"
        "  Shared encoder: generation=%llu active=%d failed=%d bitrate=%u packets=%llu underflows=%llu host_discards=%llu\n"
        "  Runtime: prepared=%d enabled=%d poisoned=%d published=%d initial_slot_free=%d\n"
        "  Pacing: catch_up=%d calls=%llu max_gap_us=%llu max_due_lag_us=%llu rebases=%llu rebased_periods=%llu\n"
        "  Source: queue_deferrals=%llu read_calls=%llu pcm_bytes=%llu catch_up_aus=%llu burst_max=%llu\n"
        "  Work timing (us): pcm_read_max=%llu pcm_read_ge_1ms=%llu encode_max=%llu enqueue_max=%llu callback_max=%llu\n"
        "  Clock: phase=%u stop_reason=%u stop_media_age_us=%llu stop_host_queue=%zu progress_frames=%lld\n",
        config.codec_type,config.codec_priority,config.sample_rate,config.bits_per_sample,config.channel_mode,
        static_cast<unsigned long long>(status.generation),status.active,status.failed,status.bitrate,
        static_cast<unsigned long long>(status.packets),static_cast<unsigned long long>(status.underflows),
        static_cast<unsigned long long>(status.host_discards),
        prepared.load(),enabled.load(),poisoned.load(),published.load(),initial_slot_free.load(),
        kPixel17CatchUpExperiment,
        static_cast<unsigned long long>(status.send_calls),static_cast<unsigned long long>(status.max_gap_us),
        static_cast<unsigned long long>(status.max_due_lag_us),static_cast<unsigned long long>(status.clock_rebases),
        static_cast<unsigned long long>(status.rebased_periods),static_cast<unsigned long long>(status.queue_deferrals),
        static_cast<unsigned long long>(status.pcm_read_calls),static_cast<unsigned long long>(status.pcm_read_bytes),
        static_cast<unsigned long long>(status.catch_up_aus),static_cast<unsigned long long>(status.burst_max),
        static_cast<unsigned long long>(status.pcm_read_max_us),static_cast<unsigned long long>(status.pcm_read_slow_calls),
        static_cast<unsigned long long>(status.encode_max_us),static_cast<unsigned long long>(status.enqueue_max_us),
        static_cast<unsigned long long>(status.callback_max_us),
        static_cast<unsigned>(clock.phase),static_cast<unsigned>(clock.stop_reason),
        static_cast<unsigned long long>(clock.stop_media_age_us),clock.stop_host_queue_length,
        static_cast<long long>(clock.signed_progress_frames));
}
struct Hook { const char* name; void* replacement; void** original; void* target=nullptr; };
bool transaction(const Image& image,Hook* hooks,size_t count,RuntimeHook install,RuntimeUnhook remove) {
    if(!count || count>24 || !install || !remove) return false;
    ResolvedHook resolved[24]{};
    for(size_t i=0;i<count;++i) {
        auto& h=hooks[i]; h.target=image.function(h.name);
        if(!h.target || !h.replacement || !h.original || *h.original) return false;
        for(size_t j=0;j<i;++j) if(hooks[j].target==h.target || hooks[j].original==h.original) return false;
        resolved[i]={h.target,h.replacement,h.original};
    }
    const bool ok=hook_transaction(resolved,count,install,remove);
    if(!ok) poisoned.store(true);
    return ok;
}
#endif
}

bool pixel17_runtime_prepare(void* module,RuntimeHook install,RuntimeUnhook remove,void (*registered)()) {
#if defined(__ANDROID__) && defined(__aarch64__) && defined(ELD_PRIVATE_OWNED_FDK)
    if(prepared.load() || poisoned.load() || !module || !registered) return false;
    try {
        Image image; Pixel17Contract contract;
        if(!image.open(module) || !pixel17_contract(image.symbols,&contract) || !pixel17_wire_contract(image.symbols)) {
            log("image/semantic ABI contract rejected"); return false;
        }
        const char* contexts[]={kPixel17Init,kPixel17CacheInit,
            "_ZN15A2dpCodecConfigC2E23btav_a2dp_codec_index_tN9bluetooth4a2dp7CodecIdERKNSt3__112basic_stringIcNS4_11char_traitsIcEENS4_9allocatorIcEEEE26btav_a2dp_codec_priority_t",
            "_ZN9bluetooth5audio4aidl4a2dp11setup_codecERKNS0_4a2dp24ahal_codec_configurationE",
            "_ZN9bluetooth5audio4aidl4a2dp5codec24getHalCodecConfigurationERKNS0_4a2dp24ahal_codec_configurationEPN4aidl7android8hardware9bluetooth5audio18CodecConfigurationE",
            "_ZN9bluetooth5audio4aidl4a2dp5codec22getHalPcmConfigurationERKNS0_4a2dp24ahal_codec_configurationEPN4aidl7android8hardware9bluetooth5audio16PcmConfigurationE",
            "_ZNK9bluetooth5audio4aidl4a2dp12ProviderInfo13SupportsCodecE23btav_a2dp_codec_index_t"};
        for(const auto* name:contexts) if(!image.function(name)) { log("software route/read instruction context rejected"); return false; }
        size_t vtable_count=0; const auto* vtable_contexts=pixel17_vtable_context_names(&vtable_count);
        if(!vtable_contexts || !vtable_count || vtable_count>32) return false;
        for(size_t i=0;i<vtable_count;++i) if(!image.function(vtable_contexts[i])) {
            log("native virtual consumer instruction context rejected"); return false;
        }
        size_t wire_count=0; const auto* wire_contexts=pixel17_wire_contract_symbols(&wire_count);
        if(!wire_contexts || !wire_count || wire_count>32) return false;
        for(size_t i=0;i<wire_count;++i) if(!image.function(wire_contexts[i])) {
            log("native wire/encoder consumer instruction context rejected"); return false;
        }
        size_t lifecycle_count=0; const auto* lifecycle_contexts=pixel17_lifecycle_context_names(&lifecycle_count);
        if(!lifecycle_contexts || !lifecycle_count || lifecycle_count>16) return false;
        for(size_t i=0;i<lifecycle_count;++i) if(!image.function(lifecycle_contexts[i])) {
            log("native registry lifecycle instruction context rejected"); return false;
        }
        native_allocate=reinterpret_cast<Allocate>(image.function(kPixel17Allocate));
        native_release=reinterpret_cast<void (*)(void*,size_t)>(image.function(kPixel17Release));
        construct=reinterpret_cast<void (*)(void*,int32_t)>(image.function(kPixel17AacCtor));
        provider=reinterpret_cast<bool (*)(int32_t)>(image.function(kPixel17Provider));
        active_peer=reinterpret_cast<Peer (*)(void*)>(image.function(kPixel17ActivePeer));
        packet_allocate=reinterpret_cast<void* (*)(size_t)>(image.function("_Z10osi_mallocm"));
        packet_release=reinterpret_cast<void (*)(void*)>(image.function("_Z8osi_freePv"));
        auto lock=reinterpret_cast<void (*)(void*)>(image.function(kPixel17Lock));
        auto unlock=reinterpret_cast<void (*)(void*)>(image.function(kPixel17Unlock));
        auto assign=reinterpret_cast<void* (*)(void*,const char*,size_t)>(image.function(kPixel17Assign));
        auto balance=reinterpret_cast<void (*)(void*,void*) noexcept>(image.function(kPixel17Balance));
        if(!native_allocate || !native_release || !construct || !provider || !active_peer ||
           !packet_allocate || !packet_release || !lock || !unlock || !assign || !balance) {
            log("complete native binding contexts rejected"); return false;
        }
        const auto* table=probe(image);
        if(!table || !pixel17_codec_prepare({allocate,release,construct,table,lock,unlock,assign,debug_codec})) {
            log("AAC host object ABI probe failed"); return false;
        }
        const auto api=eld_owned_fdk_api();
        EldFdkEncoder* test=nullptr; EldFdkConfig config{};
        if(eld_fdk_open(&api,256000,&test,&config)!=ELD_FDK_OK) { log("owned encoder preflight rejected"); return false; }
        static_assert(sizeof(EldOwnedPcm)*8 == kPixel17PcmBits);
        EldOwnedPcm silence[960]{}; uint8_t access_unit[321]{}; size_t bytes=0;
        const auto encoded=eld_owned_fdk_encode(test,silence,480,access_unit,sizeof(access_unit),&bytes);
        eld_fdk_close(&test);
        if(encoded!=ELD_FDK_OK || config.sample_rate!=48000 || config.channels!=2 ||
           config.frame_samples!=480 || config.pcm_sample_bytes!=kPixel17PcmBits/8) { log("owned PCM/ELD contract rejected"); return false; }
        EncoderHost encoder{api,packet_allocate,packet_release,encoder_failed,actual_peer,pixel17_codec_info,peer_mtu,
            kPixel17CatchUpExperiment,boottime_us,pixel17_game_clock_opportunity,kPixel17PcmBits/8};
        if(!encoder_prepare(encoder) || !pixel17_wire_prepare({wire_active,quarantined,selected_encoder,5})) return false;
        registries={allocate,release,lock,unlock,balance,readable,provider};
        // Actual trampoline addresses are populated transactionally before any Bluetooth init executes.
        // Cohort callbacks dereference the stable pointer slots, never a replacement entry point.
        on_registered=registered;
        size_t count=0; const auto* specs=pixel17_wire_hook_specs(&count);
        if(!specs || count>22) return false;
        Hook hooks[24]{};
        for(size_t i=0;i<count;++i) hooks[i]={specs[i].symbol,specs[i].replacement,specs[i].original};
        hooks[count++]={kPixel17CacheInit,reinterpret_cast<void*>(cache_init),&original_cache};
        hooks[count++]={kPixel17Init,reinterpret_cast<void*>(codec_init),&original_init};
        // Allocate the controller before arming hooks. A preparation allocation failure leaves no detours.
        // Originals are forwarded via stable trampoline slots populated by the hook transaction.
        cohort=std::make_unique<Pixel17Cohort>(contract.registry_count,Pixel17CohortBindings{
            nullptr,forward_cache,forward_init,admitted,commit,completed,invalidated,
            lock_registry,unlock_registry});
        if(!cohort->valid() || !transaction(image,hooks,count,install,remove)) {
            log("hook transaction rejected; restart required"); return false;
        }
        if(!pixel17_wire_ready()) { pixel17_runtime_quarantine("wire hooks unavailable"); return false; }
        prepared.store(true,std::memory_order_release);
        bool game=false;
        try { game=prepare_game(image,install,remove); }
        catch(...) { pixel17_game_runtime_disable(); pixel17_game_host_disable(); }
        log(game ? "optional native Game observation/HCI boundary prepared" :
            "optional native Game boundary unavailable; ELD unchanged");
        log("native hooks prepared; waiting for complete codec registry initialization");
        return true;
    } catch(...) { pixel17_runtime_quarantine("native preparation exception"); log("native preparation failed; AAC retained"); return false; }
#else
    (void)module; (void)install; (void)remove; (void)registered;
    return false;
#endif
}
void pixel17_runtime_set_enabled(bool value) {
    if(value && prepared.load() && !poisoned.load()) enabled.store(true);
    else { enabled.store(false); if(published.load()) pixel17_runtime_quarantine("startup enable revoked"); }
}
void pixel17_runtime_initial_slot_free(bool free) {
    initial_slot_free.store(free,std::memory_order_release);
    if(!free && published.load()) pixel17_runtime_quarantine("initial codec slot revoked");
}
void pixel17_runtime_quarantine(const char* reason) {
    const bool already=poisoned.exchange(true);
#if defined(__ANDROID__) && defined(__aarch64__) && defined(ELD_PRIVATE_OWNED_FDK)
    if(!already) __android_log_print(ANDROID_LOG_ERROR,"LibrePodsELD",
        "Pixel17: quarantine reason=%s",reason ? reason : "unknown");
#else
    (void)reason;
#endif
    enabled.store(false); pixel17_codec_quarantine();
    // Keep published carrier identity stable, but report one real-peer route failure so
    // Java's existing generation-aware watchdog can request ordinary AAC recovery.
    if(!already && published.load()) encoder_fail_route();
}
bool pixel17_runtime_registered() { return published.load(std::memory_order_acquire); }
}
