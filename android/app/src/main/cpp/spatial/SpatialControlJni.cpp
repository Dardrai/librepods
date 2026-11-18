#include "SpatialRenderControlWire.h"
#include "FrozenApiPins.h"
#include "SpatialRequestFence.h"
#include <aidl/android/hardware/media/c2/IComponentStore.h>
#include <android/binder_manager.h>
#include <dlfcn.h>
#include <jni.h>
#include <unistd.h>
#include <sys/random.h>
#include <time.h>
#include <condition_variable>
#include <thread>
#include <mutex>
#include <memory>
#include <chrono>

namespace api=aidl::android::hardware::media::c2;
namespace wire=dolby_pose::spatial_control;
namespace {
uint64_t nowNs() {
    timespec t{};
    if(clock_gettime(CLOCK_BOOTTIME,&t)||t.tv_sec<0||t.tv_nsec<0||t.tv_nsec>=1000000000)return 0;
    const auto n=uint64_t(t.tv_sec)*1000000000ULL+uint64_t(t.tv_nsec);
    return n&&n<=uint64_t(INT64_MAX)?n:0;
}
struct Request {
    uint64_t serial=0,choice=0;
    wire::Scope scope;
    uint32_t choices=0;
    bool release=false;
};
class Client {
    std::mutex lock_;
    std::condition_variable wake_;
    Request pending_;
    uint64_t completed_=0,updated_=0,lastConnect_=0;
    SpatialRequestFence fence_;
    int error_=1;
    bool closed_=false,connected_=false,owned_=false;
    bool cachedOwnerMatch_=false;
    wire::Status cached_;
    wire::Command owner_;
    std::shared_ptr<api::IComponentStore> store_;
    std::shared_ptr<api::IConfigurable> configurable_;
    template<class T> bool matches(const std::shared_ptr<T>& x) {
        int32_t v=0;std::string h;
        return x&&x->asBinder().get()&&AIBinder_isRemote(x->asBinder().get())&&
            x->getInterfaceVersion(&v).isOk()&&v==pose_client::kInterfaceVersion&&
            x->getInterfaceHash(&h).isOk()&&h==pose_client::kInterfaceHash;
    }
    bool connect() {
        configurable_.reset();store_.reset();owned_=false;
        static const auto checkService = reinterpret_cast<decltype(&AServiceManager_checkService)>(
            dlsym(RTLD_DEFAULT, "AServiceManager_checkService"));
        if (!checkService) return false;
        ndk::SpAIBinder binder(checkService(pose_client::kEndpoint));
        if(!binder.get()||!AIBinder_isRemote(binder.get()))return false;
        store_=api::IComponentStore::fromBinder(binder);
        if(!matches(store_)||!store_->getConfigurable(&configurable_).isOk()||!matches(configurable_))return false;
        bool command=false,status=false;
        for(int32_t start=0;start<1024;start+=128) {
            std::vector<api::ParamDescriptor> values;
            if(!configurable_->querySupportedParams(start,128,&values).isOk()||values.size()>128)return false;
            for(const auto& d:values) {
                if(d.name.size()>256)return false;
                if(d.name==wire::kCommandName) {if(command||uint32_t(d.index)!=wire::kCommandIndex)return false;command=true;}
                if(d.name==wire::kStatusName) {if(status||uint32_t(d.index)!=wire::kStatusIndex)return false;status=true;}
            }
            if(values.size()<128)return command&&status;
        }
        return false;
    }
    bool query(wire::Status* out) {
        api::IConfigurable::QueryResult result;
        if(!configurable_||!configurable_->query({int32_t(wire::kStatusIndex)},true,&result).isOk()||
            result.status.status!=0||result.params.params.size()!=wire::kStatusBytes)return false;
        return wire::decode({result.params.params.data(),result.params.params.size()},out);
    }
    bool current(uint64_t serial) {
        std::lock_guard<std::mutex> hold(lock_);
        return !closed_&&fence_.current(serial);
    }
    bool command(wire::Command& value,uint64_t serial) {
        if(!current(serial))return false;
        const auto bytes=wire::encode(value);
        api::Params input;input.params.assign(bytes.begin(),bytes.end());
        api::IConfigurable::ConfigResult result;
        if(!configurable_||!configurable_->config(input,true,&result).isOk()||result.status.status!=0||
            !result.failures.empty()||result.params.params.size()!=wire::kCommandBytes)return false;
        wire::Command ack;
        if(!wire::decode({result.params.params.data(),result.params.params.size()},&ack,true))return false;
        auto expected=value;expected.result=wire::Result::Accepted;
        return wire::encode(ack)==wire::encode(expected);
    }
    bool apply(const Request& request) {
        if(request.release) {
            if(!owned_)return true;
            if(owner_.scope.generation!=request.scope.generation)return true;
            auto c=owner_;c.opcode=wire::Opcode::Release;++c.sequence;
            c.choiceEpoch=std::max(c.choiceEpoch,request.choice);
            const bool ok=command(c,request.serial);owned_=false;owner_={};return ok;
        }
        if(!owned_||!(owner_.scope==request.scope)) {
            if(owned_) {
                auto release=owner_;release.opcode=wire::Opcode::Release;++release.sequence;
                if(!command(release,request.serial))return false;
                owned_=false;
            }
            if(!current(request.serial))return false;
            owner_={};owner_.opcode=wire::Opcode::Acquire;
            if(getrandom(owner_.nonce.data(),owner_.nonce.size(),0)!=ssize_t(owner_.nonce.size()))return false;
            owner_.controlGeneration=nowNs();owner_.sequence=1;
        } else {owner_.opcode=wire::Opcode::Set;++owner_.sequence;}
        owner_.scope=request.scope;owner_.choiceEpoch=request.choice;owner_.choices=request.choices;
        owner_.requiredCapabilities=wire::kCapabilities;
        const bool ok=command(owner_,request.serial);owned_=ok;return ok;
    }
    void run() {
        for(;;) {
            Request request;
            {
                std::unique_lock<std::mutex> hold(lock_);
                wake_.wait_for(hold,std::chrono::milliseconds(250),[&]{return closed_||pending_.serial>completed_;});
                if(closed_)return;
                if(pending_.serial>completed_)request=pending_;
            }
            const auto now=nowNs();
            if(!connected_&&(lastConnect_==0||now-lastConnect_>=1000000000ULL)) {
                lastConnect_=now;connected_=connect();
            }
            int error=connected_?0:1;
            if(request.serial&&connected_&&current(request.serial)&&!apply(request)) {error=2;owned_=false;}
            wire::Status status;
            if(connected_&&!query(&status)) {error=3;connected_=false;owned_=false;status={};}
            if(owned_&&(!(status.flags&wire::Owned)||status.ownerPid!=uint32_t(getpid())||
                status.controlGeneration!=owner_.controlGeneration||status.nonce!=owner_.nonce))owned_=false;
            {
                std::lock_guard<std::mutex> hold(lock_);
                cached_=status;error_=error;updated_=nowNs();
                cachedOwnerMatch_=owned_&&status.controlGeneration==owner_.controlGeneration&&status.nonce==owner_.nonce;
                if(request.serial)completed_=request.serial;
            }
        }
    }
public:
    Client() {std::thread([this]{run();}).detach();}
    uint64_t submit(Request r) {
        std::lock_guard<std::mutex> hold(lock_);
        if(closed_)return 0;
        r.serial=fence_.submit(r.scope.generation,r.choice,r.release);
        if(!r.serial)return 0;
        pending_=r;wake_.notify_one();return r.serial;
    }
    std::array<jlong,36> snapshot() {
        std::lock_guard<std::mutex> hold(lock_);const auto&s=cached_;
        return {1,jlong(updated_),jlong(error_==0),jlong(error_),jlong(s.flags),jlong(s.capabilities),
            jlong(s.desired),jlong(s.choiceEpoch),jlong(s.ownerPid),jlong(s.scope.routeId),jlong(s.scope.generation),
            jlong(s.controlGeneration),jlong(s.sequence),jlong(s.appliedChoiceEpoch),jlong(s.actual),jlong(s.codec),
            jlong(s.outputFrames),jlong(s.renderedBoottimeNs),jlong(s.committedBoottimeNs),s.inputPtsUs,s.outputPtsUs,
            jlong(s.failure),jlong(completed_),jlong(fence_.serial),jlong(pending_.choice),jlong(pending_.choices),
            jlong(getpid()),jlong(s.nativeSource),jlong(s.nativeMaximum),jlong(s.nativeMod[0]),jlong(s.nativeMod[1]),
            jlong(s.nativeMod[2]),jlong(s.pregain),jlong(s.systemGain),jlong(s.nativeDax),jlong(cachedOwnerMatch_)};
    }
};
// A stalled Binder call owns one quarantined lane for this Root process; never spawn retries.
Client* instance() {static Client* value=new Client;return value;}
bool caller(JNIEnv* env,jclass type) {
    if(getuid()!=0||!type)return false;
    jclass expected=env->FindClass("me/kavishdevar/librepods/shizuku/SpatialNativeClient");
    const bool ok=expected&&env->IsSameObject(expected,type);
    if(expected)env->DeleteLocalRef(expected);return ok;
}
}
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*,void*) {return getuid()==0?JNI_VERSION_1_6:JNI_ERR;}
extern "C" JNIEXPORT jlong JNICALL Java_me_kavishdevar_librepods_shizuku_SpatialNativeClient_nativeRequest(
    JNIEnv*env,jclass type,jint route,jlong generation,jbyteArray mac,jlong choice,jint choices) {
    if(!caller(env,type)||route<=0||generation<=0||choice<=0||!wire::choicesValid(uint32_t(choices))||
        !mac||env->GetArrayLength(mac)!=6)return 0;
    Request r;r.scope.routeId=uint32_t(route);r.scope.generation=uint64_t(generation);r.choice=uint64_t(choice);r.choices=uint32_t(choices);
    env->GetByteArrayRegion(mac,0,6,reinterpret_cast<jbyte*>(r.scope.mac.data()));
    if(env->ExceptionCheck()||!wire::valid(r.scope)||r.scope.generation>nowNs())return 0;
    return jlong(instance()->submit(r));
}
extern "C" JNIEXPORT jlong JNICALL Java_me_kavishdevar_librepods_shizuku_SpatialNativeClient_nativeRelease(JNIEnv*env,jclass type,jlong choice,jlong route) {
    if(!caller(env,type)||choice<=0||route<=0)return 0;Request r;r.release=true;r.choice=uint64_t(choice);
    r.scope.generation=uint64_t(route);return jlong(instance()->submit(r));
}
extern "C" JNIEXPORT jlongArray JNICALL Java_me_kavishdevar_librepods_shizuku_SpatialNativeClient_nativeSnapshot(JNIEnv*env,jclass type) {
    if(!caller(env,type))return nullptr;const auto value=instance()->snapshot();
    auto out=env->NewLongArray(value.size());if(out)env->SetLongArrayRegion(out,0,value.size(),value.data());return out;
}
