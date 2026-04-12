#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <android/log.h>
#include "llama.h"

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

    // Tokenise (first call with nullptr to get count)
    int nPrompt = -llama_tokenize(vocab, prompt, (int32_t)strlen(prompt),
                                  nullptr, 0, /*add_special=*/true, /*parse_special=*/true);
    std::vector<llama_token> tokens(nPrompt);
    llama_tokenize(vocab, prompt, (int32_t)strlen(prompt),
                   tokens.data(), nPrompt, true, true);
    env->ReleaseStringUTFChars(jPrompt, prompt);

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
    for (int i = 0; i < maxNewTokens; ++i) {
        llama_token tok = llama_sampler_sample(sampler, ctx, -1);
        llama_sampler_accept(sampler, tok);
        if (llama_vocab_is_eog(vocab, tok)) break;

        char piece[256];
        int n = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, true);
        if (n > 0) output.append(piece, n);

        llama_batch next = llama_batch_get_one(&tok, 1);
        if (llama_decode(ctx, next) != 0) break;
    }

    llama_sampler_free(sampler);
    llama_free(ctx);
    return env->NewStringUTF(output.c_str());
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
