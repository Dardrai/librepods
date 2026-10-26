// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "symbol_image.h"
#include <cstddef>
#include <cstdint>

namespace eld {
// Derives the final source address point from the named host AAC constructor's ADRP/ADD/store.
// Does not read process memory or use a link-time address/hash allowlist.
bool pixel17_vtable_address_point(const SymbolImage& image, uint64_t* address_point);
// Complete named code contexts used by this contract. Runtime must compare each entire
// immutable-image function with its mapped live instructions before accepting the proof.
const char* const* pixel17_vtable_context_names(size_t* count);
// words is a caller-validated/copyable array containing two ABI metadata words then nine
// actual runtime function pointers. The caller must independently validate readable/executable
// process mappings and compare complete function bytes before dispatching any host destructor.
bool pixel17_vtable_contract(const SymbolImage& image, uintptr_t load_base,
    uintptr_t runtime_address_point, const uintptr_t* words, size_t word_count);
}
