#include <jni.h>

#include "LiveEngine.h"

namespace {
LiveEngine gEngine;
}

extern "C" {

JNIEXPORT jint JNICALL
Java_com_example_avifone_AudioEngine_nativeStart(JNIEnv *, jobject) {
    return static_cast<jint>(gEngine.startEngine());
}

JNIEXPORT void JNICALL
Java_com_example_avifone_AudioEngine_nativeStop(JNIEnv *, jobject) {
    gEngine.stopEngine();
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
                             s.mmapOutput, s.inputPreset};
    constexpr jsize count = sizeof(values) / sizeof(values[0]);
    jfloatArray array = env->NewFloatArray(count);
    env->SetFloatArrayRegion(array, 0, count, values);
    return array;
}

}  // extern "C"
