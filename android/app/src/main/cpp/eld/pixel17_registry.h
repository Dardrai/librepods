// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cstddef>
#include <cstdint>

namespace eld {
enum class Pixel17RegistryResult { Added, AlreadyOwned, Conflict, Invalid, AllocationFailed };
struct Pixel17RegistryCounts {
    size_t active = 0, disabled = 0, source = 0, sink = 0;
};
enum class Pixel17RegistryIssue {
    None, Bindings, RegistryAddress, RegistryRange, TreeCountLimit, EmptyTree,
    TreeRoot, TreeNodeRange, TreeCycle, TreeKey, TreeColor, TreeParent, CodecRange,
    CodecIndex, CodecPriority, CodecRole, TreeBlackHeight, TreeRedChild,
    TreeCountMismatch, TreeMinimum, ListCountLimit, EmptyList, ListNodeRange,
    ListPrevious, ListCycle, ListObject, ListRole, ListOrder, ListTail,
    ActiveListCount, Membership, CurrentCodec, Exception
};
enum class Pixel17RegistryContainer { Registry, Active, Disabled, Source, Sink };
struct Pixel17RegistryDiagnostic {
    Pixel17RegistryIssue issue = Pixel17RegistryIssue::None;
    Pixel17RegistryContainer container = Pixel17RegistryContainer::Registry;
    Pixel17RegistryCounts counts{};
    size_t tree_entries = 0, list_entries = 0;
    int32_t map_index = -1, object_index = -1, priority = 0;
    uint8_t tail_byte = 0xff;
};
const char* pixel17_registry_issue_name(Pixel17RegistryIssue issue);
struct Pixel17RegistryReceipt {
    Pixel17RegistryResult result = Pixel17RegistryResult::Invalid;
    Pixel17RegistryCounts before{}, after{};
};
struct Pixel17RegistryBindings {
    void* (*allocate)(size_t);
    void (*release)(void*, size_t);
    void (*lock)(void*);
    void (*unlock)(void*);
    // This is the validated platform libc++ primitive. It cannot throw or call module policy.
    void (*balance_after_insert)(void* root, void* inserted) noexcept;
    // Pure mapped-range check: must not throw, invoke host code, or mutate/reenter any registry.
    // It is used while deriving saved insertion contexts after fallible callbacks are complete.
    bool (*readable)(const void*, size_t);
    bool (*provider_supports)(int32_t source_index);
};

// Uses no NDK container objects to interpret or mutate the host containers.
// All reads are bounded and checked through readable while the host registry mutex is held.
bool pixel17_registry_validate(const void* registry, const Pixel17RegistryBindings& bindings,
                                Pixel17RegistryCounts* counts = nullptr);
// Same validator and locks as validate(), with only bounded metadata from successful reads.
bool pixel17_registry_diagnose(const void* registry, const Pixel17RegistryBindings& bindings,
    Pixel17RegistryDiagnostic* diagnostic);
// Call only after original A2dpCodecs::init succeeds and before publishing supported codecs.
// Registry fields and binding semantics must have been verified independently against the host image.
// Admission currently supports only the separately conflict-checked slot5 local software extension.
Pixel17RegistryReceipt pixel17_registry_register(void* registry, int32_t source_index,
                                                 int32_t priority,
                                                 const Pixel17RegistryBindings& bindings);
// One cohort transaction: all registries remain unchanged on any admission or staging failure.
// Locks are acquired in address order. The caller must supply count writable receipts and keep
// the outer PeerCache initialization lock held until its supported-codec snapshot is complete.
// All host allocations and policy callbacks finish before the first container splice.
Pixel17RegistryResult pixel17_registry_register_many(void* const* registries, size_t count,
    int32_t source_index, int32_t priority, const Pixel17RegistryBindings& bindings,
    Pixel17RegistryReceipt* receipts);
}
