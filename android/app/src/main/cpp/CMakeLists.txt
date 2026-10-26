cmake_minimum_required(VERSION 3.22.1)

project("l2c_fcr_hook")
set(CMAKE_CXX_STANDARD 23)
option(LIBREPODS_PRIVATE_OWNED_FDK "Private AAC-ELD experiment; not for GPL APK redistribution" OFF)
option(LIBREPODS_PRIVATE_WIDE_PCM "Private Pixel PCM32/Q31 experiment; requires owned FDK" OFF)
if(LIBREPODS_PRIVATE_WIDE_PCM AND NOT LIBREPODS_PRIVATE_OWNED_FDK)
    message(FATAL_ERROR "Wide Pixel PCM requires the matching private owned FDK backend")
endif()
option(ELD_KEEP_GAME_FLUSH_BASELINE "Private Game Mode comparison preserving the captured flush timeout" OFF)
option(LIBREPODS_NATIVE_TRACE "Verbose native hook diagnostics" OFF)

add_library(hiddenapi SHARED
        hiddenapi.cpp
)

target_compile_options(hiddenapi PRIVATE
        -O2
        -fvisibility=hidden
)

target_link_options(hiddenapi PRIVATE
        -Wl,--strip-all
        -Wl,--gc-sections
)

target_link_libraries(hiddenapi
        android
        log
)


set(XPOSED_SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../xposed/cpp)

add_library(fluoride_hooks SHARED
        fluoride_hooks.cpp
        eld/wire.cpp
        eld/fdk_encoder.cpp
        eld/encoder_interface.cpp
        eld/note8_adapter.cpp
        eld/game_host.cpp
        eld/hal_adapter.cpp
        eld/runtime.cpp
        eld/pixel17_codec.cpp
        eld/pixel17_registry.cpp
        eld/pixel17_wire_hooks.cpp
        eld/pixel17_cohort.cpp
        eld/symbol_image.cpp
        eld/pixel17_runtime.cpp
        eld/pixel17_contract.cpp
        eld/pixel17_vtable_contract.cpp
        eld/pixel17_wire_contract.cpp
        eld/pixel17_hci.cpp
        eld/pixel17_hci_contract.cpp
        eld/pixel17_game_host.cpp
        eld/pixel17_game_host_contract.cpp
        eld/pixel17_game_runtime.cpp
        eld/hook_transaction.cpp

        xz/xz_crc32.c
        xz/xz_crc64.c
        xz/xz_sha256.c
        xz/xz_dec_stream.c
        xz/xz_dec_lzma2.c
        xz/xz_dec_bcj.c
)

target_include_directories(fluoride_hooks PRIVATE
        xz
)

target_compile_definitions(fluoride_hooks PRIVATE
        LIBREPODS_NATIVE_TRACE=$<BOOL:${LIBREPODS_NATIVE_TRACE}>
        ELD_PRIVATE_WIDE_PCM=$<BOOL:${LIBREPODS_PRIVATE_WIDE_PCM}>
        ELD_KEEP_GAME_FLUSH_BASELINE=$<BOOL:${ELD_KEEP_GAME_FLUSH_BASELINE}>
        XZ_DEC_X86
        XZ_DEC_ARM
        XZ_DEC_ARMTHUMB
        XZ_DEC_ARM64
        XZ_DEC_ANY_CHECK
        XZ_USE_CRC64
        XZ_USE_SHA256
        XZ_DEC_CONCATENATED
)

target_link_libraries(fluoride_hooks
        PRIVATE
        android
        log
        dl
)

if(LIBREPODS_PRIVATE_OWNED_FDK)
    message(WARNING "Private owned-FDK experiment: do not redistribute this combined GPL/FDK APK")
    include(eld/owned_fdk.cmake)
    eld_add_private_owned_fdk()
    target_link_libraries(fluoride_hooks PRIVATE eld_owned_fdk)
    target_compile_definitions(fluoride_hooks PRIVATE ELD_PRIVATE_OWNED_FDK=1)
    target_link_options(fluoride_hooks PRIVATE -Wl,--exclude-libs,ALL)
endif()
