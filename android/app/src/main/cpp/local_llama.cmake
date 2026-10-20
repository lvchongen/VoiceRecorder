# Build llama's GGML separately from Whisper's pinned GGML; never mix their ABIs.
include(ExternalProject)
set(LLAMA_REV 3bb63cbcbfb62056c84fe9e521b0f3d756d29c32)
set(LLAMA_ROOT "${CMAKE_CURRENT_BINARY_DIR}/local-llama")
set(LLAMA_SRC "${LLAMA_ROOT}/src")
set(LLAMA_BIN "${LLAMA_ROOT}/build")
set(LLAMA_ARCHIVES
    "${LLAMA_BIN}/src/libllama.a"
    "${LLAMA_BIN}/ggml/src/libggml.a"
    "${LLAMA_BIN}/ggml/src/libggml-cpu.a"
    "${LLAMA_BIN}/ggml/src/libggml-base.a")
ExternalProject_Add(local_llama_build
    URL "https://codeload.github.com/ggml-org/llama.cpp/tar.gz/${LLAMA_REV}"
    URL_HASH SHA256=ff9ecfc1aab2b115ef08ef49e6d1460497e3a696f80ab0c595fb8ad223a5cbe0
    SOURCE_DIR "${LLAMA_SRC}" BINARY_DIR "${LLAMA_BIN}"
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    CMAKE_ARGS
        -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DANDROID_ABI=${ANDROID_ABI} -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON
        -DBUILD_SHARED_LIBS=OFF -DLLAMA_BUILD_COMMON=OFF -DLLAMA_BUILD_TESTS=OFF
        -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_BUILD_MTMD=OFF
        -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_BACKEND_DL=OFF
        -DGGML_CPU_KLEIDIAI=OFF -DGGML_CPU_ARM_ARCH=armv8.2-a+fp16+dotprod
    BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --target llama --parallel 4
    INSTALL_COMMAND ""
    BUILD_BYPRODUCTS ${LLAMA_ARCHIVES})
add_library(meeting_llama SHARED llama_jni.cpp)
add_dependencies(meeting_llama local_llama_build)
target_include_directories(meeting_llama PRIVATE "${LLAMA_SRC}/include" "${LLAMA_SRC}/ggml/include")
target_link_libraries(meeting_llama PRIVATE ${LLAMA_ARCHIVES} android log)
# Hide static dependency symbols so loading Whisper/sherpa cannot interpose GGML.
target_link_options(meeting_llama PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,-Bsymbolic" "-Wl,-z,max-page-size=16384")
