cmake_minimum_required(VERSION 3.22.1)
project(avifone LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)

add_library(avifone SHARED
        jni_bridge.cpp
        LiveEngine.cpp
        AmbientReducer.cpp)

target_compile_options(avifone PRIVATE -Wall -Wextra -Werror=return-type)
target_link_libraries(avifone PRIVATE oboe::oboe log)
