cmake_minimum_required(VERSION 3.22.1)
project(diskscan CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(diskscan SHARED
    jni.cpp
    engine.cpp
    core/scanner.cpp
    core/dupes.cpp
    core/cleaner.cpp
    core/smart.cpp)

# -O3 even in debug builds: the scanner and hasher are the whole point of the native core.
target_compile_options(diskscan PRIVATE -O3 -Wall -Wextra -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(diskscan PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
target_link_libraries(diskscan PRIVATE log)
