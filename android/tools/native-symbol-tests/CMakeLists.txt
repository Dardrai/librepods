cmake_minimum_required(VERSION 3.22.1)
project(native_symbol_tests LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
enable_testing()
find_package(Python3 REQUIRED COMPONENTS Interpreter)
add_custom_command(OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/compressed_fixtures.h"
    COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/make_compressed_fixtures.py"
        "${CMAKE_CURRENT_BINARY_DIR}/compressed_fixtures.h"
    DEPENDS make_compressed_fixtures.py VERBATIM)
get_filename_component(NATIVE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp" ABSOLUTE)
add_executable(symbol_image_test symbol_image_test.cpp "${NATIVE_ROOT}/eld/symbol_image.cpp"
    "${CMAKE_CURRENT_BINARY_DIR}/compressed_fixtures.h"
    "${NATIVE_ROOT}/xz/xz_crc32.c" "${NATIVE_ROOT}/xz/xz_crc64.c" "${NATIVE_ROOT}/xz/xz_sha256.c"
    "${NATIVE_ROOT}/xz/xz_dec_stream.c" "${NATIVE_ROOT}/xz/xz_dec_lzma2.c" "${NATIVE_ROOT}/xz/xz_dec_bcj.c")
target_include_directories(symbol_image_test PRIVATE "${NATIVE_ROOT}/eld" "${NATIVE_ROOT}/xz" "${CMAKE_CURRENT_BINARY_DIR}")
target_compile_definitions(symbol_image_test PRIVATE XZ_DEC_ARM64 XZ_DEC_X86 XZ_DEC_ANY_CHECK XZ_USE_CRC64 XZ_USE_SHA256)
if(MSVC)
    target_compile_options(symbol_image_test PRIVATE /W4 /UNDEBUG)
else()
    target_compile_options(symbol_image_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
endif()
add_test(NAME symbol_image_test COMMAND symbol_image_test)
set_tests_properties(symbol_image_test PROPERTIES TIMEOUT 60)
