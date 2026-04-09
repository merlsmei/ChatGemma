#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <android/log.h>
#include "llama.h"

#define TAG "LlamaCpp"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct LlamaHandle {
    llama_model*   model;
    llama_context* ctx;
};

extern "C" {

JNIEXPORT void JNICALL
Java_com_chatgemma_app_ai_LlamaCppInferenceEngine_nativeInit(JNIEnv*, jobject) {
    llama_backend_init();
}

JNIEXPORT jlong JNICALL
Java_com_chatgemma_app_ai_LlamaCppInferenceEngine_nativeLoadModel(
        JNIEnv* env, jobject, jstring jPath, jint nCtx, jint nThreads) {

    const char* path = env->GetStringUTFChars(jPath, nullptr);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;   // CPU-only on Android

    llama_model* model = llama_load_model_from_file(path, mp);
    env->ReleaseStringUTFChars(jPath, path);

    if (!model) { LOGE("Failed to load model"); return 0L; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx     = static_cast<uint32_t>(nCtx);
    cp.n_threads = static_cast<uint32_t>(nThreads);

    llama_context* ctx = llama_new_context_with_model(model, cp);
    if (!ctx) {
        LOGE("Failed to create context");
        llama_free_model(model);
        return 0L;
    }

    LOGI("Model loaded successfully, ctx=%d tokens, threads=%d", nCtx, nThreads);
    auto* h = new LlamaHandle{model, ctx};
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT jstring JNICALL
Java_com_chatgemma_app_ai_LlamaCppInferenceEngine_nativeGenerate(
        JNIEnv* env, jobject,
        jlong handle, jstring jPrompt,
        jint maxNewTokens, jfloat temperature, jfloat topP) {

    auto* h = reinterpret_cast<LlamaHandle*>(handle);
    if (!h) return env->NewStringUTF("");

    const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);

    // Tokenise the prompt (first call with nullptr to get count)
    int nPrompt = -llama_tokenize(h->model, prompt, (int32_t)strlen(prompt),
                                  nullptr, 0, /*add_special=*/true, /*parse_special=*/true);
    std::vector<llama_token> promptTokens(nPrompt);
    llama_tokenize(h->model, prompt, (int32_t)strlen(prompt),
                   promptTokens.data(), nPrompt, true, true);
    env->ReleaseStringUTFChars(jPrompt, prompt);

    // Evaluate prompt
    llama_kv_cache_clear(h->ctx);
    llama_batch batch = llama_batch_get_one(promptTokens.data(), (int32_t)promptTokens.size());
    if (llama_decode(h->ctx, batch) != 0) {
        LOGE("Prompt decode failed");
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

    std::string output;
    output.reserve(512);

    for (int i = 0; i < maxNewTokens; ++i) {
        llama_token tok = llama_sampler_sample(sampler, h->ctx, -1);
        llama_sampler_accept(sampler, tok);

        if (llama_token_is_eog(h->model, tok)) break;

        char piece[256];
        int n = llama_token_to_piece(h->model, tok, piece, sizeof(piece), 0, true);
        if (n > 0) output.append(piece, n);

        llama_batch next = llama_batch_get_one(&tok, 1);
        if (llama_decode(h->ctx, next) != 0) break;
    }

    llama_sampler_free(sampler);
    return env->NewStringUTF(output.c_str());
}

JNIEXPORT jint JNICALL
Java_com_chatgemma_app_ai_LlamaCppInferenceEngine_nativeCountTokens(
        JNIEnv* env, jobject, jlong handle, jstring jText) {
    auto* h = reinterpret_cast<LlamaHandle*>(handle);
    if (!h) return 0;
    const char* text = env->GetStringUTFChars(jText, nullptr);
    int n = -llama_tokenize(h->model, text, (int32_t)strlen(text),
                            nullptr, 0, false, false);
    env->ReleaseStringUTFChars(jText, text);
    return n > 0 ? n : 0;
}

JNIEXPORT void JNICALL
Java_com_chatgemma_app_ai_LlamaCppInferenceEngine_nativeFree(
        JNIEnv*, jobject, jlong handle) {
    auto* h = reinterpret_cast<LlamaHandle*>(handle);
    if (!h) return;
    llama_free(h->ctx);
    llama_free_model(h->model);
    delete h;
    LOGI("Model freed");
}

} // extern "C"
