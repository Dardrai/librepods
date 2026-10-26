// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "runtime.h"
namespace eld {
// Private SDK37 experiment. Default builds have no owned FDK backend and reject admission.
bool pixel17_runtime_prepare(void* module, RuntimeHook hook, RuntimeUnhook unhook,
                             void (*registered)());
void pixel17_runtime_set_enabled(bool enabled);
void pixel17_runtime_initial_slot_free(bool free);
void pixel17_runtime_quarantine(const char* reason);
bool pixel17_runtime_registered();
}
