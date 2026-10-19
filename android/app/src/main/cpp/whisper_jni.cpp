#include <jni.h>
#include <atomic>
#include <string>
#include <vector>
#include "whisper.h"

struct Session { whisper_context *ctx; std::atomic<bool> cancelled{false}; };
static bool aborted(void *data) { return static_cast<Session *>(data)->cancelled.load(); }
static void fail(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_github_meetingrecorder_data_WhisperNative_open(JNIEnv *env, jobject, jstring path) {
    const char *file = env->GetStringUTFChars(path, nullptr);
    auto params = whisper_context_default_params();
    params.use_gpu = false;
    auto *ctx = whisper_init_from_file_with_params(file, params);
    env->ReleaseStringUTFChars(path, file);
    if (!ctx) { fail(env, "Cannot load Whisper model. Delete and download it again, or try a smaller model."); return 0; }
    auto *session = new Session(); session->ctx = ctx;
    return reinterpret_cast<jlong>(session);
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_github_meetingrecorder_data_WhisperNative_transcribe(JNIEnv *env, jobject, jlong handle, jfloatArray audio, jint threads) {
    auto *s = reinterpret_cast<Session *>(handle);
    const auto n = env->GetArrayLength(audio);
    std::vector<float> pcm(n);
    env->GetFloatArrayRegion(audio, 0, n, pcm.data());
    auto p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.language = "auto";
    p.translate = false;
    p.no_context = true;
    p.print_realtime = false; p.print_progress = false; p.print_timestamps = false; p.print_special = false;
    p.abort_callback = aborted; p.abort_callback_user_data = s;
    if (whisper_full(s->ctx, p, pcm.data(), n) != 0) {
        fail(env, s->cancelled.load() ? "Whisper cancelled." : "Whisper transcription failed."); return nullptr;
    }
    std::string text;
    for (int i = 0; i < whisper_full_n_segments(s->ctx); ++i) {
        text += whisper_full_get_segment_text(s->ctx, i);
        text += '\n';
    }
    auto result = env->NewByteArray(static_cast<jsize>(text.size()));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    return result;
}
extern "C" JNIEXPORT void JNICALL
Java_com_github_meetingrecorder_data_WhisperNative_cancel(JNIEnv *, jobject, jlong handle) {
    reinterpret_cast<Session *>(handle)->cancelled.store(true);
}
extern "C" JNIEXPORT void JNICALL
Java_com_github_meetingrecorder_data_WhisperNative_close(JNIEnv *, jobject, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle); whisper_free(s->ctx); delete s;
}
