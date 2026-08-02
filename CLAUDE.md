# ChatGemma — Claude Notes

## LiteRT-LM (.litertlm) Models

### Hugging Face bundle selection (crash-critical)

`litert-community` repos (e.g. `litert-community/gemma-4-E2B-it-litert-lm`) ship
**multiple** `.litertlm` files: the generic mobile bundle (`gemma-4-E2B-it.litertlm`)
plus web- and NPU-specific builds (`…-web.litertlm`, `…_qualcomm_sm8750.litertlm`,
`…_Google_Tensor_G5.litertlm`, `…_intel_PTL.litertlm`). Only the generic bundle
contains the `TF_LITE_PREFILL_DECODE` CPU/GPU graph; the others fail on-device with
`Failed to create engine: NOT_FOUND: TF_LITE_PREFILL_DECODE not found in the model.`
The HF API lists siblings alphabetically and `-` sorts before `.`, so a naive
"first `.litertlm`" pick downloads the web bundle. `ModelRepositoryImpl.pickRuntimeBundle`
filters platform-specific tokens and prefers int4 > int8 > shortest name — keep it
in sync if repos introduce new platform suffixes.

### GPU support annotation (Model Manager badges)

GPU capability for LLM bundles is **not discoverable at runtime before download** —
Google's AI Edge Gallery solves this with a curated per-model `"accelerators"`
field in its allowlist (`google-ai-edge/gallery` → `model_allowlists/*.json`,
e.g. `"gpu,cpu"` vs `"cpu"`). `ModelRepositoryImpl.detectGpuSupport` mirrors that
knowledge (Gemma 3/3n/4 LiteRT = GPU; FunctionGemma-270M q8 bundles = CPU-only;
GGUF = GPU via llama.cpp OpenCL with runtime fallback) into
`ModelVersion.gpuSupport` ("gpu" | "cpu" | "unknown"), shown as a badge on the
model card. Update the curated rules when the Gallery allowlist changes.

### MediaPipe has no Gemma 4 (by design)

The MediaPipe LLM Inference API (`tasks-genai`) is in **maintenance mode**;
Google's migration target is LiteRT-LM. Gemma 4 is released for on-device use
as `.litertlm` only (`litert-community/gemma-4-E2B-it-litert-lm` / `-E4B-`) —
the exact bundles the AI Edge Gallery ships. Do not go looking for a Gemma 4
`.task`; the `-web.task` sibling is web-only. `ModelRepositoryImpl` pins these
two repos as curated entries so they always appear in Model Manager.

### Gallery parity (speed & quality)

The AI Edge Gallery's advantages over a naive LiteRT integration, all now
mirrored in `LiteRtInferenceEngine` / `ChatViewModel`:
- **Persistent `Conversation` across turns** — full history retained in the KV
  cache, prefill only processes the new message. The engine mirrors sent turns
  in `sentTurns`; a history mismatch (branch switch, compression, edits)
  rebuilds the conversation via `ConversationConfig(initialMessages=…)`.
  Never go back to one-shot conversations that send only the last user message.
- **System prompt** goes through `ConversationConfig(systemInstruction=…)`, not
  a fake user/model turn pair (PromptBuilder's `[System: …]` pair is parsed
  back out in `parsePrompt`).
- **Sampler defaults** for Gemma on LiteRT: topK=64, topP=0.95, temperature=1.0,
  maxTokens=4000 (Gallery allowlist values). Applied in `ChatViewModel.loadModel`
  only when the user hasn't customized the sampler.
- **`EngineConfig(maxNumTokens=contextSize)`** — without it the context window
  is the library default, not what the UI promises.
- **Runtime version matters**: Gemma 4 Multi-Token Prediction (>2x GPU decode)
  ships inside litertlm ≥0.11 and the standard `.litertlm` bundle — no app code
  needed, just a current `litertlm` pin.

### Engine routing

`HybridInferenceEngine` sniffs magic bytes first ("GGUF", "LITERTLM", "PK" zip →
MediaPipe `.task`), then falls back to extension, then `params.modelFormat`. Routing
a `.litertlm` to MediaPipe produces the same `TF_LITE_PREFILL_DECODE` NOT_FOUND error,
so keep magic-byte routing authoritative.

`litertlm-android` is pinned in `gradle/libs.versions.toml` (`litertlm`). New Gemma
model drops on HF have historically required the newest runtime (see HF discussions
"New gemma-4-E2B-it.litertlm broken!") — when a fresh model fails to load, check for
a newer LiteRT-LM release before debugging further.

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
