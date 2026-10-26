// SPDX-License-Identifier: GPL-3.0-or-later
#include "fdk_encoder.h"
#include <stdlib.h>
#include <string.h>
#if defined(__ANDROID__) || defined(__linux__)
#include <dlfcn.h>
#endif

static_assert(sizeof(int16_t)==2 && sizeof(int32_t)==4 && sizeof(uint32_t)==4);
static_assert(sizeof(EldFdkInfo)==96 && offsetof(EldFdkInfo,inputChannels)==12);
static_assert(offsetof(EldFdkInfo,frameLength)==16 && offsetof(EldFdkInfo,confBuf)==28);
static_assert(offsetof(EldFdkInfo,confSize)==92);
static_assert(sizeof(EldFdkInArgs)==8 && sizeof(EldFdkOutArgs)==16);
static_assert(sizeof(void*)!=8 || (sizeof(EldFdkBufDesc)==40 && offsetof(EldFdkBufDesc,bufElSizes)==32));

namespace {
constexpr uint32_t AOT=0x100, BITRATE=0x101, BITRATEMODE=0x102, RATE=0x103;
constexpr uint32_t SBR=0x104, GRANULE=0x105, CHANNELS=0x106, AFTERBURNER=0x200;
constexpr uint32_t BANDWIDTH=0x203, PEAK=0x207, TRANSPORT=0x300;
constexpr uint8_t ASC[4]={0xf8,0xe6,0x50,0};
constexpr size_t FRAMES=480, SAMPLES=960;
bool valid_bitrate(uint32_t bitrate) { return bitrate>=128000 && bitrate<=256000 && bitrate%1000==0; }
bool valid_api(const EldFdkApi* api) {
    return api && api->open && api->set && api->get && api->info && api->encode && api->close &&
        (api->pcm_sample_bytes==2 || api->pcm_sample_bytes==4);
}
}

struct EldFdkEncoder {
    EldFdkApi api;
    EldFdkHandle handle;
    EldFdkConfig config;
    bool poisoned;
};

static void dispose_handle(EldFdkEncoder* encoder) {
    if(encoder->handle) encoder->api.close(&encoder->handle);
    encoder->handle=nullptr;
}

static int configure(EldFdkEncoder* encoder,uint32_t bitrate) {
    if(encoder->api.open(&encoder->handle,1,2)!=0 || !encoder->handle) {
        dispose_handle(encoder); return ELD_FDK_CONFIG;
    }
    const struct { uint32_t key,value; } settings[]={
        {AOT,39},{RATE,48000},{CHANNELS,2},{GRANULE,480},{SBR,0},
        {BITRATE,bitrate},{BITRATEMODE,0},{PEAK,bitrate},{AFTERBURNER,1},{TRANSPORT,0},
        // The 256 kbps auto preset cuts off at 16 kHz. Lower rates retain FDK's own tuning.
        {BANDWIDTH,bitrate==256000?20000u:0u}
    };
    for(const auto& setting:settings) {
        if(encoder->api.set(encoder->handle,setting.key,setting.value)!=0) {
            dispose_handle(encoder); return ELD_FDK_CONFIG;
        }
    }
    EldFdkInfo info={};
    if(encoder->api.encode(encoder->handle,nullptr,nullptr,nullptr,nullptr)!=0 ||
       encoder->api.info(encoder->handle,&info)!=0 || info.frameLength!=FRAMES ||
       info.inputChannels!=2 || info.confSize!=sizeof(ASC) || memcmp(info.confBuf,ASC,sizeof(ASC))!=0 ||
       info.maxOutBufBytes==0 || info.maxOutBufBytes>8192) {
        dispose_handle(encoder); return ELD_FDK_CONFIG;
    }
    for(const auto& setting:settings) {
        // Automatic bandwidth reads back as the chosen frequency, not the zero request.
        if(setting.key==BANDWIDTH && setting.value==0) continue;
        if(encoder->api.get(encoder->handle,setting.key)!=setting.value) {
            dispose_handle(encoder); return ELD_FDK_CONFIG;
        }
    }
    encoder->config={48000,2,480,encoder->api.pcm_sample_bytes,bitrate,(bitrate+799)/800+1,info.nDelay,{0xf8,0xe6,0x50,0}};
    encoder->poisoned=false;
    return ELD_FDK_OK;
}

int eld_fdk_open(const EldFdkApi* api,uint32_t bitrate,EldFdkEncoder** output,EldFdkConfig* config) {
    if(!valid_api(api) || !valid_bitrate(bitrate) || !output || *output || !config) return ELD_FDK_INVALID;
    auto* encoder=static_cast<EldFdkEncoder*>(calloc(1,sizeof(EldFdkEncoder)));
    if(!encoder) return ELD_FDK_ALLOC;
    encoder->api=*api;
    int result=configure(encoder,bitrate);
    if(result!=ELD_FDK_OK) { free(encoder); return result; }
    *config=encoder->config;
    *output=encoder;
    return ELD_FDK_OK;
}

int eld_fdk_update(EldFdkEncoder* encoder,uint32_t bitrate,EldFdkConfig* config) {
    if(!encoder || !valid_bitrate(bitrate) || !config) return ELD_FDK_INVALID;
    if(encoder->poisoned) return ELD_FDK_POISONED;
    if(bitrate==encoder->config.bitrate) { *config=encoder->config; return ELD_FDK_OK; }
    EldFdkEncoder replacement={}; replacement.api=encoder->api;
    int result=configure(&replacement,bitrate);
    if(result!=ELD_FDK_OK) return result;
    dispose_handle(encoder);
    *encoder=replacement; *config=encoder->config;
    return ELD_FDK_OK;
}

int eld_fdk_reset(EldFdkEncoder* encoder) {
    if(!encoder) return ELD_FDK_INVALID;
    EldFdkEncoder replacement={}; replacement.api=encoder->api;
    int result=configure(&replacement,encoder->config.bitrate);
    if(result!=ELD_FDK_OK) return result;
    dispose_handle(encoder); *encoder=replacement;
    return ELD_FDK_OK;
}

static int encode_pcm(EldFdkEncoder* encoder,const void* pcm,size_t frames,uint32_t sample_bytes,
                      uint8_t* output,size_t capacity,size_t* bytes) {
    if(bytes) *bytes=0;
    if(!encoder || !pcm || frames!=FRAMES || !output || !bytes || capacity<encoder->config.max_au_bytes ||
       sample_bytes!=encoder->config.pcm_sample_bytes)
        return ELD_FDK_INVALID;
    if(encoder->poisoned) return ELD_FDK_POISONED;
    // Scratch capacity is independent of the negotiated limit; never expose an oversized AU.
    uint8_t scratch[8192];
    void* input=const_cast<void*>(pcm); void* destination=scratch;
    int32_t inId=0,outId=3,inSize=static_cast<int32_t>(SAMPLES*sample_bytes),outSize=sizeof(scratch);
    int32_t inElement=static_cast<int32_t>(sample_bytes),outElement=1;
    EldFdkBufDesc in={1,&input,&inId,&inSize,&inElement};
    EldFdkBufDesc out={1,&destination,&outId,&outSize,&outElement};
    EldFdkInArgs args={static_cast<int32_t>(SAMPLES),0}; EldFdkOutArgs returned={};
    int error=encoder->api.encode(encoder->handle,&in,&out,&args,&returned);
    if(error!=0 || returned.numInSamples!=SAMPLES || returned.numAncBytes!=0 || returned.numOutBytes<0 ||
       static_cast<uint32_t>(returned.numOutBytes)>encoder->config.max_au_bytes) {
        encoder->poisoned=true; return ELD_FDK_CODEC;
    }
    memcpy(output,scratch,static_cast<size_t>(returned.numOutBytes));
    *bytes=static_cast<size_t>(returned.numOutBytes);
    return ELD_FDK_OK;
}

int eld_fdk_encode(EldFdkEncoder* encoder,const int16_t* pcm,size_t frames,
                   uint8_t* output,size_t capacity,size_t* bytes) {
    return encode_pcm(encoder,pcm,frames,2,output,capacity,bytes);
}
int eld_fdk_encode_q31(EldFdkEncoder* encoder,const int32_t* pcm,size_t frames,
                       uint8_t* output,size_t capacity,size_t* bytes) {
    return encode_pcm(encoder,pcm,frames,4,output,capacity,bytes);
}

void eld_fdk_close(EldFdkEncoder** output) {
    if(!output || !*output) return;
    dispose_handle(*output); free(*output); *output=nullptr;
}

int eld_fdk_bind(void* library,EldFdkApi* output) {
    if(!library || !output) return ELD_FDK_INVALID;
#if defined(__ANDROID__) || defined(__linux__)
    EldFdkApi candidate={}; candidate.pcm_sample_bytes=2; // Pinned Note8 library retains its S16 ABI.
    void* base=nullptr;
    {
        static const uint8_t prologue[32]={0x5e,0x86,0x00,0xf8,0xfd,0x7b,0xbc,0xa9,0xfc,0x5f,0x01,0xa9,0xfd,0x03,0x00,0x91,0xf6,0x57,0x02,0xa9,0xf4,0x4f,0x03,0xa9,0xff,0x43,0x2c,0xd1,0x57,0xd0,0x3b,0xd5};
        void* symbol=dlsym(library,"aacEncOpen"); Dl_info origin={};
        if(!symbol || !dladdr(symbol,&origin) || !origin.dli_fbase ||
           (base && base!=origin.dli_fbase) ||
           reinterpret_cast<uintptr_t>(symbol)-reinterpret_cast<uintptr_t>(origin.dli_fbase)!=0x70fba0 ||
           memcmp(symbol,prologue,sizeof(prologue))!=0) return ELD_FDK_LIBRARY;
        base=origin.dli_fbase;
        candidate.open=reinterpret_cast<decltype(candidate.open)>(symbol);
    }
    {
        static const uint8_t prologue[32]={0x5e,0x86,0x00,0xf8,0xfd,0x7b,0xbe,0xa9,0xf4,0x4f,0x01,0xa9,0xfd,0x03,0x00,0x91,0xe0,0x03,0x00,0xb4,0xf4,0x03,0x02,0x2a,0xf3,0x03,0x00,0xaa,0x40,0x04,0x80,0x52};
        void* symbol=dlsym(library,"aacEncoder_SetParam"); Dl_info origin={};
        if(!symbol || !dladdr(symbol,&origin) || !origin.dli_fbase ||
           (base && base!=origin.dli_fbase) ||
           reinterpret_cast<uintptr_t>(symbol)-reinterpret_cast<uintptr_t>(origin.dli_fbase)!=0x712144 ||
           memcmp(symbol,prologue,sizeof(prologue))!=0) return ELD_FDK_LIBRARY;
        base=origin.dli_fbase;
        candidate.set=reinterpret_cast<decltype(candidate.set)>(symbol);
    }
    {
        static const uint8_t prologue[32]={0x00,0x11,0x00,0xb4,0xe8,0x03,0x00,0xaa,0xe0,0x03,0x1f,0x2a,0x3f,0x18,0x08,0x71,0x8c,0x01,0x00,0x54,0x29,0x00,0x04,0x51,0x3f,0x21,0x00,0x71,0xe8,0x03,0x00,0x54};
        void* symbol=dlsym(library,"aacEncoder_GetParam"); Dl_info origin={};
        if(!symbol || !dladdr(symbol,&origin) || !origin.dli_fbase ||
           (base && base!=origin.dli_fbase) ||
           reinterpret_cast<uintptr_t>(symbol)-reinterpret_cast<uintptr_t>(origin.dli_fbase)!=0x712790 ||
           memcmp(symbol,prologue,sizeof(prologue))!=0) return ELD_FDK_LIBRARY;
        base=origin.dli_fbase;
        candidate.get=reinterpret_cast<decltype(candidate.get)>(symbol);
    }
    {
        static const uint8_t prologue[32]={0xff,0x43,0x03,0xd1,0x5e,0x86,0x00,0xf8,0xfd,0x7b,0x09,0xa9,0xfd,0x43,0x02,0x91,0xf8,0x5f,0x0a,0xa9,0xf6,0x57,0x0b,0xa9,0xf4,0x4f,0x0c,0xa9,0x57,0xd0,0x3b,0xd5};
        void* symbol=dlsym(library,"aacEncInfo"); Dl_info origin={};
        if(!symbol || !dladdr(symbol,&origin) || !origin.dli_fbase ||
           (base && base!=origin.dli_fbase) ||
           reinterpret_cast<uintptr_t>(symbol)-reinterpret_cast<uintptr_t>(origin.dli_fbase)!=0x7129cc ||
           memcmp(symbol,prologue,sizeof(prologue))!=0) return ELD_FDK_LIBRARY;
        base=origin.dli_fbase;
        candidate.info=reinterpret_cast<decltype(candidate.info)>(symbol);
    }
    {
        static const uint8_t prologue[32]={0x5e,0x86,0x00,0xf8,0xfd,0x7b,0xba,0xa9,0xfc,0x6f,0x01,0xa9,0xfd,0x03,0x00,0x91,0xfa,0x67,0x02,0xa9,0xf8,0x5f,0x03,0xa9,0xf6,0x57,0x04,0xa9,0xf4,0x4f,0x05,0xa9};
        void* symbol=dlsym(library,"aacEncEncode"); Dl_info origin={};
        if(!symbol || !dladdr(symbol,&origin) || !origin.dli_fbase ||
           (base && base!=origin.dli_fbase) ||
           reinterpret_cast<uintptr_t>(symbol)-reinterpret_cast<uintptr_t>(origin.dli_fbase)!=0x710134 ||
           memcmp(symbol,prologue,sizeof(prologue))!=0) return ELD_FDK_LIBRARY;
        base=origin.dli_fbase;
        candidate.encode=reinterpret_cast<decltype(candidate.encode)>(symbol);
    }
    {
        static const uint8_t prologue[32]={0x5e,0x86,0x00,0xf8,0xfd,0x7b,0xbe,0xa9,0xf4,0x4f,0x01,0xa9,0xfd,0x03,0x00,0x91,0xe0,0x04,0x00,0xb4,0xf3,0x03,0x00,0xaa,0x14,0x00,0x40,0xf9,0xd4,0x04,0x00,0xb4};
        void* symbol=dlsym(library,"aacEncClose"); Dl_info origin={};
        if(!symbol || !dladdr(symbol,&origin) || !origin.dli_fbase ||
           (base && base!=origin.dli_fbase) ||
           reinterpret_cast<uintptr_t>(symbol)-reinterpret_cast<uintptr_t>(origin.dli_fbase)!=0x71006c ||
           memcmp(symbol,prologue,sizeof(prologue))!=0) return ELD_FDK_LIBRARY;
        base=origin.dli_fbase;
        candidate.close=reinterpret_cast<decltype(candidate.close)>(symbol);
    }
    *output=candidate; return ELD_FDK_OK;
#else
    return ELD_FDK_LIBRARY;
#endif
}
