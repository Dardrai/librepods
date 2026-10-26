// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "encoder_interface.h"
#include <cstddef>
#include <cstdint>

namespace eld {
inline constexpr size_t kPixel17WireStorage = 45;
inline constexpr int32_t kPixel17InvalidCodecIndex = 19;

struct Pixel17WireHookSpec {
    const char* symbol;
    void* replacement;
    void** original;
};
struct Pixel17WireBindings {
    // The caller owns registry publication and keeps this true while its codec objects survive.
    bool (*active)();
    bool (*quarantined)();
    const EncoderInterface* (*encoder)();
    int32_t source_index = 5;
};

// No hooks are installed here. The caller resolves exact symbols and validates their complete ABI.
bool pixel17_wire_prepare(const Pixel17WireBindings& bindings);
const Pixel17WireHookSpec* pixel17_wire_hook_specs(size_t* count);
bool pixel17_wire_ready();

#ifdef ELD_PIXEL17_WIRE_TESTING
void pixel17_wire_reset_for_test();
#endif
}
