#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>

namespace dolby_pose::wire {
inline constexpr char kCommandName[] = "vendor.librepods.dolby.pose-command";
inline constexpr char kProbeName[] = "vendor.librepods.dolby.pose-probe";
constexpr uint32_t kVersion = 1, kHeaderBytes = 8, kPayloadBytes = 96, kParamBytes = 104;
constexpr uint32_t kProbePayloadBytes = 32, kProbeParamBytes = 40, kProbeCanonicalOnly = 1;
constexpr size_t kAckFlagsOffset = kHeaderBytes + 46, kAckStatusOffset = kHeaderBytes + 68;
constexpr uint64_t kMaxPositiveLong = INT64_MAX;
constexpr uint64_t kMaxFreshNs = 250000000ULL;
enum class Opcode : uint32_t { Begin = 1, Update = 2, End = 3 };
enum AckFlag : uint16_t { Ack = 1, Armed = 2, CanonicalAccepted = 4, Retired = 8, NativeApplied = 16 };
constexpr uint32_t kAckAccepted = 0;
using Token = std::array<uint8_t, 16>;
using Mac = std::array<uint8_t, 6>;
using Frame = std::array<uint8_t, kParamBytes>;
using ProbeFrame = std::array<uint8_t, kProbeParamBytes>;
struct Span { const uint8_t *data = nullptr; size_t size = 0; };
struct Command {
    uint32_t version = kVersion;
    Opcode opcode = Opcode::Begin;
    Token token{};
    uint64_t generation = 0, sequence = 0;
    Mac mac{};
    uint64_t sensorNs = 0, arrivalBoottimeNs = 0;
    uint32_t discontinuity = 0;
    std::array<float, 3> rotationVector{}, bodyOmega{};
};
struct Probe {
    uint32_t version = kVersion, payloadBytes = kPayloadBytes, paramBytes = kParamBytes,
             ackPayloadBytes = kPayloadBytes, handlingTransaction = 0, uid = UINT32_MAX, pid = 0,
             flags = kProbeCanonicalOnly;
};
inline uint16_t read16(const uint8_t *p) { return uint16_t(p[0]) | uint16_t(p[1]) << 8; }
inline uint32_t read32(const uint8_t *p) {
    return uint32_t(p[0]) | uint32_t(p[1]) << 8 | uint32_t(p[2]) << 16 | uint32_t(p[3]) << 24;
}
inline uint64_t read64(const uint8_t *p) { return read32(p) | uint64_t(read32(p + 4)) << 32; }
inline void write16(uint8_t *p, uint16_t v) { p[0] = v; p[1] = v >> 8; }
inline void write32(uint8_t *p, uint32_t v) { for (unsigned i = 0; i < 4; ++i) p[i] = v >> (8 * i); }
inline void write64(uint8_t *p, uint64_t v) { write32(p, uint32_t(v)); write32(p + 4, uint32_t(v >> 32)); }
inline float readFloat(const uint8_t *p) { uint32_t bits = read32(p); float value; memcpy(&value, &bits, 4); return value; }
inline void writeFloat(uint8_t *p, float v) { uint32_t bits; memcpy(&bits, &v, 4); write32(p, bits); }
static_assert(sizeof(float) == 4 && std::numeric_limits<float>::is_iec559, "IEEE float32 wire values");
inline bool commandIndex(uint32_t index) { return (index & 0xffff0000U) == 0x60010000U && (index & 0xffffU) >= 0x8000U; }
inline bool probeIndex(uint32_t index) { return (index & 0xffff0000U) == 0xe0010000U && (index & 0xffffU) >= 0x8000U; }
inline bool positiveLong(uint64_t value) { return value > 0 && value <= kMaxPositiveLong; }

inline bool encode(uint32_t index, const Command &value, Frame *out) {
    if (!out || !commandIndex(index)) return false;
    out->fill(0); write32(out->data(), kParamBytes); write32(out->data() + 4, index);
    uint8_t *p = out->data() + kHeaderBytes;
    write32(p, value.version); write32(p + 4, uint32_t(value.opcode));
    memcpy(p + 8, value.token.data(), value.token.size());
    write64(p + 24, value.generation); write64(p + 32, value.sequence); memcpy(p + 40, value.mac.data(), value.mac.size());
    write64(p + 48, value.sensorNs); write64(p + 56, value.arrivalBoottimeNs); write32(p + 64, value.discontinuity);
    for (size_t i = 0; i < 3; ++i) { writeFloat(p + 72 + 4 * i, value.rotationVector[i]); writeFloat(p + 84 + 4 * i, value.bodyOmega[i]); }
    return true;
}
inline bool decode(Span frame, uint32_t expectedIndex, Command *out) {
    if (!out || !frame.data || frame.size != kParamBytes || !commandIndex(expectedIndex) ||
        read32(frame.data) != kParamBytes || read32(frame.data + 4) != expectedIndex) return false;
    const uint8_t *p = frame.data + kHeaderBytes;
    if (read16(p + 46) || read32(p + 68)) return false;
    Command value;
    value.version = read32(p); value.opcode = Opcode(read32(p + 4)); memcpy(value.token.data(), p + 8, value.token.size());
    value.generation = read64(p + 24); value.sequence = read64(p + 32); memcpy(value.mac.data(), p + 40, value.mac.size());
    value.sensorNs = read64(p + 48); value.arrivalBoottimeNs = read64(p + 56); value.discontinuity = read32(p + 64);
    for (size_t i = 0; i < 3; ++i) { value.rotationVector[i] = readFloat(p + 72 + 4 * i); value.bodyOmega[i] = readFloat(p + 84 + 4 * i); }
    *out = value; return true;
}
inline Frame acknowledge(const Frame &request, uint16_t flags) {
    Frame out = request;
    write16(out.data() + kAckFlagsOffset, flags); write32(out.data() + kAckStatusOffset, kAckAccepted);
    return out;
}
inline uint16_t acceptedFlags(Opcode opcode) {
    return opcode == Opcode::Begin ? Ack | Armed : opcode == Opcode::Update ? Ack | Armed | CanonicalAccepted :
           opcode == Opcode::End ? Ack | Retired : 0;
}
inline bool ackMatches(const Frame &request, Span ack) {
    if (!ack.data || ack.size != kParamBytes) return false;
    Command command;
    if (!decode({request.data(), request.size()}, read32(request.data() + 4), &command) ||
        command.version != kVersion || !acceptedFlags(command.opcode) ||
        read16(ack.data + kAckFlagsOffset) != acceptedFlags(command.opcode) ||
        read32(ack.data + kAckStatusOffset) != kAckAccepted) return false;
    for (size_t i = 0; i < request.size(); ++i) {
        if ((i >= kAckFlagsOffset && i < kAckFlagsOffset + 2) || (i >= kAckStatusOffset && i < kAckStatusOffset + 4)) continue;
        if (request[i] != ack.data[i]) return false;
    }
    return true;
}
inline bool encodeProbe(uint32_t index, const Probe &value, ProbeFrame *out) {
    if (!out || !probeIndex(index)) return false;
    out->fill(0); write32(out->data(), kProbeParamBytes); write32(out->data() + 4, index);
    const uint32_t words[] = {value.version, value.payloadBytes, value.paramBytes, value.ackPayloadBytes,
                             value.handlingTransaction, value.uid, value.pid, value.flags};
    for (size_t i = 0; i < 8; ++i) write32(out->data() + kHeaderBytes + i * 4, words[i]);
    return true;
}
inline bool decodeProbe(Span bytes, uint32_t index, Probe *out) {
    if (!out || !bytes.data || bytes.size != kProbeParamBytes || !probeIndex(index) ||
        read32(bytes.data) != kProbeParamBytes || read32(bytes.data + 4) != index) return false;
    const uint8_t *p = bytes.data + kHeaderBytes;
    *out = {read32(p), read32(p+4), read32(p+8), read32(p+12), read32(p+16), read32(p+20), read32(p+24), read32(p+28)};
    return true;
}
} // namespace dolby_pose::wire
