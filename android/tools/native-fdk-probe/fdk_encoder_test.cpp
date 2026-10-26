// SPDX-License-Identifier: GPL-3.0-or-later
#include "fdk_encoder.h"
#include "probe_pin.h"
#include <stdlib.h>
#include <string.h>

static unsigned checks;
#define CHECK(value) do { ++checks; if(!(value)) { fprintf(stderr,"FAIL line%d\n",__LINE__); exit(1); } } while(0)
struct AACENCODER { uint32_t values[0x301]; unsigned blocks; };
static struct {
    int opened,closed,encodes;
    uint32_t failSet,mismatch;
    bool openFail,initFail,badFrame,badChannels,badAsc,badSize;
    int encodeError,consumed,produced;
} mock;
static void defaults() { memset(&mock,0,sizeof(mock)); mock.consumed=960; mock.produced=320; }
static int32_t mock_open(EldFdkHandle* handle,uint32_t modules,uint32_t channels) {
    CHECK(modules==1 && channels==2 && !*handle);
    *handle=static_cast<AACENCODER*>(calloc(1,sizeof(AACENCODER))); mock.opened++;
    return mock.openFail?1:0;
}
static int32_t mock_set(EldFdkHandle handle,uint32_t key,uint32_t value) {
    CHECK(key<=0x300); if(key==mock.failSet) return 1; handle->values[key]=value; return 0;
}
static uint32_t mock_get(EldFdkHandle handle,uint32_t key) {
    return handle->values[key]+(key==mock.mismatch?1:0);
}
static int32_t mock_info(EldFdkHandle,EldFdkInfo* info) {
    CHECK(info->frameLength==0 && info->confSize==0);
    info->maxOutBufBytes=1536; info->frameLength=mock.badFrame?512:480;
    info->inputChannels=mock.badChannels?1:2; info->confSize=mock.badSize?65:4;
    const uint8_t asc[]={0xf8,0xe6,0x50,0}; memcpy(info->confBuf,asc,4);
    if(mock.badAsc) info->confBuf[2]=0x40;
    info->nDelay=240; return 0;
}
static int32_t mock_encode(EldFdkHandle handle,const EldFdkBufDesc* in,const EldFdkBufDesc* out,
                           const EldFdkInArgs* args,EldFdkOutArgs* result) {
    if(!in) { CHECK(!out && !args && !result); return mock.initFail?1:0; }
    mock.encodes++;
    CHECK(in->numBufs==1 && out->numBufs==1 && *in->bufElSizes==2 && *out->bufElSizes==1);
    CHECK(*in->bufferIdentifiers==0 && *out->bufferIdentifiers==3);
    CHECK(*in->bufSizes==1920 && *out->bufSizes>=322 && args->numInSamples==960 && args->numAncBytes==0);
    CHECK(result->numOutBytes==0 && result->numInSamples==0);
    result->numInSamples=mock.consumed;
    result->numOutBytes=handle->blocks++==0?0:mock.produced;
    if(result->numOutBytes>0) memset(*out->bufs,0x6a,static_cast<size_t>(result->numOutBytes));
    return mock.encodeError;
}
static int32_t mock_close(EldFdkHandle* handle) {
    CHECK(handle && *handle); free(*handle); *handle=nullptr; mock.closed++; return 0;
}
static const EldFdkApi api={mock_open,mock_set,mock_get,mock_info,mock_encode,mock_close};
int main(int argc,char** argv) {
    defaults(); EldFdkEncoder* encoder=nullptr; EldFdkConfig config={};
    CHECK(eld_fdk_open(nullptr,256000,&encoder,&config)==ELD_FDK_INVALID);
    CHECK(eld_fdk_open(&api,257000,&encoder,&config)==ELD_FDK_INVALID);
    CHECK(eld_fdk_open(&api,256001,&encoder,&config)==ELD_FDK_INVALID);
    CHECK(eld_fdk_open(&api,256000,&encoder,&config)==0);
    CHECK(config.sample_rate==48000 && config.channels==2 && config.frame_samples==480 && config.pcm_sample_bytes==2);
    CHECK(config.max_au_bytes==321 && config.delay_samples==240 && config.asc[2]==0x50);
    CHECK(eld_fdk_open(&api,256000,&encoder,&config)==ELD_FDK_INVALID);
    int16_t pcm[960]={}; uint8_t output[400]; memset(output,0xcc,sizeof(output)); size_t bytes=99;
    CHECK(eld_fdk_encode(encoder,pcm,480,output,sizeof(output),&bytes)==0 && bytes==0 && output[0]==0xcc);
    CHECK(eld_fdk_encode(encoder,pcm,480,output,sizeof(output),&bytes)==0 && bytes==320 && output[0]==0x6a);
    int calls=mock.encodes;
    CHECK(eld_fdk_encode(encoder,pcm,479,output,sizeof(output),&bytes)==ELD_FDK_INVALID && bytes==0);
    CHECK(eld_fdk_encode(encoder,pcm,480,output,320,&bytes)==ELD_FDK_INVALID && bytes==0 && mock.encodes==calls);
    mock.produced=322; memset(output,0xcc,sizeof(output));
    CHECK(eld_fdk_encode(encoder,pcm,480,output,sizeof(output),&bytes)==ELD_FDK_CODEC && bytes==0 && output[0]==0xcc);
    CHECK(eld_fdk_encode(encoder,pcm,480,output,sizeof(output),&bytes)==ELD_FDK_POISONED);
    CHECK(eld_fdk_update(encoder,128000,&config)==ELD_FDK_POISONED);
    mock.produced=320; CHECK(eld_fdk_reset(encoder)==0);
    CHECK(eld_fdk_encode(encoder,pcm,480,output,sizeof(output),&bytes)==0 && bytes==0);
    mock.consumed=959;
    CHECK(eld_fdk_encode(encoder,pcm,480,output,sizeof(output),&bytes)==ELD_FDK_CODEC && bytes==0);
    mock.consumed=960; CHECK(eld_fdk_reset(encoder)==0);
    mock.encodeError=1;
    CHECK(eld_fdk_encode(encoder,pcm,480,output,sizeof(output),&bytes)==ELD_FDK_CODEC);
    mock.encodeError=0; CHECK(eld_fdk_reset(encoder)==0);
    int opened=mock.opened;
    CHECK(eld_fdk_update(encoder,256000,&config)==0 && mock.opened==opened);
    mock.failSet=0x105; CHECK(eld_fdk_update(encoder,128000,&config)==ELD_FDK_CONFIG);
    mock.failSet=0; CHECK(eld_fdk_update(encoder,256000,&config)==0 && config.bitrate==256000);
    CHECK(eld_fdk_update(encoder,128000,&config)==0 && config.max_au_bytes==161);
    mock.produced=160; CHECK(eld_fdk_encode(encoder,pcm,480,output,sizeof(output),&bytes)==0 && bytes==0);
    CHECK(eld_fdk_encode(encoder,pcm,480,output,sizeof(output),&bytes)==0 && bytes==160);
    eld_fdk_close(&encoder); CHECK(!encoder && mock.opened==mock.closed); eld_fdk_close(&encoder); eld_fdk_close(nullptr);
    for(unsigned mode=0;mode<8;mode++) {
        defaults(); mock.openFail=mode==0; mock.initFail=mode==1; mock.badFrame=mode==2;
        mock.badChannels=mode==3; mock.badAsc=mode==4; mock.badSize=mode==5;
        mock.failSet=mode==6?0x105:0; mock.mismatch=mode==7?0x104:0;
        CHECK(eld_fdk_open(&api,256000,&encoder,&config)==ELD_FDK_CONFIG && !encoder);
        CHECK(mock.opened==mock.closed);
    }
    if(argc==2) CHECK(pinned_file(argv[1]));
    CHECK(!pinned_file("this-file-does-not-exist"));
    printf("PASS %u FDK mock/ABI/pin assertions; no real codec executed\n",checks);
    return 0;
}
