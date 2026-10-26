// SPDX-License-Identifier: GPL-3.0-or-later
#include "hook_transaction.h"
#include <array>
#include <cassert>
#include <cstdint>
#include <iostream>
#include <vector>
namespace {
std::vector<void*> applied,removed;
int fail=-1,remove_fail=-1,broken=-1;
int install(void* target,void*,void** original) {
    const int index=static_cast<int>(applied.size()); applied.push_back(target);
    *original=index==broken?target:reinterpret_cast<void*>(reinterpret_cast<uintptr_t>(target)+0x1000);
    return index==fail?1:0;
}
int remove(void* target) {
    removed.push_back(target); return target==reinterpret_cast<void*>(static_cast<uintptr_t>(0x100+remove_fail*16))?1:0;
}
void reset() { applied.clear(); removed.clear(); fail=remove_fail=broken=-1; }
}
int main() {
    std::array<void*,14> originals{};
    std::array<eld::ResolvedHook,14> hooks{};
    for(size_t i=0;i<hooks.size();++i) hooks[i]={reinterpret_cast<void*>(0x100+i*16),reinterpret_cast<void*>(0x800+i*16),&originals[i]};
    assert(!eld::hook_transaction(nullptr,14,install,remove));
    assert(!eld::hook_transaction(hooks.data(),25,install,remove));
    for(int mode=0;mode<2;++mode) for(int n=0;n<14;++n) {
        reset(); originals.fill(nullptr); if(mode) broken=n; else fail=n;
        assert(!eld::hook_transaction(hooks.data(),14,install,remove));
        assert(applied.size()==static_cast<size_t>(n+1) && removed.size()==applied.size());
        for(int j=0;j<=n;++j) assert(removed[j]==hooks[n-j].target);
        for(auto original:originals) assert(!original);
    }
    reset(); originals.fill(nullptr); fail=13; remove_fail=4;
    assert(!eld::hook_transaction(hooks.data(),14,install,remove));
    for(size_t i=0;i<14;++i) assert(bool(originals[i])==(i==4));
    reset(); originals.fill(nullptr); hooks[1].target=hooks[0].target;
    assert(!eld::hook_transaction(hooks.data(),14,install,remove) && applied.empty());
    hooks[1].target=reinterpret_cast<void*>(0x110);
    hooks[1].original=hooks[0].original;
    assert(!eld::hook_transaction(hooks.data(),14,install,remove) && applied.empty());
    hooks[1].original=&originals[1];
    assert(eld::hook_transaction(hooks.data(),14,install,remove));
    assert(!eld::hook_transaction(hooks.data(),14,install,remove) && applied.size()==14);
    std::cout<<"Hook preflight, 28 partial-install rollbacks, failed removal and repeat rejection passed\n";
}
