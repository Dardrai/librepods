// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cstddef>
#include <cstdint>

namespace eld {
inline constexpr size_t kCodecInfoSize = 17;
inline constexpr uint32_t kSampleRate = 48000;
inline constexpr size_t kFrameSamples = 480;
inline constexpr size_t kChannels = 2;
inline constexpr uint32_t kDefaultBitrate = 256000;
inline constexpr size_t kAuHeaderSize = 4;
inline constexpr size_t kMaximumAu = 321;

bool is_apple(const uint8_t* info, size_t size);
bool valid_configuration(const uint8_t* info, size_t size);
bool make_configuration(uint8_t* out, size_t size, uint32_t bitrate = kDefaultBitrate);
bool make_capabilities(uint8_t* out, size_t size);
bool select_configuration(const uint8_t* peer, size_t size, uint8_t* out, size_t out_size,
                          uint32_t preferred_bitrate = kDefaultBitrate);
uint32_t bitrate(const uint8_t* info, size_t size);
uint32_t max_au_bytes(uint32_t bitrate);
bool write_au_header(uint8_t* out, size_t size, uint16_t sequence, size_t au_size);
}
