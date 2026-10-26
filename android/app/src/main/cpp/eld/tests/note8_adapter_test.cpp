// SPDX-License-Identifier: GPL-3.0-or-later
#include "note8_adapter.h"
#include "encoder_interface.h"
#include "wire.h"
#include <array>
#include <atomic>
#include <cassert>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

namespace eld {
const EncoderInterface fake_encoder{};
const EncoderInterface* encoder_interface() { return &fake_encoder; }
}
namespace {
using namespace eld;
std::array<uintptr_t, 11> host_vtable{};
size_t allocations = 0, fallback_calls = 0;
std::atomic<size_t> deletions{0}, lock_depth{0};
std::mutex blocking_mutex;
std::condition_variable blocking_changed;
bool block_lock = false, lock_entered = false, release_lock = false;
bool corrupt_host = false;
int32_t last_index = -1, last_priority = -1;
void check(bool value) { if (!value) { std::fputs("adapter check failed\n", stderr); std::abort(); } }
template<class T> T get(const void* object, size_t offset) {
    T result; std::memcpy(&result, static_cast<const uint8_t*>(object) + offset, sizeof(result));
    return result;
}
template<class T> void put(void* object, size_t offset, const T& value) {
    std::memcpy(static_cast<uint8_t*>(object) + offset, &value, sizeof(value));
}
void host_delete(void* object) {
    check(get<const uintptr_t*>(object, 0) == host_vtable.data() + 2);
    ++deletions;
    delete[] static_cast<uint8_t*>(object);
}
void* host_factory(int32_t index, int32_t priority) {
    last_index = index; last_priority = priority;
    if (index != 1) return nullptr;
    auto* object = new uint8_t[0x1e8]{};
    ++allocations;
    const auto table = host_vtable.data() + 2;
    put(object, 0, table);
    put(object, 0x30, int32_t{1});
    std::memset(object + 0x38, 0x5a, 24);
    put(object, 0x50, priority);
    for (size_t offset : {0x58, 0x90, 0xc8, 0x100, 0x138, 0x170}) {
        Note8CodecConfig config{};
        config.codec_type = corrupt_host ? 2 : 1;
        put(object, offset, config);
    }
    object[0x1e4] = 1;
    return object;
}
void no_op() {}
bool host_valid(const uint8_t*) { ++fallback_calls; return false; }
int host_int(const uint8_t*) { ++fallback_calls; return -77; }
void lock(void*) {
    std::unique_lock<std::mutex> guard(blocking_mutex);
    if (block_lock) {
        lock_entered = true;
        blocking_changed.notify_all();
        blocking_changed.wait(guard, [] { return release_lock; });
    }
    ++lock_depth;
}
void unlock(void*) { check(lock_depth != 0); --lock_depth; }
const Note8HookSpec* find(const char* text) {
    size_t count;
    const auto* specs = note8_hook_specs(&count);
    for (size_t i = 0; i < count; ++i)
        if (std::strstr(specs[i].symbol, text)) return specs + i;
    std::abort();
}
template<class Fn> Fn hook(const char* name) { return reinterpret_cast<Fn>(find(name)->replacement); }
void configure() {
    note8_reset_for_test();
    host_vtable.fill(reinterpret_cast<uintptr_t>(&no_op));
    host_vtable[0] = host_vtable[1] = 0;
    host_vtable[3] = reinterpret_cast<uintptr_t>(&host_delete);
    size_t count;
    const auto* specs = note8_hook_specs(&count);
    check(count == 17);
    for (size_t i = 0; i < count; ++i) {
        check(specs[i].function_size >= 32);
        check(specs[i].prologue[0] != 0 || specs[i].prologue[1] != 0);
        for (size_t j = 0; j < i; ++j) check(specs[i].elf_va != specs[j].elf_va);
        *specs[i].original = reinterpret_cast<void*>(&no_op);
    }
    *find("createCodec")->original = reinterpret_cast<void*>(&host_factory);
    *find("IsVendorSourceCodecValid")->original = reinterpret_cast<void*>(&host_valid);
    *find("VendorGetTrackSampleRate")->original = reinterpret_cast<void*>(&host_int);
    check(!note8_set_enabled(true));
    check(!note8_prepare_for_test(nullptr, &lock, &unlock));
    check(note8_prepare_for_test(host_vtable.data() + 2, &lock, &unlock));
    check(note8_set_enabled(true));
}
void dispose(void* object) {
    const auto table = get<const uintptr_t*>(object, 0);
    reinterpret_cast<void (*)(void*)>(table[1])(object);
}
template<class Fn> Fn method(void* object, size_t slot) {
    return reinterpret_cast<Fn>(get<const uintptr_t*>(object, 0)[slot]);
}
using FactoryFn = void* (*)(int32_t, int32_t);
using ConfigFn = bool (*)(void*, const uint8_t*, bool, uint8_t*);
using UserFn = bool (*)(void*, const Note8CodecConfig&, const Note8CodecConfig&, const void*,
                       const uint8_t*, bool, uint8_t*, bool*, bool*, bool*);

void construction_and_fallback() {
    configure();
    const auto create = hook<FactoryFn>("createCodec");
    void* stock = create(1, 1234);
    check(stock && last_index == 1 && last_priority == 1234);
    check(get<int32_t>(stock, 0x30) == 1 && note8_active_objects() == 0);
    dispose(stock);
    void* codec = create(5, 0);
    check(codec && last_index == 1 && last_priority == 6001);
    check(note8_active_objects() == 1 && get<int32_t>(codec, 0x30) == 5);
    for (size_t i = 0; i < 24; ++i) check(static_cast<uint8_t*>(codec)[0x38 + i] == 0x5a);
    const auto current = get<Note8CodecConfig>(codec, 0x58);
    check(current.codec_type == 5 && current.sample_rate == 2 &&
          current.bits_per_sample == 1 && current.channel_mode == 2);
    check(!method<bool (*)(const void*)>(codec, 2)(codec));
    check(method<bool (*)(const void*)>(codec, 7)(codec));
    uint8_t info[20]{};
    check(note8_codec_info(codec, info, sizeof(info)) && bitrate(info, 20) == 256000);
    check(!note8_codec_info(codec, info, 19));
    int unrelated;
    check(!note8_codec_info(&unrelated, info, sizeof(info)));
    uint8_t unknown[20]{};
    const auto get_rate = hook<int (*)(const uint8_t*)>("VendorGetTrackSampleRate");
    const auto before = fallback_calls;
    check(get_rate(unknown) == -77 && fallback_calls == before + 1);
    check(get_rate(info) == 48000);
    check(note8_set_enabled(false));
    check(create(5, 0) == nullptr && last_index == 5);
    check(get_rate(info) == 48000); // Existing object teardown still owns its Apple dispatch.
    dispose(codec);
    check(note8_active_objects() == 0 && get_rate(info) == -77 && lock_depth == 0);
    check(note8_set_enabled(true));
    corrupt_host = true;
    check(create(5, 0) == nullptr && note8_active_objects() == 0);
    corrupt_host = false;
}
void negotiation_is_transactional() {
    configure();
    void* codec = hook<FactoryFn>("createCodec")(5, 10001);
    uint8_t peer[20]{}, out[20]{};
    check(make_configuration(peer, sizeof(peer), 128000));
    const auto set = method<ConfigFn>(codec, 3);
    check(set(codec, peer, false, out));
    check(bitrate(out, sizeof(out)) == 128000);
    std::array<uint8_t, 0x1e8> saved{};
    std::memcpy(saved.data(), codec, saved.size());
    peer[10] = 0;
    std::memset(out, 0x5a, sizeof(out));
    check(!set(codec, peer, false, out));
    check(std::memcmp(saved.data(), codec, saved.size()) == 0 && out[0] == 0x5a);
    const auto set_peer = method<bool (*)(void*, const uint8_t*)>(codec, 5);
    check(!set_peer(codec, peer));
    check(std::memcmp(saved.data(), codec, saved.size()) == 0);
    check(make_capabilities(peer, sizeof(peer)));
    Note8CodecConfig user{}; user.codec_type = 5;
    Note8CodecConfig audio{}; // The HAL feeding record carries PCM fields, not a codec selection.
    user.sample_rate = 2; user.bits_per_sample = 4; user.channel_mode = 2;
    bool input = true, output = true, updated = true;
    const auto set_user = method<UserFn>(codec, 4);
    check(!set_user(codec, user, audio, nullptr, peer, true, out, &input, &output, &updated));
    check(!input && !output && !updated && std::memcmp(saved.data(), codec, saved.size()) == 0);
    user.bits_per_sample = 1;
    check(set_user(codec, user, audio, nullptr, peer, true, out, &input, &output, &updated));
    check(!input && output && updated && bitrate(out, sizeof(out)) == 256000);
    check(set_user(codec, user, audio, nullptr, peer, true, out, &input, &output, &updated));
    check(!input && !output && !updated);
    user.codec_specific[0] = 42;
    std::memcpy(saved.data(), codec, saved.size());
    check(!set_user(codec, user, audio, nullptr, peer, true, out, &input, &output, &updated));
    check(std::memcmp(saved.data(), codec, saved.size()) == 0);
    dispose(codec);
}
void packet_and_metadata_contract() {
    configure();
    Note8PeerParams result{};
    uint8_t peer_params[] = {1, 1, 0xe8, 3};
    check(note8_peer_params(peer_params, &result) && result.peer_mtu == 1000);
    peer_params[0] = 2;
    check(!note8_peer_params(peer_params, &result));
    peer_params[0] = 1; peer_params[2] = 2; peer_params[3] = 0;
    check(!note8_peer_params(peer_params, &result) && !note8_peer_params(nullptr, &result));
    uint8_t info[20]{};
    check(make_configuration(info, sizeof(info)));
    const auto rtp = hook<bool (*)(bool, const uint8_t*)>("VendorUsesRtpHeader");
    check(rtp(false, info) && rtp(true, info));
    struct { uint16_t event, length, offset, layers; } buffer{0, 100, 32, 1};
    const auto header = hook<bool (*)(const uint8_t*, void*, uint16_t)>("VendorBuildCodecHeader");
    check(header(info, &buffer, 1));
    const auto saved = buffer;
    check(!header(info, &buffer, 2) && std::memcmp(&saved, &buffer, sizeof(buffer)) == 0);
    buffer.length = 326;
    check(!header(info, &buffer, 1));
    uint8_t bytes[5]{};
    uint32_t expected = 0xff812345, actual = 0;
    std::memcpy(bytes + 1, &expected, sizeof(expected));
    const auto stamp = hook<bool (*)(const uint8_t*, const uint8_t*, uint32_t*)>("VendorGetPacketTimestamp");
    check(stamp(info, bytes + 1, &actual) && actual == expected);
    check(!stamp(info, nullptr, &actual));
    std::array<uint8_t, 64> config;
    config.fill(0x5a);
    check(hook<bool (*)(int32_t, void*)>("VendorInitCodecConfig")(5, config.data()));
    check(valid_configuration(config.data(), 20));
    for (size_t i = 20; i < config.size(); ++i) check(config[i] == 0x5a);
}
void bounded_lifetime() {
    configure();
    const auto create = hook<FactoryFn>("createCodec");
    std::vector<void*> objects;
    for (size_t i = 0; i < 32; ++i) { objects.push_back(create(5, -1)); check(objects.back()); }
    check(note8_active_objects() == 32 && create(5, 0) == nullptr);
    for (void* object : objects) dispose(object);
    check(note8_active_objects() == 0 && allocations == deletions.load() && lock_depth == 0);
}
void destruction_waits_for_inflight_access() {
    configure();
    void* object = hook<FactoryFn>("createCodec")(5, 10001);
    {
        std::lock_guard<std::mutex> guard(blocking_mutex);
        block_lock = true; lock_entered = false; release_lock = false;
    }
    bool read_ok = false;
    uint8_t result[20]{};
    std::thread reader([&] { read_ok = note8_codec_info(object, result, sizeof(result)); });
    {
        std::unique_lock<std::mutex> guard(blocking_mutex);
        check(blocking_changed.wait_for(guard, std::chrono::seconds(2), [] { return lock_entered; }));
    }
    const auto before = deletions.load();
    std::thread deleter([&] { dispose(object); });
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(2);
    while (note8_owns_for_test(object) && std::chrono::steady_clock::now() < deadline)
        std::this_thread::yield();
    check(!note8_owns_for_test(object));
    check(deletions.load() == before && note8_active_objects() == 1);
    uint8_t rejected[20]{};
    check(!note8_codec_info(object, rejected, sizeof(rejected)));
    {
        std::lock_guard<std::mutex> guard(blocking_mutex);
        release_lock = true;
        blocking_changed.notify_all();
    }
    reader.join(); deleter.join();
    check(read_ok && valid_configuration(result, sizeof(result)));
    check(deletions.load() == before + 1 && note8_active_objects() == 0 && lock_depth == 0);
    block_lock = false;
}
void quarantine_rejects_reselection_without_relabeling() {
    configure();
    const auto create = hook<FactoryFn>("createCodec");
    void* object = create(5, 10001);
    uint8_t info[20]{}, out[20]{};
    check(note8_codec_info(object, info, sizeof(info)));
    const auto encoder = hook<const EncoderInterface* (*)(const uint8_t*)>("VendorGetEncoderInterface");
    check(encoder(info) == &eld::fake_encoder);
    std::array<uint8_t, 0x1e8> saved{};
    std::memcpy(saved.data(), object, saved.size());
    note8_quarantine();
    check(note8_is_quarantined() && !note8_set_enabled(true) && create(5, 10001) == nullptr);
    check(!method<ConfigFn>(object, 3)(object, info, false, out));
    check(!method<bool (*)(void*, const uint8_t*)>(object, 5)(object, info));
    check(!method<bool (*)(void*)>(object, 6)(object));
    check(!method<bool (*)(const void*)>(object, 7)(object));
    Note8CodecConfig user{}; user.codec_type = 5;
    Note8CodecConfig audio{};
    bool input = true, output = true, updated = true;
    check(!method<UserFn>(object, 4)(object, user, audio, nullptr, info, true, out,
                                    &input, &output, &updated));
    check(!input && !output && !updated);
    check(std::memcmp(saved.data(), object, saved.size()) == 0);
    check(!hook<bool (*)(const uint8_t*)>("IsVendorSourceCodecValid")(info));
    check(encoder(info) == nullptr);
    check(!hook<bool (*)(int32_t, void*)>("VendorInitCodecConfig")(5, out));
    const auto index = hook<int32_t (*)(const uint8_t*)>("VendorSourceCodecIndex");
    const auto name = hook<const char* (*)(const uint8_t*)>("VendorCodecName");
    check(index(info) == 5 && std::strcmp(name(info), "Apple AAC-ELD") == 0);
    dispose(object);
    // Capabilities already published through JNI must retain their identity after teardown.
    check(note8_active_objects() == 0 && index(info) == 5);
    check(std::strcmp(name(info), "Apple AAC-ELD") == 0 && !note8_set_enabled(true));
    check(!note8_prepare_for_test(host_vtable.data() + 2, &lock, &unlock));
    void* stock = create(1, 1234);
    check(stock && get<int32_t>(stock, 0x30) == 1);
    dispose(stock);
    check(allocations == deletions.load());
}
}
int main() {
    construction_and_fallback();
    negotiation_is_transactional();
    packet_and_metadata_contract();
    bounded_lifetime();
    destruction_waits_for_inflight_access();
    quarantine_rejects_reselection_without_relabeling();
    std::puts("Note8 adapter checks passed: stock fallback, owned ABI, transactional negotiation, packet metadata, bounded lifetime, concurrent teardown, sticky quarantine.");
}
