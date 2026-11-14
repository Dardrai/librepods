#include <cstddef>
#include <cstdio>
#include <linux/uhid.h>

#include "../head-tracker-probe/protocol.h"

// The START member's uint64 alignment rounds the union up despite the packed outer event.
static_assert(sizeof(uhid_event) == 4380);
static_assert(sizeof(((uhid_event*)nullptr)->u) == 4376);
static_assert(alignof(uhid_event) == 1);
static_assert(offsetof(uhid_event, type) == 0);
static_assert(offsetof(uhid_event, u) == 4);
static_assert(sizeof(uhid_create2_req) == 4372);
static_assert(HID_MAX_DESCRIPTOR_SIZE == 4096 && UHID_DATA_MAX == 4096);

#define CHECK_OFFSET(type, field, offset) \
    static_assert(offsetof(uhid_event, u) + offsetof(type, field) == offset, #type "." #field)

CHECK_OFFSET(uhid_create2_req, name, 4);
CHECK_OFFSET(uhid_create2_req, phys, 132);
CHECK_OFFSET(uhid_create2_req, uniq, 196);
CHECK_OFFSET(uhid_create2_req, rd_size, 260);
CHECK_OFFSET(uhid_create2_req, bus, 262);
CHECK_OFFSET(uhid_create2_req, vendor, 264);
CHECK_OFFSET(uhid_create2_req, product, 268);
CHECK_OFFSET(uhid_create2_req, version, 272);
CHECK_OFFSET(uhid_create2_req, country, 276);
CHECK_OFFSET(uhid_create2_req, rd_data, 280);
CHECK_OFFSET(uhid_get_report_req, id, 4);
CHECK_OFFSET(uhid_get_report_req, rnum, 8);
CHECK_OFFSET(uhid_get_report_req, rtype, 9);
CHECK_OFFSET(uhid_get_report_reply_req, id, 4);
CHECK_OFFSET(uhid_get_report_reply_req, err, 8);
CHECK_OFFSET(uhid_get_report_reply_req, size, 10);
CHECK_OFFSET(uhid_get_report_reply_req, data, 12);
CHECK_OFFSET(uhid_set_report_req, id, 4);
CHECK_OFFSET(uhid_set_report_req, rnum, 8);
CHECK_OFFSET(uhid_set_report_req, rtype, 9);
CHECK_OFFSET(uhid_set_report_req, size, 10);
CHECK_OFFSET(uhid_set_report_req, data, 12);
CHECK_OFFSET(uhid_set_report_reply_req, id, 4);
CHECK_OFFSET(uhid_set_report_reply_req, err, 8);
CHECK_OFFSET(uhid_start_req, dev_flags, 4);
CHECK_OFFSET(uhid_input2_req, size, 4);
CHECK_OFFSET(uhid_input2_req, data, 6);

#undef CHECK_OFFSET

static_assert(UHID_START == 2 && UHID_STOP == 3 && UHID_OPEN == 4 && UHID_CLOSE == 5);
static_assert(UHID_GET_REPORT == 9 && UHID_GET_REPORT_REPLY == 10);
static_assert(UHID_CREATE2 == 11 && UHID_INPUT2 == 12);
static_assert(UHID_SET_REPORT == 13 && UHID_SET_REPORT_REPLY == 14);
static_assert(UHID_FEATURE_REPORT == 0);
static_assert((UHID_DEV_NUMBERED_FEATURE_REPORTS | UHID_DEV_NUMBERED_INPUT_REPORTS) == 5);
static_assert(sizeof(head_tracker_probe::kDescriptor) <= HID_MAX_DESCRIPTOR_SIZE);

int main() {
    std::printf("UHID packed event ABI: passed; event_bytes=%zu descriptor_bytes=%zu\n",
                sizeof(uhid_event), sizeof(head_tracker_probe::kDescriptor));
    std::printf("descriptor_hex=");
    for (unsigned char value : head_tracker_probe::kDescriptor) std::printf("%02x", value);
    std::putchar('\n');
}
