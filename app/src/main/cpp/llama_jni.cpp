#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <android/log.h>
#include "llama.h"
#include "ggml-backend.h"

#define TAG "LlamaCpp"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Only the model is kept long-term; context is created fresh per generation
// to avoid llama_kv_cache_clear / llama_kv_self_clear API naming differences.
struct LlamaHandle {
    llama_model* model;
    int nCtx;
    int nThreads;
};

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
        jint nGpuLayers) {

    const char* path = env->GetStringUTFChars(jPath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = nGpuLayers;  // 0 = CPU only, 99 = full GPU (Vulkan)

    LOGI("Loading model: gpu_layers=%d, nCtx=%d, nThreads=%d", nGpuLayers, nCtx, nThreads);
    llama_model* model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jPath, path);

    if (!model) { LOGE("Failed to load model"); return 0L; }

    LOGI("Model loaded OK (nCtx=%d, nThreads=%d, gpu_layers=%d)", nCtx, nThreads, nGpuLayers);
    auto* h = new LlamaHandle{model, nCtx, nThreads};
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT jstring JNICALL
Java_com_chatgemma_app_ai_LlamaCppInferenceEngine_nativeGenerate(
        JNIEnv* env, jobject,
        jlong handle, jstring jPrompt,
        jint maxNewTokens, jfloat temperature, jfloat topP) {

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

    // The prompt text already starts with a literal "<bos>" (added by PromptBuilder),
    // which parse_special=true will convert to the BOS token. Passing add_special=true
    // as well would *also* auto-prepend BOS, producing a duplicate-BOS sequence that
    // can make Gemma immediately emit an end-of-turn token (empty response).
    int nPrompt = -llama_tokenize(vocab, prompt, (int32_t)strlen(prompt),
                                  nullptr, 0, /*add_special=*/false, /*parse_special=*/true);
    std::vector<llama_token> tokens(nPrompt);
    llama_tokenize(vocab, prompt, (int32_t)strlen(prompt),
                   tokens.data(), nPrompt, false, true);
    env->ReleaseStringUTFChars(jPrompt, prompt);
    LOGI("Prompt tokenized: %d tokens", nPrompt);

    // Evaluate prompt tokens
    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t)tokens.size());
    if (llama_decode(ctx, batch) != 0) {
        LOGE("Prompt decode failed");
        llama_free(ctx);
        return env->NewStringUTF("[Error: prompt decode failed]");
    }

    // Build sampler chain
    llama_sampler_chain_params scp = llama_sampler_chain_default_params();
    llama_sampler* sampler = llama_sampler_chain_init(scp);
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(42));
    }

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
        jint maxNewTokens, jfloat temperature, jfloat topP) {

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

    // See nativeGenerate: prompt text already contains a literal "<bos>", so
    // add_special=false avoids a duplicate-BOS sequence at the start.
    int nPrompt = -llama_tokenize(vocab, prompt, (int32_t)strlen(prompt),
                                  nullptr, 0, false, true);
    std::vector<llama_token> tokens(nPrompt);
    llama_tokenize(vocab, prompt, (int32_t)strlen(prompt),
                   tokens.data(), nPrompt, false, true);
    env->ReleaseStringUTFChars(jPrompt, prompt);
    LOGI("Prompt tokenized: %d tokens", nPrompt);

    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t)tokens.size());
    if (llama_decode(ctx, batch) != 0) {
        LOGE("Prompt decode failed");
        llama_free(ctx);
        jclass excCls = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(excCls, "llama.cpp: prompt decode failed");
        return;
    }

    llama_sampler_chain_params scp = llama_sampler_chain_default_params();
    llama_sampler* sampler = llama_sampler_chain_init(scp);
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(42));
    }

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
    llama_model_free(h->model);
    delete h;
    LOGI("Model freed");
}

} // extern "C"
