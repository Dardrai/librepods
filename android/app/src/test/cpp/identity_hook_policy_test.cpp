// SPDX-License-Identifier: GPL-3.0-or-later
#include "../../main/cpp/identity_hook_policy.h"
#include <cassert>

int main() {
    assert(useLegacyBluetoothIdentityHooks(27));
    assert(useLegacyBluetoothIdentityHooks(33));
    assert(useLegacyBluetoothIdentityHooks(36));
    assert(!useLegacyBluetoothIdentityHooks(37));
    assert(!useLegacyBluetoothIdentityHooks(38));
    assert(!useLegacyBluetoothIdentityHooks(0));
    assert(!useLegacyBluetoothIdentityHooks(-1));
}
