cmake_minimum_required(VERSION 3.22.1)
project("mixtape" LANGUAGES C)

# LAME source directory - populated by setup_lame.sh
set(LAME_DIR ${CMAKE_CURRENT_SOURCE_DIR}/lame)

# LAME source files (core encoder only - no frontend/decoder)
set(LAME_SOURCES
    ${LAME_DIR}/libmp3lame/bitstream.c
    ${LAME_DIR}/libmp3lame/encoder.c
    ${LAME_DIR}/libmp3lame/fft.c
    ${LAME_DIR}/libmp3lame/gain_analysis.c
    ${LAME_DIR}/libmp3lame/id3tag.c
    ${LAME_DIR}/libmp3lame/lame.c
    ${LAME_DIR}/libmp3lame/mpglib_interface.c
    ${LAME_DIR}/libmp3lame/newmdct.c
    ${LAME_DIR}/libmp3lame/presets.c
    ${LAME_DIR}/libmp3lame/psymodel.c
    ${LAME_DIR}/libmp3lame/quantize.c
    ${LAME_DIR}/libmp3lame/quantize_pvt.c
    ${LAME_DIR}/libmp3lame/reservoir.c
    ${LAME_DIR}/libmp3lame/set_get.c
    ${LAME_DIR}/libmp3lame/tables.c
    ${LAME_DIR}/libmp3lame/takehiro.c
    ${LAME_DIR}/libmp3lame/util.c
    ${LAME_DIR}/libmp3lame/vbrquantize.c
    ${LAME_DIR}/libmp3lame/VbrTag.c
    ${LAME_DIR}/libmp3lame/version.c
)

add_library(mp3lame STATIC ${LAME_SOURCES})

target_include_directories(mp3lame PRIVATE
    ${LAME_DIR}/include
    ${LAME_DIR}/libmp3lame
    ${LAME_DIR}
)

target_compile_definitions(mp3lame PRIVATE
    STDC_HEADERS
    HAVE_STDINT_H
    HAVE_MPGLIB
    DECODE_ON_THE_FLY
    USE_FAST_LOG
    TAKEHIRO_IEEE754_HACK
)

# JNI bridge library
add_library(lame_jni SHARED lame_jni.c)

target_include_directories(lame_jni PRIVATE
    ${LAME_DIR}/include
)

target_link_libraries(lame_jni
    mp3lame
    log
)
