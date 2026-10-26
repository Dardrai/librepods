# Pixel 17 vtable ABI admission tests

Build with CMake, then run CTest for empty-input bounds. To exercise the private shipped artifact, pass its path as the executable's sole argument. The binary is not copied into source or tests.

The guard derives the final source vtable from the named AAC constructor, compares the actual relocated runtime words with the immutable image, checks all nine roles and the adjacent table boundary, and requires connected direct virtual dispatch spans. The retained Pixel image has unconditional vptr/slot/BLR dispatch for these calls, without a vcall CFI type/range check. A changed instrumented call site is rejected before a module-owned vtable can be installed.

The private-image regression corrupts individual runtime targets, layout metadata, destructor and constant-leaf bodies, and eight connected dispatch instructions while retaining symbol metadata. ASLR is simulated with a nonzero base. The runtime must still validate live mappings and complete function bytes independently before calling any host function; the pure guard reads no process maps or Android state.
