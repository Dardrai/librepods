cmake_minimum_required(VERSION 3.22.1)
project(pixel17_game_host_tests LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
enable_testing()
find_package(Threads REQUIRED)
get_filename_component(NATIVE "${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp" ABSOLUTE)
add_executable(pixel17_game_host_test pixel17_game_host_test.cpp
    "${NATIVE}/eld/pixel17_game_host.cpp" "${NATIVE}/eld/pixel17_game_host_contract.cpp"
    "${NATIVE}/eld/wire.cpp" "${NATIVE}/eld/symbol_image.cpp"
    "${NATIVE}/xz/xz_crc32.c" "${NATIVE}/xz/xz_crc64.c" "${NATIVE}/xz/xz_sha256.c"
    "${NATIVE}/xz/xz_dec_stream.c" "${NATIVE}/xz/xz_dec_lzma2.c" "${NATIVE}/xz/xz_dec_bcj.c")
target_include_directories(pixel17_game_host_test PRIVATE "${NATIVE}/eld" "${NATIVE}/xz")
target_compile_definitions(pixel17_game_host_test PRIVATE ELD_PIXEL17_GAME_HOST_TESTING
    XZ_DEC_ARM64 XZ_DEC_X86 XZ_DEC_ANY_CHECK XZ_USE_CRC64 XZ_USE_SHA256)
target_compile_options(pixel17_game_host_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
target_link_libraries(pixel17_game_host_test PRIVATE Threads::Threads)
add_test(NAME pixel17_game_host_test COMMAND pixel17_game_host_test)
if(PIXEL17_ELF)
    add_test(NAME pixel17_game_host_contract_test COMMAND pixel17_game_host_test "${PIXEL17_ELF}")
endif()
