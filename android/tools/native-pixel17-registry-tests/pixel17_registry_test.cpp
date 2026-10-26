// SPDX-License-Identifier: GPL-3.0-or-later
#include "pixel17_registry.h"
#include "pixel17_codec.h"
#include <algorithm>
#include <array>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <memory>
#include <stdexcept>
#include <utility>
#include <vector>

namespace {
using namespace eld;
struct Range { void* start; size_t size; };
std::vector<Range> ranges;
std::recursive_mutex mutex;
std::array<uintptr_t, 11> table{};
size_t attempts = 0, allocated = 0, freed = 0, fail_at = 0, balance_calls = 0, provider_calls = 0;
bool provider = false, provider_changes = false, constructor_throws = false;
void* change_object_on_allocate = nullptr;
size_t change_at = 0;
std::vector<uintptr_t> lock_order, unlock_order;

void check(bool value, const char* text = "Pixel registry assertion failed") {
    if (!value) { std::fprintf(stderr, "%s\n", text); std::abort(); }
}
template<class T> T get(const void* object, size_t offset) {
    T result;
    std::memcpy(&result, static_cast<const uint8_t*>(object) + offset, sizeof(result));
    return result;
}
template<class T> void put(void* object, size_t offset, const T& value) {
    std::memcpy(static_cast<uint8_t*>(object) + offset, &value, sizeof(value));
}
void* at(void* object, size_t offset) { return static_cast<uint8_t*>(object) + offset; }
bool readable(const void* object, size_t size) {
    if (!object) return false;
    const auto start = reinterpret_cast<uintptr_t>(object);
    for (const auto& range : ranges) {
        const auto base = reinterpret_cast<uintptr_t>(range.start);
        if (start >= base && start - base <= range.size && size <= range.size - (start - base)) return true;
    }
    return false;
}
void* allocate(size_t size) {
    ++attempts;
    if (fail_at && attempts == fail_at) return nullptr;
    if (change_object_on_allocate && (!change_at || attempts == change_at)) {
        put(change_object_on_allocate, 0x58, int32_t{22222});
        change_object_on_allocate = nullptr;
    }
    auto* object = new uint8_t[size]{};
    ranges.push_back({object, size});
    ++allocated;
    return object;
}
void release(void* object, size_t size) {
    const auto range = std::find_if(ranges.begin(), ranges.end(), [&](const Range& value) { return value.start == object; });
    check(range != ranges.end() && range->size == size, "Every allocation must return to its host heap exactly once");
    ranges.erase(range);
    ++freed;
    delete[] static_cast<uint8_t*>(object);
}
void lock(void* object) { mutex.lock(); lock_order.push_back(reinterpret_cast<uintptr_t>(object)); }
void unlock(void* object) { unlock_order.push_back(reinterpret_cast<uintptr_t>(object)); mutex.unlock(); }
void no_op() {}
bool classification(const void*) { return false; }
void destruct(void* object) { check(get<const uintptr_t*>(object, 0) == table.data() + 2); }
void deleting_destruct(void* object) { destruct(object); release(object, kPixel17CodecObjectSize); }
void construct(void* object, int32_t priority) {
    if (constructor_throws) throw std::runtime_error("constructor failure");
    put(object, 0, table.data() + 2);
    put(object, 0x30, int32_t{1}); put(object, 0x38, uint64_t{2});
    put(object, 0x58, priority); put(object, 0x5c, priority);
    static_cast<uint8_t*>(object)[0x40] = 6;
    std::memcpy(at(object, 0x41), "AAC\0", 4);
    for (size_t offset : {0x60, 0xa0, 0xe0, 0x120, 0x160}) {
        Pixel17NativeConfig config{}; config.codec_type = 1;
        put(object, offset, config);
    }
    static_cast<uint8_t*>(object)[0x227] = 1;
}
void* assign_short(void* string, const char* name, size_t size) {
    check(size <= 22 && (get<uint8_t>(string, 0) & 1) == 0);
    static_cast<uint8_t*>(string)[0] = static_cast<uint8_t>(size * 2);
    std::memcpy(at(string, 1), name, size);
    static_cast<uint8_t*>(string)[size + 1] = 0;
    return string;
}
bool supports(int32_t index) {
    check(index == 5);
    ++provider_calls;
    return provider || (provider_changes && provider_calls % 2 == 0);
}

// Independent test implementation of the platform red/black balancing contract.
// Only this callback is allowed to repair colors/rotations after the transaction publishes its child link.
void rotate_left(void* node) noexcept {
    auto* pivot = get<void*>(node, 8);
    auto* child = get<void*>(pivot, 0);
    auto* parent = get<void*>(node, 0x10);
    put(node, 8, child);
    if (child) put(child, 0x10, node);
    put(pivot, 0x10, parent);
    put(parent, get<void*>(parent, 0) == node ? 0 : 8, pivot);
    put(pivot, 0, node); put(node, 0x10, pivot);
}
void rotate_right(void* node) noexcept {
    auto* pivot = get<void*>(node, 0);
    auto* child = get<void*>(pivot, 8);
    auto* parent = get<void*>(node, 0x10);
    put(node, 0, child);
    if (child) put(child, 0x10, node);
    put(pivot, 0x10, parent);
    put(parent, get<void*>(parent, 0) == node ? 0 : 8, pivot);
    put(pivot, 8, node); put(node, 0x10, pivot);
}
bool black(const void* node) { return !node || get<uint8_t>(node, 0x18) == 1; }
void balance(void* root, void* node) noexcept {
    ++balance_calls;
    auto* sentinel = get<void*>(root, 0x10);
    while (get<void*>(node, 0x10) != sentinel && !black(get<void*>(node, 0x10))) {
        auto* parent = get<void*>(node, 0x10);
        auto* grandparent = get<void*>(parent, 0x10);
        const bool parent_left = get<void*>(grandparent, 0) == parent;
        auto* uncle = get<void*>(grandparent, parent_left ? 8 : 0);
        if (!black(uncle)) {
            put(parent, 0x18, uint8_t{1}); put(uncle, 0x18, uint8_t{1});
            put(grandparent, 0x18, uint8_t{0}); node = grandparent; continue;
        }
        if (get<void*>(parent, parent_left ? 8 : 0) == node) {
            node = parent;
            if (parent_left) rotate_left(node); else rotate_right(node);
            parent = get<void*>(node, 0x10); grandparent = get<void*>(parent, 0x10);
        }
        put(parent, 0x18, uint8_t{1}); put(grandparent, 0x18, uint8_t{0});
        if (parent_left) rotate_right(grandparent); else rotate_left(grandparent);
    }
    put(get<void*>(sentinel, 0), 0x18, uint8_t{1});
}
Pixel17RegistryBindings bindings() { return {allocate, release, lock, unlock, balance, readable, supports}; }
void dispose_codec(void* object) {
    const auto* vtable = get<const uintptr_t*>(object, 0);
    reinterpret_cast<void (*)(void*)>(vtable[1])(object);
}
void destroy_tree(void* node) {
    if (!node) return;
    destroy_tree(get<void*>(node, 0)); destroy_tree(get<void*>(node, 8));
    dispose_codec(get<void*>(node, 0x28)); release(node, 0x30);
}

struct Fixture {
    alignas(8) std::array<uint8_t, 0xc0> bytes{};
    Fixture() {
        ranges.push_back({bytes.data(), bytes.size()});
        for (size_t offset : {0x30, 0x48, 0x60}) put(bytes.data(), offset, at(bytes.data(), offset + 8));
        for (size_t offset : {0x78, 0x90}) {
            put(bytes.data(), offset, at(bytes.data(), offset));
            put(bytes.data(), offset + 8, at(bytes.data(), offset));
        }
        provider = provider_changes = constructor_throws = false; provider_calls = 0; fail_at = 0;
    }
    ~Fixture() {
        for (size_t offset : {0x78, 0x90}) {
            auto* sentinel = at(bytes.data(), offset);
            auto* node = get<void*>(sentinel, 8);
            while (node != sentinel) { auto* next = get<void*>(node, 8); release(node, 0x18); node = next; }
        }
        destroy_tree(get<void*>(bytes.data(), 0x50)); destroy_tree(get<void*>(bytes.data(), 0x68));
        const auto range = std::find_if(ranges.begin(), ranges.end(), [&](const Range& r) { return r.start == bytes.data(); });
        check(range != ranges.end()); ranges.erase(range);
    }
    void* stock(int32_t index, int32_t priority, bool source = true, bool disabled = false, uint64_t id = 0) {
        auto* object = allocate(kPixel17CodecObjectSize); check(object); construct(object, priority);
        put(object, 0x30, index); put(object, 0x38, id ? id : uint64_t(index + 100));
        static_cast<uint8_t*>(object)[0x227] = source;
        for (size_t offset : {0x60, 0xa0, 0xe0, 0x120, 0x160}) {
            auto config = get<Pixel17NativeConfig>(object, offset); config.codec_type = index; put(object, offset, config);
        }
        auto* node = allocate(0x30); check(node);
        const auto begin_offset = disabled ? 0x60 : 0x48;
        const auto root_offset = begin_offset + 8;
        const auto count_offset = root_offset + 8;
        auto* parent = at(bytes.data(), root_offset);
        auto* link = parent;
        auto* cursor = get<void*>(bytes.data(), root_offset);
        while (cursor) { parent = cursor; link = at(cursor, index < get<int32_t>(cursor, 0x20) ? 0 : 8); cursor = get<void*>(link, 0); }
        put(node, 0x10, parent); put(node, 0x20, index); put(node, 0x28, object); put(link, 0, node);
        auto count = get<size_t>(bytes.data(), count_offset);
        const auto* begin = get<const void*>(bytes.data(), begin_offset);
        if (!count || index < get<int32_t>(begin, 0x20)) put(bytes.data(), begin_offset, node);
        balance(get<void*>(bytes.data(), root_offset), node);
        put(bytes.data(), count_offset, count + 1);
        if (!disabled) {
            auto offset = source ? 0x78 : 0x90;
            auto* before = at(bytes.data(), offset);
            cursor = get<void*>(before, 8);
            while (cursor != before) {
                const auto* old = get<const void*>(cursor, 0x10);
                auto old_priority = get<int32_t>(old, 0x58);
                if (priority > old_priority || (priority == old_priority && index > get<int32_t>(old, 0x30))) { before = cursor; break; }
                cursor = get<void*>(cursor, 8);
            }
            auto* previous = get<void*>(before, 0);
            auto* entry = allocate(0x18); check(entry);
            put(entry, 0, previous); put(entry, 8, before); put(entry, 0x10, object);
            put(previous, 8, entry); put(before, 0, entry);
            put(bytes.data(), offset + 0x10, get<size_t>(bytes.data(), offset + 0x10) + 1);
        }
        return object;
    }
    Pixel17RegistryReceipt add(int32_t priority = 10001) {
        return pixel17_registry_register(bytes.data(), 5, priority, bindings());
    }
    std::vector<int32_t> source_order() {
        std::vector<int32_t> order;
        auto* sentinel = at(bytes.data(), 0x78);
        for (auto* node = get<void*>(sentinel, 8); node != sentinel; node = get<void*>(node, 8))
            order.push_back(get<int32_t>(get<void*>(node, 0x10), 0x30));
        return order;
    }
};

void empty_registration_and_idempotence() {
    Fixture fixture;
    Pixel17RegistryCounts counts{};
    check(pixel17_registry_validate(fixture.bytes.data(), bindings(), &counts));
    auto receipt = fixture.add();
    check(receipt.result == Pixel17RegistryResult::Added && receipt.before.active == 0 && receipt.after.active == 1);
    check(receipt.after.source == 1 && pixel17_registry_validate(fixture.bytes.data(), bindings(), &counts));
    check(fixture.source_order() == std::vector<int32_t>{5});
    const auto before = attempts;
    check(fixture.add().result == Pixel17RegistryResult::AlreadyOwned && attempts == before);
    auto* object = get<void*>(get<void*>(fixture.bytes.data(), 0x50), 0x28);
    check(pixel17_codec_owns(object) && get<uint64_t>(object, 0x38) == kPixel17EldCodecId);
    check(get<int32_t>(object, 0x58) == 10001);
}

void ordered_tree_and_list_insertion_preserves_stock() {
    const std::array<int, 7> order{3, 1, 6, 0, 2, 4, 7};
    Fixture fixture;
    std::vector<std::pair<void*, std::array<uint8_t, kPixel17CodecObjectSize>>> original;
    for (auto index : order) {
        auto* object = fixture.stock(index, index >= 4 ? 10001 : 9000);
        original.push_back({object, {}}); std::memcpy(original.back().second.data(), object, kPixel17CodecObjectSize);
    }
    fixture.stock(12, 3000, false); fixture.stock(13, 2000, false); fixture.stock(14, -1, false, true);
    put(fixture.bytes.data(), 0x28, original[1].first);
    check(pixel17_registry_validate(fixture.bytes.data(), bindings()));
    const auto saved_current = get<void*>(fixture.bytes.data(), 0x28);
    const auto receipt = fixture.add();
    check(receipt.result == Pixel17RegistryResult::Added && receipt.before.active == 9 && receipt.after.active == 10);
    check(receipt.before.disabled == 1 && receipt.after.disabled == 1 && receipt.after.source == 8 && receipt.after.sink == 2);
    check(fixture.source_order() == (std::vector<int32_t>{7, 6, 5, 4, 3, 2, 1, 0}));
    check(get<void*>(fixture.bytes.data(), 0x28) == saved_current);
    for (const auto& value : original) check(std::memcmp(value.first, value.second.data(), value.second.size()) == 0);
    check(pixel17_registry_validate(fixture.bytes.data(), bindings()));
}

void collisions_and_provider_changes_do_not_publish() {
    for (int variant = 0; variant < 4; ++variant) {
        Fixture fixture;
        if (variant == 0) fixture.stock(5, 3000);
        if (variant == 1) fixture.stock(5, -1, true, true);
        if (variant == 2) fixture.stock(3, 3000, true, false, kPixel17EldCodecId);
        if (variant == 3) provider = true;
        const auto saved = fixture.bytes;
        const auto count = ranges.size();
        check(fixture.add().result == Pixel17RegistryResult::Conflict);
        check(fixture.bytes == saved && ranges.size() == count && pixel17_registry_validate(fixture.bytes.data(), bindings()));
    }
    Fixture fixture;
    provider_changes = true;
    const auto saved = fixture.bytes;
    const auto count = ranges.size();
    check(fixture.add().result == Pixel17RegistryResult::Conflict);
    check(fixture.bytes == saved && ranges.size() == count && pixel17_codec_active_objects() == 0);
}

void allocation_and_callback_failures_are_atomic() {
    for (int failure = 1; failure <= 3; ++failure) {
        Fixture fixture; fixture.stock(1, 1000);
        const auto saved = fixture.bytes;
        const auto count = ranges.size();
        fail_at = attempts + failure;
        check(fixture.add().result == Pixel17RegistryResult::AllocationFailed);
        check(fixture.bytes == saved && ranges.size() == count && pixel17_codec_active_objects() == 0);
        fail_at = 0;
        check(pixel17_registry_validate(fixture.bytes.data(), bindings()));
    }
    Fixture fixture;
    const auto saved = fixture.bytes;
    const auto count = ranges.size();
    constructor_throws = true;
    check(fixture.add().result == Pixel17RegistryResult::AllocationFailed);
    check(fixture.bytes == saved && ranges.size() == count);
    constructor_throws = false;
    auto* stock = fixture.stock(1, 1000);
    const auto populated = fixture.bytes;
    change_object_on_allocate = stock;
    check(fixture.add().result == Pixel17RegistryResult::Invalid);
    check(fixture.bytes == populated && get<int32_t>(stock, 0x58) == 22222);
    check(pixel17_codec_active_objects() == 0);
    put(stock, 0x58, int32_t{1000});
}

void malformed_structures_are_rejected_before_writes() {
    Fixture fixture;
    auto* object = fixture.stock(1, 1000);
    fixture.stock(0, 500); fixture.stock(2, 1500);
    check(pixel17_registry_validate(fixture.bytes.data(), bindings()));
    auto test = [&](void* location, size_t offset, auto invalid) {
        using T = decltype(invalid);
        const auto previous = get<T>(location, offset);
        put(location, offset, invalid);
        const auto before = attempts;
        check(!pixel17_registry_validate(fixture.bytes.data(), bindings()));
        Pixel17RegistryDiagnostic diagnostic;
        check(!pixel17_registry_diagnose(fixture.bytes.data(), bindings(), &diagnostic));
        check(diagnostic.issue != Pixel17RegistryIssue::None &&
            diagnostic.issue != Pixel17RegistryIssue::Exception);
        check(std::strcmp(pixel17_registry_issue_name(diagnostic.issue), "Unknown") != 0);
        check(fixture.add().result == Pixel17RegistryResult::Invalid && attempts == before);
        put(location, offset, previous);
        check(pixel17_registry_validate(fixture.bytes.data(), bindings()));
    };
    test(fixture.bytes.data(), 0x58, size_t{65});
    test(fixture.bytes.data(), 0x88, size_t{65});
    test(fixture.bytes.data(), 0x50, reinterpret_cast<void*>(0x1238));
    test(fixture.bytes.data(), 0x28, reinterpret_cast<void*>(0x1238));
    auto* root = get<void*>(fixture.bytes.data(), 0x50);
    test(root, 0x10, reinterpret_cast<void*>(0x1238));
    test(root, 0x18, uint8_t{0});
    test(root, 0x18, uint8_t{2});
    test(object, 0x30, int32_t{3});
    auto* first = get<void*>(fixture.bytes.data(), 0x80);
    test(first, 0, first);
    test(first, 8, first);
    test(first, 0x10, reinterpret_cast<void*>(0x1238));
    auto* left = get<void*>(root, 0);
    test(left, 0x18, uint8_t{1}); // One unequal black height.
    test(get<void*>(first, 0x10), 0x58, int32_t{1}); // A formerly first item is now out of order.
    auto missing = bindings(); missing.readable = nullptr;
    check(!pixel17_registry_validate(fixture.bytes.data(), missing));
    check(pixel17_registry_register(fixture.bytes.data(), 5, 10001, missing).result == Pixel17RegistryResult::Invalid);
    check(pixel17_registry_register(at(fixture.bytes.data(), 1), 5, 10001, bindings()).result == Pixel17RegistryResult::Invalid);
    check(pixel17_registry_register(fixture.bytes.data(), 6, 10001, bindings()).result == Pixel17RegistryResult::Invalid);
    check(fixture.add(-1).result == Pixel17RegistryResult::Invalid);
}

void precise_diagnostics_reuse_the_validator() {
    Fixture fixture;
    auto* object = fixture.stock(1, 1000);
    fixture.stock(12, 500, false);
    Pixel17RegistryDiagnostic diagnostic;
    check(pixel17_registry_diagnose(fixture.bytes.data(), bindings(), &diagnostic));
    check(diagnostic.issue == Pixel17RegistryIssue::None && diagnostic.counts.active == 2 &&
        diagnostic.counts.source == 1 && diagnostic.counts.sink == 1 &&
        diagnostic.tree_entries == 2 && diagnostic.list_entries == 2);
    put(object, 0x30, int32_t{2});
    check(!pixel17_registry_diagnose(fixture.bytes.data(), bindings(), &diagnostic));
    check(diagnostic.issue == Pixel17RegistryIssue::CodecIndex &&
        diagnostic.container == Pixel17RegistryContainer::Active && diagnostic.map_index == 1 &&
        diagnostic.object_index == 2 && diagnostic.priority == 1000 && diagnostic.tail_byte == 1);
    put(object, 0x30, int32_t{1});
    auto* source_node = get<void*>(fixture.bytes.data(), 0x80);
    auto* sink_node = get<void*>(fixture.bytes.data(), 0x98);
    const auto original_value = get<void*>(source_node, 0x10);
    put(source_node, 0x10, get<void*>(sink_node, 0x10));
    check(!pixel17_registry_diagnose(fixture.bytes.data(), bindings(), &diagnostic));
    check(diagnostic.issue == Pixel17RegistryIssue::ListRole &&
        diagnostic.container == Pixel17RegistryContainer::Source && diagnostic.map_index == 12 && diagnostic.tail_byte == 0);
    put(source_node, 0x10, original_value);
    auto unavailable = bindings(); unavailable.readable = [](const void*, size_t) { return false; };
    check(!pixel17_registry_diagnose(fixture.bytes.data(), unavailable, &diagnostic));
    check(diagnostic.issue == Pixel17RegistryIssue::RegistryRange && diagnostic.tree_entries == 0);
    check(!pixel17_registry_diagnose(nullptr, bindings(), &diagnostic));
    check(diagnostic.issue == Pixel17RegistryIssue::RegistryAddress);
    check(!pixel17_registry_diagnose(fixture.bytes.data(), {}, &diagnostic));
    check(diagnostic.issue == Pixel17RegistryIssue::Bindings);
    check(!pixel17_registry_diagnose(fixture.bytes.data(), bindings(), nullptr));
    check(std::strcmp(pixel17_registry_issue_name(static_cast<Pixel17RegistryIssue>(1000)), "Unknown") == 0);
}

void native_list_membership_does_not_guess_a_derived_role_byte() {
    Fixture fixture;
    auto* aptx = fixture.stock(3, 4001);
    for (uint8_t raw : {uint8_t{0}, uint8_t{0x7f}, uint8_t{0xff}}) {
        static_cast<uint8_t*>(aptx)[0x227] = raw;
        check(pixel17_registry_validate(fixture.bytes.data(), bindings()));
    }
    for (int32_t index = 8; index <= 11; ++index) {
        auto* extension = fixture.stock(index, 1001 + index * 1000, false);
        static_cast<uint8_t*>(extension)[0x227] = index % 2 ? 0x7f : 1;
    }
    check(pixel17_registry_validate(fixture.bytes.data(), bindings()));
    const auto result = fixture.add();
    check(result.result == Pixel17RegistryResult::Added && result.after.source == 2 && result.after.sink == 4);
    check(fixture.source_order() == (std::vector<int32_t>{5, 3}));
    check(static_cast<uint8_t*>(aptx)[0x227] == 0xff);
    check(pixel17_registry_validate(fixture.bytes.data(), bindings()));
}

void cohort_commit_and_every_allocation_failure() {
    for (size_t failure = 0; failure <= 18; ++failure) {
        std::array<std::unique_ptr<Fixture>, 6> fixtures;
        std::array<void*, 6> registries{};
        std::array<std::array<uint8_t, 0xc0>, 6> saved{};
        std::array<Pixel17RegistryReceipt, 6> receipts{};
        for (size_t i = 0; i < fixtures.size(); ++i) {
            fixtures[i] = std::make_unique<Fixture>();
            fixtures[i]->stock(1, 1000);
            registries[5 - i] = fixtures[i]->bytes.data(); // Intentionally not address-ordered.
            saved[i] = fixtures[i]->bytes;
        }
        const auto allocated_before = ranges.size();
        const auto balanced_before = balance_calls;
        fail_at = failure ? attempts + failure : 0;
        lock_order.clear(); unlock_order.clear();
        const auto result = pixel17_registry_register_many(registries.data(), registries.size(), 5, 10001,
            bindings(), receipts.data());
        fail_at = 0;
        auto sorted = registries;
        std::sort(sorted.begin(), sorted.end(), [](const void* a, const void* b) {
            return reinterpret_cast<uintptr_t>(a) < reinterpret_cast<uintptr_t>(b);
        });
        check(lock_order.size() >= 6 && unlock_order.size() >= 6);
        for (size_t i = 0; i < 6; ++i) {
            check(lock_order[i] == reinterpret_cast<uintptr_t>(sorted[i]));
            check(unlock_order[unlock_order.size() - 6 + i] == reinterpret_cast<uintptr_t>(sorted[5 - i]));
        }
        if (failure) {
            check(result == Pixel17RegistryResult::AllocationFailed);
            check(ranges.size() == allocated_before && balance_calls == balanced_before);
            check(pixel17_codec_active_objects() == 0);
            for (size_t i = 0; i < 6; ++i) {
                check(fixtures[i]->bytes == saved[i]);
                check(receipts[i].result == Pixel17RegistryResult::AllocationFailed);
                check(receipts[i].before.active == 1 && receipts[i].after.active == 1);
            }
        } else {
            check(result == Pixel17RegistryResult::Added && pixel17_codec_active_objects() == 6);
            check(balance_calls == balanced_before + 6);
            for (const auto& receipt : receipts) check(receipt.result == Pixel17RegistryResult::Added &&
                receipt.before.active == 1 && receipt.after.active == 2);
            const auto before = attempts;
            check(pixel17_registry_register_many(registries.data(), 6, 5, 10001,
                bindings(), receipts.data()) == Pixel17RegistryResult::AlreadyOwned);
            check(attempts == before);
        }
        for (auto& fixture : fixtures) check(pixel17_registry_validate(fixture->bytes.data(), bindings()));
    }
}

void cohort_invalid_and_late_reentrant_member() {
    std::array<std::unique_ptr<Fixture>, 6> fixtures;
    std::array<void*, 6> registries{};
    std::array<std::array<uint8_t, 0xc0>, 6> saved{};
    std::array<Pixel17RegistryReceipt, 6> receipts{};
    void* first_stock = nullptr;
    for (size_t i = 0; i < 6; ++i) {
        fixtures[i] = std::make_unique<Fixture>();
        auto* stock = fixtures[i]->stock(1, 1000);
        if (!i) first_stock = stock;
        registries[i] = fixtures[i]->bytes.data(); saved[i] = fixtures[i]->bytes;
    }
    const auto allocations_before = ranges.size();
    const auto balances_before = balance_calls;
    change_object_on_allocate = first_stock;
    change_at = attempts + 16; // The final member's codec allocator reenters an earlier member.
    check(pixel17_registry_register_many(registries.data(), 6, 5, 10001, bindings(), receipts.data()) ==
        Pixel17RegistryResult::Invalid);
    change_at = 0;
    check(ranges.size() == allocations_before && balance_calls == balances_before && pixel17_codec_active_objects() == 0);
    for (size_t i = 0; i < 6; ++i) check(fixtures[i]->bytes == saved[i]);
    put(first_stock, 0x58, int32_t{1000});
    put(fixtures[5]->bytes.data(), 0x58, size_t{65});
    const auto before = attempts;
    check(pixel17_registry_register_many(registries.data(), 6, 5, 10001, bindings(), receipts.data()) ==
        Pixel17RegistryResult::Invalid && attempts == before);
    put(fixtures[5]->bytes.data(), 0x58, size_t{1});
    auto* last = registries[5]; registries[5] = registries[0];
    check(pixel17_registry_register_many(registries.data(), 6, 5, 10001, bindings(), receipts.data()) ==
        Pixel17RegistryResult::Invalid && attempts == before);
    registries[5] = last;
    check(pixel17_registry_register_many(registries.data(), 0, 5, 10001, bindings(), receipts.data()) ==
        Pixel17RegistryResult::Invalid);
    check(pixel17_registry_register_many(registries.data(), 9, 5, 10001, bindings(), receipts.data()) ==
        Pixel17RegistryResult::Invalid);
    // Existing members can coexist with new members, without allocating or replacing the old object.
    check(fixtures[3]->add().result == Pixel17RegistryResult::Added);
    auto* existing = get<void*>(get<void*>(fixtures[3]->bytes.data(), 0x50), 0x28);
    const auto old_order = fixtures[3]->source_order();
    check(pixel17_registry_register_many(registries.data(), 6, 5, 10001, bindings(), receipts.data()) ==
        Pixel17RegistryResult::Added);
    check(receipts[3].result == Pixel17RegistryResult::AlreadyOwned && fixtures[3]->source_order() == old_order);
    check(get<void*>(get<void*>(fixtures[3]->bytes.data(), 0x50), 0x28) == existing);
}

void quarantine_and_host_teardown() {
    {
        Fixture fixture;
        check(fixture.add().result == Pixel17RegistryResult::Added);
        pixel17_codec_quarantine();
        check(pixel17_registry_validate(fixture.bytes.data(), bindings()));
    }
    check(pixel17_codec_active_objects() == 0);
    Fixture fixture;
    const auto saved = fixture.bytes;
    check(fixture.add().result == Pixel17RegistryResult::AllocationFailed);
    check(fixture.bytes == saved);
}
}

int main() {
    table.fill(reinterpret_cast<uintptr_t>(&no_op)); table[0] = table[1] = 0;
    table[2] = reinterpret_cast<uintptr_t>(&destruct); table[3] = reinterpret_cast<uintptr_t>(&deleting_destruct);
    table[6] = reinterpret_cast<uintptr_t>(&classification);
    check(pixel17_codec_prepare({allocate, release, construct, table.data() + 2, lock, unlock, assign_short}));
    empty_registration_and_idempotence();
    ordered_tree_and_list_insertion_preserves_stock();
    collisions_and_provider_changes_do_not_publish();
    allocation_and_callback_failures_are_atomic();
    malformed_structures_are_rejected_before_writes();
    precise_diagnostics_reuse_the_validator();
    native_list_membership_does_not_guess_a_derived_role_byte();
    cohort_commit_and_every_allocation_failure();
    cohort_invalid_and_late_reentrant_member();
    quarantine_and_host_teardown();
    check(ranges.empty() && allocated == freed && pixel17_codec_active_objects() == 0);
    std::puts("Pixel17 registry transaction: 10 scenario groups passed");
}
