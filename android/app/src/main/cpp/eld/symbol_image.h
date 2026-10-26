// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>

namespace eld {
struct ImageSymbol {
    uint64_t address = 0;
    uint64_t size = 0;
};

// Borrows a complete ELF file, not a loaded-memory image. Keep it alive and immutable until
// the next open() or destruction; any failed open invalidates prior results. Not thread-safe
// with open(). bytes() borrows that same input. No search crosses into another loaded library.
class SymbolImage {
public:
    bool open(const uint8_t* image, size_t size);
    bool resolve(const char* name, bool function, ImageSymbol* result) const;
    // Exact definition address, never a nearest-symbol or containing-range guess.
    bool resolve_address(uint64_t address, bool function, ImageSymbol* result) const;
    const uint8_t* bytes(uint64_t address, uint64_t size, uint32_t required_flags) const;
private:
    const uint8_t* image_ = nullptr;
    size_t size_ = 0;
    std::vector<uint8_t> debug_;
};
}
