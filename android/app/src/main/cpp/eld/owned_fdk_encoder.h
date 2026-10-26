// SPDX-License-Identifier: MIT
#pragma once
#include "fdk_encoder.h"

#ifdef __cplusplus
extern "C" {
#endif

#ifndef ELD_OWNED_PCM_BITS
#define ELD_OWNED_PCM_BITS 16
#endif
#if ELD_OWNED_PCM_BITS == 32
typedef int32_t EldOwnedPcm;
#else
typedef int16_t EldOwnedPcm;
#endif

// Owned FDK backend with a build-verified PCM width. No host-library symbol lookup.
// Use through eld_fdk_open/update/reset/encode so parameter readback, actual ASC,
// frame consumption and output limits remain enforced for each encoder owner.
EldFdkApi eld_owned_fdk_api(void);
static inline int eld_owned_fdk_encode(EldFdkEncoder* encoder, const EldOwnedPcm* pcm, size_t frames,
                                       uint8_t* output, size_t capacity, size_t* bytes) {
#if ELD_OWNED_PCM_BITS == 32
    return eld_fdk_encode_q31(encoder,pcm,frames,output,capacity,bytes);
#else
    return eld_fdk_encode(encoder,pcm,frames,output,capacity,bytes);
#endif
}

#ifdef __cplusplus
}
#endif
