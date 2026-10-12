#include "LiveEngine.h"

#include <android/log.h>

#include <algorithm>
#include <cmath>

#define LOG_TAG "LiveEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {
// Soft limiter: transparent below 0.5, then bends smoothly toward 0.95 so a
// loud sound or high volume setting can never produce a full-scale blast.
inline float softLimit(float x) {
    constexpr float knee = 0.5f;
    constexpr float range = 0.45f;
    const float a = std::fabs(x);
    if (a <= knee) return x;
    const float limited = knee + range * std::tanh((a - knee) / range);
    return std::copysign(limited, x);
}

const oboe::InputPreset kPresetsToTry[] = {
        oboe::InputPreset::VoicePerformance,
        oboe::InputPreset::Unprocessed,
        oboe::InputPreset::VoiceRecognition,
};
}  // namespace

oboe::Result LiveEngine::startEngine() {
    std::lock_guard<std::mutex> lock(mLock);
    if (mRunning) return oboe::Result::OK;
    mDisconnected = false;
    closeStreams();  // leftovers from a stream that died with an error

    oboe::Result result = openStreams();
    if (result != oboe::Result::OK) {
        closeStreams();
        return result;
    }

    mReducer.prepare(static_cast<float>(mOutput->getSampleRate()));
    mSmoothing = 1.0f - std::exp(-1.0f / (0.020f * mOutput->getSampleRate()));  // 20 ms
    mGain = 0;  // fade in from silence
    mWetMix = mNoiseReductionEnabled ? 1.0f : 0.0f;
    mInputUnderflows = 0;

    result = FullDuplexStream::start();
    if (result != oboe::Result::OK) {
        LOGW("start failed: %s", oboe::convertToText(result));
        closeStreams();
        return result;
    }
    mRunning = true;
    return oboe::Result::OK;
}

void LiveEngine::stopEngine() {
    std::lock_guard<std::mutex> lock(mLock);
    if (mInput || mOutput) {
        FullDuplexStream::stop();
    }
    closeStreams();
    mRunning = false;
}

oboe::Result LiveEngine::openStreams() {
    // Output first: its native sample rate and burst size set the clock for both.
    oboe::AudioStreamBuilder outBuilder;
    outBuilder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setFormatConversionAllowed(true)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setChannelConversionAllowed(true)
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setDataCallback(this)
            ->setErrorCallback(this);
    oboe::Result result = outBuilder.openStream(mOutput);
    if (result != oboe::Result::OK) {
        LOGW("open output failed: %s", oboe::convertToText(result));
        return result;
    }

    oboe::AudioStreamBuilder inBuilder;
    inBuilder.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setFormatConversionAllowed(true)
            ->setChannelCount(oboe::ChannelCount::Mono)
            ->setChannelConversionAllowed(true)
            ->setSampleRate(mOutput->getSampleRate())
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Fastest)
            // Room for the output buffer to grow without the input overflowing.
            ->setBufferCapacityInFrames(mOutput->getBufferCapacityInFrames() * 2)
            ->setErrorCallback(this);
    for (oboe::InputPreset preset : kPresetsToTry) {
        inBuilder.setInputPreset(preset);
        result = inBuilder.openStream(mInput);
        if (result == oboe::Result::OK) {
            mInputPreset = preset;
            break;
        }
        LOGW("open input with preset %d failed: %s", static_cast<int>(preset),
             oboe::convertToText(result));
    }
    if (result != oboe::Result::OK) return result;

    mInputChannels = mInput->getChannelCount();
    mOutputChannels = mOutput->getChannelCount();

    setSharedInputStream(mInput);
    setSharedOutputStream(mOutput);
    // One burst of input cushion: costs ~1-2 ms but prevents the input from
    // running dry when the two streams' callbacks drift slightly.
    setNumInputBurstsCushion(1);

    mOutput->setBufferSizeInFrames(mOutput->getFramesPerBurst());
    mTuner = std::make_unique<oboe::LatencyTuner>(*mOutput);

    LOGI("opened: %d Hz, burst %d, in mmap=%d out mmap=%d preset=%d", mOutput->getSampleRate(),
         mOutput->getFramesPerBurst(), oboe::OboeExtensions::isMMapUsed(mInput.get()),
         oboe::OboeExtensions::isMMapUsed(mOutput.get()), static_cast<int>(mInputPreset));
    return oboe::Result::OK;
}

void LiveEngine::closeStreams() {
    mTuner.reset();
    if (mOutput) {
        mOutput->close();
        mOutput.reset();
    }
    if (mInput) {
        mInput->close();
        mInput.reset();
    }
}

oboe::DataCallbackResult LiveEngine::onAudioReady(oboe::AudioStream *stream, void *audioData,
                                                  int32_t numFrames) {
    oboe::DataCallbackResult result = FullDuplexStream::onAudioReady(stream, audioData, numFrames);
    if (mTuner) mTuner->tune();
    return result;
}

oboe::DataCallbackResult LiveEngine::onBothStreamsReady(const void *inputData, int numInputFrames,
                                                        void *outputData, int numOutputFrames) {
    const auto *in = static_cast<const float *>(inputData);
    auto *out = static_cast<float *>(outputData);

    const float targetGain = mTargetGain.load(std::memory_order_relaxed);
    const float targetWet = mNoiseReductionEnabled.load(std::memory_order_relaxed) ? 1.0f : 0.0f;
    mReducer.setStrength(mNoiseReductionStrength.load(std::memory_order_relaxed));

    if (numInputFrames < numOutputFrames) {
        mInputUnderflows.fetch_add(1, std::memory_order_relaxed);
    }

    float peak = 0;
    for (int i = 0; i < numOutputFrames; ++i) {
        const float dry = i < numInputFrames ? in[i * mInputChannels] : 0.0f;
        peak = std::max(peak, std::fabs(dry));

        // The reducer always runs so its noise-floor estimate stays current and
        // toggling it on is instant; the dry/wet crossfade avoids clicks.
        const float wet = mReducer.process(dry);
        mWetMix += mSmoothing * (targetWet - mWetMix);
        mGain += mSmoothing * (targetGain - mGain);

        const float sample = softLimit((dry + mWetMix * (wet - dry)) * mGain);
        for (int c = 0; c < mOutputChannels; ++c) {
            out[i * mOutputChannels + c] = sample;
        }
    }

    if (peak > mInputPeak.load(std::memory_order_relaxed)) {
        mInputPeak.store(peak, std::memory_order_relaxed);
    }
    mReductionDb.store(mWetMix * mReducer.currentGainDb(), std::memory_order_relaxed);
    return oboe::DataCallbackResult::Continue;
}

void LiveEngine::onErrorAfterClose(oboe::AudioStream * /*stream*/, oboe::Result error) {
    // Typically a headphone unplug or route change. The UI thread polls
    // consumeDisconnected() and decides whether to restart or stop.
    LOGW("stream closed after error: %s", oboe::convertToText(error));
    mRunning = false;
    mDisconnected = true;
}

LiveEngine::Stats LiveEngine::stats() {
    std::lock_guard<std::mutex> lock(mLock);
    Stats s;
    const float peak = mInputPeak.exchange(0, std::memory_order_relaxed);
    s.inputLevelDb = 20.0f * std::log10(std::max(peak, 1e-5f));
    s.reductionDb = mReductionDb.load(std::memory_order_relaxed);
    s.inputUnderflows = static_cast<float>(mInputUnderflows.load(std::memory_order_relaxed));
    if (!mRunning || !mOutput || !mInput) return s;

    s.sampleRate = static_cast<float>(mOutput->getSampleRate());
    s.framesPerBurst = static_cast<float>(mOutput->getFramesPerBurst());
    s.outputBufferFrames = static_cast<float>(mOutput->getBufferSizeInFrames());
    auto xruns = mOutput->getXRunCount();
    s.outputXRuns = xruns ? static_cast<float>(xruns.value()) : 0;
    auto outLatency = mOutput->calculateLatencyMillis();
    auto inLatency = mInput->calculateLatencyMillis();
    if (outLatency && inLatency) {
        s.latencyMs = static_cast<float>(outLatency.value() + inLatency.value());
    }
    s.mmapInput = oboe::OboeExtensions::isMMapUsed(mInput.get()) ? 1 : 0;
    s.mmapOutput = oboe::OboeExtensions::isMMapUsed(mOutput.get()) ? 1 : 0;
    s.inputPreset = static_cast<float>(mInputPreset);
    return s;
}
