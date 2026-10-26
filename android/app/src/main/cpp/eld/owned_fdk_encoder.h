// SPDX-License-Identifier: MIT
#pragma once
#include "fdk_encoder.h"

#ifdef __cplusplus
extern "C" {
#endif

// Owned, unmodified S16 FDK backend. No host-library symbol lookup or private ABI.
// Use through eld_fdk_open/update/reset/encode so parameter readback, actual ASC,
// frame consumption and output limits remain enforced for each encoder owner.
EldFdkApi eld_owned_fdk_api(void);

#ifdef __cplusplus
}
#endif
