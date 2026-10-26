cmake_minimum_required(VERSION 3.22.1)
project(pixel17_codec_tests LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
find_package(Threads REQUIRED)
enable_testing()
set(ELD_SOURCE ../../app/src/main/cpp/eld)
add_executable(pixel17_codec_test pixel17_codec_test.cpp
    ${ELD_SOURCE}/pixel17_codec.cpp ${ELD_SOURCE}/wire.cpp)
target_include_directories(pixel17_codec_test PRIVATE ${ELD_SOURCE})
target_link_libraries(pixel17_codec_test PRIVATE Threads::Threads)
target_compile_options(pixel17_codec_test PRIVATE -Wall -Wextra -Werror)
add_test(NAME pixel17_codec_test COMMAND pixel17_codec_test)

add_executable(pixel17_codec_test_q31 pixel17_codec_test.cpp
    ${ELD_SOURCE}/pixel17_codec.cpp ${ELD_SOURCE}/wire.cpp)
target_include_directories(pixel17_codec_test_q31 PRIVATE ${ELD_SOURCE})
target_link_libraries(pixel17_codec_test_q31 PRIVATE Threads::Threads)
target_compile_options(pixel17_codec_test_q31 PRIVATE -Wall -Wextra -Werror)
add_test(NAME pixel17_codec_test_q31 COMMAND pixel17_codec_test_q31)

target_compile_definitions(pixel17_codec_test_q31 PRIVATE ELD_PRIVATE_WIDE_PCM=1)
