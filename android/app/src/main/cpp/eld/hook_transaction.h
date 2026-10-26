// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "runtime.h"
namespace eld {
struct ResolvedHook { void* target; void* replacement; void** original; };
// Context/ABI checks must finish before this function. Returns false on any failed install;
// preserves forwarding trampolines whose detours could not be removed. Never retries in-process.
bool hook_transaction(const ResolvedHook* hooks,size_t count,RuntimeHook install,RuntimeUnhook remove);
}
