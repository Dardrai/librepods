// SPDX-License-Identifier: GPL-3.0-or-later
#include "note8_adapter.h"
#include "encoder_interface.h"
#include "wire.h"
#include <array>
#include <atomic>
#include <condition_variable>
#include <cstring>
#include <memory>
#include <mutex>
#include <unordered_map>

namespace eld {
namespace {
constexpr size_t kMutex = 8;
constexpr size_t kIndex = 0x30;
constexpr size_t kPriority = 0x50;
constexpr size_t kDefaultPriority = 0x54;
constexpr size_t kConfigs[] = {0x58, 0x90, 0xc8, 0x100, 0x138, 0x170};
constexpr size_t kCurrent = kConfigs[0];
constexpr size_t kUser = kConfigs[4];
constexpr size_t kAudio = kConfigs[5];
constexpr size_t kOta = 0x1a8;
constexpr size_t kPeerCaps = 0x1bc;
constexpr size_t kPeerConfig = 0x1d0;
constexpr size_t kSource = 0x1e4;
constexpr size_t kVtableEntries = 9;
constexpr size_t kMaximumObjects = 32;
constexpr uintptr_t kAacVtable = 0xc81950;
constexpr uintptr_t kFactory = 0x53d554;
constexpr uintptr_t kAacFunctions[] = {
    0x543dac, 0x543db0, 0x48e924, 0x543e48, 0x53dd88,
    0x544700, 0x543ddc, 0x48e924, 0x545b9c};
enum Hook : size_t {
    Factory, Valid, Rtp, Name, TypeEquals, Equals, BitRate, Rate, Bits,
    Channels, Timestamp, Header, Encoder, Adjust, SourceIndex, IndexName,
    InitConfig, HookCount
};
void* originals[HookCount]{};
std::atomic<bool> prepared{false};
std::atomic<bool> enabled{false};
std::atomic<bool> quarantined{false};
std::atomic<size_t> object_count{0};
const uintptr_t* original_vtable = nullptr;
Note8MutexFn platform_lock = nullptr;
Note8MutexFn platform_unlock = nullptr;
struct Context {
    void* object;
    const uintptr_t* host_vtable;
    std::array<uintptr_t, kVtableEntries + 2> vtable;
    size_t readers = 0;
};
std::mutex registry_mutex;
std::condition_variable readers_finished;
std::unordered_map<const void*, std::shared_ptr<Context>> registry;
struct Lease {
    std::shared_ptr<Context> owned;
    explicit Lease(std::shared_ptr<Context> value) : owned(std::move(value)) {}
    Lease(const Lease&) = delete;
    Lease(Lease&&) = default;
    ~Lease() {
        if (!owned) return;
        std::lock_guard<std::mutex> lock(registry_mutex);
        if (--owned->readers == 0) readers_finished.notify_all();
    }
    explicit operator bool() const { return owned != nullptr; }
};

template<class T> T load(const void* object, size_t offset) {
    T result;
    std::memcpy(&result, static_cast<const uint8_t*>(object) + offset, sizeof(result));
    return result;
}
template<class T> void store(void* object, size_t offset, const T& value) {
    std::memcpy(static_cast<uint8_t*>(object) + offset, &value, sizeof(value));
}
template<class Fn> Fn original(Hook hook) {
    return reinterpret_cast<Fn>(originals[hook]);
}
Lease context(const void* object) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    const auto found = registry.find(object);
    if (found == registry.end()) return Lease(nullptr);
    ++found->second->readers;
    return Lease(found->second);
}
struct CodecLock {
    void* mutex;
    explicit CodecLock(void* object)
        : mutex(static_cast<uint8_t*>(object) + kMutex) { platform_lock(mutex); }
    ~CodecLock() { platform_unlock(mutex); }
};
bool handling() {
    return prepared.load() && (enabled.load() || object_count.load() != 0 || quarantined.load());
}
bool apple(const uint8_t* info) {
    return handling() && is_apple(info, kNote8CodecStorage);
}
bool compatible(const uint8_t* info) {
    uint8_t selected[kNote8CodecStorage]{};
    return select_configuration(info, kNote8CodecStorage, selected, sizeof(selected));
}
Note8CodecConfig fixed_config(int32_t priority) {
    return {kNote8EldSlot, priority, 2, 1, 2, 0, {0, 0, 0, 0}};
}
bool accepts(const Note8CodecConfig& config, bool require_type = true) {
    if ((require_type && config.codec_type != kNote8EldSlot) || config.codec_priority < -1 ||
        config.codec_priority > 1000000) return false;
    if (config.sample_rate && !(config.sample_rate & 2)) return false;
    if (config.bits_per_sample && !(config.bits_per_sample & 1)) return false;
    if (config.channel_mode && !(config.channel_mode & 2)) return false;
    for (const auto value : config.codec_specific) if (value) return false;
    return true;
}
bool select(void* self, const uint8_t* peer, bool capability, uint8_t* out) {
    if (quarantined.load() || !peer || !out) return false;
    uint8_t selected[kNote8CodecStorage]{};
    if (capability) {
        if (!select_configuration(peer, kNote8CodecStorage, selected, sizeof(selected)))
            return false;
    } else {
        if (!valid_configuration(peer, kNote8CodecStorage)) return false;
        std::memcpy(selected, peer, kCodecInfoSize);
    }
    const auto user = load<Note8CodecConfig>(self, kUser);
    const auto audio = load<Note8CodecConfig>(self, kAudio);
    if (!accepts(user) || !accepts(audio, false)) return false;
    const auto current = fixed_config(load<int32_t>(self, kPriority));
    store(self, kCurrent, current);
    std::memcpy(static_cast<uint8_t*>(self) + kOta, selected, sizeof(selected));
    std::memcpy(static_cast<uint8_t*>(self) + (capability ? kPeerCaps : kPeerConfig),
                peer, kNote8CodecStorage);
    store(self, kConfigs[1], current);
    store(self, kConfigs[3], current);
    std::memcpy(out, selected, sizeof(selected));
    return true;
}

void destroy(void* self, size_t slot) {
    std::shared_ptr<Context> owned;
    {
        std::unique_lock<std::mutex> lock(registry_mutex);
        const auto found = registry.find(self);
        if (found == registry.end()) return;
        owned = found->second;
        registry.erase(found);
        readers_finished.wait(lock, [&owned] { return owned->readers == 0; });
        object_count.fetch_sub(1);
    }
    // Host destruction owns its string, mutex and allocation; no NDK STL crosses this boundary.
    store(self, 0, owned->host_vtable);
    reinterpret_cast<void (*)(void*)>(owned->host_vtable[slot])(self);
}
void destructor(void* self) { destroy(self, 0); }
void deleting_destructor(void* self) { destroy(self, 1); }
bool marker(const void*) { return false; }
bool set_config(void* self, const uint8_t* peer, bool capability, uint8_t* out) {
    const auto owned = context(self);
    if (!owned) return false;
    CodecLock lock(self);
    return select(self, peer, capability, out);
}
bool set_user(void* self, const Note8CodecConfig& user, const Note8CodecConfig& audio,
              const void* peer_params, const uint8_t* peer, bool capability, uint8_t* out,
              bool* restart_input, bool* restart_output, bool* updated) {
    if (!restart_input || !restart_output || !updated) return false;
    *restart_input = *restart_output = *updated = false;
    const auto owned = context(self);
    Note8PeerParams decoded{};
    if (!owned || !out || !accepts(user) || !accepts(audio, false) ||
        (peer_params && !note8_peer_params(peer_params, &decoded))) return false;
    CodecLock lock(self);
    const auto previous_user = load<Note8CodecConfig>(self, kUser);
    const auto previous_audio = load<Note8CodecConfig>(self, kAudio);
    const auto previous_current = load<Note8CodecConfig>(self, kCurrent);
    const auto previous_ota = load<std::array<uint8_t, kNote8CodecStorage>>(self, kOta);
    store(self, kUser, user);
    store(self, kAudio, audio);
    if (!select(self, peer, capability, out)) {
        store(self, kUser, previous_user);
        store(self, kAudio, previous_audio);
        return false;
    }
    const auto current = load<Note8CodecConfig>(self, kCurrent);
    *restart_input = previous_current.sample_rate != current.sample_rate ||
                     previous_current.bits_per_sample != current.bits_per_sample ||
                     previous_current.channel_mode != current.channel_mode;
    *restart_output = std::memcmp(previous_ota.data(), out, kCodecInfoSize) != 0;
    *updated = *restart_input || *restart_output;
    return true;
}
bool set_peer(void* self, const uint8_t* peer) {
    const auto owned = context(self);
    if (quarantined.load() || !owned || !compatible(peer)) return false;
    CodecLock lock(self);
    if (quarantined.load()) return false;
    std::memcpy(static_cast<uint8_t*>(self) + kPeerCaps, peer, kNote8CodecStorage);
    const auto fixed = fixed_config(load<int32_t>(self, kPriority));
    store(self, kConfigs[1], fixed);
    store(self, kConfigs[3], fixed);
    return true;
}
bool init(void* self) { return !quarantined.load() && static_cast<bool>(context(self)); }
bool valid(const void* self) {
    const auto owned = context(self);
    if (quarantined.load() || !owned) return false;
    CodecLock lock(const_cast<void*>(self));
    return !quarantined.load() && load<int32_t>(self, kIndex) == kNote8EldSlot &&
           valid_configuration(static_cast<const uint8_t*>(self) + kOta, kNote8CodecStorage);
}
void debug(void*, int) {}

void* factory(int32_t index, int32_t priority) {
    const auto host = original<void* (*)(int32_t, int32_t)>(Factory);
    if (!host) return nullptr;
    if (index != kNote8EldSlot || !enabled.load()) return host(index, priority);
    if (quarantined.load() || !prepared.load() || priority < -1 || priority > 1000000) return nullptr;
    const int32_t effective_priority = priority == 0 ? 6001 : priority;
    void* object = host(1, effective_priority);
    if (!object) return nullptr;
    const auto vtable = load<const uintptr_t*>(object, 0);
    bool correct = vtable == original_vtable && load<int32_t>(object, kIndex) == 1 &&
                   load<uint8_t>(object, kSource) == 1;
    for (const auto offset : kConfigs)
        correct = correct && load<Note8CodecConfig>(object, offset).codec_type == 1;
    if (!correct || quarantined.load()) {
        // The pinned factory returned an unexpected object. Its own vtable retains ownership.
        if (vtable) reinterpret_cast<void (*)(void*)>(vtable[1])(object);
        return nullptr;
    }
    uint8_t selected[kNote8CodecStorage]{};
    if (!make_configuration(selected, sizeof(selected))) {
        reinterpret_cast<void (*)(void*)>(vtable[1])(object);
        return nullptr;
    }
    auto owned = std::make_shared<Context>();
    owned->object = object;
    owned->host_vtable = vtable;
    std::memcpy(owned->vtable.data(), vtable - 2, sizeof(owned->vtable));
    const uintptr_t callbacks[] = {
        reinterpret_cast<uintptr_t>(&destructor), reinterpret_cast<uintptr_t>(&deleting_destructor),
        reinterpret_cast<uintptr_t>(&marker), reinterpret_cast<uintptr_t>(&set_config),
        reinterpret_cast<uintptr_t>(&set_user), reinterpret_cast<uintptr_t>(&set_peer),
        reinterpret_cast<uintptr_t>(&init), reinterpret_cast<uintptr_t>(&valid),
        reinterpret_cast<uintptr_t>(&debug)};
    std::memcpy(owned->vtable.data() + 2, callbacks, sizeof(callbacks));
    bool inserted = false;
    {
        std::lock_guard<std::mutex> lock(registry_mutex);
        if (registry.size() < kMaximumObjects) {
            inserted = registry.emplace(object, owned).second;
            if (inserted) object_count.fetch_add(1);
        }
    }
    if (!inserted) {
        reinterpret_cast<void (*)(void*)>(vtable[1])(object);
        return nullptr;
    }
    store(object, kIndex, index);
    store(object, kPriority, effective_priority);
    store(object, kDefaultPriority, priority);
    const auto fixed = fixed_config(effective_priority);
    for (size_t i = 0; i < 4; ++i) store(object, kConfigs[i], fixed);
    Note8CodecConfig preference{};
    preference.codec_type = kNote8EldSlot;
    store(object, kUser, preference);
    store(object, kAudio, preference);
    std::memcpy(static_cast<uint8_t*>(object) + kOta, selected, sizeof(selected));
    std::memset(static_cast<uint8_t*>(object) + kPeerCaps, 0, kNote8CodecStorage * 2);
    store(object, 0, owned->vtable.data() + 2);
    return object;
}

bool codec_valid(const uint8_t* info) {
    if (apple(info)) return !quarantined.load() && compatible(info);
    const auto host = original<bool (*)(const uint8_t*)>(Valid);
    return host && host(info);
}
bool uses_rtp(bool protection, const uint8_t* info) {
    if (apple(info)) return compatible(info);
    const auto host = original<bool (*)(bool, const uint8_t*)>(Rtp);
    return host && host(protection, info);
}
const char* codec_name(const uint8_t* info) {
    if (apple(info)) return "Apple AAC-ELD";
    const auto host = original<const char* (*)(const uint8_t*)>(Name);
    return host ? host(info) : "UNKNOWN";
}
bool type_equals(const uint8_t* left, const uint8_t* right) {
    if (apple(left) || apple(right)) return apple(left) && apple(right);
    const auto host = original<bool (*)(const uint8_t*, const uint8_t*)>(TypeEquals);
    return host && host(left, right);
}
bool equals(const uint8_t* left, const uint8_t* right) {
    if (apple(left) || apple(right))
        return apple(left) && apple(right) && valid_configuration(left, kNote8CodecStorage) &&
               valid_configuration(right, kNote8CodecStorage) &&
               std::memcmp(left, right, kCodecInfoSize) == 0;
    const auto host = original<bool (*)(const uint8_t*, const uint8_t*)>(Equals);
    return host && host(left, right);
}
int bit_rate(const uint8_t* info) {
    if (apple(info)) return valid_configuration(info, kNote8CodecStorage)
                            ? static_cast<int>(bitrate(info, kNote8CodecStorage)) : -1;
    const auto host = original<int (*)(const uint8_t*)>(BitRate);
    return host ? host(info) : -1;
}
template<Hook hook, int value> int parameter(const uint8_t* info) {
    if (apple(info)) return compatible(info) ? value : -1;
    const auto host = original<int (*)(const uint8_t*)>(hook);
    return host ? host(info) : -1;
}
bool timestamp(const uint8_t* info, const uint8_t* data, uint32_t* out) {
    if (apple(info)) {
        if (!data || !out || !valid_configuration(info, kNote8CodecStorage)) return false;
        std::memcpy(out, data, sizeof(*out));
        return true;
    }
    const auto host = original<bool (*)(const uint8_t*, const uint8_t*, uint32_t*)>(Timestamp);
    return host && host(info, data, out);
}
struct BufferHeader { uint16_t event, length, offset, layer_specific; };
static_assert(sizeof(BufferHeader) == 8);
bool build_header(const uint8_t* info, void* buffer, uint16_t frames) {
    if (apple(info)) {
        if (quarantined.load() || !buffer || frames != 1 ||
            !valid_configuration(info, kNote8CodecStorage)) return false;
        const auto header = load<BufferHeader>(buffer, 0);
        return header.layer_specific == 1 && header.length > kAuHeaderSize &&
               header.length <= kAuHeaderSize + max_au_bytes(bitrate(info, kNote8CodecStorage));
    }
    const auto host = original<bool (*)(const uint8_t*, void*, uint16_t)>(Header);
    return host && host(info, buffer, frames);
}
const EncoderInterface* encoder(const uint8_t* info) {
    if (apple(info)) return !quarantined.load() && valid_configuration(info, kNote8CodecStorage)
                            ? encoder_interface() : nullptr;
    const auto host = original<const EncoderInterface* (*)(const uint8_t*)>(Encoder);
    return host ? host(info) : nullptr;
}
bool adjust(uint8_t* info) {
    if (apple(info)) return !quarantined.load() && valid_configuration(info, kNote8CodecStorage);
    const auto host = original<bool (*)(uint8_t*)>(Adjust);
    return host && host(info);
}
int32_t source_index(const uint8_t* info) {
    if (apple(info)) return compatible(info) ? kNote8EldSlot : 11;
    const auto host = original<int32_t (*)(const uint8_t*)>(SourceIndex);
    return host ? host(info) : 11;
}
const char* index_name(int32_t index) {
    if (handling() && index == kNote8EldSlot) return "Apple AAC-ELD";
    const auto host = original<const char* (*)(int32_t)>(IndexName);
    return host ? host(index) : "UNKNOWN";
}
bool init_config(int32_t index, void* config) {
    if (handling() && index == kNote8EldSlot) {
        uint8_t capabilities[kNote8CodecStorage]{};
        if (quarantined.load() || !config || !make_capabilities(capabilities, sizeof(capabilities)))
            return false;
        // AvdtpSepConfig begins with codec_info[20]; all later members stay host-owned.
        std::memcpy(config, capabilities, sizeof(capabilities));
        return true;
    }
    const auto host = original<bool (*)(int32_t, void*)>(InitConfig);
    return host && host(index, config);
}

const Note8HookSpec hooks[] = {
#include "note8_hook_specs.inc"
};
static_assert(sizeof(hooks) / sizeof(hooks[0]) == HookCount);

bool prepare(const uintptr_t* vtable, Note8MutexFn lock, Note8MutexFn unlock) {
    if (quarantined.load() || !vtable || !lock || !unlock || object_count.load()) return false;
    original_vtable = vtable;
    platform_lock = lock;
    platform_unlock = unlock;
    prepared.store(true);
    return true;
}
}

const Note8HookSpec* note8_hook_specs(size_t* count) {
    if (count) *count = HookCount;
    return hooks;
}
bool note8_prepare(uintptr_t image_base, Note8MutexFn lock, Note8MutexFn unlock) {
    if (quarantined.load() || !image_base || enabled.load() || object_count.load()) return false;
    prepared.store(false);
    const auto table = reinterpret_cast<const uintptr_t*>(image_base + kAacVtable);
    if (table[-2] || table[-1]) return false;
    for (size_t i = 0; i < kVtableEntries; ++i)
        if (table[i] != image_base + kAacFunctions[i]) return false;
    const auto host = originals[Factory] ? original<void* (*)(int32_t, int32_t)>(Factory)
                                        : reinterpret_cast<void* (*)(int32_t, int32_t)>(image_base + kFactory);
    // This exact build has no LC3 implementation in slot 5. Reject rather than replacing one.
    if (void* occupied = host(kNote8EldSlot, 0)) {
        const auto occupied_table = load<const uintptr_t*>(occupied, 0);
        if (occupied_table) reinterpret_cast<void (*)(void*)>(occupied_table[1])(occupied);
        return false;
    }
    return prepare(table, lock, unlock);
}
bool note8_set_enabled(bool value) {
    if (value) {
        if (quarantined.load() || !prepared.load()) return false;
        for (void* trampoline : originals) if (!trampoline) return false;
    }
    enabled.store(value);
    return true;
}
void note8_quarantine() {
    quarantined.store(true);
    enabled.store(false);
}
bool note8_is_quarantined() { return quarantined.load(); }
bool note8_is_prepared() { return prepared.load(); }
size_t note8_active_objects() { return object_count.load(); }
bool note8_codec_info(const void* codec, uint8_t* out, size_t size) {
    const auto owned = context(codec);
    if (!owned || !out || size < kNote8CodecStorage) return false;
    CodecLock lock(const_cast<void*>(codec));
    const auto info = static_cast<const uint8_t*>(codec) + kOta;
    if (!valid_configuration(info, kNote8CodecStorage)) return false;
    std::memcpy(out, info, kNote8CodecStorage);
    return true;
}
bool note8_peer_params(const void* opaque, Note8PeerParams* out) {
    if (!opaque || !out) return false;
    const auto bytes = static_cast<const uint8_t*>(opaque);
    if (bytes[0] > 1 || bytes[1] > 1) return false;
    uint16_t mtu;
    std::memcpy(&mtu, bytes + 2, sizeof(mtu));
    if (mtu < 48) return false;
    *out = {bytes[0] != 0, bytes[1] != 0, mtu};
    return true;
}
#ifdef ELD_ADAPTER_TESTING
bool note8_prepare_for_test(const uintptr_t* vtable, Note8MutexFn lock, Note8MutexFn unlock) {
    return prepare(vtable, lock, unlock);
}
bool note8_owns_for_test(const void* object) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    return registry.find(object) != registry.end();
}
void note8_reset_for_test() {
    if (object_count.load()) return;
    enabled.store(false);
    prepared.store(false);
    quarantined.store(false);
    original_vtable = nullptr;
    platform_lock = platform_unlock = nullptr;
    for (auto& trampoline : originals) trampoline = nullptr;
}
#endif
}
