cmake_minimum_required(VERSION 3.22.1)

Project(Winlator)

# Keep build-machine checkout paths out of diagnostics and native debug data.
# Map the whole checkout, including generated CMake files, before configuring
# any native subprojects so bundled libraries share the same source identity.
get_filename_component(WOWBOX_SOURCE_ROOT "${CMAKE_SOURCE_DIR}/../../../.." ABSOLUTE)
add_compile_options("-ffile-prefix-map=${WOWBOX_SOURCE_ROOT}=."
                    "-fdebug-prefix-map=${WOWBOX_SOURCE_ROOT}=.")

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")

add_subdirectory(patchelf)
add_subdirectory(adrenotools)
add_subdirectory(proot)
add_subdirectory(virglrenderer)
add_subdirectory(OpenXR-SDK)

add_executable(libsigquit-exec.so
               winlator/sigquit_exec.c)

target_compile_options(libsigquit-exec.so PRIVATE
                       -fPIE
                       -Wall
                       -Wextra
                       -Werror)

target_link_options(libsigquit-exec.so PRIVATE
                    -fPIE
                    -pie)

add_library(winlator SHARED
            xr/engine.c
            xr/framebuffer.c
            xr/input.c
            xr/main.c
            xr/math.c
            xr/renderer.c
            winlator/drawable.c
            winlator/gpu_image.c
            winlator/sysvshared_memory.c
            winlator/xconnector_epoll.c
            winlator/alsa_client.c
            winlator/vulkan.c)

target_link_libraries(winlator
                      log
                      android
                      jnigraphics
                      openxr_loader
                      aaudio
                      EGL
                      GLESv2
                      GLESv3
                      adrenotools)
