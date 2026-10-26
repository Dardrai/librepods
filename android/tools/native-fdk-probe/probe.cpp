// SPDX-License-Identifier: GPL-3.0-or-later
#include "fdk_encoder.h"
#include "probe_pin.h"
#include <dlfcn.h>
#include <fcntl.h>
#include <math.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

static EldFdkApi original;
static EldFdkInfo lastInfo;
static uint32_t failedParameter;
static int lastFdkError;
static int32_t observed_set(EldFdkHandle handle,uint32_t key,uint32_t value) {
    int result=original.set(handle,key,value);
    if(result) { failedParameter=key; lastFdkError=result; }
    return result;
}
static int32_t observed_info(EldFdkHandle handle,EldFdkInfo* info) {
    int result=original.info(handle,info); lastInfo=*info;
    if(result) lastFdkError=result;
    return result;
}
static int32_t observed_encode(EldFdkHandle handle,const EldFdkBufDesc* in,const EldFdkBufDesc* out,
                               const EldFdkInArgs* args,EldFdkOutArgs* result) {
    int error=original.encode(handle,in,out,args,result);
    if(error) lastFdkError=error;
    return error;
}
static void json_string(const char* text) {
    putchar('"');
    if(text) for(unsigned i=0;text[i] && i<1024;i++) {
        unsigned char c=static_cast<unsigned char>(text[i]);
        if(c=='"' || c=='\\') { putchar('\\'); putchar(c); }
        else if(c<32) printf("\\u%04x",c);
        else putchar(c);
    }
    putchar('"');
}
static uint64_t nanos() {
    timespec now={}; clock_gettime(CLOCK_MONOTONIC,&now);
    return static_cast<uint64_t>(now.tv_sec)*1000000000ULL+now.tv_nsec;
}
static bool write_record(FILE* output,uint32_t bitrate,uint32_t frame,const uint8_t* bytes,uint32_t size) {
    uint32_t header[3]={bitrate,frame,size};
    return fwrite(header,sizeof(header),1,output)==1 && fwrite(bytes,1,size,output)==size;
}
int main(int argc,char** argv) {
    if(argc!=3) { fprintf(stderr,"Usage: eld_fdk_probe <pinned-library-path> <new-synthetic-output.aus>\n"); return 2; }
    // Default SIGALRM ends only this isolated process, including a blocked vendor call.
    alarm(15);
    if(!pinned_file(argv[1])) {
        printf("{\"stage\":\"file_pin\",\"accepted\":false,\"error\":\"Expected exact13786880B Note8 library SHA256\"}\n"); return 2;
    }
    printf("{\"stage\":\"file_pin\",\"accepted\":true,\"library_sha256\":\"d965f16cba9b9f103d657844eae618ac13b915581da36a4d7720995229e114a7\",\"synthetic_only\":true}\n"); fflush(stdout);
    // No JNI_OnLoad invocation, Bluetooth API, namespace bypass or alternate library fallback.
    void* library=dlopen(argv[1],RTLD_NOW|RTLD_LOCAL);
    if(!library) {
        printf("{\"stage\":\"dlopen\",\"accepted\":false,\"error\":"); json_string(dlerror()); puts("}"); return 3;
    }
    int bound=eld_fdk_bind(library,&original);
    if(bound!=0) { printf("{\"stage\":\"bind\",\"accepted\":false,\"result\":%d}\n",bound); dlclose(library); return 4; }
    int descriptor=open(argv[2],O_WRONLY|O_CREAT|O_EXCL,0600);
    if(descriptor<0) { puts("{\"stage\":\"output\",\"accepted\":false,\"error\":\"Output must be new and writable\"}"); dlclose(library); return 7; }
    FILE* output=fdopen(descriptor,"wb");
    if(!output) { close(descriptor); dlclose(library); return 7; }
    const uint8_t fileHeader[]={'F','D','K','P','R','O','B','E','1',0xf8,0xe6,0x50,0};
    if(fwrite(fileHeader,1,sizeof(fileHeader),output)!=sizeof(fileHeader)) { fclose(output); dlclose(library); return 7; }
    EldFdkApi observed=original; observed.set=observed_set; observed.info=observed_info; observed.encode=observed_encode;
    EldFdkEncoder* encoder=nullptr; EldFdkConfig config={};
    int status=0;
    const uint32_t bitrates[]={128000,192000,256000};
    for(uint32_t bitrate: bitrates) {
        memset(&lastInfo,0,sizeof(lastInfo)); failedParameter=0; lastFdkError=0;
        int result=encoder?eld_fdk_update(encoder,bitrate,&config):eld_fdk_open(&observed,bitrate,&encoder,&config);
        if(result!=0) {
            printf("{\"stage\":\"configure\",\"accepted\":false,\"bitrate\":%u,\"result\":%d,\"fdk_error\":%d,\"failed_parameter\":%u,\"observed_frames\":%u,\"observed_channels\":%u,\"observed_asc_size\":%u,\"observed_asc\":\"",
                bitrate,result,lastFdkError,failedParameter,lastInfo.frameLength,lastInfo.inputChannels,lastInfo.confSize);
            for(uint32_t i=0;i<lastInfo.confSize && i<64;i++) printf("%02x",lastInfo.confBuf[i]);
            puts("\"}"); status=5; break;
        }
        uint32_t emitted=0,maximum=0,total=0; uint64_t maxCall=0,started=nanos();
        for(uint32_t frame=0;frame<64;frame++) {
            int16_t pcm[960]={};
            // First100ms is digital silence; following stereo tones never enter an audio output.
            if(frame>=10) for(uint32_t i=0;i<480;i++) {
                double sample=static_cast<double>((frame-10)*480+i);
                pcm[i*2]=static_cast<int16_t>(lround(4000*sin(6.283185307179586*997*sample/48000)));
                pcm[i*2+1]=static_cast<int16_t>(lround(4000*sin(6.283185307179586*1499*sample/48000)));
            }
            uint8_t bytes[321]; size_t count=0; uint64_t before=nanos();
            result=eld_fdk_encode(encoder,pcm,480,bytes,sizeof(bytes),&count);
            uint64_t elapsed=nanos()-before; if(elapsed>maxCall) maxCall=elapsed;
            if(result!=0) {
                printf("{\"stage\":\"encode\",\"accepted\":false,\"bitrate\":%u,\"frame\":%u,\"result\":%d,\"fdk_error\":%d}\n",bitrate,frame,result,lastFdkError);
                status=6; break;
            }
            if(count) {
                ++emitted; total+=static_cast<uint32_t>(count);
                if(count>maximum) maximum=static_cast<uint32_t>(count);
                if(!write_record(output,bitrate,frame,bytes,static_cast<uint32_t>(count))) { status=7; break; }
            }
        }
        if(status) break;
        if(emitted==0) { puts("{\"stage\":\"encode\",\"accepted\":false,\"error\":\"No encoded output\"}"); status=6; break; }
        printf("{\"stage\":\"encode\",\"accepted\":true,\"bitrate\":%u,\"sample_rate\":%u,\"channels\":%u,\"frames\":%u,\"input_sample_bytes\":%u,\"asc\":\"%02x%02x%02x%02x\",\"input_blocks\":64,\"nonempty_aus\":%u,\"max_au_bytes\":%u,\"total_au_bytes\":%u,\"elapsed_ns\":%llu,\"max_encode_call_ns\":%llu}\n",
            bitrate,config.sample_rate,config.channels,config.frame_samples,config.pcm_sample_bytes,
            config.asc[0],config.asc[1],config.asc[2],config.asc[3],emitted,maximum,total,
            static_cast<unsigned long long>(nanos()-started),static_cast<unsigned long long>(maxCall)); fflush(stdout);
        result=eld_fdk_reset(encoder);
        if(result!=0) { printf("{\"stage\":\"reset\",\"accepted\":false,\"result\":%d}\n",result); status=5; break; }
    }
    eld_fdk_close(&encoder);
    if(fclose(output)!=0) status=7;
    dlclose(library); alarm(0);
    printf("{\"stage\":\"complete\",\"accepted\":%s,\"result\":%d,\"no_audio_or_bluetooth_calls\":true,\"decoder_or_a2dp_playback_proven\":false}\n",status?"false":"true",status);
    return status;
}
