#include <jni.h>
#include "llama.h"
#include <algorithm>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *sampler = nullptr;
    std::vector<llama_token> tokens;
    size_t fed = 0;
    ~Session() {
        if (sampler) llama_sampler_free(sampler);
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
    }
};
Session *session(jlong handle) { return reinterpret_cast<Session *>(handle); }
std::string bytes(JNIEnv *env, jbyteArray value) {
    std::string result(env->GetArrayLength(value), '\0');
    env->GetByteArrayRegion(value, 0, result.size(), reinterpret_cast<jbyte *>(result.data()));
    return result;
}
void fail(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
std::vector<llama_token> tokenize(Session *s, const std::string &text) {
    const auto *vocab = llama_model_get_vocab(s->model);
    int n = llama_tokenize(vocab, text.data(), text.size(), nullptr, 0, true, true);
    if (n == 0) return {};
    std::vector<llama_token> tokens(n < 0 ? -n : n);
    n = llama_tokenize(vocab, text.data(), text.size(), tokens.data(), tokens.size(), true, true);
    if (n < 0) throw std::runtime_error("Local tokenizer failed");
    tokens.resize(n);
    return tokens;
}
}
#define JNI(name) Java_com_github_meetingrecorder_data_LlamaNative_##name
extern "C" JNIEXPORT jlong JNICALL JNI(load)(JNIEnv *env, jobject, jbyteArray path, jint contextSize) {
    try {
        static std::once_flag initialized;
        std::call_once(initialized, [] { llama_backend_init(); });
        auto s = std::make_unique<Session>();
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        s->model = llama_model_load_from_file(bytes(env, path).c_str(), mp);
        if (!s->model) throw std::runtime_error("Cannot load local GGUF model; download it again");
        auto cp = llama_context_default_params();
        cp.n_ctx = contextSize;
        cp.n_batch = 256;
        cp.n_ubatch = 128;
        cp.n_threads = 4;
        cp.n_threads_batch = 4;
        s->ctx = llama_init_from_model(s->model, cp);
        if (!s->ctx) throw std::runtime_error("Cannot allocate local model context; close other apps or use a smaller model");
        s->sampler = llama_sampler_init_greedy();
        return reinterpret_cast<jlong>(s.release());
    } catch (const std::exception &e) { fail(env, e.what()); return 0; }
}
extern "C" JNIEXPORT jint JNICALL JNI(countTokens)(JNIEnv *env, jobject, jlong h, jbyteArray text) {
    try { return tokenize(session(h), bytes(env, text)).size(); }
    catch (const std::exception &e) { fail(env, e.what()); return 0; }
}
extern "C" JNIEXPORT void JNICALL JNI(prepare)(JNIEnv *env, jobject, jlong h, jbyteArray text) {
    try {
        auto *s = session(h);
        s->tokens = tokenize(s, bytes(env, text));
        if (s->tokens.empty() || s->tokens.size() >= llama_n_ctx(s->ctx)) throw std::runtime_error("Local prompt exceeds context");
        llama_memory_clear(llama_get_memory(s->ctx), true);
        llama_sampler_reset(s->sampler);
        s->fed = 0;
    } catch (const std::exception &e) { fail(env, e.what()); }
}
// One bounded prefill batch per call lets Kotlin observe cancellation between batches.
extern "C" JNIEXPORT jboolean JNICALL JNI(feed)(JNIEnv *env, jobject, jlong h) {
    auto *s = session(h);
    const int count = std::min<size_t>(256, s->tokens.size() - s->fed);
    if (count == 0) return false;
    if (llama_decode(s->ctx, llama_batch_get_one(s->tokens.data() + s->fed, count)) != 0) {
        fail(env, "Local prompt evaluation failed"); return false;
    }
    s->fed += count;
    return s->fed < s->tokens.size();
}
// Null is EOS; byte arrays preserve UTF-8 pieces split across token boundaries.
extern "C" JNIEXPORT jbyteArray JNICALL JNI(next)(JNIEnv *env, jobject, jlong h) {
    auto *s = session(h);
    auto token = llama_sampler_sample(s->sampler, s->ctx, -1);
    const auto *vocab = llama_model_get_vocab(s->model);
    if (llama_vocab_is_eog(vocab, token)) return nullptr;
    std::vector<char> piece(256);
    int size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
    if (size < 0) {
        piece.resize(-size);
        size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
    }
    if (size < 0 || llama_decode(s->ctx, llama_batch_get_one(&token, 1)) != 0) {
        fail(env, "Local generation failed"); return nullptr;
    }
    auto result = env->NewByteArray(size);
    env->SetByteArrayRegion(result, 0, size, reinterpret_cast<const jbyte *>(piece.data()));
    return result;
}
extern "C" JNIEXPORT void JNICALL JNI(free)(JNIEnv *, jobject, jlong h) { delete session(h); }
