// SPDX-License-Identifier: GPL-3.0-or-later
#include "pixel17_packet_policy.h"
#include <cassert>
#include <fstream>
#include <iostream>
#include <iterator>
#include <vector>
int main(int argc,char** argv) {
    eld::SymbolImage image; eld::Pixel17PacketSymbols symbols;
    assert(!eld::pixel17_packet_contract(image,&symbols));
    assert(!eld::pixel17_packet_contract(image,nullptr));
    if(argc!=2) return 0;
    std::ifstream file(argv[1],std::ios::binary); assert(file);
    const std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(file)),{});
    assert(image.open(bytes.data(),bytes.size()));
    assert(eld::pixel17_packet_contract(image,&symbols));
    for(const auto& symbol:{symbols.command,symbols.event,symbols.allocate,symbols.enqueue})
        assert(image.bytes(symbol.address,symbol.size,5));
    for(size_t i=0;i<112;i+=4) {
        auto changed=bytes;
        auto* p=image.bytes(symbols.command.address+i,4,5); assert(p);
        changed.at(size_t(p-bytes.data()))^=0x10;
        eld::SymbolImage altered; assert(altered.open(changed.data(),changed.size()));
        eld::Pixel17PacketSymbols rejected;
        assert(!eld::pixel17_packet_contract(altered,&rejected));
    }
    for(size_t i:{size_t(0),size_t(44),size_t(64)}) {
        auto changed=bytes;
        auto* p=image.bytes(symbols.event.address+i,4,5); assert(p);
        changed.at(size_t(p-bytes.data()))^=0x10;
        eld::SymbolImage altered; assert(altered.open(changed.data(),changed.size()));
        eld::Pixel17PacketSymbols rejected;
        assert(!eld::pixel17_packet_contract(altered,&rejected));
    }
    std::cout<<"Packet callback ABI admitted; 31 role mutations rejected\n";
}
