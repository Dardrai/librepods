get_filename_component(ANDROID_SDK "${CMAKE_ANDROID_NDK}/../.." ABSOLUTE)
find_program(SPATIAL_AIDL aidl PATHS "${ANDROID_SDK}/build-tools/36.0.0" NO_DEFAULT_PATH REQUIRED)

set(idl "${CMAKE_CURRENT_SOURCE_DIR}/idl")
set(generated "${CMAKE_CURRENT_BINARY_DIR}/generated")
file(MAKE_DIRECTORY "${generated}/include")
file(TO_NATIVE_PATH "${idl}" aidl_root)
file(TO_NATIVE_PATH "${generated}" aidl_out)
file(TO_NATIVE_PATH "${generated}/include" aidl_headers)
set(bindings)

function(generate_bindings package version hash)
    file(GLOB inputs CONFIGURE_DEPENDS RELATIVE "${idl}" "${idl}/${package}/*.aidl")
    if(NOT inputs)
        message(FATAL_ERROR "Missing frozen AIDL sources for ${package}")
    endif()
    list(SORT inputs)
    set(api_checksums "")
    foreach(input IN LISTS inputs)
        file(SHA1 "${idl}/${input}" input_hash)
        string(APPEND api_checksums "${input_hash}  ./${input}\n")
        string(REGEX REPLACE "\\.aidl$" ".cpp" binding "${input}")
        list(APPEND bindings "${generated}/${binding}")
        list(APPEND sources "${idl}/${input}")
        file(TO_NATIVE_PATH "${idl}/${input}" aidl_input)
        list(APPEND aidl_sources "${aidl_input}")
    endforeach()
    if(version EQUAL 1)
        set(previous_version "latest-version")
    else()
        math(EXPR previous_version "${version} - 1")
    endif()
    string(SHA1 source_hash "${api_checksums}${previous_version}\n")
    if(NOT source_hash STREQUAL hash)
        message(FATAL_ERROR "Frozen AIDL hash mismatch for ${package}")
    endif()
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${sources})
    execute_process(COMMAND "${SPATIAL_AIDL}"
        --lang=ndk --structured --stability=vintf --min_sdk_version=35
        "--version=${version}" "--hash=${hash}" "-I${aidl_root}"
        "--out=${aidl_out}" "--header_out=${aidl_headers}" ${aidl_sources}
        COMMAND_ERROR_IS_FATAL ANY)
    set(bindings "${bindings}" PARENT_SCOPE)
endfunction()

generate_bindings(android/hardware/media/c2 2 2f7448ba8211c5cd1de7bd6c17fe7c5fd034ebbe)
generate_bindings(android/hardware/media/bufferpool2 2 c12818c51418fbfc76f1b81eba6678d75cf526c1)
generate_bindings(android/hardware/common 2 c32ddfdeb69c6e4a8a45519e6f9a39c4b66fd99f)
generate_bindings(android/hardware/common/fmq 1 6a780550f6e6965d6969fd7964c3ca81b6b0ccdf)

add_library(pixel_spatial_control SHARED SpatialControlJni.cpp ${bindings})
target_include_directories(pixel_spatial_control PRIVATE . "${generated}/include" support)
# Frozen Codec2 parcelables reference APIs newer than the app's minimum SDK.
target_compile_options(pixel_spatial_control PRIVATE --target=aarch64-linux-android35
    -O2 -ffunction-sections -fdata-sections -fvisibility=hidden)
target_link_options(pixel_spatial_control PRIVATE --target=aarch64-linux-android35 -Wl,--gc-sections -Wl,--exclude-libs,ALL
    -Wl,-z,now -Wl,-z,relro -Wl,-z,nodelete -Wl,-z,max-page-size=16384)
target_link_libraries(pixel_spatial_control PRIVATE binder_ndk nativewindow log dl)
