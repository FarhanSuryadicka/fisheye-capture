cmake_minimum_required(VERSION 3.22)
project(fisheyecapture_native C)

# libusb + Android-patched libuvc, vendored from room-surveillance-project.
set(LIBUSB_INSTALL_TARGETS OFF)
set(LIBUSB_BUILD_SHARED_LIBS OFF)
set(LIBUSB_BUILD_EXAMPLES OFF)
set(LIBUSB_BUILD_TESTING OFF)
add_subdirectory(libusb-cmake)

set(LIBUVC_SOURCES
    libuvc/src/ctrl.c
    libuvc/src/ctrl-gen.c
    libuvc/src/device.c
    libuvc/src/diag.c
    libuvc/src/frame.c
    libuvc/src/init.c
    libuvc/src/misc.c
    libuvc/src/stream.c
)
set(libuvc_VERSION_MAJOR 0)
set(libuvc_VERSION_MINOR 0)
set(libuvc_VERSION_PATCH 7)
set(libuvc_VERSION 0.0.7)
configure_file(
    libuvc/include/libuvc/libuvc_config.h.in
    ${CMAKE_CURRENT_BINARY_DIR}/include/libuvc/libuvc_config.h
    @ONLY
)
add_library(uvc STATIC ${LIBUVC_SOURCES})
target_include_directories(uvc PUBLIC libuvc/include ${CMAKE_CURRENT_BINARY_DIR}/include)
target_link_libraries(uvc PRIVATE libusb::usb-1.0)

add_library(fisheyecapture SHARED
    fisheye_capture_jni.c
    log_ring.c
    uvc_growable_buf.c
    uvc_mode_negotiation.c
    uvc_mjpeg_dht_fix.c
)
target_link_libraries(fisheyecapture PRIVATE uvc libusb::usb-1.0 android log)
target_compile_options(fisheyecapture PRIVATE -Wall -Wextra)
