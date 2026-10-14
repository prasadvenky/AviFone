#include <jni.h>

#include "LiveEngine.h"

namespace {
LiveEngine gEngine;
}

extern "C" {

JNIEXPORT jint JNICALL
Java_com_example_avifone_AudioEngine_nativeSetModes(JNIEnv *, jobject, jboolean listen,
                                                    jboolean capture) {
    return static_cast<jint>(gEngine.setModes(listen, capture));
}

JNIEXPORT jint JNICALL
Java_com_example_avifone_AudioEngine_nativeReadCapture(JNIEnv *env, jobject, jfloatArray dest) {
    const jsize capacity = env->GetArrayLength(dest);
    jfloat *buffer = env->GetFloatArrayElements(dest, nullptr);
    const int32_t read = gEngine.readCapture(buffer, capacity);
    env->ReleaseFloatArrayElements(dest, buffer, 0);
    return read;
}

JNIEXPORT jint JNICALL
Java_com_example_avifone_AudioEngine_nativeCaptureSampleRate(JNIEnv *, jobject) {
    return gEngine.captureSampleRate();
}

JNIEXPORT jboolean JNICALL
Java_com_example_avifone_AudioEngine_nativeIsRunning(JNIEnv *, jobject) {
    return gEngine.isRunning();
}

JNIEXPORT void JNICALL
Java_com_example_avifone_AudioEngine_nativeSetVolume(JNIEnv *, jobject, jfloat gain) {
    gEngine.setVolume(gain);
}

JNIEXPORT void JNICALL
Java_com_example_avifone_AudioEngine_nativeSetNoiseReduction(JNIEnv *, jobject,
                                                               jboolean enabled,
                                                               jfloat strength) {
    gEngine.setNoiseReduction(enabled, strength);
}

JNIEXPORT jboolean JNICALL
Java_com_example_avifone_AudioEngine_nativeConsumeDisconnected(JNIEnv *, jobject) {
    return gEngine.consumeDisconnected();
}

// Order must match AudioEngine.Stats.fromArray().
JNIEXPORT jfloatArray JNICALL
Java_com_example_avifone_AudioEngine_nativeGetStats(JNIEnv *env, jobject) {
    const LiveEngine::Stats s = gEngine.stats();
    const jfloat values[] = {s.sampleRate, s.framesPerBurst, s.outputBufferFrames,
                             s.latencyMs,  s.outputXRuns,    s.inputUnderflows,
                             s.inputLevelDb, s.reductionDb,  s.mmapInput,
                             s.mmapOutput, s.inputPreset,   s.captureDropped};
    constexpr jsize count = sizeof(values) / sizeof(values[0]);
    jfloatArray array = env->NewFloatArray(count);
    env->SetFloatArrayRegion(array, 0, count, values);
    return array;
}

}  // extern "C"
