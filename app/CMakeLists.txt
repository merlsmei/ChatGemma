cmake_minimum_required(VERSION 3.22.1)
project("chatgemma_llama")

# Pre-built llama.cpp/ggml libraries are built by CI into src/main/jniLibs/<ABI>/
set(LLAMA_LIB_DIR "${CMAKE_SOURCE_DIR}/src/main/jniLibs/${ANDROID_ABI}")
set(LLAMA_INCLUDE_DIR "${CMAKE_SOURCE_DIR}/src/main/cpp/llama_include")

# Import every pre-built shared library found in jniLibs/<ABI>/. Modern
# llama.cpp (BUILD_SHARED_LIBS=ON) splits its output across multiple .so
# files (libllama.so, libggml.so, libggml-base.so, libggml-cpu.so,
# libggml-opencl.so, ...) plus our bundled libOpenCL.so ICD loader. We need
# all of them on the chatgemma_llama link line since llama_jni.cpp calls
# ggml_backend_* symbols directly (exported from the ggml libraries, not
# libllama.so), and the linker enforces -Wl,--no-undefined.
file(GLOB LLAMA_PREBUILT_LIBS "${LLAMA_LIB_DIR}/*.so")

set(LLAMA_IMPORTED_TARGETS "")
foreach(LIB_PATH ${LLAMA_PREBUILT_LIBS})
    get_filename_component(LIB_NAME ${LIB_PATH} NAME_WE)
    string(REPLACE "lib" "" TARGET_NAME ${LIB_NAME})
    add_library(${TARGET_NAME} SHARED IMPORTED)
    set_target_properties(${TARGET_NAME} PROPERTIES
        IMPORTED_LOCATION ${LIB_PATH}
    )
    list(APPEND LLAMA_IMPORTED_TARGETS ${TARGET_NAME})
endforeach()

# Our thin JNI bridge (compiles in seconds)
add_library(chatgemma_llama SHARED
    src/main/cpp/llama_jni.cpp
)

target_include_directories(chatgemma_llama PRIVATE ${LLAMA_INCLUDE_DIR})

target_link_libraries(chatgemma_llama
    ${LLAMA_IMPORTED_TARGETS}
    android
    log
)
