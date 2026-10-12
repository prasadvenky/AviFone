#include "AmbientReducer.h"

#include <algorithm>
#include <cmath>

namespace {
constexpr float kPi = 3.14159265358979f;
// Sound must rise this far above the tracked noise floor before the expander
// stops attenuating it (+10 dB).
constexpr float kThresholdAboveFloor = 3.16f;
constexpr float kMinFloor = 1e-5f;  // -100 dBFS

float dbToLinear(float db) { return std::pow(10.0f, db / 20.0f); }
}  // namespace

float AmbientReducer::coefficientFor(float seconds) const {
    return 1.0f - std::exp(-1.0f / (seconds * mSampleRate));
}

void AmbientReducer::prepare(float sampleRate) {
    mSampleRate = sampleRate;
    mEnvAttack = coefficientFor(0.001f);
    mEnvRelease = coefficientFor(0.060f);
    // The floor drops quickly to follow quiet moments, but rises slowly so that
    // sustained speech is not mistaken for background noise.
    mFloorFall = coefficientFor(0.150f);
    mFloorRise = coefficientFor(4.0f);
    mGainOpen = coefficientFor(0.002f);
    mGainClose = coefficientFor(0.080f);
    mStrength = -1;  // force coefficient refresh
    setStrength(0.5f);
    reset();
}

void AmbientReducer::reset() {
    mZ1 = mZ2 = 0;
    mEnvelope = 0;
    mNoiseFloor = 1e-3f;
    mGain = 1;
}

void AmbientReducer::setStrength(float strength) {
    strength = std::clamp(strength, 0.0f, 1.0f);
    if (strength == mStrength) return;
    mStrength = strength;
    // Expansion ratio 1:2 .. 1:5, max attenuation -9 dB .. -36 dB,
    // high-pass corner 80 Hz .. 200 Hz.
    mRatioMinusOne = 1.0f + 3.0f * strength;
    mMinGain = dbToLinear(-9.0f - 27.0f * strength);
    designHighPass(80.0f + 120.0f * strength);
}

void AmbientReducer::designHighPass(float cutoffHz) {
    if (cutoffHz == mHighPassHz) return;
    mHighPassHz = cutoffHz;
    // RBJ cookbook high-pass, Q = 1/sqrt(2) (Butterworth).
    const float w0 = 2.0f * kPi * cutoffHz / mSampleRate;
    const float cosW = std::cos(w0);
    const float alpha = std::sin(w0) / (2.0f * 0.70710678f);
    const float a0 = 1.0f + alpha;
    mB0 = (1.0f + cosW) / 2.0f / a0;
    mB1 = -(1.0f + cosW) / a0;
    mB2 = mB0;
    mA1 = -2.0f * cosW / a0;
    mA2 = (1.0f - alpha) / a0;
}

float AmbientReducer::process(float x) {
    const float y = mB0 * x + mZ1;
    mZ1 = mB1 * x - mA1 * y + mZ2;
    mZ2 = mB2 * x - mA2 * y;

    const float level = std::fabs(y);
    mEnvelope += (level > mEnvelope ? mEnvAttack : mEnvRelease) * (level - mEnvelope);
    mNoiseFloor += (mEnvelope < mNoiseFloor ? mFloorFall : mFloorRise) * (mEnvelope - mNoiseFloor);
    mNoiseFloor = std::max(mNoiseFloor, kMinFloor);

    const float threshold = mNoiseFloor * kThresholdAboveFloor;
    float target = 1.0f;
    if (mEnvelope < threshold) {
        target = std::max(std::pow(mEnvelope / threshold + 1e-9f, mRatioMinusOne), mMinGain);
    }
    mGain += (target > mGain ? mGainOpen : mGainClose) * (target - mGain);
    return y * mGain;
}

float AmbientReducer::currentGainDb() const {
    return 20.0f * std::log10(std::max(mGain, 1e-6f));
}
