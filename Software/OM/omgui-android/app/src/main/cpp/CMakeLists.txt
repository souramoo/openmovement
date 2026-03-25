cmake_minimum_required(VERSION 3.22.1)

project(omgui_native)

set(OMCONVERT_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../../../AX3/omconvert)
set(OMCONVERT_MAIN ${OMCONVERT_DIR}/main.c)

set(OMCONVERT_SOURCES
    ${OMCONVERT_MAIN}
    ${OMCONVERT_DIR}/agfilter.c
    ${OMCONVERT_DIR}/butter.c
    ${OMCONVERT_DIR}/calc-csv.c
    ${OMCONVERT_DIR}/calc-paee.c
    ${OMCONVERT_DIR}/calc-sleep.c
    ${OMCONVERT_DIR}/calc-step.c
    ${OMCONVERT_DIR}/calc-svm.c
    ${OMCONVERT_DIR}/calc-wtv.c
    ${OMCONVERT_DIR}/linearregression.c
    ${OMCONVERT_DIR}/omcalibrate.c
    ${OMCONVERT_DIR}/omconvert.c
    ${OMCONVERT_DIR}/omdata.c
    ${OMCONVERT_DIR}/wav.c
)

set_source_files_properties(${OMCONVERT_MAIN} PROPERTIES COMPILE_DEFINITIONS "main=omconvert_cli_main")

add_library(
    omgui-native
    SHARED
    native_bridge.cpp
    ${OMCONVERT_SOURCES}
)

target_include_directories(
    omgui-native
    PRIVATE
    ${CMAKE_CURRENT_LIST_DIR}/shims
    ${OMCONVERT_DIR}
)

target_link_libraries(
    omgui-native
    android
    log
    m
)
