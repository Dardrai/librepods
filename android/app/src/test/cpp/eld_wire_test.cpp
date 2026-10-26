// SPDX-License-Identifier: GPL-3.0-or-later
#include "../../main/cpp/eld/wire.h"
#include <array>
#include <cassert>
#include <cstring>

int main() {
    const uint8_t expected[] = {16,0,0xff,0x4c,0,0,0,1,0x80,0,0x80,0,0x84,0,0x83,0xe8,0};
    std::array<uint8_t, 20> info{};
    info.fill(0xa5);
    assert(eld::make_configuration(info.data(), info.size()));
    assert(std::memcmp(info.data(), expected, sizeof(expected)) == 0 && info[17] == 0xa5);
    assert(eld::valid_configuration(info.data(), info.size()));
    assert(eld::bitrate(info.data(), info.size()) == 256000);
    assert(eld::max_au_bytes(256000) == 321 && eld::max_au_bytes(128000) == 161);
    for (size_t size = 0; size < sizeof(expected); ++size) {
        assert(!eld::valid_configuration(info.data(), size));
        assert(!eld::select_configuration(info.data(), size, info.data(), info.size()));
    }
    assert(!eld::is_apple(nullptr, 20));
    const auto original = info;
    for (auto value : {0u, 127000u, 128001u, 257000u}) {
        assert(!eld::make_configuration(info.data(), info.size(), value));
        assert(info == original);
    }
    for (size_t index : {size_t(0), size_t(1), size_t(2), size_t(3), size_t(7), size_t(8)}) {
        auto changed = original;
        changed[index] ^= 1;
        assert(!eld::is_apple(changed.data(), changed.size()));
    }
    auto peer = original;
    peer[12] = 0x8c;
    std::array<uint8_t, 20> selected{};
    assert(eld::select_configuration(peer.data(), peer.size(), selected.data(), selected.size()));
    assert(eld::valid_configuration(selected.data(), selected.size()) && selected[12] == 0x84);
    peer[14] = 0x83; peer[15] = 0x0d; peer[16] = 0x3f; // 199999 cap -> 199000.
    assert(eld::select_configuration(peer.data(), peer.size(), selected.data(), selected.size()));
    assert(eld::bitrate(selected.data(), selected.size()) == 199000);
    peer[14] = 0x81; peer[15] = 0xf0; peer[16] = 0x00; // Below 128k.
    auto before = selected;
    assert(!eld::select_configuration(peer.data(), peer.size(), selected.data(), selected.size()));
    assert(before == selected);
    peer = original; peer[14] &= 0x7f;
    assert(!eld::select_configuration(peer.data(), peer.size(), selected.data(), selected.size()));
    std::array<uint8_t, 5> header{};
    header[4] = 0xa5;
    assert(eld::write_au_header(header.data(), 4, 0xffff, 321));
    assert((header == std::array<uint8_t,5>{0xbf,0xff,0x11,0x41,0xa5}));
    before = selected;
    for (auto size : {size_t(0),size_t(322)}) {
        auto saved = header;
        assert(!eld::write_au_header(header.data(), 4, 0, size));
        assert(header == saved);
    }
    assert(!eld::write_au_header(header.data(), 3, 0, 1));
    assert(eld::write_au_header(header.data(), 4, 0x1000, 1));
    assert(header[0] == 0xb0 && header[1] == 0 && header[2] == 0x10 && header[3] == 1);
}
