#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generate miniature synthetic ELF/XZ inputs; no device binaries are used."""
import lzma
from pathlib import Path
import struct
import sys
import zlib


def mini_elf():
    data = bytearray(1024)

    def put(offset, value, width):
        data[offset:offset + width] = value.to_bytes(width, "little")

    data[:7] = b"\x7fELF\x02\x01\x01"
    for offset, value, width in ((16, 3, 2), (18, 183, 2), (20, 1, 4),
                                  (40, 512, 8), (52, 64, 2), (58, 64, 2),
                                  (60, 5, 2), (62, 1, 2)):
        put(offset, value, width)
    sections = b"\0.shstrtab\0.strtab\0.symtab\0.text\0"
    symbols = b"\0target\0target_suffix\0table\0"
    data[320:320 + len(sections)] = sections
    data[384:384 + len(symbols)] = symbols
    for index, name, kind, offset, length in ((1, 1, 3, 320, len(sections)),
            (2, 11, 3, 384, len(symbols)), (3, 19, 2, 432, 72), (4, 27, 8, 0, 64)):
        at = 512 + index * 64
        for field, value, width in ((0, name, 4), (4, kind, 4), (24, offset, 8), (32, length, 8)):
            put(at + field, value, width)
    for field, value, width in ((40, 2, 4), (44, 1, 4), (56, 24, 8)):
        put(512 + 3 * 64 + field, value, width)
    put(512 + 4 * 64 + 8, 6, 8)
    put(512 + 4 * 64 + 16, 0x1000, 8)
    for at, name, address in ((456, 1, 0x1000), (480, 8, 0x1010)):
        for field, value, width in ((0, name, 4), (4, 0x12, 1), (6, 4, 2), (8, address, 8), (16, 16, 8)):
            put(at + field, value, width)
    return bytes(data)


def excessive_dictionary(stream):
    data = bytearray(stream)
    block = 12
    length = (data[block] + 1) * 4
    # One LZMA2 filter, no optional block-size fields. Only change its declared
    # dictionary to 64 MiB; this avoids allocating a 64 MiB compression history.
    assert data[block + 1:block + 4] == b"\0\x21\x01"
    data[block + 4] = 28
    struct.pack_into("<I", data, block + length - 4,
                     zlib.crc32(data[block:block + length - 4]))
    return bytes(data)


def main():
    elf = mini_elf()
    crc32 = lzma.compress(elf, check=lzma.CHECK_CRC32)
    malformed = bytearray(elf)
    malformed[384] = 1  # A string table's mandatory empty first entry.
    fixtures = {
        "debug_crc32": crc32,
        "debug_crc64": lzma.compress(elf, check=lzma.CHECK_CRC64),
        "debug_sha256": lzma.compress(elf, check=lzma.CHECK_SHA256),
        "debug_malformed": lzma.compress(malformed, check=lzma.CHECK_CRC32),
        "debug_non_elf": lzma.compress(b"not an ELF", check=lzma.CHECK_CRC32),
        "debug_dictionary_limit": excessive_dictionary(crc32),
        "debug_exact_limit": lzma.compress(elf + bytes((16 << 20) - len(elf)), check=lzma.CHECK_CRC32),
        "debug_output_limit": lzma.compress(elf + bytes((16 << 20) + 1 - len(elf)), check=lzma.CHECK_CRC32),
    }
    lines = ["// Generated from synthetic ELF data by make_compressed_fixtures.py.",
             "// SPDX-License-Identifier: GPL-3.0-or-later", "#pragma once", "#include <cstdint>"]
    for name, data in fixtures.items():
        lines.append(f"inline constexpr uint8_t {name}[] = {{")
        lines.extend("    " + ",".join(f"0x{value:02x}" for value in data[i:i + 16]) + ","
                     for i in range(0, len(data), 16))
        lines.append("};")
    Path(sys.argv[1]).write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
