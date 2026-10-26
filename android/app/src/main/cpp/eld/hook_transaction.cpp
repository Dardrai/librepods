// SPDX-License-Identifier: GPL-3.0-or-later
#include "hook_transaction.h"
namespace eld {
bool hook_transaction(const ResolvedHook* hooks,size_t count,RuntimeHook install,RuntimeUnhook remove) {
    if(!hooks || !count || count>24 || !install || !remove) return false;
    for(size_t i=0;i<count;++i) {
        const auto& h=hooks[i];
        if(!h.target || !h.replacement || !h.original || *h.original) return false;
        for(size_t j=0;j<i;++j) if(hooks[j].target==h.target || hooks[j].original==h.original) return false;
    }
    for(size_t i=0;i<count;++i) {
        const auto& h=hooks[i];
        if(!install(h.target,h.replacement,h.original) && *h.original && *h.original!=h.target) continue;
        if(!remove(h.target)) *h.original=nullptr;
        while(i) { const auto& previous=hooks[--i]; if(!remove(previous.target)) *previous.original=nullptr; }
        return false;
    }
    return true;
}
}
