// SPDX-License-Identifier: GPL-3.0-or-later
#include "runtime.h"
#include "note8_adapter.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <vector>

static unsigned checks;
#define CHECK(x) do { ++checks; if(!(x)) { std::fprintf(stderr,"FAIL line%d\n",__LINE__); std::exit(1); } } while(0)
static bool enabled=false,quarantined=false,fail_during_enable=false;
namespace eld {
bool note8_set_enabled(bool value) {
    if(value) {
        CHECK(runtime_ready()); // Identity must be visible before native admission.
        if(fail_during_enable) { fail_during_enable=false; runtime_test_encoder_failed(); }
    }
    enabled=value && !quarantined; return !value || !quarantined;
}
void note8_quarantine() { quarantined=true; enabled=false; }
}
static std::vector<uintptr_t> hooked,unhooked;
static int fail_at=-1; static uintptr_t unhook_failure=0;
static int hook(void* address,void*,void** backup) {
    auto value=reinterpret_cast<uintptr_t>(address); hooked.push_back(value);
    *backup=reinterpret_cast<void*>(value+0x1000);
    return static_cast<int>(hooked.size())-1==fail_at?1:0;
}
static int unhook(void* address) {
    auto value=reinterpret_cast<uintptr_t>(address); unhooked.push_back(value);
    return value==unhook_failure?1:0;
}
static void reset(bool pin=true) {
    eld::runtime_test_reset(); enabled=false; quarantined=false; fail_during_enable=false;
    if(pin) eld::runtime_test_lifetime_pinned();
    hooked.clear(); unhooked.clear(); fail_at=-1; unhook_failure=0;
}
int main() {
    using namespace eld;
    uint8_t file[128],active[128];
    for(unsigned i=0;i<128;i++) file[i]=static_cast<uint8_t>(i+1);
    std::memcpy(active,file,sizeof(file));
    RuntimeTestSegment segment={0,0,128,128,5}; uintptr_t base=reinterpret_cast<uintptr_t>(active);
    CHECK(runtime_test_context(file,128,base,&segment,1,16,64,file+16));
    active[70]^=1; CHECK(!runtime_test_context(file,128,base,&segment,1,16,64,file+16)); active[70]^=1;
    uint8_t wrong[32]={}; CHECK(!runtime_test_context(file,128,base,&segment,1,16,64,wrong));
    segment.flags=7; CHECK(!runtime_test_context(file,128,base,&segment,1,16,64)); segment.flags=5;
    CHECK(!runtime_test_context(file,128,base,&segment,1,120,16));
    CHECK(!runtime_test_context(file,128,base,&segment,1,std::numeric_limits<uintptr_t>::max()-1,8));
    CHECK(!runtime_test_context(file,128,std::numeric_limits<uintptr_t>::max()-8,&segment,1,16,32));
    CHECK(!runtime_test_context(file,128,base,&segment,129,0,32));
    segment.offset=64; CHECK(!runtime_test_context(file,128,base,&segment,1,0,32));
    segment={16,32,64,64,5}; std::memcpy(active+16,file+32,64);
    CHECK(runtime_test_context(file,128,base,&segment,1,16,64,file+32));
    segment.memory_size=32; CHECK(!runtime_test_context(file,128,base,&segment,1,16,64));
    segment={0,0,16,128,6};
    CHECK(runtime_test_memory(&segment,1,96,8,6)); // Real writable BSS need not be file-backed.
    CHECK(!runtime_test_memory(&segment,1,124,8,6));
    segment.flags=4; CHECK(!runtime_test_memory(&segment,1,96,8,6));
    segment.flags=7; CHECK(!runtime_test_memory(&segment,1,96,8,6));
    CHECK(runtime_test_image_anchor(0x1000,0x1100,0x1000,0x100));
    CHECK(!runtime_test_image_anchor(0x1000,0x1100,0x2000,0x100));
    CHECK(!runtime_test_image_anchor(0x1000,0x1104,0x1000,0x100));
    CHECK(!runtime_test_image_anchor(0,0x100,0,0x100));
    CHECK(!runtime_test_image_anchor(std::numeric_limits<uintptr_t>::max()-8,0,
        std::numeric_limits<uintptr_t>::max()-8,16));

    Note8HookSpec specs[3]={}; void* originals[3]={};
    for(unsigned i=0;i<3;i++) specs[i]={"mock",16+i*64,32,{},reinterpret_cast<void*>(static_cast<uintptr_t>(0x9000+i)),&originals[i]};
    reset(); CHECK(runtime_test_install(specs,3,0x1000,hook,unhook));
    CHECK(hooked.size()==3 && unhooked.empty());
    CHECK(!runtime_test_install(specs,3,0x1000,hook,unhook) && hooked.size()==3);
    std::memset(originals,0,sizeof(originals)); reset(); fail_at=2;
    CHECK(!runtime_test_install(specs,3,0x1000,hook,unhook));
    CHECK(unhooked==std::vector<uintptr_t>({0x1090,0x1050,0x1010}));
    CHECK(!originals[0] && !originals[1] && !originals[2]);
    reset(); fail_at=2; unhook_failure=0x1050;
    CHECK(!runtime_test_install(specs,3,0x1000,hook,unhook));
    CHECK(!originals[0] && originals[1] && !originals[2]); // Keep failed rollback's forwarding trampoline.
    std::memset(originals,0,sizeof(originals)); reset(); specs[1].elf_va=specs[0].elf_va;
    CHECK(!runtime_test_install(specs,3,0x1000,hook,unhook) && hooked.empty()); specs[1].elf_va=80;
    Note8HookSpec all[21]={}; void* all_originals[21]={};
    for(unsigned i=0;i<21;i++) all[i]={"mock",16+i*64,32,{},reinterpret_cast<void*>(static_cast<uintptr_t>(0x9000+i)),&all_originals[i]};
    reset(); fail_at=20; CHECK(!runtime_test_install(all,21,0x1000,hook,unhook));
    CHECK(unhooked.size()==21 && unhooked.front()==0x1510 && unhooked.back()==0x1010);
    for(void* original:all_originals) CHECK(!original);

    reset(); runtime_init(hook,unhook); runtime_set_enabled(true); runtime_test_native_complete();
    CHECK(!runtime_ready() && !enabled && runtime_native_codec_index()==-1);
    runtime_java_ready(true); CHECK(runtime_ready() && enabled && runtime_native_codec_index()==5);
    runtime_set_enabled(false); CHECK(runtime_ready() && !enabled);
    CHECK(runtime_native_codec_index()==5); // Published native identities stay stable after disable.
    runtime_set_enabled(true); CHECK(runtime_ready() && !enabled);
    reset(); runtime_init(hook,unhook); runtime_set_enabled(false); runtime_java_ready(true); runtime_test_native_complete();
    runtime_set_enabled(true); CHECK(!runtime_ready() && !enabled && runtime_native_codec_index()==-1);
    reset(); runtime_init(hook,unhook); runtime_set_enabled(true); runtime_java_ready(true); runtime_test_late(); runtime_test_native_complete();
    CHECK(!runtime_ready() && !enabled);
    reset(); runtime_init(hook,unhook); runtime_set_enabled(true); runtime_java_ready(false); runtime_test_native_complete();
    runtime_java_ready(true); CHECK(runtime_ready() && enabled);
    runtime_java_ready(false); runtime_java_ready(true); CHECK(runtime_ready() && !enabled);
    reset(); runtime_init(hook,unhook); runtime_set_enabled(true); runtime_java_ready(true); runtime_test_native_complete();
    runtime_test_encoder_failed(); CHECK(runtime_ready() && quarantined && !enabled);
    CHECK(runtime_native_codec_index()==5); // Quarantine blocks use without reinterpreting persisted IDs.
    runtime_set_enabled(true); CHECK(runtime_ready() && !enabled);
    reset(); runtime_init(hook,unhook); runtime_set_enabled(true); runtime_java_ready(true); fail_during_enable=true;
    runtime_test_native_complete(); CHECK(runtime_ready() && quarantined && !enabled);
    reset(); runtime_init(nullptr,unhook); runtime_set_enabled(true); runtime_java_ready(true); runtime_test_native_complete();
    CHECK(!runtime_ready() && !enabled);
    reset(false); runtime_init(hook,unhook); runtime_set_enabled(true); runtime_java_ready(true); runtime_test_native_complete();
    CHECK(!runtime_ready() && !enabled && runtime_native_codec_index()==-1); // No real lifetime pin.
    runtime_test_lifetime_pinned(); CHECK(!runtime_ready() && !enabled);
    runtime_java_ready(true); CHECK(runtime_ready() && enabled);
    std::printf("PASS %u runtime bounds/context/rollback/state assertions; no hooks or libraries loaded\n",checks);
}
