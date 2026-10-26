// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "fdk_encoder.h"
#include <cstddef>
#include <cstdint>

namespace eld {
struct PacketHeader {
    uint16_t event;
    uint16_t len;
    uint16_t offset;
    uint16_t layer_specific;
};
static_assert(sizeof(PacketHeader) == 8);
using ReadPcm = uint32_t (*)(uint8_t*, uint32_t);
using EnqueuePacket = bool (*)(PacketHeader*, size_t frames, uint32_t pcm_bytes);

struct EncoderInterface {
    void (*init)(const void* peer, void* codec, ReadPcm read, EnqueuePacket enqueue);
    void (*cleanup)();
    void (*reset)();
    void (*flush)();
    uint64_t (*interval_ms)();
    int (*effective_frame_size)();
    void (*send_frames)(uint64_t now_us);
    void (*queue_length)(size_t);
};
static_assert(sizeof(EncoderInterface) == sizeof(void*) * 8);
static_assert(offsetof(EncoderInterface, interval_ms) == sizeof(void*) * 4);

struct EncoderHost {
    EldFdkApi fdk;
    void* (*allocate)(size_t);
    void (*release)(void*);
    void (*failed)(uint64_t generation);
    bool (*peer)(uint8_t* out, size_t size);
    // A host adapter copies its validated native codec storage and decodes its own peer ABI.
    // Called under the encoder lock. Failure never falls back to a different platform reader;
    // null retains the Note8 backend.
    bool (*codec_info)(const void* codec, uint8_t* out, size_t size) = nullptr;
    bool (*peer_params)(const void* peer, uint16_t* mtu) = nullptr;
    // Private, measured source-scheduler experiment; legacy hosts retain one AU per callback.
    bool catch_up_after_stall = false;
    // Trusted CLOCK_BOOTTIME microseconds, sampled after native enqueue completion.
    // Null leaves finite clock adjustment unavailable on legacy hosts.
    uint64_t (*monotonic_us)() = nullptr;
    // Optional opportunity after a real accepted non-primer AU callback. Called
    // outside the encoder mutex; never on underflow, flush, failure or queue12.
    void (*clock_opportunity)() = nullptr;
};
inline constexpr size_t kEncoderCodecStorage = 45;
struct EncoderStatus {
    uint64_t generation;
    uint64_t packets;
    uint64_t underflows;
    uint64_t host_discards;
    uint32_t bitrate;
    bool active;
    bool failed;
    uint8_t peer[6];
    // Per-init counters survive feeding reset and cleanup. No audio samples are retained.
    uint64_t send_calls;
    uint64_t max_gap_us;
    uint64_t max_due_lag_us;
    uint64_t clock_rebases;
    uint64_t rebased_periods;
    uint64_t queue_deferrals;
    uint64_t pcm_read_calls;
    uint64_t pcm_read_bytes;
    uint64_t catch_up_aus; // Additional accepted AUs beyond the first in a callback.
    uint32_t burst_max; // Complete AUs transferred to the host in one callback, including discards.
};

enum class EncoderClockPhase : uint32_t {
    Unavailable, Idle, Armed, Active, Completed, Cancelled, Expired
};
struct EncoderClockStatus {
    uint32_t version = 1;
    EncoderClockPhase phase = EncoderClockPhase::Unavailable;
    uint64_t serial = 0;
    uint64_t encoder_generation = 0;
    uint8_t peer[6]{};
    uint32_t requested_interval_tenths = 10;
    uint32_t effective_interval_tenths = 10;
    uint32_t target_drift_ms = 0;
    uint64_t actual_frames = 0;
    uint64_t nominal_frames = 0;
    // Positive means submitted media leads nominal time. Completion is AU-quantized.
    int64_t signed_progress_frames = 0;
    uint64_t started_us = 0;
    uint64_t deadline_us = 0;
};
enum class EncoderClockRejection : uint32_t {
    None, Unavailable, Inactive, Priming, Pending, Owner, Parameters,
    MediaAge, NoDeadline, DueDebt, QueueFull, Range
};
struct EncoderClockAdmission {
    EncoderClockRejection reason = EncoderClockRejection::None;
    size_t queued = 0;
    uint64_t media_age_us = 0, due_lag_us = 0;
};

bool encoder_prepare(const EncoderHost& host);
bool encoder_ready();
const EncoderInterface* encoder_interface();
EncoderStatus encoder_status();
// Version1: only9/11 with a1..500ms budget. Normal10 is restored by cancellation.
// The actual peer/generation, completed priming and settled source deadline must match.
uint64_t encoder_clock_begin(const uint8_t* peer, size_t size, uint64_t encoder_generation,
                             uint32_t interval_tenths, uint32_t drift_ms);
uint64_t encoder_clock_begin_checked(const uint8_t* peer, size_t size, uint64_t encoder_generation,
                                     uint32_t interval_tenths, uint32_t drift_ms,
                                     EncoderClockAdmission* admission);
bool encoder_clock_cancel(uint64_t serial);
EncoderClockStatus encoder_clock_status();
// A validated selected-ELD HAL setup can fail before encoder.init is reached.
// Record that failure against the actual native source peer for ordinary-codec recovery.
void encoder_fail_route();
}
