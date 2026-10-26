# Exact module-symbol validation

```sh
cmake -S android/tools/native-symbol-tests -B build/symbols -DCMAKE_BUILD_TYPE=Release
cmake --build build/symbols -j4
ctest --test-dir build/symbols --output-on-failure
```

Python 3 generates small synthetic ELF/XZ fixtures during the build. No Apple or
device binary is included. The optional command
`build/symbols/symbol_image_test /private/path/to/libbluetooth_jni.so` additionally
checks four known symbols by exact name and exact definition address in a local
Pixel image. That integration check does not pin or authorize its whole-file hash.

For ASan/UBSan, use a separate Debug build with both `CMAKE_C_FLAGS` and
`CMAKE_CXX_FLAGS` set to `-fsanitize=address,undefined -fno-omit-frame-pointer`,
and `CMAKE_EXE_LINKER_FLAGS=-fsanitize=address,undefined`. The same source also
cross-compiles with the Android NDK for arm64/API26.

`SymbolImage` borrows an immutable complete ELF file; the caller keeps that input
alive until the next `open()` or destruction. It does not parse a loaded-memory
image. `bytes()` returns a borrowed file pointer. Failed opens invalidate the old
image. Concurrent reads require the caller to exclude `open()` and input mutation.

The supported format is ELF64 little-endian AArch64 ET_DYN, with ordinary section
indices and disjoint PT_LOAD memory ranges. Name queries use complete names;
address queries require an exact `st_value` of the requested symbol type, not a
containing function or nearest match. Function bounds must be instruction-aligned,
belong to an allocated executable section, and fit readable executable file-backed
memory. Conflicting definitions or same-address sizes fail. This is metadata and
mapping validation; a runtime adapter must still verify its full live code context.

Limits are 128 MiB input, 4,096 sections, 128 program headers, one million entries
per symbol table, and one mini-debug XZ stream with at most 8 MiB compressed input,
16 MiB output and a 32 MiB dictionary. Mini-debug NOBITS code sections are supported.
String tables require their mandatory first/last NUL; offsets then validate in
constant time per symbol. Allocation failure and malformed input leave no active
image, and the XZ decoder is released on every path.

Regression coverage includes exact names/addresses and ICF aliases, section and
load bounds, invalid alignments, overlapping mappings, string tables, compression
checks and truncation, dictionary/output limits (including exact output capacity),
allocation failure, failed-open recovery and 4,000 repeatable mutation cases.
