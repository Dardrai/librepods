// SPDX-License-Identifier: GPL-3.0-or-later
#include <array>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>

#ifdef LIBREPODS_DONGLE_HID_HOST_TEST
#include "hidraw_test_platform.h"
#else
#include <jni.h>
#include <linux/hidraw.h>
#include <linux/input.h>
#include <sys/ioctl.h>
#endif

namespace {
constexpr size_t report_size = 64;
constexpr size_t descriptor_limit = 4096;
struct Failure { const char* stage = "unknown"; int code = EIO; };

bool failed(Failure& failure, const char* stage, int code) {
    failure = {stage, code};
    return false;
}

bool valid_report(const uint8_t* bytes, size_t size, int report) {
    const uint8_t header[] = {static_cast<uint8_t>(report), 'A', 'P', 'D', 'G', 1, 64};
    return size == report_size && bytes != nullptr && !std::memcmp(bytes, header, sizeof(header));
}

bool device_identity(int fd, int product, Failure& failure) {
    if (fd < 0) return failed(failure, "identity fd", EBADF);
    if (product != 0 && product != 0x4005 && product != 0x4015)
        return failed(failure, "expected product", EINVAL);
    hidraw_devinfo info{};
    if (ioctl(fd, HIDIOCGRAWINFO, &info) < 0) return failed(failure, "device info", errno);
    if (info.bustype != BUS_USB || static_cast<uint16_t>(info.vendor) != 0xcafe ||
        (info.product != 0x4005 && info.product != 0x4015) || (product != 0 && info.product != product))
        return failed(failure, "device identity", ENODEV);
    return true;
}

bool identity(int fd, int product, const char* serial, size_t size, Failure& failure) {
    if (product != 0x4005 && product != 0x4015) return failed(failure, "expected product", EINVAL);
    if (serial == nullptr || size < 1 || size > 64) return failed(failure, "expected serial", EINVAL);
    for (size_t at = 0; at < size; ++at)
        if (static_cast<unsigned char>(serial[at]) < 33 || static_cast<unsigned char>(serial[at]) > 126)
            return failed(failure, "expected serial", EINVAL);
    if (!device_identity(fd, product, failure)) return false;
    std::array<char, 65> actual{};
    const int count = ioctl(fd, HIDIOCGRAWUNIQ(actual.size()), actual.data());
    if (count < 0) return failed(failure, "device serial", errno);
    if (count != static_cast<int>(size + 1) || actual[size] != '\0' || std::memcmp(actual.data(), serial, size))
        return failed(failure, "device serial mismatch", ENODEV);
    return true;
}

bool descriptor(int fd, std::array<uint8_t, descriptor_limit>& bytes, int& size, Failure& failure) {
    if (!device_identity(fd, 0, failure)) return false;
    size = 0;
    if (ioctl(fd, HIDIOCGRDESCSIZE, &size) < 0) return failed(failure, "descriptor size", errno);
    if (size < 1 || size > static_cast<int>(descriptor_limit))
        return failed(failure, "descriptor size", EOVERFLOW);
    hidraw_report_descriptor value{};
    // hidraw requires the actual descriptor length here, not the buffer capacity.
    value.size = static_cast<uint32_t>(size);
    if (ioctl(fd, HIDIOCGRDESC, &value) < 0) return failed(failure, "descriptor read", errno);
    if (value.size != static_cast<uint32_t>(size)) return failed(failure, "descriptor length", EIO);
    std::memcpy(bytes.data(), value.value, static_cast<size_t>(size));
    return true;
}

bool exchange(int fd, int operation, int report, const uint8_t* payload, size_t size,
              std::array<uint8_t, report_size>& bytes, Failure& failure) {
    if (fd < 0) return failed(failure, "feature fd", EBADF);
    if ((operation != 1 && operation != 2) || (report != 2 && report != 3) ||
        (operation == 1 ? size != 0 : !valid_report(payload, size, report)))
        return failed(failure, "feature request", EINVAL);
    bytes = {static_cast<uint8_t>(report)};
    if (operation == 2) std::memcpy(bytes.data(), payload, report_size);
    const int count = ioctl(fd, operation == 1 ? HIDIOCGFEATURE(report_size) : HIDIOCSFEATURE(report_size), bytes.data());
    const char* stage = operation == 1 ? "get feature" : "set feature";
    if (count < 0) return failed(failure, stage, errno);
    if (count != static_cast<int>(report_size)) return failed(failure, "feature length", EIO);
    if (operation == 1 && !valid_report(bytes.data(), bytes.size(), report))
        return failed(failure, "feature header", EPROTO);
    // SET acknowledges transport only; the caller must GET the device's readback.
    if (operation == 2) bytes = {static_cast<uint8_t>(report), 'A', 'P', 'D', 'G', 1, 64};
    return true;
}

#ifndef LIBREPODS_DONGLE_HID_HOST_TEST
jbyteArray exception(JNIEnv* env, const Failure& failure) {
    char message[160];
    std::snprintf(message, sizeof(message), "USB HID %s failed (errno %d: %s)",
                  failure.stage, failure.code, std::strerror(failure.code));
    const jclass type = env->FindClass("java/io/IOException");
    if (type != nullptr) env->ThrowNew(type, message);
    return nullptr;
}

jbyteArray result(JNIEnv* env, const uint8_t* bytes, size_t size) {
    jbyteArray value = env->NewByteArray(static_cast<jsize>(size));
    if (value != nullptr) env->SetByteArrayRegion(value, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(bytes));
    return value;
}
#endif
} // namespace

#ifndef LIBREPODS_DONGLE_HID_HOST_TEST
extern "C" JNIEXPORT jboolean JNICALL
Java_me_kavishdevar_librepods_dongle_DongleHidNative_identity(
        JNIEnv* env, jobject, jint fd, jint product, jstring serial) {
    if (serial == nullptr) { exception(env, {"expected serial", EINVAL}); return JNI_FALSE; }
    const jsize size = env->GetStringUTFLength(serial);
    if (size < 1 || size > 64) { exception(env, {"expected serial", EINVAL}); return JNI_FALSE; }
    const char* value = env->GetStringUTFChars(serial, nullptr);
    if (value == nullptr) return JNI_FALSE;
    Failure failure;
    const bool valid = identity(fd, product, value, static_cast<size_t>(size), failure);
    env->ReleaseStringUTFChars(serial, value);
    if (!valid) { exception(env, failure); return JNI_FALSE; }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_me_kavishdevar_librepods_dongle_DongleHidNative_descriptor(JNIEnv* env, jobject, jint fd) {
    std::array<uint8_t, descriptor_limit> bytes{};
    int size = 0;
    Failure failure;
    if (!descriptor(fd, bytes, size, failure)) return exception(env, failure);
    return result(env, bytes.data(), static_cast<size_t>(size));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_me_kavishdevar_librepods_dongle_DongleHidNative_exchange(
        JNIEnv* env, jobject, jint fd, jint operation, jint report, jbyteArray payload) {
    if (payload == nullptr) return exception(env, {"feature payload", EINVAL});
    const jsize size = env->GetArrayLength(payload);
    if (size != (operation == 1 ? 0 : static_cast<jsize>(report_size)))
        return exception(env, {"feature payload", EINVAL});
    std::array<uint8_t, report_size> input{}, output{};
    if (size != 0) env->GetByteArrayRegion(payload, 0, size, reinterpret_cast<jbyte*>(input.data()));
    if (env->ExceptionCheck()) return nullptr;
    Failure failure;
    if (!exchange(fd, operation, report, input.data(), static_cast<size_t>(size), output, failure))
        return exception(env, failure);
    return result(env, output.data(), output.size());
}
#endif
