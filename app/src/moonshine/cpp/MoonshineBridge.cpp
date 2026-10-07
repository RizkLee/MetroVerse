/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 * Calls the public Moonshine v0.1.5 C API; intentionally avoids its Java JNI wrapper.
 */
#include <jni.h>

#include <cmath>
#include <cstdlib>
#include <limits>
#include <memory>
#include <mutex>
#include <shared_mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>

#include "Utf8.h"
#include "moonshine-c-api.h"

namespace {

constexpr int kSampleRate = 16000;
constexpr jsize kMaximumChunkSamples = kSampleRate * 30;
constexpr const char *kLineClass = "com/metrolist/music/podcast/transcript/MoonshineLine";

struct TranscriberState {
    explicit TranscriberState(int value) : handle(value) {}
    const int handle;
    std::mutex mutex;
    bool closed = false;
    std::unordered_map<int, bool> streams;
};

std::mutex registryMutex;
std::unordered_map<int, std::shared_ptr<TranscriberState>> registry;
// Model creation/destruction mutates the upstream process-wide handle table.
// Independent live transcribers may still infer concurrently under shared locks.
std::shared_mutex runtimeHandlesMutex;

void checkStatus(int status, const char *operation) {
    if (status != MOONSHINE_ERROR_NONE) {
        const char *detail = moonshine_error_to_string(status);
        throw std::runtime_error(std::string(operation) + ": " +
            (detail != nullptr ? detail : "unknown native error"));
    }
}

jstring javaString(JNIEnv *env, std::string_view text) {
    const auto utf16 = metroverse::utf8ToUtf16(text);
    if (utf16.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        throw std::runtime_error("Transcript text is too large");
    }
    return env->NewString(reinterpret_cast<const jchar *>(utf16.data()), static_cast<jsize>(utf16.size()));
}

void throwJava(JNIEnv *env, const char *message) noexcept {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type == nullptr) return;
    try {
        const auto constructor = env->GetMethodID(type, "<init>", "(Ljava/lang/String;)V");
        if (constructor != nullptr) {
            jstring detail = javaString(env, message);
            if (detail != nullptr) {
                jobject error = env->NewObject(type, constructor, detail);
                env->DeleteLocalRef(detail);
                if (error != nullptr) {
                    env->Throw(static_cast<jthrowable>(error));
                    env->DeleteLocalRef(error);
                }
            }
        }
    } catch (...) {
        if (!env->ExceptionCheck()) {
            // This literal is ASCII, which is identical in standard and modified UTF-8.
            env->ThrowNew(type, "Moonshine native operation failed");
        }
    }
    env->DeleteLocalRef(type);
}

class FloatElements {
public:
    FloatElements(JNIEnv *env, jfloatArray array) : env_(env), array_(array), data_(env->GetFloatArrayElements(array, nullptr)) {}
    ~FloatElements() {
        if (data_ != nullptr) env_->ReleaseFloatArrayElements(array_, data_, JNI_ABORT);
    }
    FloatElements(const FloatElements &) = delete;
    FloatElements &operator=(const FloatElements &) = delete;
    const float *data() const { return data_; }
private:
    JNIEnv *env_;
    jfloatArray array_;
    jfloat *data_;
};

std::string modelPath(JNIEnv *env, jstring value) {
    if (value == nullptr) throw std::invalid_argument("Model directory is required");
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) throw std::runtime_error("Could not read model directory");
    std::string result;
    try {
        result = metroverse::utf16ToUtf8(std::u16string_view(
            reinterpret_cast<const char16_t *>(chars), static_cast<size_t>(env->GetStringLength(value))));
    } catch (...) {
        env->ReleaseStringChars(value, chars);
        throw;
    }
    env->ReleaseStringChars(value, chars);
    if (result.empty() || result.find('\0') != std::string::npos) {
        throw std::invalid_argument("Invalid model directory");
    }
    return result;
}

std::shared_ptr<TranscriberState> findState(jint handle) {
    std::lock_guard<std::mutex> lock(registryMutex);
    const auto found = registry.find(handle);
    if (found == registry.end()) throw std::logic_error("Transcriber has been closed");
    return found->second;
}

void requireOpen(const TranscriberState &state) {
    if (state.closed) throw std::logic_error("Transcriber has been closed");
}

void requireStream(const TranscriberState &state, jint stream, bool requireActive) {
    requireOpen(state);
    const auto found = state.streams.find(stream);
    if (found == state.streams.end()) throw std::logic_error("Stream has been freed");
    if (requireActive && !found->second) throw std::logic_error("Stream has been stopped");
}

jobjectArray copyTranscript(JNIEnv *env, const transcript_t *transcript) {
    if (transcript == nullptr || (transcript->line_count != 0 && transcript->lines == nullptr)) {
        throw std::runtime_error("Native transcription returned no result");
    }
    if (transcript->line_count > static_cast<uint64_t>(std::numeric_limits<jsize>::max())) {
        throw std::runtime_error("Transcript contains too many lines");
    }
    jclass lineClass = env->FindClass(kLineClass);
    if (lineClass == nullptr) return nullptr;
    const auto constructor = env->GetMethodID(lineClass, "<init>", "(JLjava/lang/String;FFZ)V");
    if (constructor == nullptr) {
        env->DeleteLocalRef(lineClass);
        return nullptr;
    }
    const auto size = static_cast<jsize>(transcript->line_count);
    jobjectArray result = env->NewObjectArray(size, lineClass, nullptr);
    if (result == nullptr) {
        env->DeleteLocalRef(lineClass);
        return nullptr;
    }
    for (jsize i = 0; i < size && !env->ExceptionCheck(); ++i) {
        const auto &line = transcript->lines[i];
        if (!std::isfinite(line.start_time) || !std::isfinite(line.duration) || line.start_time < 0 || line.duration < 0) {
            env->DeleteLocalRef(result);
            env->DeleteLocalRef(lineClass);
            throw std::runtime_error("Native transcription returned invalid timing");
        }
        jstring text = javaString(env, line.text == nullptr ? "" : line.text);
        if (text == nullptr) break;
        jobject entry = env->NewObject(lineClass, constructor, static_cast<jlong>(line.id), text,
            static_cast<jfloat>(line.start_time), static_cast<jfloat>(line.duration),
            static_cast<jboolean>(line.is_complete != 0));
        env->DeleteLocalRef(text);
        if (entry == nullptr) break;
        env->SetObjectArrayElement(result, i, entry);
        env->DeleteLocalRef(entry);
    }
    env->DeleteLocalRef(lineClass);
    if (env->ExceptionCheck()) {
        env->DeleteLocalRef(result);
        return nullptr;
    }
    return result;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_metrolist_music_podcast_transcript_MoonshineNative_create(JNIEnv *env, jobject, jstring directory) {
    try {
        const auto path = modelPath(env, directory);
        const moonshine_option_t options[] = {
            {"return_audio_data", "false"},
            {"identify_speakers", "false"},
            {"word_timestamps", "false"},
            {"vad_max_segment_duration", "15"},
            {"log_output_text", "false"},
            {"ort_provider", "cpu"},
        };
        std::unique_lock<std::shared_mutex> runtimeLock(runtimeHandlesMutex);
        // Moonshine v0.1.5 reads this when creating each ORT session. It does
        // not change system settings or the application's other worker pools.
        if (setenv("MOONSHINE_ORT_SINGLE_THREAD", "1", 1) != 0) {
            throw std::runtime_error("Could not configure recognition threads");
        }
        const int handle = moonshine_load_transcriber_from_files(path.c_str(), MOONSHINE_MODEL_ARCH_SMALL_STREAMING,
            options, sizeof(options) / sizeof(options[0]), MOONSHINE_HEADER_VERSION);
        if (handle < 0) checkStatus(handle, "Load model");
        try {
            auto state = std::make_shared<TranscriberState>(handle);
            std::lock_guard<std::mutex> lock(registryMutex);
            if (!registry.emplace(handle, std::move(state)).second) {
                throw std::runtime_error("Duplicate native transcriber handle");
            }
        } catch (...) {
            moonshine_free_transcriber(handle);
            throw;
        }
        return handle;
    } catch (const std::exception &error) {
        throwJava(env, error.what());
    } catch (...) {
        throwJava(env, "Unknown error while loading model");
    }
    return -1;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_metrolist_music_podcast_transcript_MoonshineNative_createStream(JNIEnv *env, jobject, jint handle) {
    try {
        const auto state = findState(handle);
        std::lock_guard<std::mutex> lock(state->mutex);
        requireOpen(*state);
        std::shared_lock<std::shared_mutex> runtimeLock(runtimeHandlesMutex);
        const int stream = moonshine_create_stream(handle, 0);
        if (stream < 0) checkStatus(stream, "Create stream");
        try {
            checkStatus(moonshine_start_stream(handle, stream), "Start stream");
            state->streams.emplace(stream, true);
        } catch (...) {
            moonshine_free_stream(handle, stream);
            throw;
        }
        return stream;
    } catch (const std::exception &error) {
        throwJava(env, error.what());
    } catch (...) {
        throwJava(env, "Unknown error while creating stream");
    }
    return -1;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_metrolist_music_podcast_transcript_MoonshineNative_acceptAudio(
    JNIEnv *env, jobject, jint handle, jint stream, jfloatArray samples) {
    try {
        if (samples == nullptr) throw std::invalid_argument("Audio samples are required");
        const jsize count = env->GetArrayLength(samples);
        if (count <= 0 || count > kMaximumChunkSamples) throw std::invalid_argument("Invalid audio chunk size");
        const auto state = findState(handle);
        std::lock_guard<std::mutex> lock(state->mutex);
        requireStream(*state, stream, true);
        std::shared_lock<std::shared_mutex> runtimeLock(runtimeHandlesMutex);
        {
            FloatElements data(env, samples);
            if (data.data() == nullptr) return nullptr;
            for (jsize i = 0; i < count; ++i) {
                if (!std::isfinite(data.data()[i])) throw std::invalid_argument("Audio samples must be finite");
            }
            checkStatus(moonshine_transcribe_add_audio_to_stream(handle, stream, data.data(), count, kSampleRate, 0), "Add audio");
        }
        // The C API has copied PCM into its stream; release the JNI array before inference.
        transcript_t *transcript = nullptr;
        checkStatus(moonshine_transcribe_stream(handle, stream, 0, &transcript), "Transcribe audio");
        return copyTranscript(env, transcript);
    } catch (const std::exception &error) {
        throwJava(env, error.what());
    } catch (...) {
        throwJava(env, "Unknown error while transcribing audio");
    }
    return nullptr;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_metrolist_music_podcast_transcript_MoonshineNative_finishStream(JNIEnv *env, jobject, jint handle, jint stream) {
    try {
        const auto state = findState(handle);
        std::lock_guard<std::mutex> lock(state->mutex);
        requireStream(*state, stream, false);
        std::shared_lock<std::shared_mutex> runtimeLock(runtimeHandlesMutex);
        if (state->streams.at(stream)) {
            checkStatus(moonshine_stop_stream(handle, stream), "Stop stream");
            state->streams.at(stream) = false;
        }
        transcript_t *transcript = nullptr;
        checkStatus(moonshine_transcribe_stream(handle, stream, MOONSHINE_FLAG_FORCE_UPDATE, &transcript), "Finish transcription");
        return copyTranscript(env, transcript);
    } catch (const std::exception &error) {
        throwJava(env, error.what());
    } catch (...) {
        throwJava(env, "Unknown error while finishing transcription");
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_metrolist_music_podcast_transcript_MoonshineNative_freeStream(JNIEnv *env, jobject, jint handle, jint stream) {
    try {
        std::shared_ptr<TranscriberState> state;
        {
            std::lock_guard<std::mutex> lock(registryMutex);
            const auto found = registry.find(handle);
            if (found == registry.end()) return;
            state = found->second;
        }
        std::lock_guard<std::mutex> lock(state->mutex);
        if (state->closed || state->streams.find(stream) == state->streams.end()) return;
        std::shared_lock<std::shared_mutex> runtimeLock(runtimeHandlesMutex);
        checkStatus(moonshine_free_stream(handle, stream), "Release stream");
        state->streams.erase(stream);
    } catch (const std::exception &error) {
        throwJava(env, error.what());
    } catch (...) {
        throwJava(env, "Unknown error while releasing stream");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_metrolist_music_podcast_transcript_MoonshineNative_close(JNIEnv *env, jobject, jint handle) {
    try {
        std::shared_ptr<TranscriberState> state;
        {
            std::lock_guard<std::mutex> lock(registryMutex);
            const auto found = registry.find(handle);
            if (found == registry.end()) return;
            state = found->second;
            registry.erase(found);
        }
        std::lock_guard<std::mutex> lock(state->mutex);
        std::unique_lock<std::shared_mutex> runtimeLock(runtimeHandlesMutex);
        state->closed = true;
        state->streams.clear();
        // The upstream destructor owns and frees any remaining streams.
        moonshine_free_transcriber(handle);
    } catch (const std::exception &error) {
        throwJava(env, error.what());
    } catch (...) {
        throwJava(env, "Unknown error while releasing transcriber");
    }
}
