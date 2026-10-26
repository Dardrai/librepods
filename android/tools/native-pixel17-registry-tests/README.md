# Pixel 17 registry transaction tests

Build this directory with CMake and a C++17 host compiler, then run CTest. The target contains only synthetic native objects and the pure binding components; it does not load Android, connect to a phone or alter the shared Android build.

The fake host independently implements red-black balancing and sized allocation ownership. Coverage includes empty/nonempty maps, priority ordering, foreign codec collisions, complete tree/list validation, every one of eighteen allocation failures in a six-registry cohort, late reentrant mutation of an earlier member, mixed already-owned/new members and normal host teardown after quarantine. `readable` is deliberately a pure synthetic range predicate, matching the production binding requirement.

The parent runtime must verify the real image's layout, bindings and six-member initialization cohort before using this transaction. Passing these tests does not prove a runtime codec is advertised or playable.
