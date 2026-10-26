// SPDX-License-Identifier: GPL-3.0-or-later
#include "symbol_image.h"
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
extern "C" {
#include "xz.h"
}

namespace eld {
namespace {
constexpr size_t kMaximumImage = 128U << 20;
constexpr size_t kMaximumDebug = 16U << 20;
bool range(uint64_t offset, uint64_t count, size_t size) {
    return offset <= size && count <= size - offset;
}
bool alignment(uint64_t value) { return !value || !(value & (value - 1)); }
uint16_t u16(const uint8_t* p) { return uint16_t(p[0]) | uint16_t(p[1]) << 8; }
uint32_t u32(const uint8_t* p) { return uint32_t(u16(p)) | uint32_t(u16(p + 2)) << 16; }
uint64_t u64(const uint8_t* p) { return uint64_t(u32(p)) | uint64_t(u32(p + 4)) << 32; }
bool header(const uint8_t* data, size_t size, bool loaded) {
    if (!data || size < 64 || size > kMaximumImage || std::memcmp(data, "\177ELF", 4) ||
        data[4] != 2 || data[5] != 1 || data[6] != 1 || u16(data + 18) != 183 ||
        u32(data + 20) != 1 || u16(data + 52) != 64 || u16(data + 58) != 64 ||
        !u16(data + 60) || u16(data + 60) > 4096 || !u16(data + 62) || u16(data + 62) >= u16(data + 60) ||
        u64(data + 40) < 64 || (u64(data + 40) & 7) ||
        !range(u64(data + 40), uint64_t(u16(data + 60)) * 64, size)) return false;
    if (!loaded) return u16(data + 16) == 3;
    return u16(data + 16) == 3 && u16(data + 54) == 56 && u16(data + 56) &&
        u16(data + 56) <= 128 && u64(data + 32) >= 64 && !(u64(data + 32) & 7) &&
        range(u64(data + 32), uint64_t(u16(data + 56)) * 56, size);
}
const uint8_t* section(const uint8_t* data, size_t size, unsigned index) {
    if (index >= u16(data + 60)) return nullptr;
    const auto* s = data + u64(data + 40) + uint64_t(index) * 64;
    // NOBITS has no file payload, including in the reduced mini-debug image.
    return u32(s + 4) == 8 || range(u64(s + 24), u64(s + 32), size) ? s : nullptr;
}
const char* string_at(const uint8_t* data, const uint8_t* strings, uint32_t offset) {
    if (!strings || u32(strings + 4) != 3 || offset >= u64(strings + 32)) return nullptr;
    // valid_sections checks the table's final NUL once, avoiding quadratic per-symbol scans.
    return reinterpret_cast<const char*>(data + u64(strings + 24) + offset);
}
bool valid_strings(const uint8_t* data, const uint8_t* strings) {
    return strings && u32(strings + 4) == 3 && !(u64(strings + 8) & 0x800) &&
        u64(strings + 32) && !data[u64(strings + 24)] &&
        !data[u64(strings + 24) + u64(strings + 32) - 1];
}
bool valid_sections(const uint8_t* data, size_t size) {
    const auto* strings = section(data, size, u16(data + 62));
    const auto* null_section = section(data, size, 0);
    const uint8_t zero[64]{};
    if (!valid_strings(data, strings) || !null_section || std::memcmp(null_section, zero, sizeof(zero))) return false;
    for (unsigned i = 0; i < u16(data + 60); ++i) {
        const auto* s = section(data, size, i);
        if (!s || !alignment(u64(s + 48)) ||
            ((u64(s + 8) & 2) && u64(s + 32) > std::numeric_limits<uint64_t>::max() - u64(s + 16)) ||
            (u32(s + 4) == 3 && !valid_strings(data, s))) return false;
    }
    for (unsigned i = 0; i < u16(data + 60); ++i) {
        const auto* s = section(data, size, i);
        if (!s || !string_at(data, strings, u32(s))) return false;
        if (u32(s + 4) != 2 && u32(s + 4) != 11) continue;
        if ((u64(s + 8) & 0x800) || u64(s + 56) != 24 || u64(s + 32) < 24 ||
            u64(s + 32) % 24 || u64(s + 32) / 24 > 1'000'000 ||
            !u32(s + 44) || u32(s + 44) > u64(s + 32) / 24) return false;
        const auto* names = section(data, size, u32(s + 40));
        if (!valid_strings(data, names) || std::memcmp(data + u64(s + 24), zero, 24)) return false;
        for (uint64_t n = 0; n < u64(s + 32); n += 24) {
            const auto* sym = data + u64(s + 24) + n;
            if (!string_at(data, names, u32(sym))) return false;
        }
    }
    return true;
}
bool decompress(const uint8_t* data, size_t size, std::vector<uint8_t>* output) {
    if (!size || size > (8U << 20)) return false;
    static std::once_flag crc;
    std::call_once(crc, [] {
        xz_crc32_init();
#ifdef XZ_USE_CRC64
        xz_crc64_init();
#endif
    });
    std::unique_ptr<xz_dec, decltype(&xz_dec_end)> decoder{xz_dec_init(XZ_DYNALLOC, 32U << 20), xz_dec_end};
    if (!decoder) return false;
    bool ok = false;
    try {
        output->resize(1U << 20);
        xz_buf buffer{};
        buffer.in = data; buffer.in_size = size;
        for (;;) {
            buffer.out = output->data(); buffer.out_size = output->size();
            const size_t before_in = buffer.in_pos, before_out = buffer.out_pos;
            const auto status = xz_dec_run(decoder.get(), &buffer);
            if (status == XZ_STREAM_END) { ok = buffer.in_pos == size; break; }
            if (status != XZ_OK || (buffer.in_pos == before_in && buffer.out_pos == before_out)) break;
            if (buffer.out_pos == output->size()) {
                if (output->size() == kMaximumDebug) break;
                output->resize(output->size() * 2);
            }
        }
        if (ok) output->resize(buffer.out_pos);
    } catch (const std::bad_alloc&) {
        ok = false;
    }
    if (!ok) output->clear();
    return ok;
}
bool find_symbol(const uint8_t* data, size_t size, const char* name, uint64_t address, bool function,
                 bool* found, ImageSymbol* result) {
    for (unsigned i = 0; i < u16(data + 60); ++i) {
        const auto* s = section(data, size, i);
        if (u32(s + 4) != 2 && u32(s + 4) != 11) continue;
        const auto* names = section(data, size, u32(s + 40));
        for (uint64_t n = 0; n < u64(s + 32); n += 24) {
            const auto* sym = data + u64(s + 24) + n;
            if (name) {
                if (std::strcmp(string_at(data, names, u32(sym)), name)) continue;
            } else if (u64(sym + 8) != address || (sym[4] & 15) != (function ? 2 : 1)) {
                continue; // Mapping labels and section markers do not define a function or object.
            }
            if (!u16(sym + 6)) continue; // Undefined dynamic imports cannot identify a host implementation.
            if (u16(sym + 6) >= u16(data + 60) ||
                (sym[4] & 15) != (function ? 2 : 1) || !u64(sym + 16) ||
                (function && ((u64(sym + 8) | u64(sym + 16)) & 3))) return false;
            const ImageSymbol value{u64(sym + 8), u64(sym + 16)};
            const auto* owner = section(data, size, u16(sym + 6));
            const auto base = u64(owner + 16), length = u64(owner + 32);
            if (!(u64(owner + 8) & 2) || (function && !(u64(owner + 8) & 4)) ||
                value.address < base || value.address - base > length ||
                value.size > length - (value.address - base)) return false;
            if (*found && (result->address != value.address || result->size != value.size)) return false;
            *found = true; *result = value;
        }
    }
    return true;
}
}

bool SymbolImage::open(const uint8_t* image, size_t size) {
    image_ = nullptr; size_ = 0; debug_.clear();
    if (!header(image, size, true) || !valid_sections(image, size)) return false;
    bool load = false;
    for (unsigned i = 0; i < u16(image + 56); ++i) {
        const auto* p = image + u64(image + 32) + uint64_t(i) * 56;
        if (u32(p) != 1) continue;
        load = load || (u64(p + 32) && (u32(p + 4) & 4));
        const auto align = u64(p + 48);
        if ((u32(p + 4) & ~7U) || (u32(p + 4) & 3) == 3 ||
            !alignment(align) || (align && u64(p + 8) % align != u64(p + 16) % align) ||
            u64(p + 32) > u64(p + 40) ||
            !range(u64(p + 8), u64(p + 32), size) ||
            u64(p + 40) > std::numeric_limits<uint64_t>::max() - u64(p + 16)) return false;
        // Conflicting permissions or partial aliases cannot identify one runtime mapping.
        for (unsigned j = 0; j < i; ++j) {
            const auto* previous = image + u64(image + 32) + uint64_t(j) * 56;
            if (u32(previous) == 1 && u64(p + 40) && u64(previous + 40) &&
                u64(p + 16) < u64(previous + 16) + u64(previous + 40) &&
                u64(previous + 16) < u64(p + 16) + u64(p + 40)) return false;
        }
    }
    if (!load) return false;
    const auto* names = section(image, size, u16(image + 62));
    bool compressed = false;
    for (unsigned i = 0; i < u16(image + 60); ++i) {
        const auto* s = section(image, size, i);
        if (std::strcmp(string_at(image, names, u32(s)), ".gnu_debugdata")) continue;
        if (compressed || u32(s + 4) != 1 || (u64(s + 8) & 0x800) ||
            !decompress(image + u64(s + 24), u64(s + 32), &debug_) ||
            !header(debug_.data(), debug_.size(), false) || !valid_sections(debug_.data(), debug_.size())) {
            debug_.clear(); return false;
        }
        compressed = true;
    }
    image_ = image; size_ = size;
    return true;
}

const uint8_t* SymbolImage::bytes(uint64_t address, uint64_t size, uint32_t required) const {
    if (!image_ || !size || !(required & 4) || (required & ~7U) ||
        size > std::numeric_limits<uint64_t>::max() - address) return nullptr;
    const uint8_t* result = nullptr;
    for (unsigned i = 0; i < u16(image_ + 56); ++i) {
        const auto* p = image_ + u64(image_ + 32) + uint64_t(i) * 56;
        const auto va = u64(p + 16), length = u64(p + 32);
        if (u32(p) != 1 || (u32(p + 4) & required) != required || address < va ||
            address - va > length || size > length - (address - va)) continue;
        const auto* candidate = image_ + u64(p + 8) + address - va;
        if (result && result != candidate) return nullptr;
        result = candidate;
    }
    return result;
}

bool SymbolImage::resolve(const char* name, bool function, ImageSymbol* result) const {
    if (result) *result = {};
    if (!image_ || !name || !*name || !result) return false;
    size_t length = 0;
    while (length <= 2048 && name[length]) ++length;
    if (length > 2048) return false;
    ImageSymbol selected{}; bool found = false;
    if (!find_symbol(image_, size_, name, 0, function, &found, &selected) ||
        (!debug_.empty() && !find_symbol(debug_.data(), debug_.size(), name, 0, function, &found, &selected)) ||
        !found || !bytes(selected.address, selected.size, function ? 5 : 4)) return false;
    *result = selected;
    return true;
}

bool SymbolImage::resolve_address(uint64_t address, bool function, ImageSymbol* result) const {
    if (result) *result = {};
    if (!image_ || !result) return false;
    ImageSymbol selected{}; bool found = false;
    if (!find_symbol(image_, size_, nullptr, address, function, &found, &selected) ||
        (!debug_.empty() && !find_symbol(debug_.data(), debug_.size(), nullptr, address, function, &found, &selected)) ||
        !found || !bytes(selected.address, selected.size, function ? 5 : 4)) return false;
    *result = selected;
    return true;
}
}
