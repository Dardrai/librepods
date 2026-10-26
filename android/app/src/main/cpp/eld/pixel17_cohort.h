// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "pixel17_registry.h"
#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>

namespace eld {
inline constexpr char kPixel17PeerCacheInitSymbol[] =
    "_ZN16BtaAvCoPeerCache4InitERKNSt3__16vectorI24btav_a2dp_codec_config_tNS0_9allocatorIS2_EEEE29btav_a2dp_codec_sample_rate_tNS1_I10RawAddressNS3_IS9_EEEE";
inline constexpr char kPixel17A2dpCodecsInitSymbol[] =
    "_ZN10A2dpCodecs4initE29btav_a2dp_codec_sample_rate_t";
inline constexpr size_t kPixel17MaximumCohort = 8;

enum class Pixel17CohortIssue {
    None, NotReady, InvalidArguments, NestedInitialization, ReentrantInner,
    DuplicateRegistry, RateMismatch, OriginalFailed, OriginalThrew, CountMismatch,
    RegistrationFailed, Cancelled
};
struct Pixel17CohortResult {
    Pixel17CohortIssue issue = Pixel17CohortIssue::None;
    Pixel17RegistryResult registration = Pixel17RegistryResult::Invalid;
    uint64_t generation = 0;
    void* cache = nullptr;
    size_t count = 0;
    bool committed = false;
    std::array<void*, kPixel17MaximumCohort> registries{};
    std::array<Pixel17RegistryReceipt, kPixel17MaximumCohort> receipts{};
};
struct Pixel17CohortBindings {
    void* context = nullptr;
    void (*original_cache_init)(void*, const void*, int32_t, void*) = nullptr;
    bool (*original_registry_init)(void*, int32_t) = nullptr;
    bool (*admitted)(void*) noexcept = nullptr;
    // This callback must implement an all-or-nothing cohort transaction. Fixed slot and
    // priority are deliberately part of this boundary rather than inferred preferences.
    Pixel17RegistryResult (*register_cohort)(void*, void* const*, size_t, int32_t, int32_t,
        Pixel17RegistryReceipt*) noexcept = nullptr;
    // Publication belongs here, after the original outer initializer has returned and the
    // complete runtime call pattern is known. Copy result if it must outlive the callback.
    void (*completed)(void*, const Pixel17CohortResult&) noexcept = nullptr;
    // A violation after commit cannot remove host-owned tree nodes. The runtime must
    // quarantine owned codecs and veto its capability publication before metadata export.
    void (*invalidated)(void*, Pixel17CohortIssue, bool committed) noexcept = nullptr;
    // Verified recursive registry mutex: retain it across a later standalone init and its
    // single-registry registration. Both callbacks are required before initial publication.
    void (*lock_registry)(void*, void*) = nullptr;
    void (*unlock_registry)(void*, void*) = nullptr;
};

// No symbol lookup, hooking, Android API or host-container ABI is implemented here.
// The runtime must prove the exact outer function has expected_count direct inner call sites
// and keeps its cache lock held through the final site before arming either hook.
class Pixel17Cohort {
public:
    Pixel17Cohort(size_t expected_count, const Pixel17CohortBindings& bindings);
    Pixel17Cohort(const Pixel17Cohort&) = delete;
    Pixel17Cohort& operator=(const Pixel17Cohort&) = delete;
    bool valid() const;
    void cache_init(void* cache, const void* config_vector, int32_t sample_rate,
                    void* address_vector_storage);
    bool registry_init(void* registry, int32_t sample_rate);
    // Cancels current generations; readiness controls whether a later initialization can try
    // again. Completion/invalidation callbacks serialize with cancellation, including races.
    void cancel();
    uint64_t generation() const { return generation_.load(std::memory_order_acquire); }
private:
    struct Frame;
    static thread_local Frame* active_;
    static thread_local Pixel17Cohort* standalone_owner_;
    static thread_local bool standalone_reentered_;
    bool standalone_init(void* registry, int32_t sample_rate);
    void invalidate(Frame& frame, Pixel17CohortIssue issue);
    void finish(Frame& frame);
    void maybe_commit(Frame& frame);
    const size_t expected_count_;
    const Pixel17CohortBindings bindings_;
    std::recursive_mutex control_;
    std::atomic<uint64_t> generation_{1};
    std::atomic<uint64_t> completed_generation_{0};
    std::atomic<bool> any_committed_{false};
};
}
