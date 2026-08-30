cmake_minimum_required(VERSION 3.16)
project(PlayerESPInjector LANGUAGES CXX RC)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
find_package(JNI REQUIRED)

add_executable(loader loader.cpp)
target_compile_definitions(loader PRIVATE UNICODE _UNICODE WIN32_LEAN_AND_MEAN NOMINMAX)
if(MINGW)
    target_link_options(loader PRIVATE -municode -static)
endif()

add_library(playeresp_hook SHARED hook.cpp)
target_compile_definitions(playeresp_hook PRIVATE UNICODE _UNICODE WIN32_LEAN_AND_MEAN NOMINMAX)
set_target_properties(playeresp_hook PROPERTIES OUTPUT_NAME "Entry" PREFIX "")

add_library(playeresp_library SHARED library.cpp payloads.rc)
set_property(SOURCE payloads.rc PROPERTY OBJECT_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/../payload/build/libs/playeresp_payload.jar;${CMAKE_CURRENT_SOURCE_DIR}/../payload/build/libs/playeresp_payload-runtime.jar;${CMAKE_CURRENT_SOURCE_DIR}/../payload/build/libs/playeresp_payload-named.jar")
# JNI headers are only needed by C++, not by the resource compiler. This also
# keeps MinGW windres from splitting JDK include paths that contain spaces.
foreach(JNI_INCLUDE_DIR IN LISTS JNI_INCLUDE_DIRS)
    target_include_directories(playeresp_library PRIVATE
        "$<$<COMPILE_LANGUAGE:CXX>:${JNI_INCLUDE_DIR}>")
endforeach()
target_compile_definitions(playeresp_library PRIVATE UNICODE _UNICODE WIN32_LEAN_AND_MEAN NOMINMAX)
set_target_properties(playeresp_library PROPERTIES OUTPUT_NAME "playeresp_library" PREFIX "")

if(MINGW)
    target_link_options(playeresp_hook PRIVATE -static-libgcc -static-libstdc++)
    target_link_options(playeresp_library PRIVATE -static-libgcc -static-libstdc++)
endif()
