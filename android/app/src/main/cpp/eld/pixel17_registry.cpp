// SPDX-License-Identifier: GPL-3.0-or-later
#include "pixel17_registry.h"
#include "pixel17_codec.h"
#include <array>
#include <cstring>
#include <limits>
#include <optional>

namespace eld {
namespace {
constexpr size_t kRegistrySize = 0xc0, kMaximumEntries = 64;
constexpr size_t kActiveBegin = 0x48, kActiveRoot = 0x50, kActiveCount = 0x58;
constexpr size_t kDisabledBegin = 0x60, kDisabledRoot = 0x68, kDisabledCount = 0x70;
constexpr size_t kSourceList = 0x78, kSinkList = 0x90;
constexpr size_t kTreeNodeSize = 0x30, kListNodeSize = 0x18;
constexpr size_t kMaximumRegistries = 8;

template<class T> T load(const void* bytes, size_t offset) {
    T out;
    std::memcpy(&out, static_cast<const uint8_t*>(bytes) + offset, sizeof(out));
    return out;
}
template<class T> void store(void* bytes, size_t offset, const T& value) {
    std::memcpy(static_cast<uint8_t*>(bytes) + offset, &value, sizeof(value));
}
void* at(void* bytes, size_t offset) { return static_cast<uint8_t*>(bytes) + offset; }
const void* at(const void* bytes, size_t offset) { return static_cast<const uint8_t*>(bytes) + offset; }
bool complete(const Pixel17RegistryBindings& b) {
    return b.allocate && b.release && b.lock && b.unlock && b.balance_after_insert &&
        b.readable && b.provider_supports;
}
struct RegistryLock {
    const Pixel17RegistryBindings& bindings;
    void* registry;
    RegistryLock(void* object, const Pixel17RegistryBindings& b) : bindings(b), registry(object) { b.lock(object); }
    ~RegistryLock() { bindings.unlock(registry); }
};
struct CohortLock {
    const Pixel17RegistryBindings& bindings;
    std::array<void*, kMaximumRegistries> registries{};
    size_t held = 0;
    explicit CohortLock(const Pixel17RegistryBindings& b) : bindings(b) {}
    ~CohortLock() { while (held) bindings.unlock(registries[--held]); }
    void acquire() {
        while (held < registries.size() && registries[held]) {
            bindings.lock(registries[held]);
            ++held;
        }
    }
};
struct Entry {
    const void* node = nullptr;
    void* object = nullptr;
    int32_t index = 0, priority = 0;
    uint64_t id = 0;
    bool active = false, source = false, listed = false;
    uint8_t tail_byte = 0xff;
};
struct State {
    const Pixel17RegistryBindings& bindings;
    const void* registry;
    Pixel17RegistryDiagnostic* diagnostic = nullptr;
    std::array<Entry, kMaximumEntries> entries{};
    size_t used = 0;
    std::array<const void*, kMaximumEntries> list_nodes{};
    size_t list_used = 0;
    Pixel17RegistryCounts counts{};

    bool fail(Pixel17RegistryIssue issue) {
        if (diagnostic && diagnostic->issue == Pixel17RegistryIssue::None) {
            diagnostic->issue = issue;
            diagnostic->tree_entries = used;
            diagnostic->list_entries = list_used;
        }
        return false;
    }
    void fields(int32_t map_index, int32_t object_index, int32_t priority, uint8_t tail_byte) {
        if (!diagnostic) return;
        diagnostic->map_index = map_index; diagnostic->object_index = object_index;
        diagnostic->priority = priority; diagnostic->tail_byte = tail_byte;
    }

    bool readable(const void* object, size_t bytes) const {
        return object && reinterpret_cast<uintptr_t>(object) % alignof(void*) == 0 &&
            reinterpret_cast<uintptr_t>(object) <=
            std::numeric_limits<uintptr_t>::max() - bytes && bindings.readable(object, bytes);
    }
    bool tree_node(const void* node, const void* parent, int64_t low, int64_t high,
                   bool active, size_t depth, size_t& black_height) {
        if (!node) { black_height = 1; return true; }
        if (depth >= kMaximumEntries || used >= kMaximumEntries) return fail(Pixel17RegistryIssue::TreeCountLimit);
        if (!readable(node, kTreeNodeSize)) return fail(Pixel17RegistryIssue::TreeNodeRange);
        for (size_t i = 0; i < used; ++i) if (entries[i].node == node) return fail(Pixel17RegistryIssue::TreeCycle);
        const auto index = load<int32_t>(node, 0x20);
        const auto color = load<uint8_t>(node, 0x18);
        auto* object = load<void*>(node, 0x28);
        fields(index, -1, 0, 0xff);
        if (index < 0 || index > 18 || index <= low || index >= high) return fail(Pixel17RegistryIssue::TreeKey);
        if (color > 1) return fail(Pixel17RegistryIssue::TreeColor);
        if (load<const void*>(node, 0x10) != parent) return fail(Pixel17RegistryIssue::TreeParent);
        if (!readable(object, kPixel17CodecObjectSize)) return fail(Pixel17RegistryIssue::CodecRange);
        const auto object_index = load<int32_t>(object, 0x30);
        const auto priority = load<int32_t>(object, 0x58);
        // +0x227 is initialized by AAC/Ext constructors, but is not a universal role field
        // (the stock aptX source object need not initialize it). Keep it diagnostic only.
        // The runtime ABI guard proves init's index<=7 source-list branch before mutation.
        const auto tail_byte = load<uint8_t>(object, 0x227);
        fields(index, object_index, priority, tail_byte);
        if (object_index != index) return fail(Pixel17RegistryIssue::CodecIndex);
        if (priority < -1 || priority > 1000000 || (active ? priority == -1 : priority != -1))
            return fail(Pixel17RegistryIssue::CodecPriority);
        for (size_t i = 0; i < used; ++i)
            if (entries[i].object == object || entries[i].index == index) return fail(Pixel17RegistryIssue::TreeCycle);
        entries[used++] = {node, object, index, priority, load<uint64_t>(object, 0x38),
                            active, index <= 7, false, tail_byte};
        const auto* left = load<const void*>(node, 0);
        const auto* right = load<const void*>(node, 8);
        size_t left_height = 0, right_height = 0;
        if (!tree_node(left, node, low, index, active, depth + 1, left_height) ||
            !tree_node(right, node, index, high, active, depth + 1, right_height)) return false;
        fields(index, object_index, priority, tail_byte);
        if (left_height != right_height) return fail(Pixel17RegistryIssue::TreeBlackHeight);
        if (color == 0 && ((left && load<uint8_t>(left, 0x18) == 0) ||
                           (right && load<uint8_t>(right, 0x18) == 0))) return fail(Pixel17RegistryIssue::TreeRedChild);
        black_height = left_height + color;
        return true;
    }
    bool tree(size_t begin_offset, size_t root_offset, size_t count_offset, bool active) {
        if (diagnostic) diagnostic->container = active ? Pixel17RegistryContainer::Active : Pixel17RegistryContainer::Disabled;
        const auto count = load<size_t>(registry, count_offset);
        const auto* root = load<const void*>(registry, root_offset);
        const auto* begin = load<const void*>(registry, begin_offset);
        const auto* sentinel = at(registry, root_offset);
        if (count > kMaximumEntries || count > kMaximumEntries - used) return fail(Pixel17RegistryIssue::TreeCountLimit);
        if (count == 0) return (!root && begin == sentinel) || fail(Pixel17RegistryIssue::EmptyTree);
        if (!root || !readable(root, kTreeNodeSize) || load<uint8_t>(root, 0x18) != 1)
            return fail(Pixel17RegistryIssue::TreeRoot);
        const auto before = used;
        size_t height = 0;
        if (!tree_node(root, sentinel, -1, 19, active, 0, height)) return false;
        if (used - before != count) return fail(Pixel17RegistryIssue::TreeCountMismatch);
        auto* minimum = root;
        for (size_t depth = 0; depth < count; ++depth) {
            const auto* left = load<const void*>(minimum, 0);
            if (!left) return begin == minimum || fail(Pixel17RegistryIssue::TreeMinimum);
            minimum = left;
        }
        return fail(Pixel17RegistryIssue::TreeMinimum);
    }
    bool list(size_t offset, bool source) {
        if (diagnostic) diagnostic->container = source ? Pixel17RegistryContainer::Source : Pixel17RegistryContainer::Sink;
        const auto* sentinel = at(registry, offset);
        const auto count = load<size_t>(sentinel, 0x10);
        const auto* first = load<const void*>(sentinel, 8);
        const auto* last = load<const void*>(sentinel, 0);
        if (count > kMaximumEntries || count > kMaximumEntries - list_used) return fail(Pixel17RegistryIssue::ListCountLimit);
        if (count == 0) return (first == sentinel && last == sentinel) || fail(Pixel17RegistryIssue::EmptyList);
        const void* previous = sentinel;
        const void* node = first;
        const Entry* previous_entry = nullptr;
        for (size_t i = 0; i < count; ++i) {
            if (node == sentinel || !readable(node, kListNodeSize)) return fail(Pixel17RegistryIssue::ListNodeRange);
            if (load<const void*>(node, 0) != previous) return fail(Pixel17RegistryIssue::ListPrevious);
            for (size_t j = 0; j < list_used; ++j) if (list_nodes[j] == node) return fail(Pixel17RegistryIssue::ListCycle);
            list_nodes[list_used++] = node;
            auto* object = load<void*>(node, 0x10);
            Entry* entry = nullptr;
            for (size_t j = 0; j < used; ++j) if (entries[j].object == object) { entry = &entries[j]; break; }
            if (!entry) return fail(Pixel17RegistryIssue::ListObject);
            fields(entry->index, entry->index, entry->priority, entry->tail_byte);
            if (!entry->active || entry->source != source || entry->listed) return fail(Pixel17RegistryIssue::ListRole);
            if (previous_entry && (previous_entry->priority < entry->priority ||
                (previous_entry->priority == entry->priority && previous_entry->index < entry->index)))
                return fail(Pixel17RegistryIssue::ListOrder);
            entry->listed = true;
            previous_entry = entry;
            previous = node;
            node = load<const void*>(node, 8);
        }
        return (node == sentinel && last == previous) || fail(Pixel17RegistryIssue::ListTail);
    }
    bool validate() {
        if (!readable(registry, kRegistrySize)) return fail(Pixel17RegistryIssue::RegistryRange);
        counts = {load<size_t>(registry, kActiveCount), load<size_t>(registry, kDisabledCount),
            load<size_t>(registry, kSourceList + 0x10), load<size_t>(registry, kSinkList + 0x10)};
        if (diagnostic) diagnostic->counts = counts;
        if (!tree(kActiveBegin, kActiveRoot, kActiveCount, true) ||
            !tree(kDisabledBegin, kDisabledRoot, kDisabledCount, false) ||
            !list(kSourceList, true) || !list(kSinkList, false)) return false;
        if (diagnostic) diagnostic->container = Pixel17RegistryContainer::Registry;
        if (counts.source + counts.sink != counts.active) return fail(Pixel17RegistryIssue::ActiveListCount);
        const auto* current = load<const void*>(registry, 0x28);
        bool current_found = current == nullptr;
        for (size_t i = 0; i < used; ++i) {
            if (entries[i].active != entries[i].listed) return fail(Pixel17RegistryIssue::Membership);
            if (entries[i].object == current && entries[i].active) current_found = true;
        }
        return current_found || fail(Pixel17RegistryIssue::CurrentCodec);
    }
};
void destroy_codec(void* object) {
    if (!object) return;
    const auto* table = load<const uintptr_t*>(object, 0);
    reinterpret_cast<void (*)(void*)>(table[1])(object);
}
}

const char* pixel17_registry_issue_name(Pixel17RegistryIssue issue) {
    static constexpr const char* names[]{"None", "Bindings", "RegistryAddress", "RegistryRange",
        "TreeCountLimit", "EmptyTree", "TreeRoot", "TreeNodeRange", "TreeCycle", "TreeKey",
        "TreeColor", "TreeParent", "CodecRange", "CodecIndex", "CodecPriority", "CodecRole",
        "TreeBlackHeight", "TreeRedChild", "TreeCountMismatch", "TreeMinimum", "ListCountLimit",
        "EmptyList", "ListNodeRange", "ListPrevious", "ListCycle", "ListObject", "ListRole",
        "ListOrder", "ListTail", "ActiveListCount", "Membership", "CurrentCodec", "Exception"};
    static_assert(sizeof(names) / sizeof(names[0]) == static_cast<size_t>(Pixel17RegistryIssue::Exception) + 1);
    const auto index = static_cast<size_t>(issue);
    return index < sizeof(names) / sizeof(names[0]) ? names[index] : "Unknown";
}

bool pixel17_registry_diagnose(const void* registry, const Pixel17RegistryBindings& bindings,
        Pixel17RegistryDiagnostic* diagnostic) {
    if (!diagnostic) return false;
    *diagnostic = {};
    if (!complete(bindings)) { diagnostic->issue = Pixel17RegistryIssue::Bindings; return false; }
    if (!registry || reinterpret_cast<uintptr_t>(registry) % alignof(void*) != 0) {
        diagnostic->issue = Pixel17RegistryIssue::RegistryAddress; return false;
    }
    try {
        if (!bindings.readable(registry, kRegistrySize)) {
            diagnostic->issue = Pixel17RegistryIssue::RegistryRange; return false;
        }
        RegistryLock lock(const_cast<void*>(registry), bindings);
        State state{bindings, registry, diagnostic};
        const bool valid = state.validate();
        diagnostic->tree_entries = state.used;
        diagnostic->list_entries = state.list_used;
        return valid;
    } catch (...) { diagnostic->issue = Pixel17RegistryIssue::Exception; return false; }
}

bool pixel17_registry_validate(const void* registry, const Pixel17RegistryBindings& bindings,
                                Pixel17RegistryCounts* counts) {
    if (!complete(bindings) || !registry || reinterpret_cast<uintptr_t>(registry) % alignof(void*) != 0 ||
        !bindings.readable(registry, kRegistrySize)) return false;
    try {
        RegistryLock lock(const_cast<void*>(registry), bindings);
        State state{bindings, registry};
        if (!state.validate()) return false;
        if (counts) *counts = state.counts;
        return true;
    } catch (...) { return false; }
}

Pixel17RegistryResult pixel17_registry_register_many(void* const* registries, size_t count,
        int32_t index, int32_t priority, const Pixel17RegistryBindings& bindings,
        Pixel17RegistryReceipt* receipts) {
    if (!receipts || !count || count > kMaximumRegistries) return Pixel17RegistryResult::Invalid;
    for (size_t i = 0; i < count; ++i) receipts[i] = {};
    auto fail = [&](Pixel17RegistryResult result) {
        for (size_t i = 0; i < count; ++i) receipts[i].result = result;
        return result;
    };
    if (!complete(bindings) || !registries || index != 5 || priority < 0 || priority > 1000000)
        return fail(Pixel17RegistryResult::Invalid);
    try {
        CohortLock lock(bindings);
        for (size_t i = 0; i < count; ++i) {
            auto* registry = registries[i];
            if (!registry || reinterpret_cast<uintptr_t>(registry) % alignof(void*) != 0 ||
                !bindings.readable(registry, kRegistrySize)) return fail(Pixel17RegistryResult::Invalid);
            for (size_t j = 0; j < i; ++j) {
                const auto a = reinterpret_cast<uintptr_t>(registry);
                const auto b = reinterpret_cast<uintptr_t>(registries[j]);
                if (a >= b ? a - b < kRegistrySize : b - a < kRegistrySize)
                    return fail(Pixel17RegistryResult::Invalid);
            }
            // Numeric address ordering is defined without comparing unrelated C++ pointers.
            size_t position = i;
            while (position && reinterpret_cast<uintptr_t>(lock.registries[position - 1]) >
                    reinterpret_cast<uintptr_t>(registry)) {
                lock.registries[position] = lock.registries[position - 1];
                --position;
            }
            lock.registries[position] = registry;
        }
        lock.acquire();
        struct Pending {
            const Pixel17RegistryBindings* host = nullptr;
            void* registry = nullptr;
            void* codec = nullptr;
            void* tree = nullptr;
            void* list = nullptr;
            void* parent = nullptr;
            void* link = nullptr;
            void* before = nullptr;
            void* previous = nullptr;
            bool new_begin = false, existing = false, committed = false;
            ~Pending() {
                if (!host || committed) return;
                if (list) host->release(list, kListNodeSize);
                if (tree) host->release(tree, kTreeNodeSize);
                destroy_codec(codec);
            }
        };
        std::array<std::optional<State>, kMaximumRegistries> states;
        std::array<Pending, kMaximumRegistries> pending;
        size_t additions = 0;
        for (size_t i = 0; i < count; ++i) {
            auto& state = states[i].emplace(State{bindings, registries[i]});
            if (!state.validate()) return fail(Pixel17RegistryResult::Invalid);
            receipts[i].before = receipts[i].after = state.counts;
            if (bindings.provider_supports(index)) return fail(Pixel17RegistryResult::Conflict);
            auto& item = pending[i];
            item.host = &bindings;
            item.registry = registries[i];
            for (size_t j = 0; j < state.used; ++j) {
                const auto& entry = state.entries[j];
                if (entry.index == index || entry.id == kPixel17EldCodecId) {
                    if (entry.index != index || entry.id != kPixel17EldCodecId || !entry.active ||
                        !entry.source || !pixel17_codec_owns(entry.object) || item.existing)
                        return fail(Pixel17RegistryResult::Conflict);
                    std::array<uint8_t, kPixel17CodecStorage> info{};
                    if (!pixel17_codec_info(entry.object, info.data(), info.size()))
                        return fail(Pixel17RegistryResult::Conflict);
                    item.existing = true;
                }
            }
            if (!item.existing && state.used >= kMaximumEntries) return fail(Pixel17RegistryResult::Invalid);
        }
        // Stage the entire cohort before changing any host registry.
        for (size_t i = 0; i < count; ++i) {
            auto& item = pending[i];
            if (item.existing) continue;
            try {
                item.codec = pixel17_codec_create(index, priority);
                if (item.codec) item.tree = bindings.allocate(kTreeNodeSize);
                if (item.tree) item.list = bindings.allocate(kListNodeSize);
            } catch (...) { return fail(Pixel17RegistryResult::AllocationFailed); }
            if (!item.codec || !item.tree || !item.list) return fail(Pixel17RegistryResult::AllocationFailed);
            const auto& state = *states[i];
            if (!state.readable(item.tree, kTreeNodeSize) || !state.readable(item.list, kListNodeSize) ||
                !state.readable(item.codec, kPixel17CodecObjectSize)) return fail(Pixel17RegistryResult::Invalid);
            std::array<uint8_t, kPixel17CodecStorage> info{};
            if (!pixel17_codec_info(item.codec, info.data(), info.size())) return fail(Pixel17RegistryResult::Conflict);
            ++additions;
        }
        // Allocation and policy callbacks may reenter the host. Reject changes in any member,
        // including a member staged earlier, before using its saved insertion context.
        for (size_t i = 0; i < count; ++i)
            if (bindings.provider_supports(index)) return fail(Pixel17RegistryResult::Conflict);
        for (size_t i = 0; i < count; ++i) {
            const auto& state = *states[i];
            State checked{bindings, registries[i]};
            if (!checked.validate() || checked.used != state.used || checked.list_used != state.list_used)
                return fail(Pixel17RegistryResult::Invalid);
            for (size_t j = 0; j < state.used; ++j) {
                const auto& a = state.entries[j];
                const auto& b = checked.entries[j];
                if (a.node != b.node || a.object != b.object || a.index != b.index || a.priority != b.priority ||
                    a.id != b.id || a.active != b.active || a.source != b.source)
                    return fail(Pixel17RegistryResult::Invalid);
            }
            for (size_t j = 0; j < state.list_used; ++j)
                if (state.list_nodes[j] != checked.list_nodes[j]) return fail(Pixel17RegistryResult::Invalid);
            auto& item = pending[i];
            if (item.existing) continue;
            item.parent = at(item.registry, kActiveRoot);
            item.link = item.parent;
            void* node = load<void*>(item.registry, kActiveRoot);
            while (node) {
                item.parent = node;
                item.link = at(node, index < load<int32_t>(node, 0x20) ? 0 : 8);
                node = load<void*>(item.link, 0);
            }
            item.before = at(item.registry, kSourceList);
            void* cursor = load<void*>(item.before, 8);
            const auto new_priority = load<int32_t>(item.codec, 0x58);
            while (cursor != item.before) {
                const auto* object = load<const void*>(cursor, 0x10);
                const auto old_priority = load<int32_t>(object, 0x58);
                if (new_priority > old_priority ||
                    (new_priority == old_priority && index > load<int32_t>(object, 0x30))) {
                    item.before = cursor;
                    break;
                }
                cursor = load<void*>(cursor, 8);
            }
            item.previous = load<void*>(item.before, 0);
            const auto* begin = load<const void*>(item.registry, kActiveBegin);
            item.new_begin = state.counts.active == 0 || index < load<int32_t>(begin, 0x20);
            std::memset(item.tree, 0, kTreeNodeSize);
            store(item.tree, 0x10, item.parent);
            store(item.tree, 0x20, index);
            store(item.tree, 0x28, item.codec);
            store(item.list, 0, item.previous);
            store(item.list, 8, item.before);
            store(item.list, 0x10, item.codec);
        }
        // No fallible calls or policy callbacks remain. All member locks remain held until
        // every splice is complete; ordinary host destruction now owns all committed nodes.
        for (size_t i = 0; i < count; ++i) {
            auto& item = pending[i];
            auto& receipt = receipts[i];
            if (item.existing) { receipt.result = Pixel17RegistryResult::AlreadyOwned; continue; }
            store(item.link, 0, item.tree);
            if (item.new_begin) store(item.registry, kActiveBegin, item.tree);
            bindings.balance_after_insert(load<void*>(item.registry, kActiveRoot), item.tree);
            store(item.registry, kActiveCount, receipt.before.active + 1);
            store(item.previous, 8, item.list);
            store(item.before, 0, item.list);
            store(item.registry, kSourceList + 0x10, receipt.before.source + 1);
            item.committed = true;
            receipt.result = Pixel17RegistryResult::Added;
            receipt.after.active++;
            receipt.after.source++;
        }
        return additions ? Pixel17RegistryResult::Added : Pixel17RegistryResult::AlreadyOwned;
    } catch (...) { return fail(Pixel17RegistryResult::Invalid); }
}

Pixel17RegistryReceipt pixel17_registry_register(void* registry, int32_t index, int32_t priority,
                                                 const Pixel17RegistryBindings& bindings) {
    Pixel17RegistryReceipt receipt;
    pixel17_registry_register_many(&registry, 1, index, priority, bindings, &receipt);
    return receipt;
}
}
