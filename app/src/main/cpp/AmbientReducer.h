#pragma once

#include <cstdint>

// Low-latency ambient sound reduction.
//
// Two stages, both sample-by-sample with no look-ahead, so they add zero latency:
//   1. A 2nd-order Butterworth high-pass removes low-frequency rumble
//      (traffic, HVAC, wind, handling noise).
//   2. An adaptive downward expander tracks the background-noise floor and
//      attenuates anything that sits near it, while letting louder foreground
//      sound (speech, music) through untouched.
//
// It reduces steady background noise in the gaps between foreground sound; it
// cannot remove noise that overlaps the foreground in time and frequency.
class AmbientReducer {
public:
    void prepare(float sampleRate);
    void reset();

    // 0 = gentle, 1 = aggressive. Audio thread only.
    void setStrength(float strength);

    float process(float x);

    // Current attenuation applied by the expander, in dB (<= 0).
    float currentGainDb() const;

private:
    float mSampleRate = 48000.0f;

    // High-pass biquad (transposed direct form II).
    float mB0 = 1, mB1 = 0, mB2 = 0, mA1 = 0, mA2 = 0;
    float mZ1 = 0, mZ2 = 0;
    float mHighPassHz = 0;

    // Envelope follower and noise-floor tracker.
    float mEnvelope = 0;
    float mNoiseFloor = 1e-3f;
    float mEnvAttack = 0, mEnvRelease = 0;
    float mFloorFall = 0, mFloorRise = 0;

    // Expander gain and its smoothing.
    float mGain = 1;
    float mGainOpen = 0, mGainClose = 0;
    float mRatioMinusOne = 1;
    float mMinGain = 0.1f;

    float mStrength = -1;

    void designHighPass(float cutoffHz);
    float coefficientFor(float seconds) const;
};
