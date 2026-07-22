cmake_minimum_required(VERSION 3.22.1)
project(quickjs_android_bridge LANGUAGES C CXX)

if(NOT DEFINED QUICKJS_SOURCE_DIR)
    message(FATAL_ERROR "QUICKJS_SOURCE_DIR is required")
endif()

if(NOT EXISTS "${QUICKJS_SOURCE_DIR}/quickjs.h")
    message(FATAL_ERROR "QuickJS source is invalid: ${QUICKJS_SOURCE_DIR}")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(QJS_BUILD_LIBC OFF CACHE BOOL "" FORCE)
set(QJS_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(QJS_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)

add_subdirectory("${QUICKJS_SOURCE_DIR}" quickjs EXCLUDE_FROM_ALL)

add_library(quickjs_bridge SHARED quickjs_bridge.cpp)
target_compile_features(quickjs_bridge PRIVATE cxx_std_17)
target_link_libraries(quickjs_bridge PRIVATE qjs log)
