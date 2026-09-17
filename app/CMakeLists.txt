cmake_minimum_required(VERSION 3.22.1)
project("itantra_native")

# Support modern C++20
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -fPIC -pthread -Wall -Wextra")

# Locate Android log & Oboe
find_package(oboe REQUIRED CONFIG)

# Native library sources
add_library(
    itantra_native
    SHARED
    src/main/cpp/oboe_audio_engine.cpp
    src/main/cpp/rms_gate.cpp
    src/main/cpp/onnx_mmap_loader.cpp
    src/main/cpp/jni_bridge.cpp
)

target_include_directories(
    itantra_native
    PRIVATE
    src/main/cpp
)

target_link_libraries(
    itantra_native
    PRIVATE
    oboe::oboe
    log
    android
)
