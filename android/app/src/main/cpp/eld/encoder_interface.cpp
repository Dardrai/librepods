// SPDX-License-Identifier: GPL-3.0-or-later
#include "encoder_interface.h"
#include "note8_adapter.h"
#include "wire.h"
#include <algorithm>
#include <array>
#include <cstring>
#include <mutex>

namespace eld {
namespace {
constexpr size_t kHeadroom = 32; // Stock AAC reserves 23; RTP/L2CAP prepend into this space.
constexpr uint64_t kPeriodUs = 10000;
constexpr uint64_t kClockSafeguardUs = 10000000;
constexpr unsigned kPrimerFrames = 10;
constexpr unsigned kCatchUpFrames = 4;
struct State {
    EncoderHost host{};
    EldFdkEncoder* encoder = nullptr;
    EldFdkConfig config{};
    ReadPcm read = nullptr;
    EnqueuePacket enqueue = nullptr;
    EncoderStatus status{};
    std::array<int16_t, kFrameSamples * kChannels> pcm{};
    std::array<int32_t, kFrameSamples * kChannels> pcm_q31{};
    size_t filled = 0;
    size_t queued = 0;
    uint64_t due_us = 0;
    uint64_t last_send_us = 0;
    uint64_t last_media_submit_us = 0;
    uint64_t clock_serial = 0;
    EncoderClockStatus clock{};
    uint32_t timestamp = 0;
    uint16_t sequence = 0;
    unsigned primer = kPrimerFrames;
    unsigned empty_outputs = 0;
    uint16_t mtu = 0;
    bool prepared = false;
};
std::mutex mutex;
State state;

bool clock_pending() {
    return state.clock.phase == EncoderClockPhase::Armed || state.clock.phase == EncoderClockPhase::Active;
}
uint64_t real_us() { return state.host.monotonic_us ? state.host.monotonic_us() : 0; }
struct StageTiming {
    uint64_t& maximum;
    uint64_t* slow_calls;
    const uint64_t started = real_us();
    explicit StageTiming(uint64_t& max, uint64_t* slow = nullptr) : maximum(max), slow_calls(slow) {}
    ~StageTiming() {
        const auto finished = real_us();
        if (!started || !finished || finished < started) return;
        const auto elapsed = finished - started;
        maximum = std::max(maximum, elapsed);
        if (slow_calls && elapsed >= 1000) ++*slow_calls;
    }
};
uint64_t period_us() { return uint64_t(state.clock.effective_interval_tenths) * 1000; }
uint64_t following_period(uint64_t time_us, uint64_t period = kPeriodUs) {
    return time_us > UINT64_MAX - period ? UINT64_MAX : time_us + period;
}
void clock_progress(uint64_t now_us) {
    if (state.clock.phase != EncoderClockPhase::Active || now_us < state.clock.started_us) return;
    const auto elapsed = std::min(now_us, state.clock.deadline_us) - state.clock.started_us;
    state.clock.nominal_frames = elapsed / 1000 * 48 + elapsed % 1000 * 48 / 1000;
    state.clock.signed_progress_frames = static_cast<int64_t>(state.clock.actual_frames) -
        static_cast<int64_t>(state.clock.nominal_frames);
}
void stop_clock(EncoderClockPhase phase, uint64_t now_us,
                EncoderClockStopReason reason = EncoderClockStopReason::None) {
    if (!clock_pending()) return;
    clock_progress(now_us);
    state.clock.phase = phase;
    state.clock.stop_reason = reason;
    state.clock.stop_media_age_us = state.last_media_submit_us && now_us >= state.last_media_submit_us ?
        now_us - state.last_media_submit_us : 0;
    state.clock.stop_host_queue_length = state.queued;
    state.clock.effective_interval_tenths = 10;
    // Keep the next actual AU deadline: normalization must not erase queue debt.
}
void refresh_clock(uint64_t now_us) {
    if (!clock_pending()) return;
    const auto requested_us = state.clock.deadline_us - kClockSafeguardUs;
    if (!now_us || now_us < requested_us || now_us < state.last_media_submit_us) {
        stop_clock(EncoderClockPhase::Cancelled, now_us, EncoderClockStopReason::InvalidTime);
    } else if (now_us >= state.clock.deadline_us) {
        stop_clock(EncoderClockPhase::Expired, state.clock.deadline_us, EncoderClockStopReason::Deadline);
    } else if (state.last_media_submit_us &&
               now_us - state.last_media_submit_us > kCatchUpFrames * std::max(kPeriodUs, period_us())) {
        // Accept only gaps within the source's bounded catch-up capacity.
        stop_clock(EncoderClockPhase::Cancelled, now_us, EncoderClockStopReason::MediaGap);
    } else clock_progress(now_us);
}
void commit_clock(uint64_t now_us, size_t queued) {
    refresh_clock(now_us);
    if (state.clock.phase == EncoderClockPhase::Armed) {
        if (queued >= 12 || (now_us > state.due_us && now_us - state.due_us > 2000)) {
            stop_clock(EncoderClockPhase::Cancelled, now_us,
                queued >= 12 ? EncoderClockStopReason::QueueFull : EncoderClockStopReason::DueDebt);
            return;
        }
        // This AU ran at the normal rate; subsequent accepted media AUs spend the budget.
        state.clock.phase = EncoderClockPhase::Active;
        state.clock.started_us = now_us;
        state.clock.effective_interval_tenths = state.clock.requested_interval_tenths;
        state.due_us = following_period(now_us, period_us());
    } else if (state.clock.phase == EncoderClockPhase::Active) {
        state.clock.actual_frames += kFrameSamples;
        clock_progress(now_us);
        if (queued >= 12) stop_clock(EncoderClockPhase::Cancelled, now_us, EncoderClockStopReason::QueueFull);
    }
}
void finish_clock_callback(uint64_t now_us, unsigned produced, unsigned budget) {
    refresh_clock(now_us);
    if (state.clock.phase != EncoderClockPhase::Active) return;
    if (state.due_us <= now_us) {
        // A producer stall is not deliberate drift. Keep measuring until its
        // debt is paid, or cancel if the entire catch-up budget could not pay it.
        if (produced == budget)
            stop_clock(EncoderClockPhase::Cancelled, now_us, EncoderClockStopReason::DueDebt);
        return;
    }
    // All accepted AUs in this callback must enter the ledger before completion:
    // later catch-up AUs can undo the apparent slow-clock drift of its first AU.
    const auto signed_frames = state.clock.signed_progress_frames;
    const auto achieved = state.clock.requested_interval_tenths == 9 ? signed_frames : -signed_frames;
    if (achieved >= static_cast<int64_t>(state.clock.target_drift_ms) * 48)
        stop_clock(EncoderClockPhase::Completed, now_us);
}

void capture_peer_locked() {
    std::memset(state.status.peer, 0, sizeof(state.status.peer));
    uint8_t peer[sizeof(state.status.peer)]{};
    if (state.prepared && state.host.peer && state.host.peer(peer, sizeof(peer)))
        std::memcpy(state.status.peer, peer, sizeof(peer));
}
bool default_peer_params(const void* peer, uint16_t* mtu) {
    Note8PeerParams decoded{};
    if (!note8_peer_params(peer, &decoded)) return false;
    *mtu = decoded.peer_mtu;
    return true;
}
void retire_locked(bool failed) {
    stop_clock(EncoderClockPhase::Cancelled, real_us(), EncoderClockStopReason::Retired);
    eld_fdk_close(&state.encoder);
    state.status.active = false;
    state.status.failed = failed;
    state.read = nullptr;
    state.enqueue = nullptr;
    state.filled = state.queued = 0;
    state.due_us = 0;
}
void restart_clock_locked(bool new_transport = false) {
    stop_clock(EncoderClockPhase::Cancelled, real_us(), EncoderClockStopReason::FeedingReset);
    state.filled = state.queued = 0;
    state.due_us = 0;
    state.last_send_us = 0;
    state.last_media_submit_us = 0;
    // Feeding reset/flush is not a new RTP stream. Preserve AU/timestamp continuity.
    if (new_transport) {
        state.timestamp = 0;
        state.sequence = 0;
    }
    state.primer = kPrimerFrames;
    state.empty_outputs = 0;
    state.pcm.fill(0);
    state.pcm_q31.fill(0);
}
void init(const void* peer, void* codec, ReadPcm read, EnqueuePacket enqueue) {
    void (*failed)(uint64_t) = nullptr;
    uint64_t generation = 0;
    {
        std::lock_guard<std::mutex> lock(mutex);
        retire_locked(false);
        const auto next_generation = state.status.generation + 1;
        state.status = {};
        state.status.generation = next_generation;
        capture_peer_locked();
        uint8_t info[kEncoderCodecStorage]{};
        uint16_t mtu = 0;
        const auto codec_info = state.host.codec_info ? state.host.codec_info : note8_codec_info;
        const auto peer_params = state.host.peer_params ? state.host.peer_params : default_peer_params;
        bool valid = state.prepared && read && enqueue &&
            codec_info(codec, info, sizeof(info)) &&
            peer_params(peer, &mtu) && valid_configuration(info, sizeof(info));
        const auto rate = bitrate(info, sizeof(info));
        valid = valid && max_au_bytes(rate) + kAuHeaderSize <= mtu;
        if (valid && eld_fdk_open(&state.host.fdk, rate, &state.encoder, &state.config) == ELD_FDK_OK &&
            state.config.sample_rate == kSampleRate && state.config.channels == kChannels &&
            state.config.frame_samples == kFrameSamples && state.config.pcm_sample_bytes == state.host.pcm_sample_bytes &&
            state.config.max_au_bytes == max_au_bytes(rate)) {
            restart_clock_locked(true);
            state.mtu = mtu;
            state.read = read;
            state.enqueue = enqueue;
            state.status.active = true;
            state.status.bitrate = rate;
        } else {
            retire_locked(true);
            failed = state.host.failed;
            generation = state.status.generation;
        }
    }
    if (failed) failed(generation);
}
void cleanup() {
    std::lock_guard<std::mutex> lock(mutex);
    retire_locked(false);
    ++state.status.generation;
}
void reset() {
    void (*failed)(uint64_t) = nullptr;
    uint64_t generation = 0;
    {
        std::lock_guard<std::mutex> lock(mutex);
        if (!state.status.active) return;
        ++state.status.generation;
        if (eld_fdk_reset(state.encoder) != ELD_FDK_OK) {
            retire_locked(true);
            failed = state.host.failed;
            generation = state.status.generation;
        } else {
            restart_clock_locked();
        }
    }
    if (failed) failed(generation);
}
uint64_t interval_ms() { return 10; }
int effective_frame_size() {
    std::lock_guard<std::mutex> lock(mutex);
    return state.status.active ? state.mtu : 0;
}
void rebase_clock_locked(uint64_t now_us) {
    if (state.due_us) {
        ++state.status.clock_rebases;
        if (now_us >= state.due_us)
            state.status.rebased_periods += (now_us - state.due_us) / kPeriodUs + 1;
    }
    state.due_us = following_period(now_us);
}
void send_frames(uint64_t now_us) {
    void (*failed)(uint64_t) = nullptr;
    void (*opportunity)() = nullptr;
    uint64_t generation = 0;
    {
        std::lock_guard<std::mutex> lock(mutex);
        if (!state.status.active) return;
        const StageTiming callback_timing(state.status.callback_max_us);
        refresh_clock(real_us());
        ++state.status.send_calls;
        if (state.last_send_us && now_us >= state.last_send_us)
            state.status.max_gap_us = std::max(state.status.max_gap_us, now_us - state.last_send_us);
        state.last_send_us = now_us;
        if (state.due_us && now_us >= state.due_us)
            state.status.max_due_lag_us = std::max(state.status.max_due_lag_us, now_us - state.due_us);
        if (state.due_us && now_us < state.due_us && state.due_us - now_us > 2000) return;
        const bool catch_up = state.host.catch_up_after_stall;
        const bool priming = state.primer != 0;
        if (catch_up) {
            if (!state.due_us) state.due_us = now_us;
        } else if (state.due_us && now_us >= state.due_us && now_us - state.due_us < 2 * kPeriodUs) {
            state.due_us = following_period(state.due_us);
        } else rebase_clock_locked(now_us);
        const unsigned budget = catch_up && !priming ? kCatchUpFrames : 1;
        size_t queued = state.queued;
        unsigned produced = 0;
        bool accepted_media = false, opportunity_blocked = false;
        for (unsigned attempt = 0; attempt < budget; ++attempt) {
            if (attempt && state.due_us > now_us) break;
            if (queued >= 12) {
                opportunity_blocked = true;
                stop_clock(EncoderClockPhase::Cancelled, real_us(), EncoderClockStopReason::QueueFull);
                ++state.status.queue_deferrals;
                break; // Keep opt-in debt: the next callback may see a drained native queue.
            }
            bool fatal = false;
            if (state.primer == 0) {
                const size_t remaining = kFrameSamples * kChannels * state.config.pcm_sample_bytes - state.filled;
                void* pcm = state.config.pcm_sample_bytes == 4 ? static_cast<void*>(state.pcm_q31.data()) : state.pcm.data();
                auto* destination = static_cast<uint8_t*>(pcm) + state.filled;
                ++state.status.pcm_read_calls;
                uint32_t count;
                {
                    const StageTiming timing(state.status.pcm_read_max_us, &state.status.pcm_read_slow_calls);
                    count = state.read(destination, static_cast<uint32_t>(remaining));
                }
                if (count > remaining || count % (kChannels * state.config.pcm_sample_bytes) != 0) {
                    fatal = true;
                } else {
                    state.status.pcm_read_bytes += count;
                    state.filled += count;
                    if (state.filled < kFrameSamples * kChannels * state.config.pcm_sample_bytes) {
                        opportunity_blocked = true;
                        ++state.status.underflows;
                        stop_clock(EncoderClockPhase::Cancelled, real_us(), EncoderClockStopReason::Underflow);
                        if (catch_up) rebase_clock_locked(now_us);
                        break;
                    }
                }
            } else {
                // Prime the AirPods before consuming music; do not replace its first 100 ms.
                state.pcm.fill(0);
                state.pcm_q31.fill(0);
            }
            std::array<uint8_t, kMaximumAu> au{};
            size_t bytes = 0;
            if (!fatal) {
                {
                    const StageTiming timing(state.status.encode_max_us);
                    fatal = (state.config.pcm_sample_bytes == 4 ?
                        eld_fdk_encode_q31(state.encoder, state.pcm_q31.data(), kFrameSamples, au.data(), au.size(), &bytes) :
                        eld_fdk_encode(state.encoder, state.pcm.data(), kFrameSamples, au.data(), au.size(), &bytes)) != ELD_FDK_OK;
                }
                state.filled = 0;
            }
            if (!fatal && bytes == 0) {
                opportunity_blocked = true;
                // FDK may buffer during startup, but a broken encoder must not stay silent forever.
                if (++state.empty_outputs <= 16) {
                    stop_clock(EncoderClockPhase::Cancelled, real_us(), EncoderClockStopReason::EmptyOutput);
                    if (catch_up) rebase_clock_locked(now_us);
                    break;
                }
                fatal = true;
            }
            if (!fatal) {
                state.empty_outputs = 0;
                auto* packet = static_cast<PacketHeader*>(
                    state.host.allocate(sizeof(PacketHeader) + kHeadroom + kAuHeaderSize + bytes));
                if (!packet) {
                    fatal = true; // The PCM frame was consumed; recover instead of replaying stale audio.
                } else {
                    *packet = {0, static_cast<uint16_t>(kAuHeaderSize + bytes),
                               static_cast<uint16_t>(kHeadroom), 1};
                    auto* data = reinterpret_cast<uint8_t*>(packet + 1);
                    std::memset(data, 0, kHeadroom);
                    std::memcpy(data, &state.timestamp, sizeof(state.timestamp));
                    if (!write_au_header(data + kHeadroom, kAuHeaderSize, state.sequence, bytes)) {
                        state.host.release(packet);
                        fatal = true;
                    } else {
                        std::memcpy(data + kHeadroom + kAuHeaderSize, au.data(), bytes);
                        // Both results transfer ownership. False is the native queue's normal
                        // tx-flush path during suspend, not an encoder/transport failure.
                        bool accepted;
                        {
                            const StageTiming timing(state.status.enqueue_max_us);
                            accepted = state.enqueue(packet, 1, kFrameSamples * kChannels * state.config.pcm_sample_bytes);
                        }
                        ++produced;
                        state.status.burst_max = std::max(state.status.burst_max, produced);
                        // The AU was encoded even if the host discarded it. Never reuse its
                        // RTP time or sequence when a queued timer races with suspension.
                        state.timestamp += static_cast<uint32_t>(kFrameSamples);
                        state.sequence = static_cast<uint16_t>((state.sequence + 1) & 0xfff);
                        if (catch_up) state.due_us = following_period(state.due_us, period_us());
                        if (accepted) {
                            ++state.status.packets;
                            ++queued; // Include this callback's AUs in the queue12 snapshot.
                            if (produced > 1) ++state.status.catch_up_aus;
                            if (state.primer) --state.primer;
                            else {
                                const auto submitted_us = real_us();
                                commit_clock(submitted_us, queued);
                                state.last_media_submit_us = submitted_us;
                                accepted_media = true;
                            }
                        } else {
                            opportunity_blocked = true;
                            ++state.status.host_discards;
                            stop_clock(EncoderClockPhase::Cancelled, real_us(), EncoderClockStopReason::HostDiscard);
                            if (catch_up) rebase_clock_locked(now_us);
                            break; // A normal suspension may race this timer; do not read another AU.
                        }
                    }
                }
            }
            if (fatal) {
                retire_locked(true);
                failed = state.host.failed;
                generation = state.status.generation;
                break;
            }
            // Silence primers are paced across real callbacks, never burst to pay old debt.
            if (catch_up && priming && state.due_us <= now_us) rebase_clock_locked(now_us);
        }
        if(accepted_media && !opportunity_blocked && !failed && state.status.active && !state.filled && queued<12) {
            finish_clock_callback(real_us(), produced, budget);
            opportunity=state.host.clock_opportunity;
        }
    }
    // Recovery can re-enter the source lifecycle; never invoke it while holding our mutex.
    if (failed) failed(generation);
    else if(opportunity) opportunity();
}
void queue_length(size_t count) {
    std::lock_guard<std::mutex> lock(mutex);
    state.queued = count;
    if (count >= 12) stop_clock(EncoderClockPhase::Cancelled, real_us(), EncoderClockStopReason::QueueFull);
}
const EncoderInterface interface = {
    init, cleanup, reset, reset, interval_ms, effective_frame_size, send_frames, queue_length
};
}

bool encoder_prepare(const EncoderHost& host) {
    std::lock_guard<std::mutex> lock(mutex);
    if (state.prepared || state.status.active || !host.allocate || !host.release ||
        !host.fdk.open || !host.fdk.set || !host.fdk.get || !host.fdk.info ||
        !host.fdk.encode || !host.fdk.close ||
        (host.pcm_sample_bytes != 2 && host.pcm_sample_bytes != 4) ||
        host.fdk.pcm_sample_bytes != host.pcm_sample_bytes) return false;
    state.host = host;
    state.clock.phase = host.monotonic_us && host.catch_up_after_stall ?
        EncoderClockPhase::Idle : EncoderClockPhase::Unavailable;
    state.prepared = true;
    return true;
}
bool encoder_ready() {
    std::lock_guard<std::mutex> lock(mutex);
    return state.prepared;
}
const EncoderInterface* encoder_interface() { return &interface; }
EncoderStatus encoder_status() {
    std::lock_guard<std::mutex> lock(mutex);
    return state.status;
}
uint64_t encoder_clock_begin(const uint8_t* peer, size_t size, uint64_t generation,
                             uint32_t interval, uint32_t drift_ms) {
    return encoder_clock_begin_checked(peer,size,generation,interval,drift_ms,nullptr);
}
uint64_t encoder_clock_begin_checked(const uint8_t* peer,size_t size,uint64_t generation,
                                     uint32_t interval,uint32_t drift_ms,EncoderClockAdmission* admission) {
    std::lock_guard<std::mutex> lock(mutex);
    const auto now_us = real_us();
    refresh_clock(now_us);
    EncoderClockAdmission result;
    result.queued=state.queued;
    result.media_age_us=now_us>=state.last_media_submit_us ? now_us-state.last_media_submit_us : UINT64_MAX;
    result.due_lag_us=now_us>state.due_us ? now_us-state.due_us : 0;
    if(!state.prepared || !state.host.monotonic_us || !state.host.catch_up_after_stall)
        result.reason=EncoderClockRejection::Unavailable;
    else if(!state.status.active || state.status.failed) result.reason=EncoderClockRejection::Inactive;
    else if(state.primer) result.reason=EncoderClockRejection::Priming;
    else if(clock_pending()) result.reason=EncoderClockRejection::Pending;
    else if(!peer || size != sizeof(state.status.peer) || !generation || generation != state.status.generation ||
        std::memcmp(peer, state.status.peer, size) != 0 ||
        !std::any_of(peer, peer + size, [](uint8_t value) { return value != 0; }) ||
        std::all_of(peer, peer + size, [](uint8_t value) { return value == 0xff; })) result.reason=EncoderClockRejection::Owner;
    else if((interval!=9 && interval!=11) || drift_ms<1 || drift_ms>500) result.reason=EncoderClockRejection::Parameters;
    else if(!state.last_media_submit_us || now_us<state.last_media_submit_us ||
        now_us-state.last_media_submit_us>2*kPeriodUs) result.reason=EncoderClockRejection::MediaAge;
    else if(!state.due_us) result.reason=EncoderClockRejection::NoDeadline;
    else if(now_us>state.due_us && now_us-state.due_us>2000) result.reason=EncoderClockRejection::DueDebt;
    else if(state.queued>=12) result.reason=EncoderClockRejection::QueueFull;
    else if(now_us>UINT64_MAX-kClockSafeguardUs || state.clock_serial==UINT64_MAX) result.reason=EncoderClockRejection::Range;
    if(admission) *admission=result;
    if(result.reason!=EncoderClockRejection::None) return 0;
    state.clock = {};
    state.clock.phase = EncoderClockPhase::Armed;
    state.clock.serial = ++state.clock_serial;
    state.clock.encoder_generation = generation;
    std::memcpy(state.clock.peer, peer, size);
    state.clock.requested_interval_tenths = interval;
    state.clock.target_drift_ms = drift_ms;
    state.clock.deadline_us = now_us + kClockSafeguardUs;
    return state.clock.serial;
}
bool encoder_clock_cancel(uint64_t serial) {
    std::lock_guard<std::mutex> lock(mutex);
    if (!serial || serial != state.clock.serial) return false;
    stop_clock(EncoderClockPhase::Cancelled, real_us(), EncoderClockStopReason::Requested);
    return true;
}
EncoderClockStatus encoder_clock_status(bool refresh) {
    std::lock_guard<std::mutex> lock(mutex);
    if (refresh) refresh_clock(real_us());
    return state.clock;
}
void encoder_fail_route() {
    void (*failed)(uint64_t) = nullptr;
    uint64_t generation = 0;
    {
        std::lock_guard<std::mutex> lock(mutex);
        if (!state.prepared) return;
        retire_locked(true);
        ++state.status.generation;
        capture_peer_locked();
        failed = state.host.failed;
        generation = state.status.generation;
    }
    if (failed) failed(generation);
}
}
