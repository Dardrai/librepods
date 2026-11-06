// The host fake implements only the ioctl surface used by the production shim.
#pragma once
#include <cstdint>
#define BUS_USB 3
#define HIDIOCGRAWINFO 1UL
#define HIDIOCGRDESCSIZE 2UL
#define HIDIOCGRDESC 3UL
#define HIDIOCGFEATURE(size) (0x10000UL | static_cast<unsigned long>(size))
#define HIDIOCSFEATURE(size) (0x20000UL | static_cast<unsigned long>(size))
#define HIDIOCGRAWUNIQ(size) (0x30000UL | static_cast<unsigned long>(size))
struct hidraw_devinfo { uint32_t bustype; int16_t vendor, product; };
struct hidraw_report_descriptor { uint32_t size; uint8_t value[4096]; };
int ioctl(int fd, unsigned long request, void* value);
