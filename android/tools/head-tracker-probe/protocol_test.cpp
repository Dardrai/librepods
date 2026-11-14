#include "protocol.h"

#include <cassert>
#include <cstdio>

using namespace head_tracker_probe;

int main() {
    std::array<uint8_t, 6> mac{};
    assert(parseMac("12:34:56:78:9a:BC", &mac));
    const auto identity = identityReport(mac);
    assert(identity[0] == 2);
    assert(std::memcmp(identity.data() + 1, kDescription, 23) == 0);
    for (size_t i = 24; i < 32; ++i) assert(identity[i] == 0);
    assert(identity[32] == 'B' && identity[33] == 'T');
    assert(std::memcmp(identity.data() + 34, mac.data(), 6) == 0);
    for (const char* invalid : {"", "12:34:56:78:9A:B", "12-34:56:78:9A:BC",
                               "12:34:56:78:9A:BG", "00:00:00:00:00:00",
                               "FF:FF:FF:FF:FF:FF", "12:34:56:78:9A:BCX"}) {
        assert(!parseMac(invalid, &mac));
    }
    assert(!parseMac(nullptr, &mac));
    assert(!parseMac("12:34:56:78:9A:BC", nullptr));

    Control control;
    assert(!control.enabled());
    const uint8_t on[] = {1, 3};
    assert(control.set(on, sizeof(on)) && !control.enabled());
    control.identityRead = true;
    assert(control.enabled() && control.intervalNs() == 10000000);
    for (unsigned raw = 0; raw < 256; ++raw) {
        const uint8_t report[] = {1, static_cast<uint8_t>(raw)};
        assert(control.set(report, sizeof(report)));
        assert(control.enabled() == ((raw & 3) == 3));
        assert(control.intervalNs() >= 10000000 && control.intervalNs() <= 100000000);
    }
    assert(control.intervalNs() == 100000000);
    assert(!control.set(on, 1) && !control.set(on, 3) && !control.set(nullptr, 2));
    const uint8_t wrongId[] = {2, 3};
    assert(!control.set(wrongId, sizeof(wrongId)));
    assert(kStationaryInput[0] == 1 && kStationaryInput.size() == 14);
    for (size_t i = 1; i < kStationaryInput.size(); ++i) assert(kStationaryInput[i] == 0);

    // Read the actual HID items to catch accidental report-size/ID drift.
    unsigned bits[3][3]{};
    unsigned reportId = 0, reportSize = 0, reportCount = 0, collections = 0;
    for (size_t offset = 0; offset < sizeof(kDescriptor);) {
        const uint8_t prefix = kDescriptor[offset++];
        assert(prefix != 0xfe);
        const unsigned size = (prefix & 3) == 3 ? 4 : prefix & 3;
        assert(offset + size <= sizeof(kDescriptor));
        uint32_t value = 0;
        for (unsigned i = 0; i < size; ++i) value |= uint32_t(kDescriptor[offset++]) << (i * 8);
        switch (prefix & 0xfc) {
            case 0x84: reportId = value; assert(reportId < 3); break;
            case 0x74: reportSize = value; break;
            case 0x94: reportCount = value; break;
            case 0xa0: ++collections; break;
            case 0xc0: assert(collections > 0); --collections; break;
            case 0x80: bits[0][reportId] += reportSize * reportCount; break;
            case 0x90: bits[1][reportId] += reportSize * reportCount; break;
            case 0xb0: bits[2][reportId] += reportSize * reportCount; break;
            default: break;
        }
    }
    assert(collections == 0);
    assert(bits[0][1] == 104 && bits[0][2] == 0);
    assert(bits[1][1] == 0 && bits[1][2] == 0);
    assert(bits[2][1] == 8 && bits[2][2] == 312);
    std::puts("Head tracker identity, controls, stationary pose, HID report sizes: passed");
}
