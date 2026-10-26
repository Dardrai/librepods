// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cstddef>
#include <cstdint>
namespace eld {
using RuntimeHook = int (*)(void*, void*, void**);
using RuntimeUnhook = int (*)(void*);
void runtime_init(RuntimeHook hook, RuntimeUnhook unhook);
void runtime_library_loaded(const char* name, void* handle);
void runtime_set_enabled(bool enabled);
void runtime_java_ready(bool ready);
// Published codec identity remains stable for this process, including after stream failure/disable.
bool runtime_ready();
int runtime_native_codec_index();

#ifdef ELD_RUNTIME_TESTING
struct RuntimeTestSegment { uint64_t va, offset, file_size, memory_size; uint32_t flags; };
bool runtime_test_context(const uint8_t* file, size_t size, uintptr_t base,
    const RuntimeTestSegment* segments, size_t count, uintptr_t va, size_t bytes,
    const uint8_t* prologue = nullptr);
bool runtime_test_memory(const RuntimeTestSegment* segments, size_t count,
    uintptr_t va, size_t bytes, uint32_t required_flags);
bool runtime_test_image_anchor(uintptr_t base, uintptr_t symbol, uintptr_t origin, uintptr_t va);
struct Note8HookSpec;
bool runtime_test_install(const Note8HookSpec* specs, size_t count, uintptr_t base,
    RuntimeHook hook, RuntimeUnhook unhook);
void runtime_test_reset();
void runtime_test_native_complete();
void runtime_test_late();
void runtime_test_encoder_failed();
void runtime_test_lifetime_pinned();
#endif
}
