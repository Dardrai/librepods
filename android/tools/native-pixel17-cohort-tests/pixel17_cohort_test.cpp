// SPDX-License-Identifier: GPL-3.0-or-later
#include "pixel17_cohort.h"
#include <atomic>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <functional>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace {
using namespace eld;
void check(bool condition, const char* message = "Pixel cohort assertion failed") {
    if (!condition) { std::fprintf(stderr, "%s\n", message); std::abort(); }
}
struct Harness;
struct Plan {
    Harness* harness = nullptr;
    size_t calls = 6, original_inner = 0, outer_depth = 0;
    int failed = -1, throw_at = -1, wrong_rate = -1, duplicate = -1;
    int nested_at = -1, reentrant_at = -1, cancel_at = -1;
    bool nested_done = false, reentrant_done = false;
    bool throw_outer = false;
    std::array<uint64_t, 9> registries{};
    uint64_t cache = 0, configs = 0, addresses = 0;
    std::function<void(size_t)> inner_action;
};
thread_local Plan* plan = nullptr;
void original_cache(void*, const void*, int32_t, void*);
bool original_registry(void*, int32_t);

struct Harness {
    std::unique_ptr<Pixel17Cohort> cohort;
    std::atomic<bool> ready{true}, published{false};
    Pixel17RegistryResult reply = Pixel17RegistryResult::Added;
    bool malformed_receipt = false, cancel_in_register = false;
    size_t cancel_in_admitted = 0, admitted_calls = 0;
    std::atomic<size_t> transactions{0};
    std::recursive_mutex native_mutex;
    std::atomic<size_t> native_depth{0}, native_locks{0};
    std::mutex log_mutex;
    std::vector<Pixel17CohortResult> results;
    std::vector<std::pair<Pixel17CohortIssue, bool>> faults;
    static bool admitted(void* context) noexcept {
        auto& h = *static_cast<Harness*>(context);
        if (++h.admitted_calls == h.cancel_in_admitted) h.cohort->cancel();
        return h.ready.load();
    }
    static Pixel17RegistryResult commit(void* context, void* const* registries, size_t count,
            int32_t index, int32_t priority, Pixel17RegistryReceipt* receipts) noexcept {
        auto& h = *static_cast<Harness*>(context);
        ++h.transactions;
        check(index == 5 && priority == 10001 && (count == 6 || count == 1));
        if (count == 1) check(h.native_depth == 1, "Standalone transaction must retain the original native lock");
        for (size_t i = 0; i < count; ++i) {
            check(registries[i] == &plan->registries[i]);
            receipts[i].before = {3, 1, 2, 1};
            receipts[i].after = receipts[i].before;
            receipts[i].result = h.reply;
            if (h.reply == Pixel17RegistryResult::Added) {
                receipts[i].after.active++; receipts[i].after.source++;
            }
        }
        if (h.malformed_receipt) receipts[count - 1].after.source++;
        if (h.cancel_in_register) h.cohort->cancel();
        return h.reply;
    }
    static void completed(void* context, const Pixel17CohortResult& result) noexcept {
        auto& h = *static_cast<Harness*>(context);
        std::lock_guard<std::mutex> lock(h.log_mutex);
        h.results.push_back(result);
        h.published = result.issue == Pixel17CohortIssue::None && result.committed;
    }
    static void invalidated(void* context, Pixel17CohortIssue issue, bool committed) noexcept {
        auto& h = *static_cast<Harness*>(context);
        std::lock_guard<std::mutex> lock(h.log_mutex);
        h.faults.push_back({issue, committed});
        h.published = false;
    }
    static void lock_registry(void* context, void*) {
        auto& h = *static_cast<Harness*>(context);
        h.native_mutex.lock(); ++h.native_depth; ++h.native_locks;
    }
    static void unlock_registry(void* context, void*) {
        auto& h = *static_cast<Harness*>(context);
        --h.native_depth; h.native_mutex.unlock();
    }
    explicit Harness(size_t expected = 6, bool standalone = true) {
        cohort = std::make_unique<Pixel17Cohort>(expected, Pixel17CohortBindings{
            this, original_cache, original_registry, admitted, commit, completed, invalidated,
            standalone ? lock_registry : nullptr, standalone ? unlock_registry : nullptr});
    }
    void run(Plan& value) {
        value.harness = this;
        auto* previous = plan; plan = &value;
        try { cohort->cache_init(&value.cache, &value.configs, 2, &value.addresses); }
        catch (...) { plan = previous; throw; }
        plan = previous;
    }
    Pixel17CohortResult last() {
        std::lock_guard<std::mutex> lock(log_mutex);
        check(!results.empty()); return results.back();
    }
    bool standalone(Plan& value) {
        value.harness = this;
        auto* previous = plan; plan = &value;
        try {
            const auto result = cohort->registry_init(&value.registries[0], 2);
            plan = previous; return result;
        } catch (...) { plan = previous; throw; }
    }
};

void original_cache(void* cache, const void* configs, int32_t rate, void* addresses) {
    check(plan && cache == &plan->cache && configs == &plan->configs && rate == 2 && addresses == &plan->addresses,
        "Every opaque original argument must be forwarded unchanged");
    ++plan->outer_depth;
    for (size_t i = 0; i < plan->calls; ++i) {
        if (plan->nested_at == static_cast<int>(i) && !plan->nested_done) {
            plan->nested_done = true;
            plan->harness->cohort->cache_init(cache, configs, rate, addresses);
        }
        if (plan->cancel_at == static_cast<int>(i)) plan->harness->cohort->cancel();
        const auto index = plan->duplicate == static_cast<int>(i) ? 0 : i;
        const auto supplied_rate = plan->wrong_rate == static_cast<int>(i) ? 1 : rate;
        const auto previous = plan->original_inner;
        const auto result = plan->harness->cohort->registry_init(&plan->registries[index], supplied_rate);
        check(result == (plan->failed != static_cast<int>(previous)), "Original inner boolean must be preserved");
    }
    --plan->outer_depth;
    if (plan->throw_outer) throw std::runtime_error("outer failure");
}
bool original_registry(void* registry, int32_t rate) {
    check(plan && registry && (rate == 1 || rate == 2));
    // Match the real recursive mutex acquired by A2dpCodecs::init itself.
    std::lock_guard<std::recursive_mutex> lock(plan->harness->native_mutex);
    const auto position = plan->original_inner++;
    if (plan->inner_action) plan->inner_action(position);
    if (plan->reentrant_at == static_cast<int>(position) && !plan->reentrant_done) {
        plan->reentrant_done = true;
        plan->harness->cohort->registry_init(&plan->registries[8], rate);
    }
    if (plan->throw_at == static_cast<int>(position)) throw std::runtime_error("inner failure");
    return plan->failed != static_cast<int>(position);
}

void successful_cohort_and_reinitialization() {
    Harness h;
    check(h.cohort->valid());
    Plan first; h.run(first);
    auto result = h.last();
    check(result.issue == Pixel17CohortIssue::None && result.registration == Pixel17RegistryResult::Added &&
        result.count == 6 && result.committed && h.transactions == 1 && h.published);
    check(first.original_inner == 6 && result.cache == &first.cache && result.generation == 1);
    h.reply = Pixel17RegistryResult::AlreadyOwned;
    Plan second; h.run(second);
    result = h.last();
    check(result.issue == Pixel17CohortIssue::None && result.registration == Pixel17RegistryResult::AlreadyOwned &&
        h.transactions == 2 && h.published);
    Plan plain; plain.harness = &h; plan = &plain;
    check(h.cohort->registry_init(&plain.registries[0], 2));
    plan = nullptr;
    check(plain.original_inner == 1 && h.transactions == 3 && h.native_depth == 0);
}

void rejected_patterns_and_original_failures() {
    for (int scenario = 0; scenario < 8; ++scenario) {
        Harness h;
        Plan value;
        Pixel17CohortIssue issue;
        switch (scenario) {
            case 0: value.calls = 5; issue = Pixel17CohortIssue::CountMismatch; break;
            case 1: value.failed = 3; issue = Pixel17CohortIssue::OriginalFailed; break;
            case 2: value.wrong_rate = 2; issue = Pixel17CohortIssue::RateMismatch; break;
            case 3: value.duplicate = 5; issue = Pixel17CohortIssue::DuplicateRegistry; break;
            case 4: value.nested_at = 2; issue = Pixel17CohortIssue::NestedInitialization; break;
            case 5: value.reentrant_at = 2; issue = Pixel17CohortIssue::ReentrantInner; break;
            case 6: h.ready = false; issue = Pixel17CohortIssue::NotReady; break;
            default: value.cancel_at = 2; issue = Pixel17CohortIssue::Cancelled; break;
        }
        h.run(value);
        check(h.last().issue == issue && !h.last().committed && !h.published && h.transactions == 0);
    }
    Harness invalid(0); Plan value; invalid.run(value);
    check(!invalid.cohort->valid() && invalid.last().issue == Pixel17CohortIssue::InvalidArguments &&
        invalid.transactions == 0 && value.original_inner == 6);
}

void postcommit_violation_vetoes_publication() {
    for (int scenario = 0; scenario < 4; ++scenario) {
        Harness h;
        Plan value;
        if (scenario == 0) value.calls = 7;
        if (scenario == 1) { value.calls = 7; value.nested_at = 6; }
        if (scenario == 2) { value.calls = 7; value.cancel_at = 6; }
        if (scenario == 3) h.malformed_receipt = true;
        h.run(value);
        check(h.transactions == 1 && h.last().committed && h.last().issue != Pixel17CohortIssue::None && !h.published);
        bool quarantined = false;
        for (const auto& fault : h.faults) quarantined = quarantined || fault.second;
        check(quarantined, "A postcommit violation must request quarantine, never pretend rollback succeeded");
    }
    Harness h; h.reply = Pixel17RegistryResult::AllocationFailed;
    Plan value; h.run(value);
    check(h.transactions == 1 && !h.last().committed && h.last().issue == Pixel17CohortIssue::RegistrationFailed && !h.published);
}

void exception_scope_cleanup_and_retry() {
    for (int scenario = 0; scenario < 3; ++scenario) {
        Harness h;
        Plan broken;
        if (scenario == 0) broken.throw_at = 2;
        if (scenario == 1) broken.throw_outer = true;
        if (scenario == 2) { broken.throw_at = 7; broken.nested_at = 2; }
        bool threw = false;
        try { h.run(broken); } catch (const std::runtime_error&) { threw = true; }
        check(threw && h.last().issue != Pixel17CohortIssue::None && !h.published);
        Plan restored; h.run(restored);
        check(h.last().issue == Pixel17CohortIssue::None && h.published && restored.original_inner == 6);
    }
}

void cancellation_race_and_reentrant_cancellation() {
    Harness h;
    Plan value;
    std::mutex gate_mutex;
    std::condition_variable gate;
    bool waiting = false, resume = false;
    value.inner_action = [&](size_t position) {
        if (position != 4) return;
        std::unique_lock<std::mutex> lock(gate_mutex);
        waiting = true; gate.notify_all();
        gate.wait(lock, [&] { return resume; });
    };
    std::thread worker([&] { h.run(value); });
    {
        std::unique_lock<std::mutex> lock(gate_mutex);
        gate.wait(lock, [&] { return waiting; });
    }
    h.cohort->cancel();
    {
        std::lock_guard<std::mutex> lock(gate_mutex); resume = true;
    }
    gate.notify_all(); worker.join();
    check(h.last().issue == Pixel17CohortIssue::Cancelled && h.transactions == 0 && !h.published);
    Plan retry; h.run(retry);
    check(h.last().issue == Pixel17CohortIssue::None && h.last().generation == 2 && h.published);
    h.cohort->cancel(); check(!h.published && h.faults.back().second);

    Harness reentrant; reentrant.cancel_in_register = true;
    Plan cancelled; reentrant.run(cancelled);
    check(reentrant.transactions == 1 && reentrant.last().committed &&
        reentrant.last().issue == Pixel17CohortIssue::Cancelled && !reentrant.published);
    for (size_t call : {size_t{1}, size_t{2}}) {
        Harness admission; admission.cancel_in_admitted = call;
        Plan change; admission.run(change);
        check(admission.last().issue == Pixel17CohortIssue::Cancelled && !admission.published);
        check(admission.transactions == (call == 1 ? 0 : 1));
    }
}

void concurrent_outer_scopes_are_thread_local() {
    Harness h;
    Plan first, second;
    std::thread one([&] { h.run(first); });
    std::thread two([&] { h.run(second); });
    one.join(); two.join();
    check(h.transactions == 2 && h.results.size() == 2 && h.faults.empty());
    check(h.results[0].issue == Pixel17CohortIssue::None && h.results[1].issue == Pixel17CohortIssue::None);
    check(h.results[0].cache != h.results[1].cache);
}

void standalone_first_completion_gate_and_atomic_lock() {
    Harness h(6, true);
    Plan early; check(h.standalone(early));
    check(early.original_inner == 1 && h.transactions == 0 && h.native_locks == 0 && h.results.empty());
    Plan initial; h.run(initial);
    check(h.published && h.transactions == 1);
    const auto publications = h.results.size();
    Plan reset; check(h.standalone(reset));
    check(reset.original_inner == 1 && h.transactions == 2 && h.native_locks == 1 && h.native_depth == 0);
    check(h.results.size() == publications && h.published && h.faults.empty());
    h.reply = Pixel17RegistryResult::AlreadyOwned;
    Plan duplicate; check(h.standalone(duplicate));
    check(h.transactions == 3 && h.results.size() == publications && h.native_depth == 0);
    h.reply = Pixel17RegistryResult::Added;
    // A reset/new object may reuse its registry address. No previous pointer or receipt is cached.
    for (size_t i = 0; i < 3; ++i) {
        reset.original_inner = 0;
        check(h.standalone(reset));
        check(reset.original_inner == 1 && h.transactions == 4 + i && h.native_depth == 0);
    }
    Plan fresh; check(h.standalone(fresh));
    check(h.transactions == 7 && h.results.size() == publications);
    // Revoked admission cannot register a later original-success generation.
    h.ready = false;
    Plan disabled; check(h.standalone(disabled));
    check(h.transactions == 7 && h.native_locks == 6 && disabled.original_inner == 1);

    Harness failed(6, true); failed.ready = false;
    Plan incomplete; failed.run(incomplete);
    failed.ready = true;
    Plan cold; check(failed.standalone(cold));
    check(failed.transactions == 0 && failed.native_locks == 0 && !failed.published);

    Harness missing(6, false);
    check(!missing.cohort->valid());
    Plan invalid; missing.run(invalid);
    check(!missing.published && missing.transactions == 0);
}

void standalone_original_failure_exception_and_registration_rejection() {
    for (int scenario = 0; scenario < 5; ++scenario) {
        Harness h(6, true); Plan initial; h.run(initial);
        const auto publications = h.results.size();
        Plan reset;
        if (scenario == 0) reset.failed = 0;
        if (scenario == 1) reset.throw_at = 0;
        if (scenario == 2) h.reply = Pixel17RegistryResult::AllocationFailed;
        if (scenario == 3) h.malformed_receipt = true;
        if (scenario == 4) reset.reentrant_at = 0;
        bool threw = false;
        try { check(h.standalone(reset) == (scenario != 0)); }
        catch (const std::runtime_error& e) { threw = true; check(std::string(e.what()) == "inner failure"); }
        check(threw == (scenario == 1) && h.native_depth == 0 && h.results.size() == publications);
        check(h.transactions == (scenario == 2 || scenario == 3 ? 2 : 1));
        const auto issue = scenario == 0 ? Pixel17CohortIssue::OriginalFailed :
            scenario == 1 ? Pixel17CohortIssue::OriginalThrew : scenario == 4 ?
            Pixel17CohortIssue::ReentrantInner : Pixel17CohortIssue::RegistrationFailed;
        check(!h.faults.empty() && h.faults.back().first == issue);
        check(h.faults.back().second == (scenario == 3));
    }
}

void standalone_cancellation_and_policy_changes() {
    for (int scenario = 0; scenario < 3; ++scenario) {
        Harness h(6, true); Plan initial; h.run(initial);
        const auto publications = h.results.size();
        Plan reset;
        if (scenario == 0) reset.inner_action = [&](size_t) { h.cohort->cancel(); };
        if (scenario == 1) h.cancel_in_register = true;
        if (scenario == 2) reset.inner_action = [&](size_t) { h.ready = false; };
        check(h.standalone(reset));
        check(h.native_depth == 0 && h.results.size() == publications && !h.published);
        check(h.transactions == (scenario == 1 ? 2 : 1));
        check(h.faults.back().first == (scenario == 2 ? Pixel17CohortIssue::NotReady : Pixel17CohortIssue::Cancelled));
        if (scenario == 1) check(h.faults.back().second);
        // A cancelled initial generation is not revived by another standalone call.
        if (scenario != 2) {
            h.cancel_in_register = false;
            Plan stale; check(h.standalone(stale));
            check(h.transactions == (scenario == 1 ? 2 : 1) && h.native_depth == 0);
        }
    }
}
}

int main() {
    check(std::string(kPixel17A2dpCodecsInitSymbol) == "_ZN10A2dpCodecs4initE29btav_a2dp_codec_sample_rate_t");
    check(std::string(kPixel17PeerCacheInitSymbol).find("BtaAvCoPeerCache4Init") != std::string::npos);
    successful_cohort_and_reinitialization();
    standalone_first_completion_gate_and_atomic_lock();
    standalone_original_failure_exception_and_registration_rejection();
    standalone_cancellation_and_policy_changes();
    rejected_patterns_and_original_failures();
    postcommit_violation_vetoes_publication();
    exception_scope_cleanup_and_retry();
    cancellation_race_and_reentrant_cancellation();
    concurrent_outer_scopes_are_thread_local();
    std::puts("Pixel17 cohort controller: 9 scenario groups passed");
}
