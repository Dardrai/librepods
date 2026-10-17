#include "../../main/cpp/fluoride_hooks.h"
#include <cassert>

static int calls;
static uint16_t observedVendor;
static uint16_t observedSource;

static tBTA_STATUS original(tSDP_DI_RECORD* record, uint32_t* handle) {
    ++calls;
    observedVendor = record->vendor;
    observedSource = record->vendor_id_source;
    *handle = 42;
    // A second call must not replace the first registration's result.
    return calls == 1 ? BTA_SUCCESS : BTA_NO_RESOURCES;
}

int main() {
    tSDP_DI_RECORD record{};
    record.vendor = 0x18D1;
    record.vendor_id_source = 2;
    uint32_t handle = 0;
    assert(setLocalDiRecord(&record, &handle, false, original) == BTA_SUCCESS);
    assert(calls == 1 && handle == 42);
    assert(record.vendor == 0x18D1 && record.vendor_id_source == 2);
    assert(observedVendor == 0x18D1 && observedSource == 2);

    calls = 0;
    handle = 0;
    assert(setLocalDiRecord(&record, &handle, true, original) == BTA_SUCCESS);
    assert(calls == 1 && handle == 42);
    assert(observedVendor == 0x004C && observedSource == 1);

    record.vendor = 0x18D1;
    handle = 0;
    assert(setLocalDiRecord(&record, &handle, true, nullptr) == BTA_FAILURE);
    assert(calls == 1 && handle == 0 && record.vendor == 0x18D1);
}
