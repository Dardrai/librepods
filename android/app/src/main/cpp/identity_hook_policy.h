// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

// The old SDP/FCR detours do not have a verified Android 17 ABI.
constexpr bool useLegacyBluetoothIdentityHooks(int apiLevel) {
    return apiLevel > 0 && apiLevel < 37;
}
