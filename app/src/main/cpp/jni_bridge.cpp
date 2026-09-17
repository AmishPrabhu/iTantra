#include <jni.h>
#include <string>
#include <memory>
#include "oboe_audio_engine.hpp"
#include "onnx_mmap_loader.hpp"

static std::unique_ptr<itantra::OboeAudioEngine> gAudioEngine;
static std::unique_ptr<itantra::MmapModelLoader> gModelLoader;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_itantra_audio_NativeAudioBridge_nativeInit(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (!gAudioEngine) {
        gAudioEngine = std::make_unique<itantra::OboeAudioEngine>();
    }
    if (!gModelLoader) {
        gModelLoader = std::make_unique<itantra::MmapModelLoader>();
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_itantra_audio_NativeAudioBridge_nativeStartRecording(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (!gAudioEngine) return JNI_FALSE;
    return gAudioEngine->startRecording() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_itantra_audio_NativeAudioBridge_nativeStopRecording(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (gAudioEngine) {
        gAudioEngine->stopRecording();
    }
}

JNIEXPORT jboolean JNICALL
Java_com_itantra_audio_NativeAudioBridge_nativeStartPlayback(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (!gAudioEngine) return JNI_FALSE;
    return gAudioEngine->startPlayback() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_itantra_audio_NativeAudioBridge_nativeStopPlayback(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (gAudioEngine) {
        gAudioEngine->stopPlayback();
    }
}

JNIEXPORT void JNICALL
Java_com_itantra_audio_NativeAudioBridge_nativeWritePlaybackAudio(
    JNIEnv* env, jobject /*thiz*/, jshortArray audioSamples, jint count) {
    if (!gAudioEngine || !audioSamples || count <= 0) return;

    jshort* samples = env->GetShortArrayElements(audioSamples, nullptr);
    if (samples) {
        gAudioEngine->writePlaybackAudio(reinterpret_cast<const int16_t*>(samples), static_cast<size_t>(count));
        env->ReleaseShortArrayElements(audioSamples, samples, JNI_ABORT);
    }
}

JNIEXPORT jint JNICALL
Java_com_itantra_audio_NativeAudioBridge_nativeReadPreRollBuffer(
    JNIEnv* env, jobject /*thiz*/, jshortArray outBuffer, jint maxSamples) {
    if (!gAudioEngine || !outBuffer || maxSamples <= 0) return 0;

    jshort* buffer = env->GetShortArrayElements(outBuffer, nullptr);
    size_t samplesRead = 0;
    if (buffer) {
        samplesRead = gAudioEngine->getPreRollBuffer().readLatest(
            reinterpret_cast<int16_t*>(buffer), static_cast<size_t>(maxSamples)
        );
        env->ReleaseShortArrayElements(outBuffer, buffer, 0); // Copy back to Java
    }
    return static_cast<jint>(samplesRead);
}

JNIEXPORT jboolean JNICALL
Java_com_itantra_audio_NativeAudioBridge_nativeIsAudioActive(
    JNIEnv* env, jobject /*thiz*/, jshortArray samples, jint count) {
    if (!gAudioEngine || !samples || count <= 0) return JNI_FALSE;

    jshort* data = env->GetShortArrayElements(samples, nullptr);
    bool active = false;
    if (data) {
        active = gAudioEngine->getRmsGate().isAudioActive(
            reinterpret_cast<const int16_t*>(data), static_cast<size_t>(count)
        );
        env->ReleaseShortArrayElements(samples, data, JNI_ABORT);
    }
    return active ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_itantra_audio_NativeAudioBridge_nativeLoadMmapModel(
    JNIEnv* env, jobject /*thiz*/, jstring filePath) {
    if (!gModelLoader || !filePath) return JNI_FALSE;

    const char* pathChars = env->GetStringUTFChars(filePath, nullptr);
    std::string path(pathChars);
    env->ReleaseStringUTFChars(filePath, pathChars);

    return gModelLoader->loadModelFile(path) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_itantra_audio_NativeAudioBridge_nativeReleaseMmapModel(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    if (gModelLoader) {
        gModelLoader->release();
    }
}

} // extern "C"
