// SPDX-License-Identifier: GPL-3.0-or-later
#include "pixel17_game_host.h"
#include <cstdio>
#include <cstring>

namespace eld {
bool pixel17_game_copy_batch(const void* source,const Pixel17GameRead* reads,size_t count) noexcept {
    if(!source || !reads || !count || count>6) return false;
    const auto base=reinterpret_cast<uintptr_t>(source);
    uintptr_t addresses[6]{};
    bool readable[6]{};
    for(size_t i=0;i<count;++i) {
        const auto& read=reads[i];
        if(!read.out || !read.size || read.offset>UINTPTR_MAX-base ||
            read.size>UINTPTR_MAX-base-read.offset) return false;
        // Normalize only the maps lookup. The actual copy retains the Android TBI tag.
        addresses[i]=(base+read.offset)&UINT64_C(0x00ffffffffffffff);
        if(!addresses[i] || read.size>UINTPTR_MAX-addresses[i]) return false;
    }
    FILE* file=fopen("/proc/self/maps","r");
    if(!file) return false;
    char line[1024],mode[5];
    unsigned long long begin,end;
    size_t remaining=count;
    while(remaining && fgets(line,sizeof(line),file)) {
        if(sscanf(line,"%llx-%llx %4s",&begin,&end,mode)!=3 || mode[0]!='r') continue;
        for(size_t i=0;i<count;++i) if(!readable[i] && addresses[i]>=begin &&
            addresses[i]+reads[i].size<=end) {
            readable[i]=true;
            --remaining;
        }
    }
    const bool valid=!remaining && !ferror(file);
    const int closed=fclose(file);
    if(!valid || closed) return false;
    for(size_t i=0;i<count;++i)
        std::memcpy(reads[i].out,reinterpret_cast<const void*>(base+reads[i].offset),reads[i].size);
    return true;
}
}
