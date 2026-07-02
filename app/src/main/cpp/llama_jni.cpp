#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <android/log.h>
#include "llama.h"
#include "ggml-backend.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "LlamaCpp"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Only the model is kept long-term; context is created fresh per generation
// to avoid llama_kv_cache_clear / llama_kv_self_clear API naming differences.
// mtmd (multimodal projector) context is likewise kept for the model lifetime;
// it is nullptr for text-only models (no mmproj file).
struct LlamaHandle {
    llama_model* model;
    mtmd_context* mtmd;
    int nCtx;
    int nThreads;
};

// Prefills `ctx` with the prompt and any attached images.
//
// Text-only path: tokenize + llama_decode (prompt already contains a literal
// "<bos>" added by PromptBuilder, so add_special=false avoids duplicate BOS).
//
// Image path: the prompt must contain one mtmd media marker ("<__media__>")
// per image; mtmd_tokenize splits it into text/image chunks and
// mtmd_helper_eval_chunks runs the vision encoder + decode.
//
// Returns the number of evaluated positions (n_past) on success, -1 on failure.
static llama_pos prefill_prompt(JNIEnv* env, LlamaHandle* h, llama_context* ctx,
                                const char* prompt,
                                jobjectArray jImages, jintArray jWidths, jintArray jHeights) {
    const int nImages = jImages ? env->GetArrayLength(jImages) : 0;

    if (nImages > 0 && h->mtmd) {
        mtmd_input_text itext;
        itext.text          = prompt;
        itext.add_special   = false;
        itext.parse_special = true;

        jint* widths  = env->GetIntArrayElements(jWidths,  nullptr);
        jint* heights = env->GetIntArrayElements(jHeights, nullptr);

        std::vector<mtmd_bitmap*> bitmaps;
        bitmaps.reserve(nImages);
        for (int i = 0; i < nImages; ++i) {
            auto arr = (jbyteArray) env->GetObjectArrayElement(jImages, i);
            jbyte* data = env->GetByteArrayElements(arr, nullptr);
            // data is RGBRGB... with length = width * height * 3
            mtmd_bitmap* bmp = mtmd_bitmap_init(
                (uint32_t) widths[i], (uint32_t) heights[i],
                reinterpret_cast<const unsigned char*>(data));
            env->ReleaseByteArrayElements(arr, data, JNI_ABORT);
            env->DeleteLocalRef(arr);
            if (bmp) bitmaps.push_back(bmp);
        }
        env->ReleaseIntArrayElements(jWidths,  widths,  JNI_ABORT);
        env->ReleaseIntArrayElements(jHeights, heights, JNI_ABORT);

        llama_pos nPast = -1;
        mtmd_input_chunks* chunks = mtmd_input_chunks_init();
        int32_t res = mtmd_tokenize(h->mtmd, chunks, &itext,
                                    (const mtmd_bitmap**) bitmaps.data(),
                                    bitmaps.size());
        if (res != 0) {
            LOGE("mtmd_tokenize failed: %d (1 = marker/bitmap count mismatch, 2 = preprocess error)", res);
        } else {
            LOGI("mtmd prompt tokenized: %zu chunks, %zu tokens, %d image(s)",
                 mtmd_input_chunks_size(chunks),
                 mtmd_helper_get_n_tokens(chunks), nImages);
            llama_pos newPast = 0;
            int32_t ret = mtmd_helper_eval_chunks(h->mtmd, ctx, chunks,
                                                  /*n_past=*/0, /*seq_id=*/0,
                                                  (int32_t) llama_n_batch(ctx),
                                                  /*logits_last=*/true, &newPast);
            if (ret != 0) {
                LOGE("mtmd_helper_eval_chunks failed: %d", ret);
            } else {
                nPast = newPast;
            }
        }
        mtmd_input_chunks_free(chunks);
        for (auto* bmp : bitmaps) mtmd_bitmap_free(bmp);
        return nPast;
    }

    if (nImages > 0) {
        LOGE("%d image(s) passed but no mmproj loaded — evaluating text only", nImages);
    }

    const struct llama_vocab* vocab = llama_model_get_vocab(h->model);
    int nPrompt = -llama_tokenize(vocab, prompt, (int32_t) strlen(prompt),
                                  nullptr, 0, /*add_special=*/false, /*parse_special=*/true);
    std::vector<llama_token> tokens(nPrompt);
    llama_tokenize(vocab, prompt, (int32_t) strlen(prompt),
                   tokens.data(), nPrompt, false, true);
    LOGI("Prompt tokenized: %d tokens", nPrompt);

    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    if (llama_decode(ctx, batch) != 0) {
        LOGE("Prompt decode failed");
        return -1;
    }
    return (llama_pos) tokens.size();
}

static llama_sampler* build_sampler(float temperature, float topP) {
    llama_sampler_chain_params scp = llama_sampler_chain_default_params();
    llama_sampler* sampler = llama_sampler_chain_init(scp);
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(42));
    }
    return sampler;
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_chatgemma_app_ai_LlamaCppInferenceEngine_nativeInit(JNIEnv*, jobject) {
    llama_backend_init();
    ggml_backend_load_all();

    size_t nDevices = ggml_backend_dev_count();
    LOGI("GGML backend devices: %zu", nDevices);
    for (size_t i = 0; i < nDevices; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        LOGI("  [%zu] %s — %s (type=%d)", i,
             ggml_backend_dev_name(dev),
             ggml_backend_dev_description(dev),
             (int)ggml_backend_dev_type(dev));
    }
}

JNIEXPORT jlong JNICALL
Java_com_chatgemma_app_ai_LlamaCppInferenceEngine_nativeLoadModel(
        JNIEnv* env, jobject, jstring jPath, jint nCtx, jint nThreads,
        jint nGpuLayers, jstring jMmprojPath) {

    const char* path = env->GetStringUTFChars(jPath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = nGpuLayers;  // 0 = CPU only, 99 = full GPU offload

    LOGI("Loading model: gpu_layers=%d, nCtx=%d, nThreads=%d", nGpuLayers, nCtx, nThreads);
    llama_model* model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jPath, path);

    if (!model) { LOGE("Failed to load model"); return 0L; }

    mtmd_context* mctx = nullptr;
    if (jMmprojPath) {
        const char* mmprojPath = env->GetStringUTFChars(jMmprojPath, nullptr);
        mtmd_context_params mparams = mtmd_context_params_default();
        mparams.use_gpu       = nGpuLayers > 0;
        mparams.n_threads     = nThreads;
        mparams.print_timings = false;
        mctx = mtmd_init_from_file(mmprojPath, model, mparams);
        if (mctx) {
            LOGI("mmproj loaded: %s (vision=%d, audio=%d)", mmprojPath,
                 mtmd_support_vision(mctx), mtmd_support_audio(mctx));
        } else {
            // Model stays usable as text-only; Kotlin queries nativeHasVision().
            LOGE("Failed to load mmproj %s — continuing text-only", mmprojPath);
        }
        env->ReleaseStringUTFChars(jMmprojPath, mmprojPath);
    }

    LOGI("Model loaded OK (nCtx=%d, nThreads=%d, gpu_layers=%d)", nCtx, nThreads, nGpuLayers);
    auto* h = new LlamaHandle{model, mctx, nCtx, nThreads};
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT jboolean JNICALL
Java_com_chatgemma_app_ai_LlamaCppInferenceEngine_nativeHasVision(
        JNIEnv*, jobject, jlong handle) {
    auto* h = reinterpret_cast<LlamaHandle*>(handle);
    return (h && h->mtmd && mtmd_support_vision(h->mtmd)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_chatgemma_app_ai_LlamaCppInferenceEngine_nativeGenerate(
        JNIEnv* env, jobject,
        jlong handle, jstring jPrompt,
        jint maxNewTokens, jfloat temperature, jfloat topP,
        jobjectArray jImages, jintArray jWidths, jintArray jHeights) {

    auto* h = reinterpret_cast<LlamaHandle*>(handle);
    if (!h) return env->NewStringUTF("");

    // Create a fresh context so we never need to clear the KV cache.
    // This sidesteps the llama_kv_cache_clear → llama_kv_self_clear rename.
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx     = static_cast<uint32_t>(h->nCtx);
    cp.n_threads = static_cast<uint32_t>(h->nThreads);
    llama_context* ctx = llama_init_from_model(h->model, cp);
    if (!ctx) {
        LOGE("Failed to create context");
        return env->NewStringUTF("[Error: context creation failed]");
    }

    const struct llama_vocab* vocab = llama_model_get_vocab(h->model);
    const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);
    llama_pos nPast = prefill_prompt(env, h, ctx, prompt, jImages, jWidths, jHeights);
    env->ReleaseStringUTFChars(jPrompt, prompt);
    if (nPast < 0) {
        llama_free(ctx);
        return env->NewStringUTF("[Error: prompt decode failed]");
    }

    llama_sampler* sampler = build_sampler(temperature, topP);

    // Generate tokens
    std::string output;
    output.reserve(512);
    int nGenerated = 0;
    for (int i = 0; i < maxNewTokens; ++i) {
        llama_token tok = llama_sampler_sample(sampler, ctx, -1);
        llama_sampler_accept(sampler, tok);
        if (llama_vocab_is_eog(vocab, tok)) break;

        char piece[256];
        int n = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, true);
        if (n > 0) output.append(piece, n);
        nGenerated++;

        llama_batch next = llama_batch_get_one(&tok, 1);
        if (llama_decode(ctx, next) != 0) break;
    }
    if (nGenerated == 0) LOGI("Generation produced 0 tokens (immediate EOG)");

    llama_sampler_free(sampler);
    llama_free(ctx);
    return env->NewStringUTF(output.c_str());
}

JNIEXPORT void JNICALL
Java_com_chatgemma_app_ai_LlamaCppInferenceEngine_nativeGenerateStreaming(
        JNIEnv* env, jobject thiz,
        jlong handle, jstring jPrompt,
        jint maxNewTokens, jfloat temperature, jfloat topP,
        jobjectArray jImages, jintArray jWidths, jintArray jHeights) {

    auto* h = reinterpret_cast<LlamaHandle*>(handle);
    if (!h) return;

    // Look up the onToken callback once before entering the loop
    jclass cls = env->GetObjectClass(thiz);
    jmethodID onTokenId = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
    if (!onTokenId) { LOGE("onToken method not found"); return; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx     = static_cast<uint32_t>(h->nCtx);
    cp.n_threads = static_cast<uint32_t>(h->nThreads);
    llama_context* ctx = llama_init_from_model(h->model, cp);
    if (!ctx) {
        LOGE("Failed to create context");
        return;
    }

    const struct llama_vocab* vocab = llama_model_get_vocab(h->model);
    const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);
    llama_pos nPast = prefill_prompt(env, h, ctx, prompt, jImages, jWidths, jHeights);
    env->ReleaseStringUTFChars(jPrompt, prompt);
    if (nPast < 0) {
        llama_free(ctx);
        jclass excCls = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(excCls, "llama.cpp: prompt decode failed");
        return;
    }

    llama_sampler* sampler = build_sampler(temperature, topP);

    int nGenerated = 0;
    for (int i = 0; i < maxNewTokens; ++i) {
        llama_token tok = llama_sampler_sample(sampler, ctx, -1);
        llama_sampler_accept(sampler, tok);
        if (llama_vocab_is_eog(vocab, tok)) break;

        char piece[256];
        int n = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, true);
        if (n > 0) {
            // Send each token piece back to Kotlin immediately
            std::string s(piece, n);
            jstring jPiece = env->NewStringUTF(s.c_str());
            env->CallVoidMethod(thiz, onTokenId, jPiece);
            env->DeleteLocalRef(jPiece);
        }
        nGenerated++;

        llama_batch next = llama_batch_get_one(&tok, 1);
        if (llama_decode(ctx, next) != 0) break;
    }
    if (nGenerated == 0) LOGI("Generation produced 0 tokens (immediate EOG)");

    llama_sampler_free(sampler);
    llama_free(ctx);
}

JNIEXPORT jint JNICALL
Java_com_chatgemma_app_ai_LlamaCppInferenceEngine_nativeCountTokens(
        JNIEnv* env, jobject, jlong handle, jstring jText) {
    auto* h = reinterpret_cast<LlamaHandle*>(handle);
    if (!h) return 0;
    const struct llama_vocab* vocab = llama_model_get_vocab(h->model);
    const char* text = env->GetStringUTFChars(jText, nullptr);
    int n = -llama_tokenize(vocab, text, (int32_t)strlen(text),
                            nullptr, 0, false, false);
    env->ReleaseStringUTFChars(jText, text);
    return n > 0 ? n : 0;
}

JNIEXPORT void JNICALL
Java_com_chatgemma_app_ai_LlamaCppInferenceEngine_nativeFree(
        JNIEnv*, jobject, jlong handle) {
    auto* h = reinterpret_cast<LlamaHandle*>(handle);
    if (!h) return;
    if (h->mtmd) mtmd_free(h->mtmd);
    llama_model_free(h->model);
    delete h;
    LOGI("Model freed");
}

} // extern "C"
