cmake_minimum_required(VERSION 3.22.1)
project(pixel17_hci_tests LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
enable_testing()
get_filename_component(ELD "${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp/eld" ABSOLUTE)
add_executable(pixel17_hci_test pixel17_hci_test.cpp "${ELD}/pixel17_hci.cpp")
target_include_directories(pixel17_hci_test PRIVATE "${ELD}")
target_compile_options(pixel17_hci_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
add_test(NAME pixel17_hci_test COMMAND pixel17_hci_test)
get_filename_component(NATIVE "${ELD}/.." ABSOLUTE)
add_executable(pixel17_hci_contract_test pixel17_hci_contract_test.cpp
    "${ELD}/pixel17_hci_contract.cpp" "${ELD}/symbol_image.cpp"
    "${NATIVE}/xz/xz_crc32.c" "${NATIVE}/xz/xz_crc64.c" "${NATIVE}/xz/xz_sha256.c"
    "${NATIVE}/xz/xz_dec_stream.c" "${NATIVE}/xz/xz_dec_lzma2.c" "${NATIVE}/xz/xz_dec_bcj.c")
target_include_directories(pixel17_hci_contract_test PRIVATE "${ELD}" "${NATIVE}/xz")
target_compile_definitions(pixel17_hci_contract_test PRIVATE XZ_DEC_ARM64 XZ_DEC_X86 XZ_DEC_ANY_CHECK XZ_USE_CRC64 XZ_USE_SHA256)
target_compile_options(pixel17_hci_contract_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
add_test(NAME pixel17_hci_contract_empty COMMAND pixel17_hci_contract_test)
add_executable(pixel17_game_runtime_test pixel17_game_runtime_test.cpp
    "${ELD}/pixel17_hci.cpp" "${ELD}/pixel17_game_runtime.cpp")
target_include_directories(pixel17_game_runtime_test PRIVATE "${ELD}")
target_compile_definitions(pixel17_game_runtime_test PRIVATE ELD_PIXEL17_GAME_RUNTIME_TESTING)
target_compile_options(pixel17_game_runtime_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
foreach(scenario read baseline0 baseline320 pause-restore wrong-cid new-media cancel-at-write allocation expired-dispatch expired-tick restore-allocation watchdog-start late-target late-restore
    hold0 hold320 hold96 hold96-expiry hold96-restore-inflight hold-graceful-refresh hold-expiry hold-binder hold-stalled hold-query-no-renew clock-cancel-pending clock-invalidated-begin clock-cancel-inflight
    clock-stop clock-sco clock-wrong-channel clock-wrong-thread clock-expiry clock-new-token clock-normal
    clock-retry-success clock-retry-cancel clock-retry-expiry clock-retry-owner clock-retry-unsettled clock-static-refusal)
    add_test(NAME "pixel17_game_${scenario}" COMMAND pixel17_game_runtime_test "${scenario}")
    set_tests_properties("pixel17_game_${scenario}" PROPERTIES TIMEOUT 10)
endforeach()
