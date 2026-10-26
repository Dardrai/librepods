// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <stdio.h>
extern "C" {
#include "xz_private.h"
}
static constexpr uint8_t NOTE8_FDK_SHA[32]={
    0xd9,0x65,0xf1,0x6c,0xba,0x9b,0x9f,0x10,0x3d,0x65,0x78,0x44,0xea,0xe6,0x18,0xac,
    0x13,0xb9,0x15,0x58,0x1d,0xa3,0x6a,0x4d,0x77,0x20,0x99,0x52,0x29,0xe1,0x14,0xa7
};
static bool pinned_file(const char* path) {
    FILE* file=fopen(path,"rb"); if(!file) return false;
    xz_sha256 hash; xz_sha256_reset(&hash);
    uint8_t buffer[16384]; size_t count,total=0;
    while((count=fread(buffer,1,sizeof(buffer),file))!=0) {
        total+=count; if(total>13786880) { fclose(file); return false; }
        xz_sha256_update(buffer,count,&hash);
    }
    bool clean=!ferror(file); fclose(file);
    return clean && total==13786880 && xz_sha256_validate(NOTE8_FDK_SHA,&hash);
}
