// SPDX-License-Identifier: GPL-3.0-or-later
#include "pixel17_vtable_contract.h"
#include "pixel17_contract.h"
#include <array>
#include <cassert>
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>
#include <limits>
#include <vector>

namespace {
template<class T> T value(const uint8_t* p) { T out; std::memcpy(&out,p,sizeof(out)); return out; }
void check_image(const char* path) {
    std::ifstream input(path,std::ios::binary);
    std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(input)),{});
    eld::SymbolImage image;
    assert(image.open(bytes.data(),bytes.size()));
    size_t context_count=0;
    const auto* contexts=eld::pixel17_vtable_context_names(&context_count);
    assert(contexts && context_count==16);
    for(size_t i=0;i<context_count;++i) {
        eld::ImageSymbol context;
        assert(image.resolve(contexts[i],true,&context));
        assert(image.bytes(context.address,context.size,5));
        for(size_t j=0;j<i;++j) assert(std::strcmp(contexts[i],contexts[j]));
    }
    uint64_t table;
    assert(eld::pixel17_vtable_address_point(image,&table));
    const uint8_t* file=image.bytes(table-16,13*8,4);
    assert(file);
    const size_t file_offset=static_cast<size_t>(file-bytes.data());
    constexpr uintptr_t base=uintptr_t{0x7000000000};
    std::array<uintptr_t,11> words{};
    for(size_t i=2;i<words.size();++i) words[i]=base+value<uint64_t>(file+i*8);
    assert(eld::pixel17_vtable_contract(image,base,base+table,words.data(),words.size()));
    assert(!eld::pixel17_vtable_contract(image,base,base+table+8,words.data(),words.size()));
    assert(!eld::pixel17_vtable_contract(image,base+1,base+table,words.data(),words.size()));
    assert(!eld::pixel17_vtable_contract(image,base,base+table,words.data(),10));
    assert(!eld::pixel17_vtable_contract(image,base,base+table,words.data(),12));
    for(size_t i=0;i<words.size();++i) {
        const auto original=words[i]; words[i]=i<2?1:0;
        assert(!eld::pixel17_vtable_contract(image,base,base+table,words.data(),words.size()));
        words[i]=original;
    }
    auto changed=[&](size_t offset,uint32_t replacement) {
        auto mutated=bytes;
        std::memcpy(mutated.data()+offset,&replacement,4);
        eld::SymbolImage altered;
        assert(altered.open(mutated.data(),mutated.size()));
        assert(!eld::pixel17_vtable_contract(altered,base,base+table,words.data(),words.size()));
    };
    changed(file_offset+11*8,1); // A tenth function entry cannot be silently omitted from the clone.
    changed(file_offset+12*8,1); // Changed next-table RTTI/layout requires review.
    // Keep runtime/file target values consistent while corrupting the roles of named slots.
    for(size_t slot : {size_t{0},size_t{1},size_t{3},size_t{4},size_t{7}}) {
        const auto target=value<uint64_t>(file+(slot+2)*8);
        eld::ImageSymbol symbol;
        assert(image.resolve_address(target,true,&symbol));
        const uint8_t* code=image.bytes(target,symbol.size,5); assert(code);
        changed(static_cast<size_t>(code-bytes.data())+4,0); // leaf body / destructor forwarding contract.
    }
    struct Consumer { const char* name; uint32_t preceding; };
    const Consumer consumers[]={
        {"_ZN10A2dpCodecs14setCodecConfigEPKhbPhb",0xf9401508},
        {"_ZN10A2dpCodecs28setPeerSinkCodecCapabilitiesEPKh",0xf9401908},
        {"_ZN15A2dpCodecConfig18setCodecUserConfigERK24btav_a2dp_codec_config_tS2_PK30tA2DP_ENCODER_INIT_PEER_PARAMSPKhbPhPbS9_S9_",0xad0187e0},
        {"_ZN10A2dpCodecsD1Ev",0xf9400508},
        {"_ZN10A2dpCodecs16debug_codec_dumpEi",0xf9402108},
        {"_ZL38btif_a2dp_source_start_session_delayedRK10RawAddressNSt3__17promiseIvEE",0xf9400908},
        {"_Z15bta_av_start_okP11tBTA_AV_SCBP12tBTA_AV_DATA",0xf9400d08}};
    size_t mutations=0;
    for(const auto& consumer:consumers) {
        eld::ImageSymbol symbol;
        assert(image.resolve(consumer.name,true,&symbol));
        const uint8_t* code=image.bytes(symbol.address,symbol.size,5); assert(code);
        // Every connected virtual BLR must retain its direct dispatch. Inserting a CFI/type
        // trap or changing the call register is rejected with the retained metadata unchanged.
        for(size_t at=4;at<symbol.size;at+=4) if(value<uint32_t>(code+at)==0xd63f0100 &&
            value<uint32_t>(code+at-4)==consumer.preceding) {
            changed(static_cast<size_t>(code-bytes.data())+at,0xd4200000); ++mutations;
        }
    }
    assert(mutations>=7);
    std::cout<<"Shipped Pixel nine-slot/consumer/CFI contract and "<<mutations<<" dispatch mutations passed\n";
}
}

int main(int argc,char** argv) {
    assert(eld::pixel17_vtable_context_names(nullptr));
    eld::SymbolImage empty;
    uint64_t out=123;
    assert(!eld::pixel17_vtable_address_point(empty,&out) && out==0);
    assert(!eld::pixel17_vtable_address_point(empty,nullptr));
    std::array<uintptr_t,11> words{};
    assert(!eld::pixel17_vtable_contract(empty,0,0,words.data(),words.size()));
    assert(!eld::pixel17_vtable_contract(empty,1,2,words.data(),words.size()));
    assert(!eld::pixel17_vtable_contract(empty,1,32,nullptr,words.size()));
    assert(!eld::pixel17_vtable_contract(empty,std::numeric_limits<uintptr_t>::max(),16,words.data(),words.size()));
    if(argc==2) check_image(argv[1]);
    std::cout<<"Pixel vtable admission checks passed\n";
}
