// SPDX-License-Identifier: MIT
#pragma once
#include <android/log.h>

// The public NDK has no AOSP security-event logger; retain the issue in logcat.
static inline int android_errorWriteLog(int tag, const char* issue) {
    return __android_log_print(ANDROID_LOG_ERROR, "FDK-AAC", "security event 0x%x: %s", (unsigned)tag, issue);
}
