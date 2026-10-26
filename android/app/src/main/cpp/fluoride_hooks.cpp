/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

#include <android/log.h>
#include <android/api-level.h>
#include <cstring>
#include <string>
#include <vector>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <elf.h>
#include <atomic>
#include <jni.h>
#include "helpers.h"

#include "fluoride_hooks.h"
#include "identity_hook_policy.h"
#include "eld/runtime.h"
#include "eld/encoder_interface.h"
#include "eld/pixel17_game_runtime.h"
#include <time.h>

static std::atomic<bool> enableSdpHook(false);

static HookFunType hook_func = nullptr;

static uint8_t (*original_l2c_fcr_chk_chan_modes)(void *) = nullptr;

uint8_t fake_l2c_fcr_chk_chan_modes(void *p_ccb) {
    LOGD("fake_l2c_fcr_chk_chan_modes called");
    [[maybe_unused]] uint8_t orig = 0;
    if (original_l2c_fcr_chk_chan_modes)
        orig = original_l2c_fcr_chk_chan_modes(p_ccb);

    LOGD("fake_l2c_fcr_chk_chan_modes: orig = %d, returning 1", orig);
    return 1;
}

static tBTA_STATUS (*original_BTA_DmSetLocalDiRecord)(tSDP_DI_RECORD *, uint32_t *) = nullptr;

tBTA_STATUS fake_BTA_DmSetLocalDiRecord(tSDP_DI_RECORD *p_device_info, uint32_t *p_handle) {

    LOGD("fake_BTA_DmSetLocalDiRecord called");

    const auto status = setLocalDiRecord(p_device_info, p_handle,
                                        enableSdpHook.load(std::memory_order_relaxed),
                                        original_BTA_DmSetLocalDiRecord);
    LOGD("fake_BTA_DmSetLocalDiRecord: returning status %d", status);
    return status;
}


static bool hookLibrary(const char *libname) {
    LOGD("hookLibrary called with libname: %s", libname);

    if (!hook_func) {
        LOGE("hook_func not initialized");
        return false;
    }

    std::string path;
    if (!getLibraryPath(libname, path)) {
        LOGE("Failed to locate %s", libname);
        return false;
    }
    LOGD("hookLibrary: located path: %s", path.c_str());

    int fd = open(path.c_str(), O_RDONLY);
    if (fd < 0) {
        LOGE("hookLibrary: open failed");
        return false;
    }

    struct stat st{};
    if (fstat(fd, &st) != 0) {
        LOGE("hookLibrary: fstat failed");
        close(fd);
        return false;
    }
    LOGD("hookLibrary: opened file, size: %lld", (long long) st.st_size);

    std::vector<uint8_t> file(st.st_size);
    read(fd, file.data(), st.st_size);
    close(fd);

    auto *eh = reinterpret_cast<Elf64_Ehdr *>(file.data());
    auto *shdr = reinterpret_cast<Elf64_Shdr *>(
            file.data() + eh->e_shoff);

    const char *shstr = reinterpret_cast<const char *>(
            file.data() + shdr[eh->e_shstrndx].sh_offset);

    uint64_t chk_offset = 0;
    uint64_t sdp_offset = 0;

    for (int i = 0; i < eh->e_shnum; ++i) {
        if (!strcmp(shstr + shdr[i].sh_name, ".gnu_debugdata")) {
            LOGD("hookLibrary: found .gnu_debugdata section");

            std::vector<uint8_t> compressed(file.begin() + shdr[i].sh_offset,
                                            file.begin() + shdr[i].sh_offset + shdr[i].sh_size);

            std::vector<uint8_t> decompressed;

            if (decompressXZ(compressed.data(), compressed.size(), decompressed)) {
                chk_offset = findSymbolOffset(decompressed, "l2c_fcr_chk_chan_modes");
                sdp_offset = findSymbolOffset(decompressed, "BTA_DmSetLocalDiRecord");
            } else {
                LOGE("debugdata decompress failed");
            }

            break;
        }
    }

    if (!chk_offset) {
        LOGD("fallback dynsym chk");
        chk_offset = findSymbolOffsetDynsym(file, "l2c_fcr_chk_chan_modes");
    }

    if (!sdp_offset) {
        LOGD("fallback dynsym sdp");
        sdp_offset = findSymbolOffsetDynsym(file, "BTA_DmSetLocalDiRecord");
    }

    uintptr_t base = getModuleBase(libname);

    if (!base) {
        LOGE("hookLibrary: getModuleBase failed");
        return false;
    }

    if (chk_offset) {
        void *target = reinterpret_cast<void *>(base + chk_offset);
        hook_func(target, (void *) fake_l2c_fcr_chk_chan_modes,
                  (void **) &original_l2c_fcr_chk_chan_modes);
        LOGI("hooked chk");
    }

    if (sdp_offset) {
        void *target = reinterpret_cast<void *>(base + sdp_offset);
        hook_func(target, (void *) fake_BTA_DmSetLocalDiRecord,
                  (void **) &original_BTA_DmSetLocalDiRecord);
        LOGI("hooked sdp");
    }

    return chk_offset || sdp_offset;
}

static void on_library_loaded(const char *name, void *handle) {
    if (!name) return;
    eld::runtime_library_loaded(name, handle);
    LOGD("on_library_loaded called with name: %s", name);

    const int apiLevel = android_get_device_api_level();
    if (!useLegacyBluetoothIdentityHooks(apiLevel)) {
        if (strstr(name, "libbluetooth_jni.so") || strstr(name, "libbluetooth_qti.so"))
            LOGI("Legacy SDP/FCR hooks disabled for SDK %d; keeping platform Bluetooth handling", apiLevel);
        return;
    }

    if (strstr(name, "libbluetooth_jni.so")) {
        LOGI("Bluetooth JNI loaded");
        hookLibrary("libbluetooth_jni.so");
    }

    if (strstr(name, "libbluetooth_qti.so")) {
        LOGI("Bluetooth QTI loaded");
        hookLibrary("libbluetooth_qti.so");
    }
}

extern "C" [[gnu::visibility("default")]]
[[gnu::used]]
NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    LOGD("native_init called with entries: %p", entries);
    if (!entries || !entries->hook_func) return nullptr;
    hook_func = (HookFunType) entries->hook_func;
    // Keep the existing identity hook independent of the optional codec ABI.
    // Vector v2.2 native_api.{h,cpp}: API 2 retains the typed hook/unhook prefix.
    if (entries->version == 2)
        eld::runtime_init(hook_func, reinterpret_cast<eld::RuntimeUnhook>(entries->unhook_func));
    else LOGE("ELD native hook API version unsupported: %u", entries->version);
    LOGI("LibrePodsNativeHook initialized, sdp hook enabled: %d",
         enableSdpHook.load(std::memory_order_relaxed));
    return on_library_loaded;
}

extern "C" JNIEXPORT void JNICALL
Java_me_kavishdevar_librepods_utils_NativeBridge_setSdpHook(JNIEnv *, jobject,
                                                            jboolean enable) {
    LOGD("setSdpHook called with enable: %d", enable);
    enableSdpHook.store(enable, std::memory_order_relaxed);

    LOGI("sdp hook enabled: %d", enable);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_kavishdevar_librepods_utils_NativeBridge_isEldEncoderFailed(JNIEnv*, jobject) {
    return eld::encoder_status().failed ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jlong JNICALL
Java_me_kavishdevar_librepods_utils_NativeBridge_getEldEncoderGeneration(JNIEnv*, jobject) {
    return static_cast<jlong>(eld::encoder_status().generation);
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_me_kavishdevar_librepods_utils_NativeBridge_getEldEncoderPeer(JNIEnv* env, jobject) {
    const auto snapshot = eld::encoder_status();
    bool known = false;
    for (const auto byte : snapshot.peer) known = known || byte != 0;
    const jsize count = known && (snapshot.active || snapshot.failed) ? 6 : 0;
    auto out = env->NewByteArray(count);
    if (out && count) env->SetByteArrayRegion(out, 0, count,
        reinterpret_cast<const jbyte*>(snapshot.peer));
    return out;
}

namespace {
// Version1 matches NativeGameSnapshot. Fixed scalars only: no native pointers cross JNI.
jlongArray game_snapshot(JNIEnv* env, uint64_t submitted = 0) {
    const auto s = eld::pixel17_game_status();
    const auto c = eld::encoder_clock_status();
    const auto& h = s.host;
    jlong values[79] = {
        1, s.supported, s.busy, static_cast<jlong>(s.phase), static_cast<jlong>(s.operation),
        static_cast<jlong>(s.serial), static_cast<jlong>(s.lease_owner), static_cast<jlong>(s.error),
        static_cast<jlong>(s.deadline_ns), s.baseline_known, s.baseline_slots,
        s.current_known, s.current_slots, s.restore_pending, s.cancelled,
        static_cast<jlong>(s.owner.media_generation), static_cast<jlong>(s.owner.encoder_generation),
        s.owner.acl_handle, 0,0,0,0,0,0,
        static_cast<jlong>(h.media_generation), static_cast<jlong>(h.encoder_generation),
        static_cast<jlong>(h.started_ns), static_cast<jlong>(h.delay_serial),
        static_cast<jlong>(h.delay_received_ns), static_cast<jlong>(h.observed_ns),
        h.media_cid, h.acl_handle, h.delay_tenths, h.bta_handle, h.avdt_handle,
        (h.route_valid ? 1 : 0) | (h.streaming ? 2 : 0) | (h.encoder_ready ? 4 : 0) |
            (h.reporting ? 8 : 0) | (h.delay_valid ? 16 : 0) | (h.native_sco ? 32 : 0),
        0,0,0,0,0,0, static_cast<jlong>(submitted),
        static_cast<jlong>(c.phase), static_cast<jlong>(c.serial), c.effective_interval_tenths,
        c.target_drift_ms, c.signed_progress_frames, static_cast<jlong>(c.encoder_generation),
        static_cast<jlong>(c.actual_frames), static_cast<jlong>(c.nominal_frames),
        static_cast<jlong>(c.started_us), static_cast<jlong>(c.deadline_us),
        s.last_opcode, s.controller_status, s.completed_handle,
        s.holding, s.clock_pending, static_cast<jlong>(s.lease_deadline_ns),
        static_cast<jlong>(s.clock_request_serial), static_cast<jlong>(s.clock_observed_ns),
        s.clock.version, static_cast<jlong>(s.clock.phase), static_cast<jlong>(s.clock.serial),
        static_cast<jlong>(s.clock.encoder_generation), 0,0,0,0,0,0,
        s.clock.requested_interval_tenths, s.clock.effective_interval_tenths, s.clock.target_drift_ms,
        static_cast<jlong>(s.clock.actual_frames), static_cast<jlong>(s.clock.nominal_frames),
        s.clock.signed_progress_frames, static_cast<jlong>(s.clock.started_us), static_cast<jlong>(s.clock.deadline_us)
    };
    for (size_t i = 0; i < 6; ++i) {
        values[18+i] = s.owner.peer[i]; values[36+i] = h.peer[i]; values[65+i] = s.clock.peer[i];
    }
    auto out = env->NewLongArray(79);
    if (out) env->SetLongArrayRegion(out, 0, 79, values);
    return out;
}
bool game_owner(JNIEnv* env,jbyteArray peer,jlong media,jlong encoder,jint acl,eld::Pixel17HciOwner* out) {
    if(!out || media<=0 || encoder<=0 || acl<0 || acl>0xeff || !peer || env->GetArrayLength(peer)!=6) return false;
    env->GetByteArrayRegion(peer,0,6,reinterpret_cast<jbyte*>(out->peer));
    if(env->ExceptionCheck()) return false;
    out->media_generation=static_cast<uint64_t>(media);
    out->encoder_generation=static_cast<uint64_t>(encoder);
    out->acl_handle=static_cast<uint16_t>(acl); return true;
}
bool game_lease(jlong lease,jlong serial) {
    if(lease<=0 || serial<=0) return false;
    const auto state=eld::pixel17_game_status();
    return state.lease_owner==static_cast<uint64_t>(lease) && state.serial==static_cast<uint64_t>(serial);
}
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_me_kavishdevar_librepods_utils_NativeBridge_queryNativeGame(JNIEnv* env, jobject) {
    eld::pixel17_game_refresh();
    return game_snapshot(env);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_me_kavishdevar_librepods_utils_NativeBridge_submitNativeGame(JNIEnv* env, jobject,
        jint operation, jlong lease, jbyteArray peer, jlong media, jlong encoder, jint acl) {
    eld::Pixel17HciOwner owner{};
    if(operation<0 || operation>2 || lease<=0 || !game_owner(env,peer,media,encoder,acl,&owner))
        return env->ExceptionCheck() ? nullptr : env->NewLongArray(0);
    timespec now{};
    if (clock_gettime(CLOCK_BOOTTIME, &now) != 0 || now.tv_sec < 0 || now.tv_nsec<0 || now.tv_nsec>=1000000000 ||
        static_cast<uint64_t>(now.tv_sec)>(UINT64_MAX-UINT64_C(6000000000)-now.tv_nsec)/UINT64_C(1000000000))
        return env->NewLongArray(0);
    const uint64_t ns = static_cast<uint64_t>(now.tv_sec) * UINT64_C(1000000000) + now.tv_nsec;
    const auto op=operation==0 ? eld::Pixel17GameOperation::Read : operation==1
        ? eld::Pixel17GameOperation::Roundtrip : eld::Pixel17GameOperation::Prepare;
    const auto serial = eld::pixel17_game_submit(op, static_cast<uint64_t>(lease), owner,
        ns/1000*1000 + UINT64_C(6000000000));
    return game_snapshot(env, serial);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_me_kavishdevar_librepods_utils_NativeBridge_pollNativeGame(JNIEnv* env, jobject,
        jlong lease, jlong serial) {
    if(!game_lease(lease,serial)) return env->NewLongArray(0);
    // Only the authenticated transport's owner+serial POLL renews a held lease.
    // QUERY, diagnostic snapshots and failed/stale polls never extend it.
    eld::pixel17_game_heartbeat(static_cast<uint64_t>(lease),static_cast<uint64_t>(serial));
    return game_snapshot(env);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_me_kavishdevar_librepods_utils_NativeBridge_restoreNativeGame(JNIEnv* env,jobject,jlong lease,jlong serial) {
    if(!game_lease(lease,serial)) return env->NewLongArray(0);
    eld::pixel17_game_restore(static_cast<uint64_t>(lease),static_cast<uint64_t>(serial));
    return game_snapshot(env);
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_me_kavishdevar_librepods_utils_NativeBridge_beginNativeGameClock(JNIEnv* env,jobject,
        jlong lease,jlong serial,jbyteArray peer,jlong media,jlong encoder,jint acl,jint interval,jint drift) {
    eld::Pixel17HciOwner owner{};
    if(!game_lease(lease,serial) || (interval!=9 && interval!=11) || drift<1 || drift>500 ||
       !game_owner(env,peer,media,encoder,acl,&owner))
        return env->ExceptionCheck() ? nullptr : env->NewLongArray(0);
    const auto request=eld::pixel17_game_clock_begin(static_cast<uint64_t>(lease),static_cast<uint64_t>(serial),
        owner,static_cast<uint32_t>(interval),static_cast<uint32_t>(drift));
    return request ? game_snapshot(env,request) : env->NewLongArray(0);
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_me_kavishdevar_librepods_utils_NativeBridge_cancelNativeGameClock(JNIEnv* env,jobject,jlong lease,jlong serial) {
    if(!game_lease(lease,serial)) return env->NewLongArray(0);
    eld::pixel17_game_clock_cancel(static_cast<uint64_t>(lease),static_cast<uint64_t>(serial));
    return game_snapshot(env);
}

extern "C" JNIEXPORT void JNICALL
Java_me_kavishdevar_librepods_utils_NativeBridge_cancelNativeGame(JNIEnv*, jobject, jlong lease) {
    if (lease > 0) eld::pixel17_game_cancel_owner(static_cast<uint64_t>(lease));
}
