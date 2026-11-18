#pragma once
#include <cstdint>
#include <climits>

// Called under the client's mutex. A retired route cannot overwrite its replacement.
struct SpatialRequestFence {
    uint64_t serial=0,route=0,choice=0;
    bool released=false;
    uint64_t submit(uint64_t nextRoute,uint64_t nextChoice,bool release) {
        if(!nextRoute||!nextChoice||serial==uint64_t(INT64_MAX)||nextRoute<route||
            (nextRoute==route&&(nextChoice<choice||(nextChoice==choice&&released&&!release))))return 0;
        route=nextRoute;choice=nextChoice;released=release;return ++serial;
    }
    bool current(uint64_t value) const { return value&&value==serial; }
};
