// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "symbol_image.h"
#include <cstddef>

namespace eld {
// Checks the source-only SDK37 wire/encoder ABI by exact symbols and instruction roles.
// All returned symbol contexts must also match the loaded image before installing hooks.
bool pixel17_wire_contract(const SymbolImage& image);
const char* const* pixel17_wire_contract_symbols(size_t* count);
}
