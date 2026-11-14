#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace head_tracker_probe {

constexpr char kDescription[] = "#AndroidHeadTracker#1.0";
static_assert(sizeof(kDescription) - 1 == 23);

// Android's v1 example: feature 2 = identity, feature 1 = controls, input 1 = pose.
constexpr uint8_t kDescriptor[] = {
    0x05, 0x20, 0x09, 0xe1, 0xa1, 0x01,
    0x85, 0x02,
    0x0a, 0x08, 0x03, 0x15, 0x00, 0x26, 0xff, 0x00,
    0x75, 0x08, 0x95, 0x17, 0xb1, 0x03,
    0x0a, 0x02, 0x03, 0x95, 0x10, 0xb1, 0x03,
    0x85, 0x01,
    0x0a, 0x16, 0x03, 0x15, 0x00, 0x25, 0x01,
    0x75, 0x01, 0x95, 0x01, 0xa1, 0x02,
    0x0a, 0x40, 0x08, 0x0a, 0x41, 0x08, 0xb1, 0x00, 0xc0,
    0x0a, 0x19, 0x03, 0xa1, 0x02,
    0x0a, 0x55, 0x08, 0x0a, 0x51, 0x08, 0xb1, 0x00, 0xc0,
    0x0a, 0x0e, 0x03, 0x15, 0x00, 0x25, 0x3f,
    0x35, 0x0a, 0x45, 0x64, 0x75, 0x06, 0x95, 0x01,
    0x66, 0x01, 0x10, 0x55, 0x0d, 0xb1, 0x02,
    0x0a, 0x44, 0x05, 0x16, 0x01, 0x80, 0x26, 0xff, 0x7f,
    0x37, 0x5f, 0x4f, 0x46, 0xed, 0x47, 0xa1, 0xb0, 0xb9, 0x12,
    0x65, 0x00, 0x55, 0x08, 0x75, 0x10, 0x95, 0x03, 0x81, 0x02,
    0x0a, 0x45, 0x05, 0x35, 0xe0, 0x45, 0x20, 0x55, 0x00, 0x81, 0x02,
    0x0a, 0x46, 0x05, 0x16, 0x00, 0x00, 0x26, 0xff, 0x00,
    0x35, 0x00, 0x45, 0x00, 0x75, 0x08, 0x95, 0x01, 0x81, 0x02,
    0xc0,
};

inline int hexDigit(char value) {
    if (value >= '0' && value <= '9') return value - '0';
    if (value >= 'A' && value <= 'F') return value - 'A' + 10;
    if (value >= 'a' && value <= 'f') return value - 'a' + 10;
    return -1;
}

inline bool parseMac(const char* text, std::array<uint8_t, 6>* mac) {
    if (!text || !mac || std::strlen(text) != 17) return false;
    std::array<uint8_t, 6> parsed{};
    unsigned nonzero = 0;
    unsigned notBroadcast = 0;
    for (size_t i = 0; i < parsed.size(); ++i) {
        const int high = hexDigit(text[i * 3]);
        const int low = hexDigit(text[i * 3 + 1]);
        if (high < 0 || low < 0 || (i < 5 && text[i * 3 + 2] != ':')) return false;
        parsed[i] = static_cast<uint8_t>((high << 4) | low);
        nonzero |= parsed[i];
        notBroadcast |= parsed[i] ^ 0xff;
    }
    if (!nonzero || !notBroadcast) return false;
    *mac = parsed;
    return true;
}

inline std::array<uint8_t, 40> identityReport(const std::array<uint8_t, 6>& mac) {
    std::array<uint8_t, 40> report{};
    report[0] = 2;
    std::memcpy(report.data() + 1, kDescription, sizeof(kDescription) - 1);
    report[32] = 'B';
    report[33] = 'T';
    std::memcpy(report.data() + 34, mac.data(), mac.size());
    return report;
}

struct Control {
    uint8_t value = 0;
    bool identityRead = false;
    bool hostSet = false;

    bool set(const uint8_t* report, size_t size) {
        if (!report || size != 2 || report[0] != 1) return false;
        value = report[1];
        hostSet = true;
        return true;
    }

    bool enabled() const { return identityRead && hostSet && (value & 3) == 3; }
    int64_t intervalNs() const { return 10000000LL + (value >> 2) * 90000000LL / 63; }
};

// A stationary head: zero rotation vector/velocity and unchanged reset counter.
constexpr std::array<uint8_t, 14> kStationaryInput{1};

}  // namespace head_tracker_probe
