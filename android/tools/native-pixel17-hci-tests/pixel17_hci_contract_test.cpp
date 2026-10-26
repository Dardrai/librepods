// SPDX-License-Identifier: GPL-3.0-or-later
#include "pixel17_hci.h"
#include <cassert>
#include <fstream>
#include <iostream>
#include <iterator>
#include <vector>
int main(int argc,char** argv) {
    eld::SymbolImage image; eld::Pixel17HciSymbols symbols;
    assert(!eld::pixel17_hci_contract(image,&symbols));
    assert(!eld::pixel17_hci_contract(image,nullptr));
    if(argc != 2) return 0;
    std::ifstream file(argv[1],std::ios::binary); assert(file);
    const std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(file)),{});
    assert(image.open(bytes.data(),bytes.size()));
    assert(eld::pixel17_hci_contract(image,&symbols));
    const eld::ImageSymbol contexts[]={symbols.construct,symbols.post,symbols.destroy_callback,
        symbols.main_wrapper,symbols.transmit,symbols.complete_adapter,symbols.view_byte,
        symbols.view_payload,symbols.view_size};
    for(const auto& context : contexts) assert(image.bytes(context.address,context.size,5));
    // Reject changes to the admitted native base/callback/parameter ownership
    // roles while preserving the rest of the ELF and mini-debug metadata.
    struct Mutation { eld::ImageSymbol symbol; size_t offset; };
    const Mutation changes[]={
        {symbols.construct,8},{symbols.post,40},{symbols.destroy_callback,48},
        {symbols.main_wrapper,60},{symbols.transmit,0x328},{symbols.complete_adapter,0x1c0},
        {symbols.view_byte,20},{symbols.view_size,4},{symbols.view_payload,40}};
    for(const auto& change : changes) {
        auto changed=bytes;
        const auto* source=image.bytes(change.symbol.address+change.offset,4,5); assert(source);
        const auto offset=static_cast<size_t>(source-bytes.data());
        changed.at(offset)^=0x10;
        eld::SymbolImage altered; assert(altered.open(changed.data(),changed.size()));
        eld::Pixel17HciSymbols rejected;
        assert(!eld::pixel17_hci_contract(altered,&rejected));
    }
    std::cout << "retained ELF admitted; nine native ABI mutations rejected; main object 0x"
              << std::hex << symbols.main_thread << '\n';
}
