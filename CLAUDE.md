# ChatGemma — Claude Notes

## llama.cpp JNI Integration

### API Version (pinned: b8763)

The llama.cpp API changed significantly around build b4000. The CI pins to tag `b8763`. Use these function names:

| Old (deprecated/removed) | New |
|---|---|
| `llama_load_model_from_file` | `llama_model_load_from_file` |
| `llama_new_context_with_model` | `llama_init_from_model` |
| `llama_free_model` | `llama_model_free` |
| `llama_tokenize(model, ...)` | `llama_tokenize(vocab, ...)` |
| `llama_token_to_piece(model, ...)` | `llama_token_to_piece(vocab, ...)` |
| `llama_token_is_eog(model, tok)` | `llama_vocab_is_eog(vocab, tok)` |

Vocab-related functions now take `const struct llama_vocab *` as first argument. Obtain it via:
```cpp
const struct llama_vocab* vocab = llama_model_get_vocab(model);
```

### Header Layout

Modern llama.cpp distributes headers across multiple directories. The CI step copies from all three to `app/src/main/cpp/llama_include/`:
- `llama_src/` (root level — older layout)
- `llama_src/include/` (newer layout — `llama.h` lives here)
- `llama_src/ggml/include/` (`ggml.h`, `ggml-cpu.h`, `ggml-alloc.h`, `ggml-backend.h`, etc.)

### CI Build Strategy

llama.cpp no longer publishes pre-built Android `.so` files. The workflow builds from source:
1. Clone pinned tag (`b8763`) — **do not use unpinned HEAD**; upstream changes break CI
2. Install OpenCL headers (`KhronosGroup/OpenCL-Headers`) into the NDK sysroot, then build `KhronosGroup/OpenCL-ICD-Loader` against the NDK toolchain to produce `libOpenCL.so` (also installed into the NDK sysroot for linking)
3. CMake configure with NDK toolchain, `BUILD_SHARED_LIBS=ON`, `GGML_OPENCL=ON` + `GGML_OPENCL_USE_ADRENO_KERNELS=ON` + `GGML_OPENCL_EMBED_KERNELS=ON`, `GGML_VULKAN=OFF`, no tests/examples/Metal/CUDA
4. Copy `.so` outputs (including `libggml-opencl.so`) to `app/src/main/jniLibs/arm64-v8a/`, plus the built `libOpenCL.so` ICD loader (so `libggml-opencl.so`'s runtime dependency resolves and dispatches to the on-device Adreno driver)
5. Copy headers to `app/src/main/cpp/llama_include/`
6. Cache by `llama_jni.cpp` hash to avoid rebuilding on every push

**Why OpenCL instead of Vulkan:** Vulkan GPU offload on Qualcomm Adreno GPUs (e.g. Adreno 750 in Snapdragon 8 Gen 3 / Xiaomi 14 Ultra) is known to be unreliable — model load failures and poor performance. llama.cpp's `GGML_OPENCL` backend with `GGML_OPENCL_USE_ADRENO_KERNELS` is the Qualcomm-recommended GPU path and the one actually validated on Adreno.

**To update llama.cpp version:** Change `LLAMA_CPP_TAG` in `build-release.yml` and bump the cache key suffix (`opencl-v1` → `opencl-v2` etc.) to force a rebuild.

### GPU Backend Diagnostics

`nativeInit()` in `llama_jni.cpp` calls `ggml_backend_load_all()` and logs every registered `ggml_backend_dev_t` (name, description, type) via `LOGI`. Check `adb logcat -s LlamaCpp` after model load to confirm whether the OpenCL/Adreno GPU device registered, or whether only CPU is available.

### Fresh Context Per Generation

`llama_jni.cpp` creates and destroys `llama_context` on every `nativeGenerate` call. This avoids needing `llama_kv_cache_clear` / `llama_kv_self_clear` (which was also renamed), at the cost of slightly higher per-call overhead. The model itself (`llama_model`) is kept loaded across calls.

### Cancellation & Free Ordering (crash-critical)

`LlamaHandle` carries an `std::atomic<bool> cancelRequested` checked between decode steps. `nativeCancel` sets it; both generate loops and `decode_prompt_chunked` exit promptly when set.

On the Kotlin side (`LlamaCppInferenceEngine`), **every** native call that uses the model handle runs under `inferenceMutex`, and `release()` (a) calls `nativeCancel` to break any in-flight generation, then (b) acquires the mutex before `nativeFree`. Never call `nativeFree` outside this path: freeing the model while ggml compute threads are decoding is a use-after-free SIGSEGV inside `libggml-cpu.so` worker threads (observed on-device). Handle reads must happen *after* acquiring the mutex, since `release()` zeroes the handle under the same mutex.

The GPU crash sentinel (`AppPreferences.setGpuSentinel`) must be cleared in a `finally` with `NonCancellable` — clearing it only on the success path causes cancelled generations (stop button, leaving the screen, process kill) to masquerade as GPU crashes and silently disable GPU acceleration on next launch.

### Conditional NDK Build in build.gradle.kts

The `externalNativeBuild` block is only enabled when `libllama.so` is present (populated by CI). This lets local developer builds skip the NDK entirely:
```kotlin
val llamaSo = file("src/main/jniLibs/arm64-v8a/libllama.so")
if (llamaSo.exists()) {
    ndkVersion = "27.2.12479018"
    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")  // must be File?, not String
            version = "3.22.1+"
        }
    }
}
```

Note: `cmake { path = ... }` expects `File?` — use `file("CMakeLists.txt")`, not a plain string.
