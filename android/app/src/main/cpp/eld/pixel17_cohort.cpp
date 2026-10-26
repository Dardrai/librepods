// SPDX-License-Identifier: GPL-3.0-or-later
#include "pixel17_cohort.h"

namespace eld {
struct Pixel17Cohort::Frame {
    Pixel17Cohort* owner;
    Pixel17CohortResult result;
    int32_t sample_rate;
    bool in_inner = false;
    bool notified = false;
};
thread_local Pixel17Cohort::Frame* Pixel17Cohort::active_ = nullptr;
thread_local Pixel17Cohort* Pixel17Cohort::standalone_owner_ = nullptr;
thread_local bool Pixel17Cohort::standalone_reentered_ = false;

Pixel17Cohort::Pixel17Cohort(size_t expected_count, const Pixel17CohortBindings& bindings)
    : expected_count_(expected_count), bindings_(bindings) {}

bool Pixel17Cohort::valid() const {
    return expected_count_ && expected_count_ <= kPixel17MaximumCohort && bindings_.original_cache_init &&
        bindings_.original_registry_init && bindings_.admitted && bindings_.register_cohort &&
        bindings_.completed && bindings_.invalidated && bindings_.lock_registry && bindings_.unlock_registry;
}

void Pixel17Cohort::invalidate(Frame& frame, Pixel17CohortIssue issue) {
    std::lock_guard<std::recursive_mutex> lock(control_);
    if (frame.result.issue == Pixel17CohortIssue::None) frame.result.issue = issue;
    if (!frame.notified) {
        frame.notified = true;
        if (bindings_.invalidated) bindings_.invalidated(bindings_.context, frame.result.issue,
            frame.result.committed);
    }
}

void Pixel17Cohort::cancel() {
    std::lock_guard<std::recursive_mutex> lock(control_);
    generation_.fetch_add(1, std::memory_order_acq_rel);
    completed_generation_.store(0, std::memory_order_release);
    if (bindings_.invalidated) bindings_.invalidated(bindings_.context, Pixel17CohortIssue::Cancelled,
        any_committed_.load());
}

void Pixel17Cohort::maybe_commit(Frame& frame) {
    std::lock_guard<std::recursive_mutex> lock(control_);
    if (frame.result.issue != Pixel17CohortIssue::None || frame.result.count != expected_count_) return;
    if (frame.result.generation != generation()) { invalidate(frame, Pixel17CohortIssue::Cancelled); return; }
    const bool admitted = bindings_.admitted(bindings_.context);
    if (frame.result.generation != generation()) { invalidate(frame, Pixel17CohortIssue::Cancelled); return; }
    if (!admitted) { invalidate(frame, Pixel17CohortIssue::NotReady); return; }
    frame.result.registration = bindings_.register_cohort(bindings_.context, frame.result.registries.data(),
        expected_count_, 5, 10001, frame.result.receipts.data());
    const auto result = frame.result.registration;
    frame.result.committed = result == Pixel17RegistryResult::Added || result == Pixel17RegistryResult::AlreadyOwned;
    if (frame.result.committed) any_committed_.store(true);
    if (!frame.result.committed) { invalidate(frame, Pixel17CohortIssue::RegistrationFailed); return; }
    bool added = false;
    for (size_t i = 0; i < expected_count_; ++i) {
        const auto& receipt = frame.result.receipts[i];
        const bool is_added = receipt.result == Pixel17RegistryResult::Added;
        if ((!is_added && receipt.result != Pixel17RegistryResult::AlreadyOwned) ||
            receipt.after.active != receipt.before.active + (is_added ? 1 : 0) ||
            receipt.after.source != receipt.before.source + (is_added ? 1 : 0) ||
            receipt.after.disabled != receipt.before.disabled || receipt.after.sink != receipt.before.sink) {
            invalidate(frame, Pixel17CohortIssue::RegistrationFailed);
            return;
        }
        added = added || is_added;
    }
    if ((added && result != Pixel17RegistryResult::Added) ||
        (!added && result != Pixel17RegistryResult::AlreadyOwned))
        invalidate(frame, Pixel17CohortIssue::RegistrationFailed);
    // A callback may synchronously cancel this generation. No stale success may publish.
    if (frame.result.generation != generation()) invalidate(frame, Pixel17CohortIssue::Cancelled);
}

void Pixel17Cohort::finish(Frame& frame) {
    std::lock_guard<std::recursive_mutex> lock(control_);
    if (frame.result.generation != generation()) invalidate(frame, Pixel17CohortIssue::Cancelled);
    if (frame.result.issue == Pixel17CohortIssue::None && frame.result.count != expected_count_)
        invalidate(frame, Pixel17CohortIssue::CountMismatch);
    if (frame.result.issue == Pixel17CohortIssue::None) {
        const bool admitted = frame.result.committed && bindings_.admitted(bindings_.context);
        if (frame.result.generation != generation()) invalidate(frame, Pixel17CohortIssue::Cancelled);
        else if (!admitted) invalidate(frame, Pixel17CohortIssue::NotReady);
    }
    if (bindings_.completed) bindings_.completed(bindings_.context, frame.result);
    if (frame.result.issue == Pixel17CohortIssue::None && frame.result.committed &&
        frame.result.generation == generation() && bindings_.admitted(bindings_.context) &&
        frame.result.generation == generation())
        completed_generation_.store(frame.result.generation, std::memory_order_release);
}

void Pixel17Cohort::cache_init(void* cache, const void* configs, int32_t rate, void* addresses) {
    if (!bindings_.original_cache_init) return;
    if (standalone_owner_) {
        standalone_reentered_ = true;
        bindings_.original_cache_init(cache, configs, rate, addresses);
        return;
    }
    if (active_) {
        active_->owner->invalidate(*active_, Pixel17CohortIssue::NestedInitialization);
        bindings_.original_cache_init(cache, configs, rate, addresses);
        return;
    }
    Frame frame{this, {}, rate};
    frame.result.cache = cache;
    frame.result.generation = generation();
    active_ = &frame;
    struct Restore {
        Frame*& slot;
        ~Restore() { slot = nullptr; }
    } restore{active_};
    if (!valid() || !cache || !configs || !addresses) invalidate(frame, Pixel17CohortIssue::InvalidArguments);
    try { bindings_.original_cache_init(cache, configs, rate, addresses); }
    catch (...) {
        invalidate(frame, Pixel17CohortIssue::OriginalThrew);
        finish(frame);
        throw;
    }
    finish(frame);
}

bool Pixel17Cohort::registry_init(void* registry, int32_t rate) {
    if (!bindings_.original_registry_init) return false;
    if (standalone_owner_) {
        standalone_reentered_ = true;
        return bindings_.original_registry_init(registry, rate);
    }
    auto* frame = active_;
    if (!frame) return standalone_init(registry, rate);
    if (frame->owner != this) {
        frame->owner->invalidate(*frame, Pixel17CohortIssue::NestedInitialization);
        return bindings_.original_registry_init(registry, rate);
    }
    if (frame->in_inner) {
        invalidate(*frame, Pixel17CohortIssue::ReentrantInner);
        return bindings_.original_registry_init(registry, rate);
    }
    if (frame->result.generation != generation()) invalidate(*frame, Pixel17CohortIssue::Cancelled);
    if (!registry) invalidate(*frame, Pixel17CohortIssue::InvalidArguments);
    if (rate != frame->sample_rate) invalidate(*frame, Pixel17CohortIssue::RateMismatch);
    const size_t position = frame->result.count++;
    if (position >= expected_count_ || position >= kPixel17MaximumCohort) {
        invalidate(*frame, Pixel17CohortIssue::CountMismatch);
    } else {
        for (size_t i = 0; i < position; ++i)
            if (frame->result.registries[i] == registry) invalidate(*frame, Pixel17CohortIssue::DuplicateRegistry);
        frame->result.registries[position] = registry;
    }
    frame->in_inner = true;
    bool original_result;
    try { original_result = bindings_.original_registry_init(registry, rate); }
    catch (...) {
        frame->in_inner = false;
        invalidate(*frame, Pixel17CohortIssue::OriginalThrew);
        throw;
    }
    frame->in_inner = false;
    if (!original_result) invalidate(*frame, Pixel17CohortIssue::OriginalFailed);
    if (frame->result.count == expected_count_) maybe_commit(*frame);
    return original_result;
}

bool Pixel17Cohort::standalone_init(void* registry, int32_t rate) {
    const auto observed = completed_generation_.load(std::memory_order_acquire);
    // Initial publication remains an all-six transaction. An earlier/unsupported standalone
    // call keeps its original behavior; it cannot publish a codec capability of its own.
    if (!registry || !valid() || !bindings_.lock_registry || !observed || observed != generation() ||
        !bindings_.admitted(bindings_.context) || observed != generation())
        return bindings_.original_registry_init(registry, rate);
    struct NativeLock {
        const Pixel17CohortBindings& b; void* registry;
        NativeLock(const Pixel17CohortBindings& host, void* value) : b(host), registry(value) {
            b.lock_registry(b.context, registry);
        }
        ~NativeLock() { b.unlock_registry(b.context, registry); }
    } lock(bindings_, registry);
    standalone_owner_ = this; standalone_reentered_ = false;
    struct Restore {
        ~Restore() { standalone_owner_ = nullptr; standalone_reentered_ = false; }
    } restore;
    auto reject = [&](Pixel17CohortIssue issue, bool committed = false) {
        bindings_.invalidated(bindings_.context, issue, committed);
    };
    // Never take control_ while holding this native mutex. The initial cohort uses the
    // opposite order (control_, then native registries). Generation/admission are rechecked.
    bool original_result;
    try { original_result = bindings_.original_registry_init(registry, rate); }
    catch (...) { reject(Pixel17CohortIssue::OriginalThrew); throw; }
    if (!original_result) { reject(Pixel17CohortIssue::OriginalFailed); return original_result; }
    auto current = [&] {
        return observed == generation() && observed == completed_generation_.load(std::memory_order_acquire);
    };
    if (!current()) { reject(Pixel17CohortIssue::Cancelled); return original_result; }
    if (standalone_reentered_) { reject(Pixel17CohortIssue::ReentrantInner); return original_result; }
    if (!bindings_.admitted(bindings_.context)) { reject(Pixel17CohortIssue::NotReady); return original_result; }
    if (!current()) { reject(Pixel17CohortIssue::Cancelled); return original_result; }
    Pixel17RegistryReceipt receipt;
    void* members[]{registry};
    const auto result = bindings_.register_cohort(bindings_.context, members, 1, 5, 10001, &receipt);
    const bool committed = result == Pixel17RegistryResult::Added || result == Pixel17RegistryResult::AlreadyOwned;
    if (committed) any_committed_.store(true);
    const size_t added = result == Pixel17RegistryResult::Added ? 1 : 0;
    if (!committed || receipt.result != result || receipt.after.active != receipt.before.active + added ||
        receipt.after.source != receipt.before.source + added || receipt.after.disabled != receipt.before.disabled ||
        receipt.after.sink != receipt.before.sink) {
        reject(Pixel17CohortIssue::RegistrationFailed, committed); return original_result;
    }
    if (!current()) reject(Pixel17CohortIssue::Cancelled, committed);
    else if (standalone_reentered_) reject(Pixel17CohortIssue::ReentrantInner, committed);
    else if (!bindings_.admitted(bindings_.context)) reject(Pixel17CohortIssue::NotReady, committed);
    else if (!current()) reject(Pixel17CohortIssue::Cancelled, committed);
    // The carrier was already published by the initial cohort. No completed/Java callback.
    return original_result;
}
}
