cmake_minimum_required(VERSION 3.22)

set(LIB_NAME "fox2d" CACHE STRING "Name of the native shared library (no lib prefix, no extension)")

project(${LIB_NAME} CXX)

set(CMAKE_CXX_STANDARD 26)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

include(${CMAKE_CURRENT_SOURCE_DIR}/ExternalLibraries.cmake)

set(FOX_MANAGED_LIBS ffmpeg oboe)
foreach(lib ${FOX_MANAGED_LIBS})
    fox_ext_import(${lib})
endforeach()
set(EXT_ALL_NAMES "${FOX_MANAGED_LIBS}" CACHE INTERNAL "")
fox_ext_report()

set(EIGEN_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/ExternalLibraries/eigen")
if(NOT EXISTS "${EIGEN_ROOT}/Eigen/Dense")
    message(FATAL_ERROR "Eigen headers not found: ${EIGEN_ROOT}/Eigen/Dense (folder must contain Eigen/ directly)")
endif()
message(STATUS "[eigen] header-only include root: ${EIGEN_ROOT}")

set(GLM_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/ExternalLibraries/glm")
if(NOT EXISTS "${GLM_ROOT}/glm/glm.hpp")
    message(FATAL_ERROR "GLM headers not found: ${GLM_ROOT}/glm/glm.hpp (folder must contain glm/ directly)")
endif()
message(STATUS "[glm] header-only include root: ${GLM_ROOT}")

set(CQ_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/ExternalLibraries/concurrent_queue")
if(NOT EXISTS "${CQ_ROOT}/moodycamel/concurrentqueue.h" OR NOT EXISTS "${CQ_ROOT}/moodycamel/blockingconcurrentqueue.h")
    message(FATAL_ERROR "moodycamel headers not found: ${CQ_ROOT}/moodycamel/{concurrentqueue,blockingconcurrentqueue,lightweightsemaphore}.h (folder must contain moodycamel/ directly)")
endif()
message(STATUS "[concurrent_queue] header-only include root: ${CQ_ROOT}")

configure_file(
    ${CMAKE_CURRENT_SOURCE_DIR}/lib_config.h.in
    ${CMAKE_CURRENT_BINARY_DIR}/generated/lib_config.h
)

add_library(${LIB_NAME} SHARED
    fox_main.cpp
    bpm_detector.cpp
    fox_canvas.cpp
    fox_audio.cpp
    fox_exporter.cpp
    fox_loopgen.cpp
    fox_project.cpp
)

target_include_directories(${LIB_NAME} PRIVATE
    ${CMAKE_CURRENT_BINARY_DIR}/generated
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(${LIB_NAME} PRIVATE 
    log
    GLESv3
    EGL
    android
    m
    z
)

fox_ext_link(${LIB_NAME} ffmpeg SHARED)
fox_ext_link(${LIB_NAME} oboe SHARED)
target_include_directories(${LIB_NAME} SYSTEM PRIVATE "${EIGEN_ROOT}" "${GLM_ROOT}" "${CQ_ROOT}")
target_compile_definitions(${LIB_NAME} PRIVATE
    FOX_HAS_EIGEN=1
    FOX_HAS_GLM=1
    $<$<NOT:$<CONFIG:Debug>>:EIGEN_NO_DEBUG>   # drop Eigen's runtime asserts in non-Debug builds (keeps the audio callback lean)
    GLM_FORCE_INTRINSICS          # use the SIMD path for the detected ISA (NEON on arm64)
    # GLM_FORCE_MESSAGES          # uncomment once: prints the detected instruction set in the build log
)

target_link_options(${LIB_NAME} PRIVATE
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)
