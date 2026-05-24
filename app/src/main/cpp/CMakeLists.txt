cmake_minimum_required(VERSION 3.18.1)
project("uinput-ctrl")

add_library(uinput-ctrl SHARED uinput_bridge.cpp)
target_link_libraries(uinput-ctrl android log)
