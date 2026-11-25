cmake_minimum_required(VERSION 3.22.1)
project(usbaudio CXX)

add_library(usbaudio SHARED usbaudio.cpp)
target_compile_options(usbaudio PRIVATE -O2 -Wall)
target_link_libraries(usbaudio log)
