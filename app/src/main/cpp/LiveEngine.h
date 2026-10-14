#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <memory>
#include <mutex>

#include "AmbientReducer.h"

// Owns the microphone. Two independent features share one input stream:
//
//   listen  - full-duplex microphone -> headphone pass-through.
//   capture - raw mic samples pushed into a lock-free FIFO that the Kotlin
//             transcription pipeline drains (see nativeReadCapture).
//
// Stream configuration follows from the two flags:
//   listen            -> Duplex: FullDuplexStream, capture tapped from its callback
//   capture only      -> InputOnly: a single input stream with its own callback
//   neither           -> Off
//
// Latency/jitter choices for the Duplex path:
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
        float captureDropped = 0;
    };

    // Opens/closes/reconfigures streams so that exactly the requested features run.
    // Reopens only when the stream layout changes or a stream died.
    oboe::Result setModes(bool listen, bool capture);
    bool isRunning() const { return mRunning; }

    void setVolume(float linearGain) { mTargetGain = linearGain; }
    void setNoiseReduction(bool enabled, float strength) {
        mNoiseReductionEnabled = enabled;
        mNoiseReductionStrength = strength;
    }

    // Copies up to maxFrames captured mono samples into dest. Never blocks.
    int32_t readCapture(float *dest, int32_t maxFrames);
    int32_t captureSampleRate() const { return mCaptureSampleRate; }

    Stats stats();
    // Returns true once after the audio device disappeared or errored.
    bool consumeDisconnected() { return mDisconnected.exchange(false); }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData,
                                          int32_t numFrames) override;
    oboe::DataCallbackResult onBothStreamsReady(const void *inputData, int numInputFrames,
                                                void *outputData, int numOutputFrames) override;
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    enum class Mode { Off, Duplex, InputOnly };

    // Data callback for the InputOnly stream (the engine itself is the Duplex
    // output callback, so this needs to be a separate object).
    class CaptureCallback : public oboe::AudioStreamDataCallback {
    public:
        explicit CaptureCallback(LiveEngine &engine) : mEngine(engine) {}
        oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData,
                                              int32_t numFrames) override;
    private:
        LiveEngine &mEngine;
    };

    std::mutex mLock;
    std::shared_ptr<oboe::AudioStream> mInput;
    std::shared_ptr<oboe::AudioStream> mOutput;
    std::unique_ptr<oboe::LatencyTuner> mTuner;
    CaptureCallback mCaptureCallback{*this};
    oboe::InputPreset mInputPreset = oboe::InputPreset::VoicePerformance;
    Mode mMode = Mode::Off;
    std::atomic<bool> mRunning{false};
    std::atomic<bool> mDisconnected{false};

    // Parameters written by the UI thread.
    std::atomic<float> mTargetGain{0.0f};
    std::atomic<bool> mNoiseReductionEnabled{false};
    std::atomic<float> mNoiseReductionStrength{0.5f};

    // Capture FIFO: written by the audio thread, read by the Kotlin poller.
    // Allocated once (before any stream that writes to it starts) and never freed,
    // so the reader needs no lock.
    std::atomic<oboe::FifoBuffer *> mCaptureFifo{nullptr};
    std::atomic<bool> mCaptureEnabled{false};
    std::atomic<int32_t> mCaptureSampleRate{0};
    std::atomic<int32_t> mCaptureDropped{0};

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

    oboe::Result startDuplex();
    oboe::Result startInputOnly();
    oboe::Result openDuplexStreams();
    void stopAll();
    void closeStreams();
    void ensureCaptureFifo();
    void writeCapture(const float *samples, int32_t numFrames);
};
