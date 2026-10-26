// SPDX-License-Identifier: GPL-3.0-or-later
#include "wire.h"
#include <cstring>

namespace eld {
static constexpr uint8_t kConfiguration[kCodecInfoSize] = {
    16, 0, 0xff, 0x4c, 0, 0, 0, 1, 0x80, 0, 0x80, 0, 0x84, 0, 0x83, 0xe8, 0
};

static bool valid_bitrate(uint32_t value) {
    return value >= 128000 && value <= kDefaultBitrate && value % 1000 == 0;
}

bool is_apple(const uint8_t* info, size_t size) {
    return info && size >= kCodecInfoSize &&
        std::memcmp(info, kConfiguration, 9) == 0;
}

uint32_t bitrate(const uint8_t* info, size_t size) {
    return is_apple(info, size)
        ? (static_cast<uint32_t>(info[14] & 0x7f) << 16) |
          (static_cast<uint32_t>(info[15]) << 8) | info[16] : 0;
}

uint32_t max_au_bytes(uint32_t value) {
    return valid_bitrate(value) ? (value + 799) / 800 + 1 : 0;
}

bool valid_configuration(const uint8_t* info, size_t size) {
    return is_apple(info, size) && info[9] == 0 && info[10] == 0x80 && info[11] == 0 &&
        info[12] == 0x84 && info[13] == 0 && (info[14] & 0x80) != 0 &&
        valid_bitrate(bitrate(info, size));
}

bool make_configuration(uint8_t* out, size_t size, uint32_t value) {
    if (!out || size < kCodecInfoSize || !valid_bitrate(value)) return false;
    uint8_t target[kCodecInfoSize];
    std::memcpy(target, kConfiguration, sizeof(target));
    // The tested Apple CIE keeps this flag even with a CBR encoder.
    target[14] = 0x80 | static_cast<uint8_t>(value >> 16);
    target[15] = static_cast<uint8_t>(value >> 8);
    target[16] = static_cast<uint8_t>(value);
    std::memcpy(out, target, sizeof(target));
    return true;
}

bool make_capabilities(uint8_t* out, size_t size) {
    return make_configuration(out, size);
}

bool select_configuration(const uint8_t* peer, size_t size, uint8_t* out, size_t out_size,
                          uint32_t preferred) {
    if (!is_apple(peer, size) || !valid_bitrate(preferred) ||
        (peer[10] & 0x80) == 0 || (peer[12] & 0x84) != 0x84 ||
        (peer[14] & 0x80) == 0) return false;
    const uint32_t maximum = bitrate(peer, size);
    uint32_t selected = preferred;
    if (maximum && selected > maximum) selected = maximum / 1000 * 1000;
    return make_configuration(out, out_size, selected);
}

bool write_au_header(uint8_t* out, size_t size, uint16_t sequence, size_t au_size) {
    if (!out || size < kAuHeaderSize || au_size == 0 || au_size > kMaximumAu) return false;
    sequence &= 0xfff;
    out[0] = 0xb0 | static_cast<uint8_t>(sequence >> 8);
    out[1] = static_cast<uint8_t>(sequence);
    out[2] = 0x10 | static_cast<uint8_t>(au_size >> 8);
    out[3] = static_cast<uint8_t>(au_size);
    return true;
}
}
