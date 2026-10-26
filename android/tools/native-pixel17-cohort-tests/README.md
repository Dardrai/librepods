# Pixel 17 initialization cohort tests

Build this directory with CMake and a C++17 host compiler, then run CTest. No Android, hook engine or phone access is used.

The controller preserves the native outer initializer's four opaque arguments and each inner initializer's sample rate and bool result. It commits only after all expected original inner calls succeed, under the independently proven outer host lock. Publication is reported after the original outer call returns, so an unexpected seventh call, nested initialization or cancellation can veto it.

The runtime must prove the exact named outer function contains six direct calls and keeps its recursive cache lock held through the last call before arming the hook. A runtime violation after commit requests quarantine: the controller never claims it removed host-owned red-black/list nodes. The runtime's invalidation observer must quarantine owned objects and veto capability publication before the caller exports codec metadata.

Tests cover successful and already-owned reinitialization, missing/duplicate/rate-mismatched calls, original false/throw, nested/reentrant calls, postcommit extra calls, malformed transaction receipts, cancellation racing an original initializer, synchronous callback cancellation and independent thread-local outer scopes. Binding callbacks are noexcept; completion and cancellation callbacks serialize through the controller lock. The runtime must keep the controller and its binding context alive for all armed hook calls.
