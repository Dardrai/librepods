// SPDX-License-Identifier: GPL-3.0-or-later
#include "../../main/cpp/eld/encoder_interface.h"
#include "../../main/cpp/eld/note8_adapter.h"
#include "../../main/cpp/eld/wire.h"
#include <algorithm>
#include <array>
#include <cassert>
#include <cstdlib>
#include <cstring>
#include <set>
#include <vector>

struct EldFdkEncoder { bool valid = true; };
namespace {
bool fail_open = false, fail_reset = false, fail_encode = false, fail_alloc = false;
bool accept = true, oversized_read = false;
bool negative_read = false, unaligned_read = false, failed_peer_read = false;
bool custom_codec = false, custom_peer = false, reject_codec = false, reject_params = false;
bool catch_up = false;
bool finite_clock = false;
bool opportunity_arm = false;
unsigned opportunities = 0;
uint64_t opportunity_serial = 0;
uint64_t monotonic_now = 1000000, enqueue_delay_us = 0;
bool incomplete_codec = false, incomplete_params = false;
unsigned empty_encodes = 0, failures = 0, reads = 0, closes = 0, zero_inputs = 0;
unsigned note8_codec_reads = 0, note8_peer_reads = 0, custom_codec_reads = 0, custom_peer_reads = 0;
size_t read_limit = 1920;
size_t pcm_available = SIZE_MAX;
uint8_t cie[20]{};
std::array<uint8_t, eld::kEncoderCodecStorage> native_cie{};
std::set<void*> allocations;
std::vector<std::vector<uint8_t>> payloads;
std::vector<uint32_t> timestamps;
const eld::EncoderInterface* encoder = eld::encoder_interface();
void* allocate(size_t size) {
    if (fail_alloc) return nullptr;
    void* p = std::malloc(size);
    assert(p && allocations.insert(p).second);
    return p;
}
void release(void* p) { assert(allocations.erase(p) == 1); std::free(p); }
uint32_t read_pcm(uint8_t* out, uint32_t capacity) {
    ++reads;
    if (negative_read) return UINT32_MAX;
    if (unaligned_read) return capacity - 1;
    if (oversized_read) return capacity + 4;
    const auto count = static_cast<uint32_t>(std::min({read_limit, size_t(capacity), pcm_available}));
    if (pcm_available != SIZE_MAX) pcm_available -= count;
    std::memset(out, 0x42, count);
    return count;
}
bool enqueue(eld::PacketHeader* packet, size_t frames, uint32_t pcm_bytes) {
    assert(frames == 1 && pcm_bytes == 1920 && packet->event == 0);
    assert(packet->offset == 32 && packet->layer_specific == 1 && packet->len == 14);
    auto* data = reinterpret_cast<uint8_t*>(packet + 1);
    uint32_t timestamp = 0;
    std::memcpy(&timestamp, data, sizeof(timestamp));
    timestamps.push_back(timestamp);
    payloads.emplace_back(data + packet->offset, data + packet->offset + packet->len);
    release(packet); // Ownership transfers even on false; ASAN/double-free assertion guards it.
    monotonic_now += enqueue_delay_us;
    return accept;
}
uint64_t clock_us() { return monotonic_now; }
void clock_opportunity() {
    ++opportunities;
    const auto health=eld::encoder_status(); // Must run outside encoder mutex.
    if(opportunity_arm) {
        const uint8_t target[]={1,2,3,4,5,6};
        eld::EncoderClockAdmission admission;
        opportunity_serial=eld::encoder_clock_begin_checked(target,6,health.generation,9,22,&admission);
        assert(opportunity_serial && admission.reason==eld::EncoderClockRejection::None);
        opportunity_arm=false;
    }
}
void failed(uint64_t generation) {
    ++failures;
    const auto snapshot = eld::encoder_status(); // Recovery must run outside the encoder lock.
    assert(snapshot.failed && !snapshot.active && snapshot.generation == generation);
}
bool peer(uint8_t* out, size_t size) {
    if (size != 6) return false;
    if (failed_peer_read) { out[0] = 0xff; return false; }
    const uint8_t address[] = {1,2,3,4,5,6};
    std::memcpy(out, address, sizeof(address));
    return true;
}
int32_t api_open(EldFdkHandle*, uint32_t, uint32_t) { return 0; }
int32_t api_set(EldFdkHandle, uint32_t, uint32_t) { return 0; }
uint32_t api_get(EldFdkHandle, uint32_t) { return 0; }
int32_t api_info(EldFdkHandle, EldFdkInfo*) { return 0; }
int32_t api_encode(EldFdkHandle, const EldFdkBufDesc*, const EldFdkBufDesc*, const EldFdkInArgs*, EldFdkOutArgs*) { return 0; }
int32_t api_close(EldFdkHandle*) { return 0; }
bool native_codec_info(const void* codec, uint8_t* out, size_t size) {
    ++custom_codec_reads;
    assert(size == 45); // The Pixel source ABI copies the whole 45-byte native storage.
    if (reject_codec || codec != native_cie.data()) return false;
    if (!incomplete_codec) std::memcpy(out, native_cie.data(), native_cie.size());
    return true;
}
bool native_peer_params(const void* peer, uint16_t* mtu) {
    ++custom_peer_reads;
    if (reject_params) { *mtu = UINT16_MAX; return false; }
    if (!peer) return false;
    const auto* bytes = static_cast<const uint8_t*>(peer);
    if (bytes[0] > 1 || bytes[1] > 1) return false;
    if (!incomplete_params) *mtu = static_cast<uint16_t>(bytes[2] | uint16_t(bytes[3]) << 8);
    return true;
}
void start(uint16_t mtu = 672) {
    const eld::Note8PeerParams p{true, true, mtu};
    native_cie.fill(0xd5);
    std::memcpy(native_cie.data(), cie, sizeof(cie));
    encoder->init(&p, custom_codec ? native_cie.data() : cie, read_pcm, enqueue);
}
void send_at(uint64_t now) { monotonic_now = now; encoder->send_frames(now); }
void tick(unsigned index) { send_at(1000000 + uint64_t(index) * 10000); }
void defaults() {
    fail_open = fail_reset = fail_encode = fail_alloc = oversized_read = false;
    negative_read = unaligned_read = failed_peer_read = false;
    reject_codec = reject_params = incomplete_codec = incomplete_params = false;
    accept = true;
    empty_encodes = 0;
    read_limit = 1920;
    pcm_available = SIZE_MAX;
    enqueue_delay_us = 0;
    assert(eld::make_configuration(cie, sizeof(cie)));
}
}
extern "C" int eld_fdk_open(const EldFdkApi*, uint32_t bitrate, EldFdkEncoder** out, EldFdkConfig* cfg) {
    if (fail_open) return ELD_FDK_CONFIG;
    assert(*out == nullptr);
    *out = new EldFdkEncoder;
    *cfg = {48000,2,480,2,bitrate,eld::max_au_bytes(bitrate),0,{0xf8,0xe6,0x50,0}};
    return ELD_FDK_OK;
}
extern "C" int eld_fdk_reset(EldFdkEncoder*) { return fail_reset ? ELD_FDK_CODEC : ELD_FDK_OK; }
extern "C" void eld_fdk_close(EldFdkEncoder** out) {
    if (*out) { ++closes; delete *out; *out = nullptr; }
}
extern "C" int eld_fdk_encode(EldFdkEncoder*, const int16_t* pcm, size_t frames, uint8_t* out, size_t cap, size_t* bytes) {
    assert(frames == 480 && cap >= 321);
    if (fail_encode) return ELD_FDK_CODEC;
    const bool zero = std::all_of(pcm, pcm + 960, [](auto sample) { return sample == 0; });
    if (zero) ++zero_inputs;
    else assert(std::all_of(pcm, pcm + 960, [](auto sample) { return sample == 0x4242; }));
    *bytes = empty_encodes ? (--empty_encodes, 0) : 10;
    std::memset(out, 0x7a, *bytes);
    return ELD_FDK_OK;
}
namespace eld {
bool note8_codec_info(const void* codec, uint8_t* out, size_t size) {
    ++note8_codec_reads;
    if (codec != cie || size < sizeof(cie)) return false;
    std::memcpy(out, cie, sizeof(cie));
    return true;
}
bool note8_peer_params(const void* opaque, Note8PeerParams* out) {
    ++note8_peer_reads;
    if (!opaque || !out) return false;
    *out = *static_cast<const Note8PeerParams*>(opaque);
    return true;
}
void scheduler_cases() {
    const auto failures_before = failures;
    auto begin = [] {
        defaults(); start();
        for (unsigned i = 0; i <= 20; ++i) tick(i);
        const auto health = eld::encoder_status();
        assert(health.packets == 21 && health.send_calls == 21 && health.pcm_read_calls == 11);
        assert(health.pcm_read_bytes == 11 * 1920 && health.max_gap_us == 10000);
        assert(health.clock_rebases == 0 && health.burst_max == 1);
    };
    const auto first = payloads.size();
    const auto reads_before = reads;
    begin();
    uint64_t now = 1200000 + 475000; // Exact 475 ms callback stall, with the PCM source still available.
    encoder->send_frames(now);
    auto health = eld::encoder_status();
    assert(health.max_gap_us == 475000 && health.max_due_lag_us == 465000);
    if (!catch_up) {
        assert(health.packets == 22 && health.burst_max == 1 && health.clock_rebases == 1);
        encoder->send_frames(now + 10000);
        assert(eld::encoder_status().packets == 23); // Legacy hosts retain the original one-AU policy.
        return;
    }
    assert(health.packets == 25 && health.burst_max == 4 && health.catch_up_aus == 3);
    unsigned callbacks = 1;
    while (health.packets < (now - 1000000) / 10000 + 1 && callbacks < 32) {
        const auto before = health.packets;
        now += 10000; ++callbacks; encoder->send_frames(now); health = eld::encoder_status();
        assert(health.packets - before >= 1 && health.packets - before <= 4);
    }
    assert(callbacks <= 17 && health.packets == (now - 1000000) / 10000 + 1);
    assert(health.clock_rebases == 0 && reads - reads_before == health.packets - 10);
    assert(health.pcm_read_bytes == health.pcm_read_calls * 1920);
    for (size_t i = first; i < payloads.size(); ++i) {
        assert(timestamps[i] == (i - first) * 480);
        assert((((payloads[i][0] & 15) << 8) | payloads[i][1]) == int((i - first) & 4095));
    }
    const auto caught_up = health.packets;
    now += 10000; encoder->send_frames(now); encoder->send_frames(now);
    assert(eld::encoder_status().packets == caught_up + 1); // Steady cadence and duplicate suppression restored.

    begin(); encoder->queue_length(10); now = 1675000;
    encoder->send_frames(now);
    assert(eld::encoder_status().packets == 23 && eld::encoder_status().clock_rebases == 0);
    encoder->queue_length(12); encoder->send_frames(now + 10000);
    assert(eld::encoder_status().packets == 23 && eld::encoder_status().clock_rebases == 0);
    const auto full_reads = eld::encoder_status().pcm_read_calls;
    encoder->send_frames(now + 20000);
    assert(eld::encoder_status().pcm_read_calls == full_reads && eld::encoder_status().queue_deferrals == 3);
    encoder->queue_length(0); encoder->send_frames(now + 30000);
    assert(eld::encoder_status().packets == 27 && eld::encoder_status().burst_max == 4);

    for (size_t available : {size_t(0), size_t(960), size_t(1920)}) {
        begin(); pcm_available = available; now = 1675000;
        const auto previous_reads = reads;
        encoder->send_frames(now); health = eld::encoder_status();
        assert(health.packets == 21 + available / 1920 && health.underflows == 1);
        assert(reads - previous_reads == (available == 1920 ? 2 : 1));
        assert(health.clock_rebases == 1 && health.active && !health.failed);
        pcm_available = SIZE_MAX;
        const auto previous_packets = health.packets;
        encoder->send_frames(now + 10000);
        assert(eld::encoder_status().packets == previous_packets + 1); // Missing/partial PCM cannot retain old debt.
    }

    begin(); accept = false; now = 1675000;
    const auto before_flush = payloads.size();
    encoder->send_frames(now); health = eld::encoder_status();
    assert(payloads.size() == before_flush + 1 && health.host_discards == 1);
    assert(health.packets == 21 && health.clock_rebases == 1 && health.active && !health.failed);
    accept = true; encoder->send_frames(now + 10000);
    assert(eld::encoder_status().packets == 22 && timestamps.back() == 22 * 480);

    defaults(); start(); tick(0); now = 1475000;
    encoder->send_frames(now);
    assert(eld::encoder_status().packets == 2 && eld::encoder_status().clock_rebases == 1);
    const auto primer_reads = reads;
    for (unsigned i = 1; i <= 8; ++i) {
        encoder->send_frames(now + i * 10000);
        assert(eld::encoder_status().packets == i + 2 && reads == primer_reads);
    }
    encoder->send_frames(now + 90000);
    assert(reads == primer_reads + 1 && eld::encoder_status().burst_max == 1); // A late primer never bursts.
    const auto metrics = eld::encoder_status();
    encoder->reset(); encoder->send_frames(now + 15000000);
    assert(eld::encoder_status().max_gap_us == metrics.max_gap_us); // An intentional reset clears the call anchor.
    assert(eld::encoder_status().send_calls == metrics.send_calls + 1);
    encoder->cleanup();
    assert(eld::encoder_status().send_calls == metrics.send_calls + 1 && failures == failures_before);
}
void clock_cases() {
    using Phase = EncoderClockPhase;
    if (!finite_clock) {
        assert(encoder_clock_status().phase == Phase::Unavailable);
        const uint8_t target[] = {1,2,3,4,5,6};
        assert(encoder_clock_begin(target,6,encoder_status().generation,9,22) == 0);
        return;
    }
    const uint8_t target[] = {1,2,3,4,5,6};
    auto begin = [] {
        defaults(); monotonic_now = 1000000; start();
        for (unsigned i = 0; i <= 20; ++i) tick(i);
        return encoder_status().generation;
    };
    defaults(); start();
    assert(!encoder_clock_begin(target,6,encoder_status().generation,9,22)); // Priming is not rate-adjusted.
    const auto generation = begin();
    uint8_t other[] = {6,5,4,3,2,1}, zero[6]{}, broadcast[6];
    std::memset(broadcast,0xff,sizeof(broadcast));
    for (auto peer : {other,zero,broadcast}) assert(!encoder_clock_begin(peer,6,generation,9,22));
    assert(!encoder_clock_begin(target,5,generation,9,22));
    assert(!encoder_clock_begin(target,6,generation+1,9,22));
    for (auto interval : {0u,10u,12u}) assert(!encoder_clock_begin(target,6,generation,interval,22));
    assert(!encoder_clock_begin(target,6,generation,9,0));
    assert(!encoder_clock_begin(target,6,generation,11,501));

    uint64_t previous_serial = 0;
    for (auto interval : {9u,11u}) {
        const auto first = payloads.size();
        const auto gen = begin();
        const auto initial_packets = encoder_status().packets;
        const auto serial = encoder_clock_begin(target,6,gen,interval,interval == 9 ? 22 : 80);
        assert(serial > previous_serial && !encoder_clock_cancel(previous_serial));
        previous_serial = serial;
        auto clock = encoder_clock_status();
        assert(clock.version == 1 && clock.phase == Phase::Armed && clock.effective_interval_tenths == 10);
        assert(clock.encoder_generation == gen && std::equal(target,target+6,clock.peer));
        assert(!encoder_clock_begin(target,6,gen,interval,22));
        uint64_t now = 1200000;
        for (unsigned i = 0; i < 200 && clock.phase != Phase::Completed; ++i) {
            now += 10000; send_at(now); clock = encoder_clock_status();
            assert(clock.phase == Phase::Active || clock.phase == Phase::Completed);
        }
        assert(clock.phase == Phase::Completed && clock.effective_interval_tenths == 10);
        const auto progress = interval == 9 ? clock.signed_progress_frames : -clock.signed_progress_frames;
        assert(progress >= int64_t(clock.target_drift_ms)*48 && progress < int64_t(clock.target_drift_ms)*48+480);
        assert(clock.actual_frames == (encoder_status().packets-initial_packets-1)*480);
        assert(clock.nominal_frames == (monotonic_now-clock.started_us)*48/1000);
        for (size_t i = first; i < payloads.size(); ++i) {
            assert(timestamps[i] == (i-first)*480);
            assert((((payloads[i][0]&15)<<8)|payloads[i][1]) == int((i-first)&4095));
        }
        const auto packets = encoder_status().packets;
        for (unsigned i = 1; i <= 5; ++i) send_at(now+i*10000);
        assert(encoder_status().packets == packets+5);
        assert(encoder_clock_cancel(serial)); // A terminal receipt remains idempotent.
    }
    auto arm = [&] {
        const auto gen = begin();
        const auto serial = encoder_clock_begin(target,6,gen,11,500);
        assert(serial); send_at(1210000);
        assert(encoder_clock_status().phase == Phase::Active);
        return serial;
    };
    auto serial = arm();
    enqueue_delay_us = 5000; send_at(1220000); enqueue_delay_us = 0;
    auto clock = encoder_clock_status();
    assert(clock.phase == Phase::Active && clock.actual_frames == 480 && clock.nominal_frames == 720);
    assert(clock.signed_progress_frames == -240); // Measure enqueue completion, not callback entry.
    assert(!encoder_clock_cancel(serial+1) && encoder_clock_cancel(serial));
    assert(encoder_clock_status().phase == Phase::Cancelled);

    serial = arm(); clock = encoder_clock_status();
    monotonic_now = clock.deadline_us;
    assert(encoder_clock_status().phase == Phase::Expired);
    assert(encoder_clock_status().effective_interval_tenths == 10);
    assert(encoder_clock_status().actual_frames == 0 && encoder_clock_cancel(serial));

    arm(); send_at(1685000);
    assert(encoder_clock_status().phase == Phase::Cancelled); // A radio/scheduler stall cannot claim drift.
    assert(encoder_clock_status().effective_interval_tenths == 10);
    arm(); encoder->queue_length(12);
    assert(encoder_clock_status().phase == Phase::Cancelled);
    encoder->queue_length(0); const auto queued_packets = encoder_status().packets;
    send_at(1685000);
    assert(encoder_status().packets == queued_packets+4); // Normal catch-up retains its queue debt.

    for (size_t available : {size_t(0),size_t(960)}) {
        arm(); pcm_available = available; send_at(1220000);
        assert(encoder_clock_status().phase == Phase::Cancelled && encoder_status().active);
        pcm_available = SIZE_MAX; send_at(1230000);
        assert(encoder_clock_status().effective_interval_tenths == 10);
    }
    arm(); accept = false; send_at(1220000);
    assert(encoder_clock_status().phase == Phase::Cancelled && encoder_clock_status().actual_frames == 0);
    assert(encoder_status().host_discards == 1 && encoder_status().active);
    arm(); encoder->reset();
    assert(encoder_clock_status().phase == Phase::Cancelled && encoder_clock_status().effective_interval_tenths == 10);
    arm(); encoder->cleanup();
    assert(encoder_clock_status().phase == Phase::Cancelled);
    arm(); monotonic_now = 1200000;
    assert(encoder_clock_status().phase == Phase::Cancelled); // A backwards clock is never successful drift.

    const auto gen = begin(); encoder->queue_length(12); send_at(1675000); encoder->queue_length(0);
    assert(!encoder_clock_begin(target,6,gen,9,22)); // Existing catch-up debt is not a rate-adjustment budget.
    const auto retry_gen=begin();
    monotonic_now=1215000;
    EncoderClockAdmission diagnostic;
    assert(!encoder_clock_begin_checked(target,6,retry_gen,9,22,&diagnostic));
    assert(diagnostic.reason==EncoderClockRejection::DueDebt && diagnostic.due_lag_us==5000 && diagnostic.media_age_us==15000);
    const auto before=opportunities;
    opportunity_arm=true; send_at(1220000);
    assert(opportunities==before+1 && opportunity_serial && encoder_clock_status().phase==Phase::Armed);
    encoder_clock_cancel(opportunity_serial);
    const auto accepted=opportunities;
    encoder->queue_length(12); send_at(1230000); assert(opportunities==accepted);
    encoder->queue_length(0); pcm_available=0; send_at(1240000); assert(opportunities==accepted);
    pcm_available=SIZE_MAX; accept=false; send_at(1250000); assert(opportunities==accepted);
    accept=true;
    begin(); const auto settled=opportunities; const auto packets_before=encoder_status().packets;
    pcm_available=1920; send_at(1220000);
    assert(encoder_status().packets==packets_before+1 && opportunities==settled);
    // One real AU followed by a burst underflow is not a settled clock opportunity.
    pcm_available=SIZE_MAX;
    assert(allocations.empty());
}
}
int main(int argc, char** argv) {
    if (argc == 2) {
        finite_clock = std::strcmp(argv[1], "--clock") == 0;
        catch_up = finite_clock || std::strcmp(argv[1], "--catch-up") == 0;
        custom_codec = catch_up || std::strcmp(argv[1], "--pixel") == 0 || std::strcmp(argv[1], "--pixel-codec") == 0;
        custom_peer = catch_up || std::strcmp(argv[1], "--pixel") == 0 || std::strcmp(argv[1], "--pixel-peer") == 0;
        assert(custom_codec || custom_peer);
    } else assert(argc == 1);
    defaults();
    eld::EncoderHost host{{api_open,api_set,api_get,api_info,api_encode,api_close}, allocate,release,failed,peer};
    if (custom_codec) host.codec_info = native_codec_info;
    if (custom_peer) host.peer_params = native_peer_params;
    host.catch_up_after_stall = catch_up;
    if (finite_clock) { host.monotonic_us = clock_us; host.clock_opportunity=clock_opportunity; }
    auto invalid = host; invalid.allocate = nullptr;
    assert(!eld::encoder_prepare(invalid));
    assert(eld::encoder_prepare(host) && eld::encoder_ready());
    assert(!eld::encoder_prepare(host));
    assert(encoder->interval_ms() == 10 && encoder->effective_frame_size() == 0);
    start();
    assert(eld::encoder_status().active && encoder->effective_frame_size() == 672);
    assert(eld::encoder_status().peer[5] == 6);
    for (unsigned i = 0; i < 10; ++i) tick(i);
    assert(reads == 0 && zero_inputs == 10 && payloads.size() == 10);
    tick(10);
    assert(reads == 1 && payloads.size() == 11 && timestamps.back() == 4800);
    const std::array<uint8_t,4> header{0xb0,0x0a,0x10,0x0a};
    assert(std::equal(header.begin(), header.end(), payloads.back().begin()));
    tick(10); // Duplicate tick cannot emit another packet.
    assert(payloads.size() == 11);
    encoder->queue_length(12);
    tick(11);
    assert(payloads.size() == 11 && reads == 1);
    encoder->queue_length(0);
    read_limit = 960;
    tick(12);
    assert(payloads.size() == 11 && eld::encoder_status().underflows == 1);
    tick(13);
    assert(payloads.size() == 12 && reads == 3);
    const auto previous_timestamp = timestamps.back();
    const auto previous_generation = eld::encoder_status().generation;
    encoder->flush();
    assert(eld::encoder_status().generation > previous_generation);
    tick(14);
    assert(timestamps.back() == previous_timestamp + 480); // Flush keeps RTP continuity.
    encoder->cleanup();
    assert(!eld::encoder_status().active && allocations.empty());
    const auto packet_count = payloads.size();
    tick(15);
    assert(payloads.size() == packet_count);

    defaults();
    start(100); // Peer MTU cannot hold a complete negotiated AU.
    assert(eld::encoder_status().failed && failures == 1);
    defaults(); fail_open = true; start();
    assert(eld::encoder_status().failed && failures == 2);
    defaults(); cie[12] = 0; start();
    assert(eld::encoder_status().failed && failures == 3);
    defaults(); start(); fail_encode = true; tick(0);
    assert(eld::encoder_status().failed && failures == 4 && allocations.empty());
    tick(1);
    assert(failures == 4); // Retired failure is reported exactly once.
    defaults(); start(); fail_alloc = true; tick(0);
    assert(failures == 5 && allocations.empty());
    defaults(); start();
    for (unsigned i = 0; i < 10; ++i) tick(i);
    const auto before_suspend = eld::encoder_status();
    accept = false; tick(10); tick(11); // Native tx-flush consumes late timer packets on pause.
    assert(eld::encoder_status().active && !eld::encoder_status().failed && failures == 5);
    assert(eld::encoder_status().packets == before_suspend.packets && allocations.empty());
    assert(eld::encoder_status().host_discards == 2);
    assert(timestamps.back() == 11 * 480);
    accept = true; encoder->reset(); tick(12); // Normal resume re-primes without a codec fallback.
    assert(eld::encoder_status().active && !eld::encoder_status().failed && failures == 5);
    assert(eld::encoder_status().packets == before_suspend.packets + 1 && timestamps.back() == 12 * 480);
    const std::array<uint8_t,4> resumed_header{0xb0,0x0c,0x10,0x0a};
    assert(std::equal(resumed_header.begin(), resumed_header.end(), payloads.back().begin()));
    const auto before_priming_flush = eld::encoder_status().packets;
    accept = false; tick(13); // A dropped primer does not count as priming the AirPods.
    accept = true;
    const auto before_resume_reads = reads;
    for (unsigned i = 14; i < 23; ++i) tick(i);
    assert(reads == before_resume_reads && eld::encoder_status().packets == before_priming_flush + 9);
    tick(23);
    assert(reads == before_resume_reads + 1 && eld::encoder_status().active && failures == 5);
    encoder->cleanup();
    assert(eld::encoder_status().host_discards == 3 && allocations.empty());
    defaults(); start(); fail_reset = true; encoder->reset();
    assert(failures == 6 && eld::encoder_status().host_discards == 0 && allocations.empty());
    defaults(); start(); empty_encodes = 17;
    for (unsigned i = 0; i < 17; ++i) tick(i);
    assert(failures == 7 && eld::encoder_status().failed);
    defaults(); start();
    for (unsigned i = 0; i < 10; ++i) tick(i);
    oversized_read = true; tick(10);
    assert(failures == 8 && allocations.empty());
    defaults(); start();
    const auto before = payloads.size();
    tick(0); tick(1000000); // Stalled controller never drains a million overdue periods.
    assert(payloads.size() == before + 2);
    encoder->cleanup();
    assert(allocations.empty() && closes > 0);
    const auto before_failure = eld::encoder_status().generation;
    eld::encoder_fail_route();
    const auto route_failure = eld::encoder_status();
    assert(route_failure.failed && !route_failure.active && failures == 9);
    assert(route_failure.peer[0] == 1 && route_failure.generation > before_failure);
    defaults(); failed_peer_read = true; start();
    assert(eld::encoder_status().active);
    for (auto value : eld::encoder_status().peer) assert(value == 0);
    eld::encoder_fail_route();
    for (auto value : eld::encoder_status().peer) assert(value == 0);
    for (bool* invalid_read : {&negative_read, &unaligned_read}) {
        defaults(); start();
        for (unsigned i = 0; i < 10; ++i) tick(i);
        *invalid_read = true;
        const auto before = failures; tick(10);
        assert(failures == before + 1 && eld::encoder_status().failed && allocations.empty());
    }
    for (bool* invalid : {&reject_codec, &incomplete_codec, &reject_params, &incomplete_params}) {
        if ((invalid == &reject_codec || invalid == &incomplete_codec) ? !custom_codec : !custom_peer) continue;
        defaults(); *invalid = true;
        const auto before = failures; start();
        assert(failures == before + 1 && eld::encoder_status().failed && allocations.empty());
    }
    if (custom_codec) {
        defaults();
        const eld::Note8PeerParams p{true, true, 672};
        const auto before = failures;
        encoder->init(&p, cie, read_pcm, enqueue); // A valid legacy CIE is not a Pixel-owned object.
        assert(failures == before + 1 && eld::encoder_status().failed);
    }
    if (custom_peer) {
        defaults(); start();
        const uint8_t invalid_peer[] = {2, 1, 0xa0, 2};
        const auto before = failures;
        encoder->init(invalid_peer, custom_codec ? native_cie.data() : cie, read_pcm, enqueue);
        assert(failures == before + 1 && eld::encoder_status().failed);
    }
    defaults(); start();
    encoder->init(nullptr, custom_codec ? native_cie.data() : cie, read_pcm, enqueue);
    assert(eld::encoder_status().failed);
    defaults(); start();
    encoder->init(nullptr, nullptr, read_pcm, enqueue);
    assert(eld::encoder_status().failed);
    defaults(); start();
    encoder->init(nullptr, nullptr, nullptr, enqueue);
    assert(eld::encoder_status().failed);
    assert(custom_codec ? custom_codec_reads > 0 && note8_codec_reads == 0 : note8_codec_reads > 0);
    assert(custom_peer ? custom_peer_reads > 0 && note8_peer_reads == 0 : note8_peer_reads > 0);
    eld::scheduler_cases();
    eld::clock_cases();
    encoder->cleanup();
    assert(allocations.empty());
    return 0;
}
