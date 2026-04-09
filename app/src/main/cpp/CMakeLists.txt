cmake_minimum_required(VERSION 3.22.1)
project(airplay_native C CXX)

set(CMAKE_C_STANDARD 17)
set(CMAKE_CXX_STANDARD 17)

set(UXPLAY_LIB third_party/UxPlay/lib)

# ---------- OpenSSL (built from source via openssl-cmake) ----------
set(BUILD_OPENSSL ON CACHE BOOL "" FORCE)
set(OPENSSL_BUILD_VERSION "3.4.4" CACHE STRING "" FORCE)
set(CROSS_ANDROID ON CACHE BOOL "" FORCE)
set(FORWARD_ANDROID_NDK_ROOT "${ANDROID_NDK}" CACHE STRING "" FORCE) # CI
add_subdirectory(third_party/openssl-cmake)

# ---------- libplist (built from submodule source) ----------
set(PLIST_SRC third_party/libplist/src)
set(PLIST_CNARY third_party/libplist/libcnary)

add_library(plist STATIC
    ${PLIST_SRC}/base64.c
    ${PLIST_SRC}/bplist.c
    ${PLIST_SRC}/bytearray.c
    ${PLIST_SRC}/hashtable.c
    ${PLIST_SRC}/jplist.c
    ${PLIST_SRC}/jsmn.c
    ${PLIST_SRC}/oplist.c
    ${PLIST_SRC}/out-default.c
    ${PLIST_SRC}/out-limd.c
    ${PLIST_SRC}/out-plutil.c
    ${PLIST_SRC}/plist.c
    ${PLIST_SRC}/ptrarray.c
    ${PLIST_SRC}/time64.c
    ${PLIST_SRC}/xplist.c
    ${PLIST_CNARY}/cnary.c
    ${PLIST_CNARY}/node.c
    ${PLIST_CNARY}/node_list.c
)
target_include_directories(plist
    PUBLIC third_party/libplist/include
    PRIVATE ${PLIST_SRC} ${PLIST_CNARY}/include
)
target_compile_definitions(plist PRIVATE
    _GNU_SOURCE
    HAVE_STRNDUP
    PACKAGE_VERSION="2.6.0"
)

# ---------- llhttp (embedded in UxPlay) ----------
add_library(llhttp STATIC
    ${UXPLAY_LIB}/llhttp/api.c
    ${UXPLAY_LIB}/llhttp/http.c
    ${UXPLAY_LIB}/llhttp/llhttp.c
)
target_include_directories(llhttp PUBLIC ${UXPLAY_LIB}/llhttp)

# ---------- playfair (embedded in UxPlay) ----------
add_library(playfair STATIC
    ${UXPLAY_LIB}/playfair/hand_garble.c
    ${UXPLAY_LIB}/playfair/modified_md5.c
    ${UXPLAY_LIB}/playfair/omg_hax.c
    ${UXPLAY_LIB}/playfair/playfair.c
    ${UXPLAY_LIB}/playfair/sap_hash.c
)
target_include_directories(playfair PUBLIC ${UXPLAY_LIB}/playfair)

# ---------- UxPlay core (all .c except dnssd.c, replaced by our shim) ----------
set(AIRPLAY_SOURCES
    ${UXPLAY_LIB}/airplay_video.c
    ${UXPLAY_LIB}/byteutils.c
    ${UXPLAY_LIB}/compat.c
    ${UXPLAY_LIB}/crypto.c
    ${UXPLAY_LIB}/fairplay_playfair.c
    ${UXPLAY_LIB}/http_request.c
    ${UXPLAY_LIB}/http_response.c
    ${UXPLAY_LIB}/httpd.c
    ${UXPLAY_LIB}/logger.c
    ${UXPLAY_LIB}/mirror_buffer.c
    ${UXPLAY_LIB}/netutils.c
    ${UXPLAY_LIB}/pairing.c
    ${UXPLAY_LIB}/raop.c
    ${UXPLAY_LIB}/raop_buffer.c
    ${UXPLAY_LIB}/raop_ntp.c
    ${UXPLAY_LIB}/raop_rtp.c
    ${UXPLAY_LIB}/raop_rtp_mirror.c
    ${UXPLAY_LIB}/srp.c
    ${UXPLAY_LIB}/utils.c
)

# ---------- Apple ALAC reference decoder (Apache 2.0) ----------
set(ALAC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/alac/codec)
set(ALAC_SOURCES
    ${ALAC_DIR}/ALACDecoder.cpp
    ${ALAC_DIR}/ALACBitUtilities.c
    ${ALAC_DIR}/ag_dec.c
    ${ALAC_DIR}/dp_dec.c
    ${ALAC_DIR}/matrix_dec.c
    ${ALAC_DIR}/EndianPortable.c
)

# ---------- Android JNI bridge + dnssd shim ----------
set(BRIDGE_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/android_dnssd_shim.c
    ${CMAKE_CURRENT_SOURCE_DIR}/android_raop_callbacks.c
    ${CMAKE_CURRENT_SOURCE_DIR}/native_bridge.cpp
)

# ---------- Final shared library ----------
add_library(airplay_native SHARED
    ${AIRPLAY_SOURCES}
    ${ALAC_SOURCES}
    ${BRIDGE_SOURCES}
)

target_include_directories(airplay_native PRIVATE
    ${UXPLAY_LIB}
    ${UXPLAY_LIB}/llhttp
    ${UXPLAY_LIB}/playfair
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${ALAC_DIR}
)

target_compile_definitions(airplay_native PRIVATE
    ANDROID
    __STDC_CONSTANT_MACROS
    PLIST_210
    TARGET_RT_LITTLE_ENDIAN=1
)

target_link_libraries(airplay_native
    llhttp
    playfair
    crypto
    plist
    log
    android
)
