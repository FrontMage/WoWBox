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

option(WINLATOR_HOST_VULKAN_RENDERER "Build the system-Vulkan host compositor" OFF)
if(WINLATOR_HOST_VULKAN_RENDERER)
    # GameNative v1.2.1 (205cbf8d), independent of guest graphics assets.
    find_program(WINLATOR_GLSLANG_VALIDATOR glslangValidator NO_CMAKE_FIND_ROOT_PATH REQUIRED)
    foreach(shader vert frag)
        add_custom_command(
            OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/window_${shader}.h
            COMMAND ${WINLATOR_GLSLANG_VALIDATOR} -V --vn window_${shader}_code -x
                    -o ${CMAKE_CURRENT_BINARY_DIR}/window_${shader}.h
                    ${CMAKE_CURRENT_SOURCE_DIR}/winlator/window.${shader}
            DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/winlator/window.${shader}
            VERBATIM)
    endforeach()
    add_library(vulkan_renderer SHARED
                winlator/vulkan_jni.cpp
                winlator/VulkanRendererContext.cpp
                winlator/VulkanRendererScanout.cpp
                ${CMAKE_CURRENT_BINARY_DIR}/window_vert.h
                ${CMAKE_CURRENT_BINARY_DIR}/window_frag.h)
    target_include_directories(vulkan_renderer PRIVATE ${CMAKE_CURRENT_BINARY_DIR})
    target_compile_features(vulkan_renderer PRIVATE cxx_std_17)
    target_compile_definitions(vulkan_renderer PRIVATE VK_USE_PLATFORM_ANDROID_KHR)
    target_compile_options(vulkan_renderer PRIVATE -Wall -Wextra -fvisibility=hidden)
    target_link_libraries(vulkan_renderer log android vulkan dl atomic)
    target_link_options(vulkan_renderer PRIVATE -Wl,-z,max-page-size=16384)
endif()
