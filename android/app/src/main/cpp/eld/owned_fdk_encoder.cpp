// SPDX-License-Identifier: MIT
#include "owned_fdk_encoder.h"
#include "aacenc_lib.h"
#include <cstring>
#include <type_traits>

static_assert(sizeof(INT_PCM) * 8 == ELD_OWNED_PCM_BITS, "Owned FDK PCM ABI must match its caller");
static_assert(sizeof(INT_PCM) == sizeof(EldOwnedPcm) && std::is_signed<INT_PCM>::value);
static_assert(std::is_same<INT, int32_t>::value && std::is_same<UINT, uint32_t>::value);
static_assert(std::is_same<HANDLE_AACENCODER, EldFdkHandle>::value);
static_assert(sizeof(AACENC_InfoStruct::confBuf) == sizeof(EldFdkInfo::confBuf));

namespace {
int32_t open_owned(EldFdkHandle* output, uint32_t modules, uint32_t channels) {
    if (!output || *output) return AACENC_INVALID_HANDLE;
    return static_cast<int32_t>(aacEncOpen(output, modules, channels));
}
int32_t set_owned(EldFdkHandle handle, uint32_t parameter, uint32_t value) {
    return static_cast<int32_t>(aacEncoder_SetParam(handle, static_cast<AACENC_PARAM>(parameter), value));
}
uint32_t get_owned(EldFdkHandle handle, uint32_t parameter) {
    return aacEncoder_GetParam(handle, static_cast<AACENC_PARAM>(parameter));
}
int32_t info_owned(EldFdkHandle handle, EldFdkInfo* output) {
    if (!output) return AACENC_INVALID_HANDLE;
    AACENC_InfoStruct actual{};
    const auto result = aacEncInfo(handle, &actual);
    if (result == AACENC_OK) {
        EldFdkInfo copy{};
        copy.maxOutBufBytes = actual.maxOutBufBytes;
        copy.maxAncBytes = actual.maxAncBytes;
        copy.inBufFillLevel = actual.inBufFillLevel;
        copy.inputChannels = actual.inputChannels;
        copy.frameLength = actual.frameLength;
        copy.nDelay = actual.nDelay;
        copy.nDelayCore = actual.nDelayCore;
        std::memcpy(copy.confBuf, actual.confBuf, sizeof(copy.confBuf));
        copy.confSize = actual.confSize;
        *output = copy;
    }
    return static_cast<int32_t>(result);
}
int32_t encode_owned(EldFdkHandle handle, const EldFdkBufDesc* input, const EldFdkBufDesc* output,
                     const EldFdkInArgs* args, EldFdkOutArgs* returned) {
    AACENC_BufDesc in{}, out{};
    AACENC_InArgs request{};
    AACENC_OutArgs response{};
    if (input) in = {input->numBufs, input->bufs, input->bufferIdentifiers, input->bufSizes, input->bufElSizes};
    if (output) out = {output->numBufs, output->bufs, output->bufferIdentifiers, output->bufSizes, output->bufElSizes};
    if (args) request = {args->numInSamples, args->numAncBytes};
    const auto result = aacEncEncode(handle, input ? &in : nullptr, output ? &out : nullptr,
                                    args ? &request : nullptr, returned ? &response : nullptr);
    if (returned && result == AACENC_OK)
        *returned = {response.numOutBytes, response.numInSamples, response.numAncBytes, response.bitResState};
    return static_cast<int32_t>(result);
}
int32_t close_owned(EldFdkHandle* handle) {
    if (!handle) return AACENC_INVALID_HANDLE;
    return static_cast<int32_t>(aacEncClose(handle));
}
}

EldFdkApi eld_owned_fdk_api(void) {
    return {open_owned, set_owned, get_owned, info_owned, encode_owned, close_owned, sizeof(INT_PCM)};
}
