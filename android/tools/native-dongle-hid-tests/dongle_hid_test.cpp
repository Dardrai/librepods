// SPDX-License-Identifier: GPL-3.0-or-later
#define LIBREPODS_DONGLE_HID_HOST_TEST
#include "../../app/src/main/cpp/dongle_hid.cpp"
#include <cassert>

namespace {
int calls, descriptor_reads, reported_size = 54, feature_result = 64;
unsigned long failing_request;
int failure_code;
const char* device_serial;
int serial_count;
bool invalid_header, changed_descriptor_size, unchanged_get;
hidraw_devinfo device_info{BUS_USB, static_cast<int16_t>(0xcafe), 0x4015};
std::array<uint8_t, 64> last_write;

void reset() {
    calls = descriptor_reads = 0;
    reported_size = 54; feature_result = 64; failing_request = 0; failure_code = EACCES;
    invalid_header = changed_descriptor_size = unchanged_get = false;
    device_info = {BUS_USB, static_cast<int16_t>(0xcafe), 0x4015};
    last_write.fill(0);
    device_serial = "BOARD-A"; serial_count = 8;
}
} // namespace

int ioctl(int fd, unsigned long request, void* value) {
    assert(fd == 7);
    ++calls;
    if (request == failing_request) { errno = failure_code; return -1; }
    if (request == HIDIOCGRAWINFO) { *static_cast<hidraw_devinfo*>(value) = device_info; return 0; }
    if (request == HIDIOCGRDESCSIZE) { *static_cast<int*>(value) = reported_size; return 0; }
    if (request == HIDIOCGRAWUNIQ(65)) {
        std::memcpy(value, device_serial, std::strlen(device_serial) + 1);
        return serial_count;
    }
    if (request == HIDIOCGRDESC) {
        ++descriptor_reads;
        auto* descriptor = static_cast<hidraw_report_descriptor*>(value);
        assert(descriptor->size == static_cast<uint32_t>(reported_size));
        if (descriptor->size == 4096) { errno = EINVAL; return -1; }
        std::memset(descriptor->value, 0xa5, descriptor->size);
        if (changed_descriptor_size) ++descriptor->size;
        return 0;
    }
    auto* bytes = static_cast<uint8_t*>(value);
    assert(bytes[0] == 2 || bytes[0] == 3);
    assert(request == HIDIOCGFEATURE(64) || request == HIDIOCSFEATURE(64));
    if (request == HIDIOCGFEATURE(64)) {
        for (int at = 1; at < 64; ++at) assert(bytes[at] == 0);
        if (!unchanged_get) {
            const uint8_t header[] = {bytes[0], 'A', 'P', 'D', 'G', 1, 64};
            std::memcpy(bytes, header, sizeof(header));
        }
    }
    if (request == HIDIOCSFEATURE(64)) std::memcpy(last_write.data(), bytes, 64);
    if (invalid_header) bytes[5] = 2;
    return feature_result;
}

int main() {
    std::array<uint8_t, descriptor_limit> description{};
    std::array<uint8_t, report_size> bytes{}, payload{3, 'A', 'P', 'D', 'G', 1, 64};
    Failure failure;
    int size;
    reset();
    assert(identity(7, 0x4015, "BOARD-A", 7, failure) && calls == 2);
    reset(); device_info.product = 0x4005;
    assert(identity(7, 0x4005, "BOARD-A", 7, failure));
    reset();
    assert(!identity(7, 0x4005, "BOARD-A", 7, failure) && failure.code == ENODEV && calls == 1);
    reset(); device_serial = "BOARD-B";
    assert(!identity(7, 0x4015, "BOARD-A", 7, failure) && failure.code == ENODEV);
    reset(); serial_count = 7;
    assert(!identity(7, 0x4015, "BOARD-A", 7, failure) && failure.code == ENODEV);
    reset(); failing_request = HIDIOCGRAWUNIQ(65); failure_code = ENOTTY;
    assert(!identity(7, 0x4015, "BOARD-A", 7, failure) && failure.code == ENOTTY && calls == 2);
    reset();
    assert(!identity(7, 0, "BOARD-A", 7, failure) && calls == 0);
    assert(!identity(7, 0x4001, "BOARD-A", 7, failure) && calls == 0);
    assert(!identity(7, 0x4015, "BOARD-A", 0, failure) && calls == 0);
    assert(!identity(7, 0x4015, "BOARD-A", 65, failure) && calls == 0);
    assert(!identity(7, 0x4015, "BOARD A", 7, failure) && calls == 0);
    assert(!identity(7, 0x4015, nullptr, 7, failure) && calls == 0);
    reset();
    assert(descriptor(7, description, size, failure) && size == 54 && descriptor_reads == 1);
    assert(description[53] == 0xa5 && description[54] == 0);
    for (const int pid : {0x4005, 0x4015}) {
        reset(); device_info.product = static_cast<int16_t>(pid);
        assert(descriptor(7, description, size, failure));
    }
    for (const int invalid : {-1, 0, 4097}) {
        reset(); reported_size = invalid;
        assert(!descriptor(7, description, size, failure) && failure.code == EOVERFLOW && descriptor_reads == 0);
    }
    reset(); reported_size = 4096;
    assert(!descriptor(7, description, size, failure) && failure.code == EINVAL && descriptor_reads == 1);
    reset(); device_info.bustype = 5;
    assert(!descriptor(7, description, size, failure) && failure.code == ENODEV && calls == 1);
    reset(); device_info.vendor = 123;
    assert(!descriptor(7, description, size, failure) && failure.code == ENODEV && calls == 1);
    reset(); device_info.product = 0x4001;
    assert(!descriptor(7, description, size, failure) && failure.code == ENODEV && calls == 1);
    for (const auto request : {HIDIOCGRAWINFO, HIDIOCGRDESCSIZE, HIDIOCGRDESC}) {
        reset(); failing_request = request;
        assert(!descriptor(7, description, size, failure) && failure.code == EACCES);
    }
    reset(); changed_descriptor_size = true;
    assert(!descriptor(7, description, size, failure) && failure.code == EIO);
    reset();
    assert(!descriptor(-1, description, size, failure) && failure.code == EBADF && calls == 0);

    for (const int report : {2, 3}) {
        reset();
        assert(exchange(7, 1, report, nullptr, 0, bytes, failure) && valid_report(bytes.data(), bytes.size(), report) && calls == 1);
    }
    reset(); payload[31] = 0x77;
    assert(exchange(7, 2, 3, payload.data(), payload.size(), bytes, failure) && calls == 1);
    assert(last_write == payload && bytes[31] == 0 && valid_report(bytes.data(), bytes.size(), 3));
    for (const int offset : {0, 1, 5, 6}) {
        reset(); auto invalid = payload; invalid[offset] ^= 1;
        assert(!exchange(7, 2, 3, invalid.data(), invalid.size(), bytes, failure) && calls == 0 && failure.code == EINVAL);
    }
    reset();
    assert(!exchange(7, 0, 3, nullptr, 0, bytes, failure));
    assert(!exchange(7, 3, 3, nullptr, 0, bytes, failure));
    assert(!exchange(7, 1, 1, nullptr, 0, bytes, failure));
    assert(!exchange(7, 1, 4, nullptr, 0, bytes, failure));
    assert(!exchange(7, 1, 3, payload.data(), 1, bytes, failure));
    assert(!exchange(7, 2, 3, payload.data(), 63, bytes, failure));
    assert(!exchange(7, 2, 3, payload.data(), 65, bytes, failure));
    assert(!exchange(7, 2, 3, nullptr, 64, bytes, failure));
    assert(!exchange(-1, 1, 3, nullptr, 0, bytes, failure) && failure.code == EBADF && calls == 0);
    for (const int operation : {1, 2}) {
        reset(); feature_result = 63;
        assert(!exchange(7, operation, 3, payload.data(), operation == 1 ? 0 : 64, bytes, failure) && failure.code == EIO && calls == 1);
        reset(); failing_request = operation == 1 ? HIDIOCGFEATURE(64) : HIDIOCSFEATURE(64); failure_code = EINTR;
        assert(!exchange(7, operation, 3, payload.data(), operation == 1 ? 0 : 64, bytes, failure) && failure.code == EINTR && calls == 1);
    }
    reset(); invalid_header = true;
    assert(!exchange(7, 1, 3, nullptr, 0, bytes, failure) && failure.code == EPROTO && calls == 1);
    reset(); unchanged_get = true;
    assert(!exchange(7, 1, 3, nullptr, 0, bytes, failure) && failure.code == EPROTO && calls == 1);
    return 0;
}
