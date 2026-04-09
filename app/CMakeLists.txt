cmake_minimum_required(VERSION 3.22.1)
project("chatgemma_llama")

# Pre-built libllama.so is downloaded by CI into src/main/jniLibs/<ABI>/
set(LLAMA_LIB_DIR "${CMAKE_SOURCE_DIR}/src/main/jniLibs/${ANDROID_ABI}")
set(LLAMA_INCLUDE_DIR "${CMAKE_SOURCE_DIR}/src/main/cpp/llama_include")

# Import pre-built llama.cpp shared library
add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES
    IMPORTED_LOCATION "${LLAMA_LIB_DIR}/libllama.so"
)

# Import ggml if present as a separate .so (newer llama.cpp splits the library)
if(EXISTS "${LLAMA_LIB_DIR}/libggml.so")
    add_library(ggml SHARED IMPORTED)
    set_target_properties(ggml PROPERTIES
        IMPORTED_LOCATION "${LLAMA_LIB_DIR}/libggml.so"
    )
endif()

# Our thin JNI bridge (compiles in seconds)
add_library(chatgemma_llama SHARED
    src/main/cpp/llama_jni.cpp
)

target_include_directories(chatgemma_llama PRIVATE ${LLAMA_INCLUDE_DIR})

target_link_libraries(chatgemma_llama
    llama
    android
    log
)
