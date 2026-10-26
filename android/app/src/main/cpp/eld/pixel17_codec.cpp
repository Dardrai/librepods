// SPDX-License-Identifier: GPL-3.0-or-later
#include "pixel17_codec.h"
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
constexpr size_t kMutex = 8, kIndex = 0x30, kCodecId = 0x38, kName = 0x40;
constexpr size_t kPriority = 0x58, kDefaultPriority = 0x5c;
constexpr size_t kCurrent = 0x60, kLocal = 0xa0, kSelectable = 0xe0;
constexpr size_t kUser = 0x120, kAudio = 0x160;
constexpr size_t kOta = 0x1a0, kPeerCaps = 0x1cd, kPeerConfig = 0x1fa, kSource = 0x227;
constexpr size_t kMaximumObjects = 32;
constexpr uint8_t kUnsupportedConfiguration = 0x29;

std::mutex registry_mutex;
std::condition_variable readers_finished;
Pixel17CodecBindings host{};
bool prepared = false;
size_t reserved_objects = 0;
std::atomic<bool> quarantined{false};

struct Context {
    void* object;
    std::array<uintptr_t, kPixel17CodecVtableEntries + 2> vtable{};
    size_t readers = 0;
};
std::unordered_map<const void*, std::shared_ptr<Context>> registry;

template<class T> T load(const void* bytes, size_t offset) {
    T out;
    std::memcpy(&out, static_cast<const uint8_t*>(bytes) + offset, sizeof(out));
    return out;
}
template<class T> void store(void* bytes, size_t offset, const T& value) {
    std::memcpy(static_cast<uint8_t*>(bytes) + offset, &value, sizeof(value));
}

struct Lease {
    std::shared_ptr<Context> context;
    explicit Lease(std::shared_ptr<Context> owned) : context(std::move(owned)) {}
    Lease(const Lease&) = delete;
    Lease(Lease&&) = default;
    ~Lease() {
        if (!context) return;
        std::lock_guard<std::mutex> guard(registry_mutex);
        if (--context->readers == 0) readers_finished.notify_all();
    }
    explicit operator bool() const { return context != nullptr; }
};
Lease acquire(const void* object) {
    std::lock_guard<std::mutex> guard(registry_mutex);
    const auto found = registry.find(object);
    if (found == registry.end()) return Lease(nullptr);
    ++found->second->readers;
    return Lease(found->second);
}
struct CodecLock {
    void* mutex;
    explicit CodecLock(const void* object)
        : mutex(const_cast<uint8_t*>(static_cast<const uint8_t*>(object)) + kMutex) { host.lock(mutex); }
    ~CodecLock() { host.unlock(mutex); }
};

bool same_bindings(const Pixel17CodecBindings& a, const Pixel17CodecBindings& b) {
    return a.allocate == b.allocate && a.release == b.release &&
        a.aac_source_construct == b.aac_source_construct &&
        a.aac_source_vtable == b.aac_source_vtable && a.lock == b.lock &&
        a.unlock == b.unlock && a.assign_short == b.assign_short && a.debug == b.debug;
}
Pixel17NativeConfig fixed_config(int32_t index, int32_t priority) {
    return {index, priority, 2, 1, 2, 0, {0, 0, 0, 0}, 0};
}
bool accepts(const Pixel17NativeConfig& config, int32_t index, bool type_required) {
    if ((type_required && config.codec_type != index) || config.codec_priority < -1 ||
        config.codec_priority > 1000000 ||
        (config.sample_rate && !(config.sample_rate & 2)) ||
        (config.bits_per_sample && !(config.bits_per_sample & 1)) ||
        (config.channel_mode && !(config.channel_mode & 2))) return false;
    for (auto value : config.codec_specific) if (value) return false;
    // Padding and opaque_tail are never assigned a guessed semantic or normalized in caller records.
    return true;
}

void destroy(void* object, size_t slot) {
    std::shared_ptr<Context> owned;
    {
        std::unique_lock<std::mutex> guard(registry_mutex);
        const auto found = registry.find(object);
        if (found == registry.end()) return;
        owned = found->second;
        registry.erase(found);
        readers_finished.wait(guard, [&] { return owned->readers == 0; });
    }
    store(object, 0, host.aac_source_vtable);
    reinterpret_cast<void (*)(void*)>(host.aac_source_vtable[slot])(object);
    std::lock_guard<std::mutex> guard(registry_mutex);
    --reserved_objects;
}
void destructor(void* object) { destroy(object, 0); }
void deleting_destructor(void* object) { destroy(object, 1); }

int track_bitrate(const void* object) {
    const auto lease = acquire(object);
    if (!lease || quarantined.load()) return 0;
    CodecLock guard(object);
    return static_cast<int>(bitrate(static_cast<const uint8_t*>(object) + kOta, kPixel17CodecStorage));
}
bool marker(const void*) { return false; }
uint8_t set_config(void* object, const uint8_t* peer, bool capability, uint8_t* out) {
    return pixel17_codec_set_config(object, peer, kPixel17CodecStorage, capability,
                                    out, kPixel17CodecStorage);
}
bool set_peer(void* object, const uint8_t* peer) {
    return pixel17_codec_set_peer(object, peer, kPixel17CodecStorage);
}
bool init(void* object) { return !quarantined.load() && pixel17_codec_owns(object); }
void debug(void* object, int fd) {
    const auto lease = acquire(object);
    if (lease && host.debug) host.debug(object, fd);
}
}

bool pixel17_codec_prepare(const Pixel17CodecBindings& bindings) {
    if (!bindings.allocate || !bindings.release || !bindings.aac_source_construct ||
        !bindings.aac_source_vtable || !bindings.lock || !bindings.unlock || !bindings.assign_short)
        return false;
    for (size_t i = 0; i < kPixel17CodecVtableEntries; ++i)
        if (!bindings.aac_source_vtable[i]) return false;
    std::lock_guard<std::mutex> guard(registry_mutex);
    if (prepared) return same_bindings(host, bindings);
    host = bindings;
    prepared = true;
    return true;
}

void* pixel17_codec_create(int32_t index, int32_t priority) {
    // The observed local-source registry accepts indices 0..7. Built-ins must never be borrowed.
    if (index != 5 || priority < -1 || priority > 1000000 || quarantined.load()) return nullptr;
    {
        std::lock_guard<std::mutex> guard(registry_mutex);
        if (!prepared || reserved_objects >= kMaximumObjects) return nullptr;
        ++reserved_objects;
    }
    struct Pending {
        bool keep = false;
        ~Pending() {
            if (!keep) { std::lock_guard<std::mutex> guard(registry_mutex); --reserved_objects; }
        }
    } pending;
    void* object = nullptr;
    bool constructed = false;
    try {
        object = host.allocate(kPixel17CodecObjectSize);
        if (!object) return nullptr;
        const int32_t effective = priority == 0 ? 6001 : priority;
        host.aac_source_construct(object, effective);
        constructed = true;
        if (load<const uintptr_t*>(object, 0) != host.aac_source_vtable ||
            load<int32_t>(object, kIndex) != 1 || load<uint64_t>(object, kCodecId) != 2 ||
            load<uint8_t>(object, kSource) != 1 || load<uint8_t>(object, kName) != 6 ||
            std::memcmp(static_cast<uint8_t*>(object) + kName + 1, "AAC\0", 4) != 0)
            throw false;
        for (size_t offset : {kCurrent, kLocal, kSelectable, kUser, kAudio})
            if (load<Pixel17NativeConfig>(object, offset).codec_type != 1) throw false;
        if (quarantined.load()) throw false;
        auto owned = std::make_shared<Context>();
        owned->object = object;
        std::memcpy(owned->vtable.data(), host.aac_source_vtable - 2, sizeof(owned->vtable));
        owned->vtable[2] = reinterpret_cast<uintptr_t>(&destructor);
        owned->vtable[3] = reinterpret_cast<uintptr_t>(&deleting_destructor);
        owned->vtable[4] = reinterpret_cast<uintptr_t>(&track_bitrate);
        owned->vtable[5] = reinterpret_cast<uintptr_t>(&marker);
        // Native slot4 is deliberately kept from AAC: a local software codec, not an Ext provider object.
        owned->vtable[7] = reinterpret_cast<uintptr_t>(&set_config);
        owned->vtable[8] = reinterpret_cast<uintptr_t>(&set_peer);
        owned->vtable[9] = reinterpret_cast<uintptr_t>(&init);
        owned->vtable[10] = reinterpret_cast<uintptr_t>(&debug);
        host.assign_short(static_cast<uint8_t*>(object) + kName, "AAC-ELD", 7);
        if (load<uint8_t>(object, kName) != 14 ||
            std::memcmp(static_cast<uint8_t*>(object) + kName + 1, "AAC-ELD\0", 8) != 0) throw false;
        store(object, kIndex, index);
        store(object, kCodecId, kPixel17EldCodecId);
        store(object, kPriority, effective);
        store(object, kDefaultPriority, priority);
        const auto fixed = fixed_config(index, effective);
        for (size_t offset : {kCurrent, kLocal, kSelectable}) store(object, offset, fixed);
        Pixel17NativeConfig preference{};
        preference.codec_type = index;
        store(object, kUser, preference);
        store(object, kAudio, preference);
        std::memset(static_cast<uint8_t*>(object) + kOta, 0, kPixel17CodecStorage * 3);
        if (!make_configuration(static_cast<uint8_t*>(object) + kOta, kPixel17CodecStorage)) throw false;
        {
            std::lock_guard<std::mutex> guard(registry_mutex);
            if (quarantined.load() || !registry.emplace(object, owned).second) throw false;
            store(object, 0, owned->vtable.data() + 2);
        }
        pending.keep = true;
        return object;
    } catch (...) {
        if (object) {
            if (constructed) {
                store(object, 0, host.aac_source_vtable);
                reinterpret_cast<void (*)(void*)>(host.aac_source_vtable[1])(object);
            } else host.release(object, kPixel17CodecObjectSize);
        }
        return nullptr;
    }
}

bool pixel17_codec_owns(const void* object) {
    std::lock_guard<std::mutex> guard(registry_mutex);
    return registry.find(object) != registry.end();
}
size_t pixel17_codec_active_objects() {
    std::lock_guard<std::mutex> guard(registry_mutex);
    return registry.size();
}
void pixel17_codec_quarantine() { quarantined.store(true); }

bool pixel17_codec_info(const void* object, uint8_t* out, size_t size) {
    if (!out || size < kCodecInfoSize || size > kPixel17CodecStorage) return false;
    const auto lease = acquire(object);
    if (!lease || quarantined.load()) return false;
    CodecLock guard(object);
    const auto* info = static_cast<const uint8_t*>(object) + kOta;
    if (quarantined.load() || !valid_configuration(info, kPixel17CodecStorage)) return false;
    std::memcpy(out, info, size);
    return true;
}
bool pixel17_codec_get_config(const void* object, Pixel17NativeConfig* out) {
    if (!out) return false;
    const auto lease = acquire(object);
    if (!lease) return false;
    CodecLock guard(object);
    *out = load<Pixel17NativeConfig>(object, kCurrent);
    return true;
}
uint8_t pixel17_codec_set_config(void* object, const uint8_t* peer, size_t peer_size,
                                bool capability, uint8_t* out, size_t out_size) {
    if (!peer || !out || peer_size < kCodecInfoSize || peer_size > kPixel17CodecStorage ||
        out_size < kCodecInfoSize || out_size > kPixel17CodecStorage) return kUnsupportedConfiguration;
    const auto lease = acquire(object);
    if (!lease || quarantined.load()) return kUnsupportedConfiguration;
    std::array<uint8_t, kPixel17CodecStorage> selected{}, saved_peer{};
    if (capability) {
        if (!select_configuration(peer, peer_size, selected.data(), selected.size())) return kUnsupportedConfiguration;
    } else {
        if (!valid_configuration(peer, peer_size)) return kUnsupportedConfiguration;
        std::memcpy(selected.data(), peer, kCodecInfoSize);
    }
    std::memcpy(saved_peer.data(), peer, peer_size);
    CodecLock guard(object);
    const auto index = load<int32_t>(object, kIndex);
    if (quarantined.load() || !accepts(load<Pixel17NativeConfig>(object, kUser), index, true) ||
        !accepts(load<Pixel17NativeConfig>(object, kAudio), index, false)) return kUnsupportedConfiguration;
    const auto fixed = fixed_config(index, load<int32_t>(object, kPriority));
    store(object, kCurrent, fixed);
    store(object, kSelectable, fixed);
    std::memcpy(static_cast<uint8_t*>(object) + kOta, selected.data(), selected.size());
    std::memcpy(static_cast<uint8_t*>(object) + (capability ? kPeerCaps : kPeerConfig),
                saved_peer.data(), saved_peer.size());
    std::memcpy(out, selected.data(), out_size);
    return 0;
}
bool pixel17_codec_set_peer(void* object, const uint8_t* peer, size_t peer_size) {
    if (!peer || peer_size < kCodecInfoSize || peer_size > kPixel17CodecStorage) return false;
    const auto lease = acquire(object);
    if (!lease || quarantined.load()) return false;
    std::array<uint8_t, kPixel17CodecStorage> selected{}, saved_peer{};
    if (!select_configuration(peer, peer_size, selected.data(), selected.size())) return false;
    std::memcpy(saved_peer.data(), peer, peer_size);
    CodecLock guard(object);
    if (quarantined.load()) return false;
    std::memcpy(static_cast<uint8_t*>(object) + kPeerCaps, saved_peer.data(), saved_peer.size());
    store(object, kSelectable, fixed_config(load<int32_t>(object, kIndex), load<int32_t>(object, kPriority)));
    return true;
}
bool pixel17_copy_config(const void* bytes, size_t size, Pixel17NativeConfig* out) {
    if (!bytes || !out || size != sizeof(*out)) return false;
    std::memcpy(out, bytes, sizeof(*out));
    return true;
}
bool pixel17_peer_params(const void* bytes, size_t size, Pixel17PeerParams* out) {
    if (!bytes || !out || size != sizeof(Pixel17PeerParams)) return false;
    const auto* value = static_cast<const uint8_t*>(bytes);
    if (value[0] > 1 || value[1] > 1) return false;
    const auto mtu = static_cast<uint16_t>(value[2] | uint16_t(value[3]) << 8);
    if (mtu < kCodecInfoSize) return false;
    *out = {value[0] != 0, value[1] != 0, mtu};
    return true;
}
}
