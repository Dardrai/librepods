// SPDX-License-Identifier: GPL-3.0-or-later
#include "pixel17_contract.h"
#include <array>
#include <cassert>
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>
#include <vector>
int main(int argc,char** argv) {
    std::array<uint32_t,7> leaf{0xd503245f,0x90000008,0x91000108,0x79400909,0xb9400108,0xaa098100,0xd65f03c0};
    auto accepts=[&]{return eld::pixel17_active_peer_contract(reinterpret_cast<const uint8_t*>(leaf.data()),sizeof(leaf));};
    assert(accepts());
    for(size_t i=0;i<leaf.size();++i) { auto original=leaf[i]; leaf[i]=0; assert(!accepts()); leaf[i]=original; }
    assert(!eld::pixel17_active_peer_contract(nullptr,sizeof(leaf)));
    assert(!eld::pixel17_active_peer_contract(reinterpret_cast<const uint8_t*>(leaf.data()),sizeof(leaf)-4));
    // Synthetic source/sink splice: relative branch targets may move; the split remains <=7.
    std::array<uint32_t,16> lists{0xf9404a68,0xf94013e1,0xd10103a3,0xa9000408,0xf9000500,0xf9004a60,
        0x71001eff,0xf900081c,0x54ffff08,0xf9403e68,0xd10103a3,0xaa1603e1,0xa9005808,0xf9000500,0xf9003e60,0};
    auto accepts_lists=[&]{return eld::pixel17_source_list_contract(
        reinterpret_cast<const uint8_t*>(lists.data()),sizeof(lists),0x1000);};
    assert(accepts_lists());
    for(size_t i=0;i<15;++i) { auto original=lists[i]; lists[i]=0; assert(!accepts_lists()); lists[i]=original; }
    lists[6]=0x71002eff; assert(!accepts_lists()); lists[6]=0x71001eff; // A threshold of eleven is incompatible.
    assert(!eld::pixel17_source_list_contract(nullptr,sizeof(lists),0));
    assert(!eld::pixel17_source_list_contract(reinterpret_cast<const uint8_t*>(lists.data()),sizeof(lists)-1,0));
    // Inner init locks its own mutex at this+0. The adapter can retain that same recursive
    // lock while the original takes/releases one level and replacement registration commits.
    std::array<uint32_t,8> mutex{0xaa0003f3,0xaa1303e0,0x940003fe,0xd503201f,
        0xd503201f,0xaa1303e0,0x940007fa,0xd65f03c0};
    auto accepts_mutex=[&]{return eld::pixel17_registry_mutex_contract(
        reinterpret_cast<const uint8_t*>(mutex.data()),sizeof(mutex),0x1000,0x2000,0x3000);};
    assert(accepts_mutex());
    for(size_t at:{0U,1U,2U,5U,6U}) {
        auto old=mutex[at]; mutex[at]=0xd503201f; assert(!accepts_mutex()); mutex[at]=old;
    }
    mutex[1]=0x91002260; assert(!accepts_mutex()); mutex[1]=0xaa1303e0; // this+8 is the codec ABI, not the registry ABI.
    mutex[3]=0x940003fd; assert(!accepts_mutex()); mutex[3]=0xd503201f; // A second lock changes the proved nesting.
    assert(!eld::pixel17_registry_mutex_contract(nullptr,sizeof(mutex),0x1000,0x2000,0x3000));
    assert(!eld::pixel17_registry_mutex_contract(reinterpret_cast<const uint8_t*>(mutex.data()),sizeof(mutex)-1,0x1000,0x2000,0x3000));
    size_t lifecycle_count=0;
    assert(eld::pixel17_lifecycle_context_names(&lifecycle_count) && lifecycle_count==6);
    eld::SymbolImage image; eld::Pixel17Contract contract;
    assert(!eld::pixel17_contract(image,&contract));
    assert(!eld::pixel17_contract(image,nullptr));
    if(argc==2) {
        // Optional private shipped-image check; no Apple binary or derived instruction fixture is shipped.
        std::ifstream input(argv[1],std::ios::binary);
        std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(input)),{});
        assert(image.open(bytes.data(),bytes.size()));
        assert(eld::pixel17_contract(image,&contract));
        assert(contract.registry_count==6 && contract.raw_allocate);
        for(const char* name:{eld::kPixel17ActivePeer,eld::kPixel17Allocate,eld::kPixel17Init}) {
            eld::ImageSymbol symbol; assert(image.resolve(name,true,&symbol));
            const auto* p=image.bytes(symbol.address,symbol.size,5); assert(p);
            const size_t offset=static_cast<size_t>(p-bytes.data());
            std::vector<uint8_t> changed=bytes;
            std::memset(changed.data()+offset,0,static_cast<size_t>(symbol.size));
            eld::SymbolImage altered; assert(altered.open(changed.data(),changed.size()));
            assert(!eld::pixel17_contract(altered,&contract));
        }
        const auto* lifecycle=eld::pixel17_lifecycle_context_names(&lifecycle_count);
        // The first four contexts own the replacement, its caller and mutex construction.
        for(size_t i=0;i<4;++i) {
            eld::ImageSymbol symbol; assert(image.resolve(lifecycle[i],true,&symbol));
            const auto* p=image.bytes(symbol.address,symbol.size,5); assert(p);
            std::vector<uint8_t> changed=bytes;
            std::memset(changed.data()+(p-bytes.data()),0,static_cast<size_t>(symbol.size));
            eld::SymbolImage altered; assert(altered.open(changed.data(),changed.size()));
            assert(!eld::pixel17_contract(altered,&contract));
        }
        // Mutate each non-call instruction role in Peer::Init, then its linked calls.
        eld::ImageSymbol peer_init; assert(image.resolve(lifecycle[0],true,&peer_init));
        const auto* peer_bytes=image.bytes(peer_init.address,peer_init.size,5); assert(peer_bytes);
        for(size_t at=0;at<peer_init.size;at+=4) {
            auto changed=bytes;
            std::memset(changed.data()+(peer_bytes-bytes.data())+at,0,4);
            eld::SymbolImage altered; assert(altered.open(changed.data(),changed.size()));
            assert(!eld::pixel17_contract(altered,&contract));
        }
        std::cout<<"Shipped SDK37 semantic ABI and changed-function rejection passed\n";
    }
    std::cout<<"Pixel ABI checks passed\n";
}
