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
2. Install Vulkan C++ headers from `KhronosGroup/Vulkan-Hpp` (the NDK and `vulkan-headers` apt package only have C headers; ggml-vulkan.cpp needs `vulkan.hpp`)
3. CMake configure with NDK toolchain, `BUILD_SHARED_LIBS=ON`, `GGML_VULKAN=ON`, no tests/examples/Metal/CUDA
4. Copy `.so` outputs to `app/src/main/jniLibs/arm64-v8a/`
5. Copy headers to `app/src/main/cpp/llama_include/`
6. Cache both by `llama_jni.cpp` hash to avoid rebuilding on every push

**To update llama.cpp version:** Change `LLAMA_CPP_TAG` in `build-release.yml` and bump the cache key suffix (`vulkan-v2` → `vulkan-v3` etc.) to force a rebuild.

### Fresh Context Per Generation

`llama_jni.cpp` creates and destroys `llama_context` on every `nativeGenerate` call. This avoids needing `llama_kv_cache_clear` / `llama_kv_self_clear` (which was also renamed), at the cost of slightly higher per-call overhead. The model itself (`llama_model`) is kept loaded across calls.

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
