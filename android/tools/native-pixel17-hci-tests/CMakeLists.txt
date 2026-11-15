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
foreach(profile pixel17_packet_policy_test pixel17_packet_baseline_policy_test)
    add_executable(${profile} pixel17_packet_policy_test.cpp
        "${ELD}/pixel17_hci.cpp" "${ELD}/pixel17_packet_policy.cpp")
    target_include_directories(${profile} PRIVATE "${ELD}")
    target_compile_options(${profile} PRIVATE -Wall -Wextra -Werror -UNDEBUG)
    add_test(NAME ${profile} COMMAND ${profile})
endforeach()
target_compile_definitions(pixel17_packet_policy_test PRIVATE ELD_KEEP_GAME_PACKET_BASELINE=0)
target_compile_definitions(pixel17_packet_baseline_policy_test PRIVATE ELD_KEEP_GAME_PACKET_BASELINE=1)
add_executable(pixel17_packet_contract_test pixel17_packet_contract_test.cpp
    "${ELD}/pixel17_packet_contract.cpp" "${ELD}/symbol_image.cpp"
    "${NATIVE}/xz/xz_crc32.c" "${NATIVE}/xz/xz_crc64.c" "${NATIVE}/xz/xz_sha256.c"
    "${NATIVE}/xz/xz_dec_stream.c" "${NATIVE}/xz/xz_dec_lzma2.c" "${NATIVE}/xz/xz_dec_bcj.c")
target_include_directories(pixel17_packet_contract_test PRIVATE "${ELD}" "${NATIVE}/xz")
target_compile_definitions(pixel17_packet_contract_test PRIVATE XZ_DEC_ARM64 XZ_DEC_X86 XZ_DEC_ANY_CHECK XZ_USE_CRC64 XZ_USE_SHA256)
target_compile_options(pixel17_packet_contract_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
add_test(NAME pixel17_packet_contract_empty COMMAND pixel17_packet_contract_test)
add_executable(pixel17_game_runtime_test pixel17_game_runtime_test.cpp
    "${ELD}/pixel17_hci.cpp" "${ELD}/pixel17_packet_policy.cpp" "${ELD}/pixel17_game_runtime.cpp")
target_include_directories(pixel17_game_runtime_test PRIVATE "${ELD}")
target_compile_definitions(pixel17_game_runtime_test PRIVATE ELD_PIXEL17_GAME_RUNTIME_TESTING ELD_KEEP_GAME_FLUSH_BASELINE=0 ELD_KEEP_GAME_PACKET_BASELINE=0)
target_compile_options(pixel17_game_runtime_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
add_executable(pixel17_game_baseline_runtime_test pixel17_game_runtime_test.cpp
    "${ELD}/pixel17_hci.cpp" "${ELD}/pixel17_packet_policy.cpp" "${ELD}/pixel17_game_runtime.cpp")
target_include_directories(pixel17_game_baseline_runtime_test PRIVATE "${ELD}")
target_compile_definitions(pixel17_game_baseline_runtime_test PRIVATE ELD_PIXEL17_GAME_RUNTIME_TESTING ELD_KEEP_GAME_FLUSH_BASELINE=1 ELD_KEEP_GAME_PACKET_BASELINE=0)
target_compile_options(pixel17_game_baseline_runtime_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
add_executable(pixel17_game_packet_baseline_runtime_test pixel17_game_runtime_test.cpp
    "${ELD}/pixel17_hci.cpp" "${ELD}/pixel17_packet_policy.cpp" "${ELD}/pixel17_game_runtime.cpp")
target_include_directories(pixel17_game_packet_baseline_runtime_test PRIVATE "${ELD}")
target_compile_definitions(pixel17_game_packet_baseline_runtime_test PRIVATE
    ELD_PIXEL17_GAME_RUNTIME_TESTING ELD_KEEP_GAME_FLUSH_BASELINE=1 ELD_KEEP_GAME_PACKET_BASELINE=1)
target_compile_options(pixel17_game_packet_baseline_runtime_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
add_test(NAME pixel17_game_packets-stock-policy COMMAND pixel17_game_packet_baseline_runtime_test packets-stock-policy)
set_tests_properties(pixel17_game_packets-stock-policy PROPERTIES TIMEOUT 10)
foreach(profile pixel17_game_runtime_test pixel17_game_baseline_runtime_test)
    foreach(scenario flush0 flush320 flush-misread flush-unknown flush-stale flush-expiry flush-cancel-before-read)
        add_test(NAME "${profile}_${scenario}" COMMAND ${profile} "${scenario}")
        set_tests_properties("${profile}_${scenario}" PROPERTIES TIMEOUT 10)
    endforeach()
endforeach()
foreach(profile pixel17_game_runtime_test pixel17_game_baseline_runtime_test)
    string(REPLACE "_runtime_test" "" test_prefix "${profile}")
    foreach(scenario read baseline0 baseline320 pause-restore wrong-cid new-media cancel-at-write allocation expired-dispatch expired-tick restore-allocation watchdog-start late-target late-restore
        packets-normal packets-no-baseline packets-no-change packets-timeout packets-status-error packets-mismatch packets-owner packets-expiry packets-late-restore packets-cancel-send packets-clock-conflict
        packets-foreign-settled packets-foreign-defer packets-foreign-rejected packets-foreign-disconnect packets-foreign-disconnect-before-status packets-foreign-timeout packets-foreign-refresh-expiry packets-foreign-owner
        hold0 hold320 hold96 hold96-expiry hold96-restore-inflight hold-graceful-refresh hold-expiry hold-binder hold-stalled hold-query-no-renew clock-cancel-pending clock-invalidated-begin clock-cancel-inflight
        clock-stop clock-sco clock-wrong-channel clock-wrong-thread clock-expiry clock-new-token clock-normal
        clock-retry-success clock-retry-cancel clock-retry-expiry clock-retry-owner clock-retry-unsettled clock-static-refusal)
        add_test(NAME "${test_prefix}_${scenario}" COMMAND ${profile} "${scenario}")
        set_tests_properties("${test_prefix}_${scenario}" PROPERTIES TIMEOUT 10)
    endforeach()
endforeach()
