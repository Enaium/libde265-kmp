#
# Compiles libde265 and the KMP C++ shim into one static archive and,
# optionally, the JNI shared library or the standalone WebAssembly module.
# SPDX-License-Identifier: LGPL-3.0-or-later
#
#   cmake -S jni -B build -DBUILD_JNI=ON               desktop JVM / Android JNI
#   cmake -S jni -B build                              static archive only
#   emcmake cmake -S jni -B build-wasm -DBUILD_WASM=ON WebAssembly
#
# libde265 ships its own CMake project, so this file configures it through
# add_subdirectory() instead of listing sources. The decoder library, the
# optional encoder, the command line tools and the SDL dependency are all
# turned off; SIMD is disabled so a single scalar code path serves every
# architecture, including the cross-compiled and WebAssembly builds.
#
# DE265_DIR points at the libde265 checkout to compile; the default is the git
# submodule next to this directory.
#
cmake_minimum_required(VERSION 3.20)

project(de265_kmp C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(BUILD_JNI "Build the JNI shared library" OFF)
option(BUILD_WASM "Build the standalone WebAssembly module" OFF)

set(DE265_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../libde265" CACHE PATH "libde265 checkout to build")
if(NOT EXISTS "${DE265_DIR}/libde265/de265.h")
  message(FATAL_ERROR "libde265 sources are missing at ${DE265_DIR}")
endif()

# Force the feature set every target shares. These are CACHE variables because
# libde265 declares them with option(); a plain set() would be shadowed.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build libde265 as a static library" FORCE)
set(ENABLE_SDL OFF CACHE BOOL "libde265 SDL support" FORCE)
set(ENABLE_DECODER OFF CACHE BOOL "libde265 dec265 tool" FORCE)
set(ENABLE_ENCODER OFF CACHE BOOL "libde265 encoder" FORCE)
set(ENABLE_SHERLOCK265 OFF CACHE BOOL "libde265 sherlock265 tool" FORCE)
set(ENABLE_INTERNAL_DEVELOPMENT_TOOLS OFF CACHE BOOL "libde265 development tools" FORCE)
set(WITH_FUZZERS OFF CACHE BOOL "libde265 fuzzers" FORCE)
set(ENABLE_SIMD OFF CACHE BOOL "libde265 SIMD kernels" FORCE)
set(DE265_LOG_LEVEL "none" CACHE STRING "libde265 log level" FORCE)

add_subdirectory("${DE265_DIR}" de265-build)

# The shim becomes a member of the libde265 target, so the archive that
# cinterop links contains both the decoder and the KMP entry points.
target_sources(de265 PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/de265_kmp.cpp")
target_include_directories(de265 PUBLIC
  "$<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>"
  "$<INSTALL_INTERFACE:include>")
target_compile_definitions(de265 PRIVATE DE265_KMP_BUILD)

# The archive name is what the cinterop `-staticLibrary` flag and the JNI link
# line expect: libde265_kmp.a on Unix/MinGW.
set_target_properties(de265 PROPERTIES OUTPUT_NAME de265_kmp)

if(BUILD_JNI)
  add_library(de265_jni SHARED "${CMAKE_CURRENT_SOURCE_DIR}/de265_jni.cpp")
  target_link_libraries(de265_jni PRIVATE de265)
  set_target_properties(de265_jni PROPERTIES OUTPUT_NAME de265_jni)

  if(WIN32)
    # MinGW names shared libraries lib*.dll by default; the Kotlin loader uses
    # the unprefixed de265_jni.dll. The C++ runtime is linked statically so the
    # DLL is self-contained.
    set_target_properties(de265_jni PROPERTIES PREFIX "")
    target_link_options(de265_jni PRIVATE -static -static-libgcc -static-libstdc++)
  endif()

  if(ANDROID)
    # 16 KB page-size devices (Android 15+).
    target_link_options(de265_jni PRIVATE
      "-Wl,-z,max-page-size=16384"
      "-Wl,-z,common-page-size=16384"
    )
  else()
    # Derive the headers from JAVA_HOME first: FindJNI refuses to configure
    # when the target platform differs from the host (cross builds for MinGW).
    if(NOT JNI_INCLUDE_DIR AND DEFINED ENV{JAVA_HOME})
      set(JNI_INCLUDE_DIR "$ENV{JAVA_HOME}/include")
      if(WIN32)
        set(JNI_INCLUDE_DIR_PLATFORM "$ENV{JAVA_HOME}/include/win32")
      elseif(APPLE)
        set(JNI_INCLUDE_DIR_PLATFORM "$ENV{JAVA_HOME}/include/darwin")
      else()
        set(JNI_INCLUDE_DIR_PLATFORM "$ENV{JAVA_HOME}/include/linux")
      endif()
    endif()
    if(NOT JNI_INCLUDE_DIR)
      find_package(JNI REQUIRED)
    endif()
    if(JNI_INCLUDE_DIR)
      target_include_directories(de265_jni PRIVATE "${JNI_INCLUDE_DIR}")
    endif()
    if(JNI_INCLUDE_DIR_PLATFORM)
      target_include_directories(de265_jni PRIVATE "${JNI_INCLUDE_DIR_PLATFORM}")
    endif()
    if(JNI_INCLUDE_DIRS)
      target_include_directories(de265_jni PRIVATE ${JNI_INCLUDE_DIRS})
    endif()
  endif()
endif()

if(BUILD_WASM OR EMSCRIPTEN OR CMAKE_SYSTEM_NAME STREQUAL "Emscripten")
  add_executable(de265_wasm "${CMAKE_CURRENT_SOURCE_DIR}/de265_wasm_entry.c")
  target_link_libraries(de265_wasm PRIVATE de265)
  # The executable has only a C source but links a C++ library, so the C++
  # driver (with libc++ and the exception stubs) must run the link.
  set_target_properties(de265_wasm PROPERTIES LINKER_LANGUAGE CXX SUFFIX ".wasm")
  target_link_options(de265_wasm PRIVATE
    "-sSTANDALONE_WASM=1"
    "-sALLOW_MEMORY_GROWTH=1"
    "-sINITIAL_MEMORY=67108864"
    "-sSTACK_SIZE=8388608"
    "-sERROR_ON_UNDEFINED_SYMBOLS=1"
    "-sEXPORTED_FUNCTIONS=_dk_version_number,_dk_version_string,_dk_version_major,_dk_version_minor,_dk_version_maintenance,_dk_decoder_create,_dk_decoder_close,_dk_decoder_set_threads,_dk_decode,_dk_flush,_dk_frame_count,_dk_last_error,_dk_last_warning,_dk_error_text,_dk_output_blob,_dk_alloc,_dk_free,_malloc,_free"
    "--no-entry"
  )
endif()
