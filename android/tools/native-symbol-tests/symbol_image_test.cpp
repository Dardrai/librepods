// SPDX-License-Identifier: GPL-3.0-or-later
#include "symbol_image.h"
#include "compressed_fixtures.h"
#include <cassert>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>
#include <new>
#include <random>
#include <utility>
#include <vector>

// Fail one real decompression-buffer allocation, without adding a production test hook.
bool reject_debug_allocation = false;
void* operator new(size_t size) {
    if (reject_debug_allocation && size >= (1U << 20)) {
        reject_debug_allocation = false;
        throw std::bad_alloc();
    }
    if (void* memory = std::malloc(size ? size : 1)) return memory;
    throw std::bad_alloc();
}
void operator delete(void* memory) noexcept { std::free(memory); }
void operator delete(void* memory, size_t) noexcept { std::free(memory); }

namespace {
void put(std::vector<uint8_t>& b, size_t at, uint64_t value, size_t count) {
    for (size_t i = 0; i < count; ++i) b.at(at + i) = value >> (8 * i);
}
std::vector<uint8_t> fixture() {
    std::vector<uint8_t> b(1024);
    std::memcpy(b.data(), "\177ELF\2\1\1", 7);
    put(b, 16, 3, 2); put(b, 18, 183, 2); put(b, 20, 1, 4);
    put(b, 32, 64, 8); put(b, 40, 512, 8); put(b, 52, 64, 2);
    put(b, 54, 56, 2); put(b, 56, 1, 2); put(b, 58, 64, 2);
    put(b, 60, 5, 2); put(b, 62, 1, 2);
    put(b, 64, 1, 4); put(b, 68, 5, 4); put(b, 72, 256, 8);
    put(b, 80, 0x1000, 8); put(b, 96, 64, 8); put(b, 104, 64, 8);
    constexpr char sections[] = "\0.shstrtab\0.strtab\0.symtab\0.text\0";
    constexpr char symbols[] = "\0target\0target_suffix\0table\0";
    std::memcpy(b.data() + 320, sections, sizeof(sections));
    std::memcpy(b.data() + 384, symbols, sizeof(symbols));
    auto sh = [&](unsigned index, uint32_t name, uint32_t type, uint64_t offset, uint64_t size) {
        const size_t at = 512 + index * 64;
        put(b, at, name, 4); put(b, at + 4, type, 4);
        put(b, at + 24, offset, 8); put(b, at + 32, size, 8);
    };
    sh(1, 1, 3, 320, sizeof(sections)); sh(2, 11, 3, 384, sizeof(symbols));
    sh(3, 19, 2, 432, 72); put(b, 512 + 3 * 64 + 40, 2, 4);
    put(b, 512 + 3 * 64 + 44, 1, 4);
    put(b, 512 + 3 * 64 + 56, 24, 8); sh(4, 27, 1, 256, 64);
    put(b, 512 + 4 * 64 + 8, 6, 8); put(b, 512 + 4 * 64 + 16, 0x1000, 8);
    put(b, 456, 1, 4); b[460] = 0x12; put(b, 462, 4, 2);
    put(b, 464, 0x1000, 8); put(b, 472, 16, 8);
    put(b, 480, 8, 4); b[484] = 0x12; put(b, 486, 4, 2);
    put(b, 488, 0x1010, 8); put(b, 496, 16, 8);
    return b;
}
std::vector<uint8_t> compressed(const uint8_t* stream, size_t size) {
    auto b = fixture();
    b.resize(1024 + size);
    std::memcpy(b.data() + 1024, stream, size);
    constexpr char names[] = "\0.shstrtab\0.strtab\0.symtab\0.text\0.gnu_debugdata\0";
    std::memcpy(b.data() + 320, names, sizeof(names));
    put(b, 512 + 64 + 32, sizeof(names), 8);
    put(b, 60, 6, 2);
    put(b, 832, 33, 4); put(b, 836, 1, 4);
    put(b, 856, 1024, 8); put(b, 864, size, 8);
    put(b, 462, 0, 2); put(b, 486, 0, 2); // Definitions exist only in mini-debug.
    return b;
}
void synthetic_tests() {
    auto b = fixture(); eld::SymbolImage image; eld::ImageSymbol symbol;
    assert(image.open(b.data(), b.size()));
    assert(image.resolve("target", true, &symbol) && symbol.address == 0x1000 && symbol.size == 16);
    assert(image.resolve("target_suffix", true, &symbol) && symbol.address == 0x1010);
    assert(!image.resolve("targe", true, &symbol) && !symbol.address && !symbol.size);
    assert(!image.resolve("target", false, &symbol));
    assert(image.bytes(0x1000, 64, 5) == b.data() + 256);
    assert(!image.bytes(0x1000, 65, 5)); assert(!image.bytes(0x1000, 4, 6));
    assert(!image.bytes(UINT64_MAX, 4, 5));
    for (size_t length : {size_t(0), size_t(63), size_t(511), size_t(831)})
        assert(!image.open(b.data(), length));
    auto broken = [&](size_t at, uint64_t value, size_t count) {
        auto changed = fixture(); put(changed, at, value, count);
        assert(!image.open(changed.data(), changed.size()));
    };
    broken(4, 1, 1); broken(5, 2, 1); broken(18, 62, 2);
    broken(40, UINT64_MAX - 20, 8); broken(60, 4097, 2); broken(62, 9, 2);
    broken(68, 7, 4); broken(96, UINT64_MAX, 8); broken(104, 1, 8);
    broken(512 + 3 * 64 + 40, 99, 4); broken(512 + 3 * 64 + 56, 23, 8);
    broken(456, UINT32_MAX, 4); broken(512 + 2 * 64 + 32, 6, 8);
    b = fixture(); put(b, 480, 1, 4); assert(image.open(b.data(), b.size()));
    assert(!image.resolve("target", true, &symbol)); // Conflicting same-name definitions.
    put(b, 488, 0x1000, 8); assert(image.open(b.data(), b.size()));
    assert(image.resolve("target", true, &symbol)); // Identical aliases are harmless.
    put(b, 462, 0, 2); put(b, 486, 0, 2); assert(image.open(b.data(), b.size()));
    assert(!image.resolve("target", true, &symbol));
    b = fixture(); put(b, 472, UINT64_MAX, 8); assert(image.open(b.data(), b.size()));
    assert(!image.resolve("target", true, &symbol));
    b = fixture(); put(b, 464, 0x1001, 8); assert(image.open(b.data(), b.size()));
    assert(!image.resolve("target", true, &symbol));
    b = fixture(); put(b, 462, 0xfff1, 2); assert(image.open(b.data(), b.size()));
    assert(!image.resolve("target", true, &symbol));
    assert(!image.open(nullptr, 0)); assert(!image.resolve("target", true, &symbol));
}
void section_and_mapping_tests() {
    eld::SymbolImage image; eld::ImageSymbol symbol;
    auto broken = [&](size_t at, uint64_t value, size_t count) {
        auto changed = fixture(); put(changed, at, value, count);
        assert(!image.open(changed.data(), changed.size()));
        symbol = {1, 1};
        assert(!image.resolve("target", true, &symbol) && !symbol.address && !symbol.size);
        assert(!image.bytes(0x1000, 4, 5));
    };
    broken(32, 65, 8); broken(40, 513, 8); broken(62, 0, 2);
    broken(512 + 24, UINT64_MAX, 8); broken(512 + 4, 3, 4);
    broken(384, 1, 1); broken(512 + 2 * 64 + 32, 0, 8);
    broken(512 + 64 + 8, 0x800, 8); broken(512 + 2 * 64 + 8, 0x800, 8);
    broken(512 + 3 * 64 + 8, 0x800, 8);
    broken(512 + 3 * 64 + 44, 0, 4); broken(512 + 3 * 64 + 44, 4, 4);
    broken(512 + 3 * 64 + 32, 0, 8); broken(432, 1, 1);
    broken(512 + 4 * 64 + 48, 3, 8);
    broken(512 + 4 * 64 + 16, UINT64_MAX - 32, 8);
    broken(68, 0x85, 4); broken(112, 3, 8); broken(112, 4096, 8);
    broken(80, UINT64_MAX - 32, 8); broken(96, 0, 8);

    auto b = fixture(); put(b, 112, 256, 8);
    assert(image.open(b.data(), b.size())); // File and virtual alignment agree.
    for (size_t field : {size_t(8), size_t(16), size_t(32)}) {
        b = fixture();
        put(b, 512 + 4 * 64 + field, field == 8 ? 2 : field == 16 ? 0x1004 : 8, 8);
        assert(image.open(b.data(), b.size()));
        assert(!image.resolve("target", true, &symbol));
    }
    b = fixture(); put(b, 480, 22, 4); b[484] = 0x11;
    assert(image.open(b.data(), b.size()));
    assert(image.resolve("table", false, &symbol) && symbol.address == 0x1010);
    assert(!image.resolve("table", true, &symbol));
    std::string long_name(2049, 'x');
    assert(!image.resolve(long_name.c_str(), true, &symbol));
    assert(!image.resolve(nullptr, true, &symbol)); assert(!image.resolve("target", true, nullptr));

    for (uint64_t second_address : {uint64_t(0x1008), uint64_t(0x103f)}) {
        b = fixture(); put(b, 56, 2, 2);
        put(b, 120, 1, 4); put(b, 124, 4, 4); put(b, 128, 288, 8);
        put(b, 136, second_address, 8); put(b, 152, 4, 8); put(b, 160, 4, 8);
        assert(!image.open(b.data(), b.size())); // Even a partial non-executable alias is ambiguous.
        put(b, 152, 0, 8);
        assert(!image.open(b.data(), b.size())); // A BSS alias cannot hide a file-backed instruction.
        put(b, 136, 0x2000, 8); put(b, 152, 4, 8);
        assert(image.open(b.data(), b.size()));
        assert(image.resolve("target", true, &symbol));
    }
    // The input remains borrowed and byte-for-byte unchanged by all parser operations.
    const auto original = b;
    assert(image.bytes(0x1000, 16, 5) == b.data() + 256);
    assert(b == original);
}

void compressed_tests() {
    eld::SymbolImage image; eld::ImageSymbol symbol;
    for (auto stream : {std::pair{debug_crc32, sizeof(debug_crc32)},
            std::pair{debug_crc64, sizeof(debug_crc64)}, std::pair{debug_sha256, sizeof(debug_sha256)},
            std::pair{debug_exact_limit, sizeof(debug_exact_limit)}}) {
        const auto b = compressed(stream.first, stream.second);
        const auto original = b;
        assert(image.open(b.data(), b.size()));
        assert(image.resolve("target", true, &symbol) && symbol.address == 0x1000 && symbol.size == 16);
        assert(image.resolve_address(0x1000, true, &symbol) && symbol.address == 0x1000 && symbol.size == 16);
        assert(image.resolve("target_suffix", true, &symbol));
        assert(!image.resolve("target_suffi", true, &symbol));
        assert(b == original);
    }
    auto rejected = [&](const std::vector<uint8_t>& bytes) {
        assert(!image.open(bytes.data(), bytes.size()));
        symbol = {1, 1};
        assert(!image.resolve("target", true, &symbol) && !symbol.address && !symbol.size);
        assert(!image.resolve_address(0x1000, true, &symbol) && !symbol.address && !symbol.size);
        assert(!image.bytes(0x1000, 4, 5));
    };
    for (auto stream : {std::pair{debug_malformed, sizeof(debug_malformed)},
            std::pair{debug_non_elf, sizeof(debug_non_elf)},
            std::pair{debug_dictionary_limit, sizeof(debug_dictionary_limit)},
            std::pair{debug_output_limit, sizeof(debug_output_limit)}})
        rejected(compressed(stream.first, stream.second));
    for (size_t length = 0; length < sizeof(debug_crc32); ++length)
        rejected(compressed(debug_crc32, length));
    for (size_t position : {size_t(0), size_t(8), sizeof(debug_crc32) - 1}) {
        auto bytes = compressed(debug_crc32, sizeof(debug_crc32)); bytes[1024 + position] ^= 1;
        rejected(bytes);
    }
    auto b = compressed(debug_crc32, sizeof(debug_crc32));
    b.insert(b.end(), std::begin(debug_crc32), std::end(debug_crc32));
    put(b, 864, sizeof(debug_crc32) * 2, 8); rejected(b); // Concatenated streams.
    b = compressed(debug_crc32, sizeof(debug_crc32)); b.resize(b.size() + 4);
    put(b, 864, sizeof(debug_crc32) + 4, 8); rejected(b); // Trailing stream padding is not section data.
    b = compressed(debug_crc32, sizeof(debug_crc32)); put(b, 840, 0x800, 8); rejected(b);
    b = compressed(debug_crc32, sizeof(debug_crc32)); put(b, 864, (8U << 20) + 1, 8);
    b.resize(1024 + (8U << 20) + 1); rejected(b);
    b = compressed(debug_crc32, sizeof(debug_crc32)); put(b, 60, 7, 2);
    std::memcpy(b.data() + 896, b.data() + 832, 64); rejected(b); // Duplicate mini-debug sections.
    b = compressed(debug_crc32, sizeof(debug_crc32)); put(b, 462, 4, 2); put(b, 464, 0x1004, 8);
    assert(image.open(b.data(), b.size()));
    assert(!image.resolve("target", true, &symbol)); // Main and mini-debug disagree.
    b = compressed(debug_crc32, sizeof(debug_crc32)); put(b, 462, 4, 2);
    assert(image.open(b.data(), b.size()));
    assert(image.resolve("target", true, &symbol)); // Identical definitions agree.
    assert(image.resolve_address(0x1000, true, &symbol));
    put(b, 472, 8, 8); assert(image.open(b.data(), b.size()));
    assert(!image.resolve_address(0x1000, true, &symbol)); // Conflicting same-VA sizes across main/debug tables.
}

void address_tests() {
    eld::SymbolImage image; eld::ImageSymbol result;
    auto b = fixture();
    assert(image.open(b.data(), b.size()));
    assert(image.resolve_address(0x1000, true, &result) && result.address == 0x1000 && result.size == 16);
    for (uint64_t address : {uint64_t(0), uint64_t(0x1004), uint64_t(0x1040), UINT64_MAX}) {
        result = {1, 1};
        assert(!image.resolve_address(address, true, &result) && !result.address && !result.size);
    }
    assert(!image.resolve_address(0x1000, false, &result));
    assert(!image.resolve_address(0x1000, true, nullptr));
    put(b, 488, 0x1000, 8); assert(image.open(b.data(), b.size()));
    assert(image.resolve_address(0x1000, true, &result)); // ICF aliases with identical complete bounds.
    put(b, 496, 8, 8); assert(image.open(b.data(), b.size()));
    assert(!image.resolve_address(0x1000, true, &result));
    b[484] = 0; put(b, 496, 0, 8); assert(image.open(b.data(), b.size()));
    assert(image.resolve_address(0x1000, true, &result)); // STT_NOTYPE mapping labels are not definitions.
    b[484] = 0x12; put(b, 486, 0, 2); assert(image.open(b.data(), b.size()));
    assert(image.resolve_address(0x1000, true, &result)); // Undefined imports cannot mask a real definition.
    put(b, 462, 0, 2); assert(image.open(b.data(), b.size()));
    assert(!image.resolve_address(0x1000, true, &result));
    b = fixture(); put(b, 480, 22, 4); b[484] = 0x11;
    assert(image.open(b.data(), b.size()));
    assert(image.resolve_address(0x1010, false, &result) && result.size == 16);
    put(b, 464, 0x1040, 8); assert(image.open(b.data(), b.size()));
    assert(!image.resolve_address(0x1040, true, &result)); // Defined metadata outside its section/load.
    assert(!image.open(b.data(), 831));
    assert(!image.resolve_address(0x1010, false, &result) && !result.address && !result.size);
}

void allocation_failure_test() {
    const auto bytes = compressed(debug_crc32, sizeof(debug_crc32));
    eld::SymbolImage image; eld::ImageSymbol result;
    reject_debug_allocation = true;
    assert(!image.open(bytes.data(), bytes.size()));
    assert(!reject_debug_allocation); // The decoder reached the allocation under test.
    assert(!image.resolve("target", true, &result));
    assert(!image.resolve_address(0x1000, true, &result));
    assert(image.open(bytes.data(), bytes.size()));
    assert(image.resolve("target", true, &result));
}

void mutation_tests() {
    std::mt19937 random(0x454c4636);
    const auto plain = fixture(), packed = compressed(debug_crc32, sizeof(debug_crc32));
    eld::SymbolImage image; eld::ImageSymbol result;
    for (unsigned trial = 0; trial < 4000; ++trial) {
        auto bytes = trial & 1 ? plain : packed;
        const auto original = bytes;
        for (unsigned changes = 1 + random() % 4; changes; --changes)
            bytes[random() % bytes.size()] ^= static_cast<uint8_t>(1U << (random() % 8));
        if (trial % 3 == 0) bytes.resize(random() % bytes.size());
        const auto mutated = bytes;
        if (image.open(bytes.data(), bytes.size())) {
            image.resolve("target", true, &result);
            image.resolve("table", false, &result);
            image.resolve_address(0x1000, true, &result);
            image.bytes(random(), 4, 5);
        } else {
            assert(!image.resolve("target", true, &result));
        }
        assert(bytes == mutated);
        assert(original == (trial & 1 ? plain : packed));
    }
    for (size_t size = 0; size < plain.size(); ++size) {
        if (!image.open(plain.data(), size)) assert(!image.resolve("target", true, &result));
    }
}
}

int main(int argc, char** argv) {
    synthetic_tests();
    section_and_mapping_tests();
    compressed_tests();
    address_tests();
    allocation_failure_test();
    mutation_tests();
    if (argc == 2) {
        std::ifstream input(argv[1], std::ios::binary);
        std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(input)), {});
        eld::SymbolImage image; eld::ImageSymbol symbol;
        assert(image.open(bytes.data(), bytes.size()));
        for (const char* name : {"_ZN10A2dpCodecs4initE29btav_a2dp_codec_sample_rate_t",
                 "_ZN24A2dpCodecConfigAacSourceC1E26btav_a2dp_codec_priority_t",
                 "_ZNSt3__115recursive_mutex4lockEv", "_ZNSt3__115recursive_mutex6unlockEv"}) {
            assert(image.resolve(name, true, &symbol));
            const auto named = symbol;
            assert(image.resolve_address(named.address, true, &symbol));
            assert(symbol.address == named.address && symbol.size == named.size);
            std::cout << name << " size=" << symbol.size << '\n';
        }
        assert(!image.resolve("A2dpCodecs4init", true, &symbol));
    }
    std::cout << "Exact-symbol and malformed-image checks passed\n";
}
