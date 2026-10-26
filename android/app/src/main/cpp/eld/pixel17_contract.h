// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "symbol_image.h"
#include <cstddef>
#include <cstdint>

namespace eld {
inline constexpr char kPixel17Init[] = "_ZN10A2dpCodecs4initE29btav_a2dp_codec_sample_rate_t";
inline constexpr char kPixel17CacheInit[] = "_ZN16BtaAvCoPeerCache4InitERKNSt3__16vectorI24btav_a2dp_codec_config_tNS0_9allocatorIS2_EEEE29btav_a2dp_codec_sample_rate_tNS1_I10RawAddressNS3_IS9_EEEE";
inline constexpr char kPixel17AacCtor[] = "_ZN24A2dpCodecConfigAacSourceC1E26btav_a2dp_codec_priority_t";
inline constexpr char kPixel17Lock[] = "_ZNSt3__115recursive_mutex4lockEv";
inline constexpr char kPixel17Unlock[] = "_ZNSt3__115recursive_mutex6unlockEv";
inline constexpr char kPixel17Provider[] = "_ZN9bluetooth5audio4a2dp8provider14supports_codecE23btav_a2dp_codec_index_t";
inline constexpr char kPixel17ActivePeer[] = "_ZN9bluetooth5avrcp17A2dpInterfaceImpl11active_peerEv";
inline constexpr char kPixel17Allocate[] = "_ZnwmRKSt9nothrow_t";
inline constexpr char kPixel17Release[] = "_ZdlPvm";
inline constexpr char kPixel17Assign[] = "_ZNSt3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEE17__assign_no_aliasILb1EEERS5_PKcm";
inline constexpr char kPixel17Balance[] = "_ZNSt3__127__tree_balance_after_insertB8nn220000IPNS_16__tree_node_baseIPvEEEEvT_S5_";
struct Pixel17Contract {
    size_t registry_count = 0;
    uint64_t raw_allocate = 0;
};
// Exact symbol names + instruction semantics, independent of ASLR and link-time addresses.
// This checks the known SDK37 ABI, not arbitrary Android versions or an image hash.
bool pixel17_contract(const SymbolImage& image, Pixel17Contract* out);
bool pixel17_active_peer_contract(const uint8_t* code, size_t size);
// Proves the native source/sink list split, independently of derived codec-class fields.
bool pixel17_source_list_contract(const uint8_t* code, size_t size, uint64_t address);
// Additional native contexts required before restoring ELD into a recreated peer registry.
const char* const* pixel17_lifecycle_context_names(size_t* count);
bool pixel17_registry_mutex_contract(const uint8_t* code, size_t size, uint64_t address,
                                    uint64_t lock, uint64_t unlock);
}
