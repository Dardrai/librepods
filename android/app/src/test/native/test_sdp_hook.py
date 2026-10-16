"""Run the production SDP hook with a fake Bluetooth registration function."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile


cpp = Path(__file__).resolve().parents[2] / "main" / "cpp"
source = (cpp / "l2c_fcr_hook.cpp").read_text()
start = source.index("tBTA_STATUS fake_BTA_DmSetLocalDiRecord(")
hook = source[start:source.index("\nstatic bool decompressXZ", start)]
harness = r'''
#include <atomic>
#include <cassert>
#include <initializer_list>
#include "l2c_fcr_hook.h"
static int testLog(const char *, ...) { return 0; }
#define LOGI(...) testLog(__VA_ARGS__)
static std::atomic<bool> enableSdpHook(false);
static tBTA_STATUS (*original_BTA_DmSetLocalDiRecord)(tSDP_DI_RECORD *, uint32_t *);
static int calls;
static uint16_t seenVendor;
static tBTA_STATUS registerRecord(tSDP_DI_RECORD *record, uint32_t *handle) {
    ++calls;
    seenVendor = record ? record->vendor : 0;
    if (handle) *handle = 73;
    return BTA_BUSY;
}
''' + hook + r'''
int main() {
    original_BTA_DmSetLocalDiRecord = registerRecord;
    for (bool enabled : {false, true}) {
        enableSdpHook.store(enabled);
        tSDP_DI_RECORD record{};
        record.vendor = 0x1234;
        record.vendor_id_source = 2;
        record.product = 42;
        uint32_t handle = 0;
        calls = 0;
        assert(fake_BTA_DmSetLocalDiRecord(&record, &handle) == BTA_BUSY);
        assert(calls == 1 && handle == 73);
        assert(seenVendor == (enabled ? 0x004C : 0x1234));
        assert(record.vendor_id_source == (enabled ? 1 : 2));
        assert(record.product == 42);
    }
    calls = 0;
    assert(fake_BTA_DmSetLocalDiRecord(nullptr, nullptr) == BTA_BUSY);
    assert(calls == 1);
    original_BTA_DmSetLocalDiRecord = nullptr;
    assert(fake_BTA_DmSetLocalDiRecord(nullptr, nullptr) == BTA_FAILURE);
}
'''
with tempfile.TemporaryDirectory(prefix="librepods-sdp-test-") as temporary:
    directory = Path(temporary)
    test = directory / "sdp_hook_test.cpp"
    test.write_text(harness)
    executable = directory / "sdp_hook_test"
    subprocess.run(shlex.split(os.environ.get("CXX", "c++")) + [
        "-std=c++17", "-Wall", "-Wextra", "-Werror", "-I", str(cpp),
        str(test), "-o", str(executable),
    ], check=True)
    subprocess.run([str(executable)], check=True)
print("SDP hook regression checks passed")
