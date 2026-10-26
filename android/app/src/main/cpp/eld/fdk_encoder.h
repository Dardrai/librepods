// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Pinned public FDK structures; PCM width belongs to the supplying backend.
typedef struct AACENCODER* EldFdkHandle;
typedef struct {
    uint32_t maxOutBufBytes, maxAncBytes, inBufFillLevel, inputChannels, frameLength;
    uint32_t nDelay, nDelayCore;
    uint8_t confBuf[64];
    uint32_t confSize;
} EldFdkInfo;
typedef struct {
    int32_t numBufs;
    void** bufs;
    int32_t* bufferIdentifiers;
    int32_t* bufSizes;
    int32_t* bufElSizes;
} EldFdkBufDesc;
typedef struct { int32_t numInSamples, numAncBytes; } EldFdkInArgs;
typedef struct { int32_t numOutBytes, numInSamples, numAncBytes, bitResState; } EldFdkOutArgs;
typedef struct {
    int32_t (*open)(EldFdkHandle*, uint32_t modules, uint32_t maxChannels);
    int32_t (*set)(EldFdkHandle, uint32_t parameter, uint32_t value);
    uint32_t (*get)(EldFdkHandle, uint32_t parameter);
    int32_t (*info)(EldFdkHandle, EldFdkInfo*);
    int32_t (*encode)(EldFdkHandle, const EldFdkBufDesc*, const EldFdkBufDesc*,
                      const EldFdkInArgs*, EldFdkOutArgs*);
    int32_t (*close)(EldFdkHandle*);
    uint32_t pcm_sample_bytes;
} EldFdkApi;

typedef struct EldFdkEncoder EldFdkEncoder;
typedef struct {
    uint32_t sample_rate, channels, frame_samples, pcm_sample_bytes;
    uint32_t bitrate, max_au_bytes, delay_samples;
    uint8_t asc[4];
} EldFdkConfig;
enum {
    ELD_FDK_OK=0, ELD_FDK_INVALID=-1, ELD_FDK_CONFIG=-2,
    ELD_FDK_CODEC=-3, ELD_FDK_POISONED=-4, ELD_FDK_ALLOC=-5, ELD_FDK_LIBRARY=-6
};

// The caller pins the supplying library/ABI and keeps it loaded until every encoder is closed.
// One thread owns each encoder. No function here loads a module, sends audio or uses Bluetooth.
// Resolves only the known Note8 exports, checking common image base, offsets and32-byte prologues.
// This secondary ABI check does not replace the caller's full library SHA256 pin before loading.
int eld_fdk_bind(void* loaded_library, EldFdkApi* api);
int eld_fdk_open(const EldFdkApi* api, uint32_t bitrate, EldFdkEncoder** encoder, EldFdkConfig* config);
// Failed replacement leaves the previous instance intact; callers must handle the returned error.
int eld_fdk_update(EldFdkEncoder* encoder, uint32_t bitrate, EldFdkConfig* config);
int eld_fdk_reset(EldFdkEncoder* encoder);
// Exactly480 frames of interleaved signed16 stereo. Successful startup buffering may return zero bytes.
// Output is not copied on error. A codec/consumption/size error poisons the instance until reset.
int eld_fdk_encode(EldFdkEncoder* encoder, const int16_t* pcm, size_t frames,
                   uint8_t* output, size_t capacity, size_t* bytes);
// The owned Q31 backend only. A width mismatch is rejected before reading PCM.
int eld_fdk_encode_q31(EldFdkEncoder* encoder, const int32_t* pcm, size_t frames,
                       uint8_t* output, size_t capacity, size_t* bytes);
void eld_fdk_close(EldFdkEncoder** encoder);

#ifdef __cplusplus
}
#endif
