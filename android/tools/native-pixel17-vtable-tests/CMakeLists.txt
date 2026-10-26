cmake_minimum_required(VERSION 3.22.1)
project(pixel17_vtable_tests LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
enable_testing()
get_filename_component(NATIVE "${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp" ABSOLUTE)
add_executable(pixel17_vtable_test pixel17_vtable_test.cpp "${NATIVE}/eld/pixel17_vtable_contract.cpp"
    "${NATIVE}/eld/symbol_image.cpp" "${NATIVE}/xz/xz_crc32.c" "${NATIVE}/xz/xz_crc64.c"
    "${NATIVE}/xz/xz_sha256.c" "${NATIVE}/xz/xz_dec_stream.c" "${NATIVE}/xz/xz_dec_lzma2.c"
    "${NATIVE}/xz/xz_dec_bcj.c")
target_include_directories(pixel17_vtable_test PRIVATE "${NATIVE}/eld" "${NATIVE}/xz")
target_compile_definitions(pixel17_vtable_test PRIVATE XZ_DEC_ARM64 XZ_DEC_X86 XZ_DEC_ANY_CHECK XZ_USE_CRC64 XZ_USE_SHA256)
target_compile_options(pixel17_vtable_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
add_test(NAME pixel17_vtable_test COMMAND pixel17_vtable_test)
