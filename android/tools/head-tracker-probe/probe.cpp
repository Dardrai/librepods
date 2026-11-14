#include "protocol.h"

#include <cerrno>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <fcntl.h>
#include <linux/uhid.h>
#include <poll.h>
#include <time.h>
#include <unistd.h>

using namespace head_tracker_probe;

static volatile sig_atomic_t stopRequested = 0;
static void stop(int) { stopRequested = 1; }

static int64_t nowNs() {
    timespec now{};
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return -1;
    return int64_t(now.tv_sec) * 1000000000 + now.tv_nsec;
}

static bool sendEvent(int fd, const uhid_event& event) {
    ssize_t size;
    do { size = write(fd, &event, sizeof(event)); } while (size < 0 && errno == EINTR);
    if (size == sizeof(event)) return true;
    if (size < 0) std::perror("UHID write");
    else std::fprintf(stderr, "Short UHID write: %zd\n", size);
    return false;
}

static int probe(const std::array<uint8_t, 6>& mac, unsigned seconds) {
    const int64_t start = nowNs();
    if (start < 0) { std::perror("clock_gettime"); return 1; }
    const int fd = open("/dev/uhid", O_RDWR | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) { std::perror("open /dev/uhid"); return 1; }
    std::signal(SIGINT, stop);
    std::signal(SIGTERM, stop);
    std::signal(SIGHUP, stop);
    uhid_event create{};
    create.type = UHID_CREATE2;
    std::strcpy(reinterpret_cast<char*>(create.u.create2.name), "LibrePods synthetic head tracker probe");
    std::strcpy(reinterpret_cast<char*>(create.u.create2.phys), "librepods/head-tracker-probe");
    create.u.create2.bus = BUS_VIRTUAL;
    create.u.create2.vendor = 0;
    create.u.create2.product = 0;
    create.u.create2.version = 1;
    static_assert(sizeof(kDescriptor) <= sizeof(create.u.create2.rd_data));
    create.u.create2.rd_size = sizeof(kDescriptor);
    std::memcpy(create.u.create2.rd_data, kDescriptor, sizeof(kDescriptor));
    if (!sendEvent(fd, create)) { close(fd); return 1; }
    std::puts("Created one temporary SYNTHETIC stationary head tracker; no real motion input.");
    std::fflush(stdout);
    const auto identity = identityReport(mac);
    Control control;
    bool started = false, opened = false, failed = false;
    unsigned getIdentity = 0, getControls = 0, setControls = 0, inputs = 0;
    const int64_t deadline = start + seconds * 1000000000LL;
    int64_t nextInput = 0;
    while (!stopRequested) {
        const int64_t now = nowNs();
        if (now < 0) { std::perror("clock_gettime"); failed = true; break; }
        if (now >= deadline) break;
        const bool enabled = started && opened && control.enabled();
        const int64_t nextWake = enabled && nextInput < deadline ? nextInput : deadline;
        const int timeout = nextWake <= now ? 0 : static_cast<int>((nextWake - now + 999999) / 1000000);
        pollfd request{fd, POLLIN, 0};
        const int ready = poll(&request, 1, timeout);
        if (ready < 0) {
            if (errno == EINTR) continue;
            std::perror("UHID poll"); failed = true; break;
        }
        if (request.revents & (POLLERR | POLLHUP | POLLNVAL)) {
            std::fprintf(stderr, "UHID poll ended: 0x%x\n", request.revents);
            failed = true; break;
        }
        if (request.revents & POLLIN) {
            uhid_event event{};
            const ssize_t size = read(fd, &event, sizeof(event));
            if (size < 0 && (errno == EAGAIN || errno == EINTR)) continue;
            if (size != static_cast<ssize_t>(sizeof(event))) {
                if (size < 0) std::perror("UHID read");
                else std::fprintf(stderr, "Short UHID read: %zd\n", size);
                failed = true; break;
            }
            uhid_event reply{};
            switch (event.type) {
                case UHID_START:
                    std::printf("UHID_START flags=0x%llx\n", static_cast<unsigned long long>(event.u.start.dev_flags));
                    started = (event.u.start.dev_flags & (UHID_DEV_NUMBERED_FEATURE_REPORTS |
                               UHID_DEV_NUMBERED_INPUT_REPORTS)) == (UHID_DEV_NUMBERED_FEATURE_REPORTS |
                               UHID_DEV_NUMBERED_INPUT_REPORTS);
                    if (!started) { std::fprintf(stderr, "Expected numbered input and feature reports.\n"); failed = true; }
                    break;
                case UHID_OPEN: opened = true; std::puts("UHID_OPEN"); break;
                case UHID_CLOSE: opened = false; std::puts("UHID_CLOSE"); break;
                case UHID_STOP: started = false; std::puts("UHID_STOP"); break;
                case UHID_GET_REPORT:
                    reply.type = UHID_GET_REPORT_REPLY;
                    reply.u.get_report_reply.id = event.u.get_report.id;
                    reply.u.get_report_reply.err = EIO;
                    if (event.u.get_report.rtype == UHID_FEATURE_REPORT) {
                        if (event.u.get_report.rnum == 2) {
                            reply.u.get_report_reply.size = identity.size();
                            std::memcpy(reply.u.get_report_reply.data, identity.data(), identity.size());
                            reply.u.get_report_reply.err = 0;
                            control.identityRead = true;
                            ++getIdentity;
                        } else if (event.u.get_report.rnum == 1) {
                            reply.u.get_report_reply.size = 2;
                            reply.u.get_report_reply.data[0] = 1;
                            reply.u.get_report_reply.data[1] = control.value;
                            reply.u.get_report_reply.err = 0;
                            ++getControls;
                        }
                    }
                    std::printf("GET_REPORT number=%u type=%u bytes=%u err=%u\n", event.u.get_report.rnum,
                                event.u.get_report.rtype, reply.u.get_report_reply.size, reply.u.get_report_reply.err);
                    if (!sendEvent(fd, reply)) failed = true;
                    break;
                case UHID_SET_REPORT:
                    reply.type = UHID_SET_REPORT_REPLY;
                    reply.u.set_report_reply.id = event.u.set_report.id;
                    reply.u.set_report_reply.err = EIO;
                    if (event.u.set_report.rtype == UHID_FEATURE_REPORT && event.u.set_report.rnum == 1 &&
                        control.set(event.u.set_report.data, event.u.set_report.size)) {
                        reply.u.set_report_reply.err = 0;
                        ++setControls;
                        nextInput = nowNs() + control.intervalNs();
                    }
                    std::printf("SET_REPORT number=%u type=%u bytes=%u err=%u controls=0x%02x interval_ns=%lld\n",
                                event.u.set_report.rnum, event.u.set_report.rtype, event.u.set_report.size,
                                reply.u.set_report_reply.err, control.value, static_cast<long long>(control.intervalNs()));
                    if (!sendEvent(fd, reply)) failed = true;
                    break;
                default: std::printf("UHID event=%u\n", event.type); break;
            }
            std::fflush(stdout);
            if (failed) break;
        }
        const int64_t after = nowNs();
        if (after < 0) { std::perror("clock_gettime"); failed = true; break; }
        if (after >= deadline || stopRequested) break;
        if (started && opened && control.enabled() && after >= nextInput) {
            uhid_event input{};
            input.type = UHID_INPUT2;
            input.u.input2.size = kStationaryInput.size();
            std::memcpy(input.u.input2.data, kStationaryInput.data(), kStationaryInput.size());
            if (!sendEvent(fd, input)) { failed = true; break; }
            ++inputs;
            nextInput = after + control.intervalNs();
        }
    }
    uhid_event destroy{};
    destroy.type = UHID_DESTROY;
    if (!sendEvent(fd, destroy)) failed = true;
    if (close(fd) != 0) { std::perror("close /dev/uhid"); failed = true; }
    std::printf("Destroyed/closed temporary device: identity_gets=%u control_gets=%u control_sets=%u synthetic_inputs=%u\n",
                getIdentity, getControls, setControls, inputs);
    std::puts("HAL sensor discovery and audio association require separate sensorservice/audio dumps.");
    return failed ? 1 : 0;
}

int main(int argc, char** argv) {
    if (argc != 4 || std::strcmp(argv[1], "--probe") != 0) {
        std::fprintf(stderr, "Usage: %s --probe AUDIO_IDENTITY_MAC SECONDS(1..60)\nCreates a temporary synthetic UHID device only when explicitly invoked.\n", argv[0]);
        return 2;
    }
    std::array<uint8_t, 6> mac{};
    if (!parseMac(argv[2], &mac)) { std::fprintf(stderr, "Invalid Bluetooth audio identity MAC.\n"); return 2; }
    char* end = nullptr;
    errno = 0;
    const unsigned long seconds = std::strtoul(argv[3], &end, 10);
    if (errno || !argv[3][0] || *end || argv[3][0] == '-' || seconds < 1 || seconds > 60) {
        std::fprintf(stderr, "Duration must be 1..60 seconds.\n"); return 2;
    }
    return probe(mac, static_cast<unsigned>(seconds));
}
