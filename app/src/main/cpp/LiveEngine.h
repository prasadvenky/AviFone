#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <memory>
#include <mutex>

#include "AmbientReducer.h"

// Full-duplex microphone -> headphone pass-through.
//
// Latency/jitter choices:
//   - Oboe LowLatency + Exclusive streams (MMAP path when the device supports it).
//   - Output callback drives everything; input is read non-blocking inside it, so
//     there is a single clock and no extra FIFO between capture and playback.
//   - VoicePerformance input preset (designed for live monitoring), falling back
//     to Unprocessed / default if the device rejects it.
//   - LatencyTuner starts at the smallest output buffer and grows it only when
//     an underrun actually happens.
//   - Volume and noise-reduction changes are smoothed per-sample, so moving a
//     slider never clicks.
class LiveEngine : public oboe::FullDuplexStream, public oboe::AudioStreamErrorCallback {
public:
    struct Stats {
        float sampleRate = 0;
        float framesPerBurst = 0;
        float outputBufferFrames = 0;
        float latencyMs = -1;
        float outputXRuns = 0;
        float inputUnderflows = 0;
        float inputLevelDb = -100;
        float reductionDb = 0;
        float mmapInput = 0;
        float mmapOutput = 0;
        float inputPreset = 0;
    };

    oboe::Result startEngine();
    void stopEngine();
    bool isRunning() const { return mRunning; }

    void setVolume(float linearGain) { mTargetGain = linearGain; }
    void setNoiseReduction(bool enabled, float strength) {
        mNoiseReductionEnabled = enabled;
        mNoiseReductionStrength = strength;
    }

    Stats stats();
    // Returns true once after the audio device disappeared or errored.
    bool consumeDisconnected() { return mDisconnected.exchange(false); }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData,
                                          int32_t numFrames) override;
    oboe::DataCallbackResult onBothStreamsReady(const void *inputData, int numInputFrames,
                                                void *outputData, int numOutputFrames) override;
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    std::mutex mLock;
    std::shared_ptr<oboe::AudioStream> mInput;
    std::shared_ptr<oboe::AudioStream> mOutput;
    std::unique_ptr<oboe::LatencyTuner> mTuner;
    oboe::InputPreset mInputPreset = oboe::InputPreset::VoicePerformance;
    std::atomic<bool> mRunning{false};
    std::atomic<bool> mDisconnected{false};

    // Parameters written by the UI thread.
    std::atomic<float> mTargetGain{0.0f};
    std::atomic<bool> mNoiseReductionEnabled{false};
    std::atomic<float> mNoiseReductionStrength{0.5f};

    // Audio-thread state.
    AmbientReducer mReducer;
    float mGain = 0;
    float mWetMix = 0;
    float mSmoothing = 0;
    int32_t mInputChannels = 1;
    int32_t mOutputChannels = 2;

    // Published by the audio thread for the UI.
    std::atomic<float> mInputPeak{0};
    std::atomic<float> mReductionDb{0};
    std::atomic<int32_t> mInputUnderflows{0};

    oboe::Result openStreams();
    void closeStreams();
};
