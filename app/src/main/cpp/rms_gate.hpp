#pragma once

#include <cstdint>
#include <cstddef>

namespace itantra {

/**
 * Pure Mathematical RMS (Root Mean Square) Energy Gate.
 * Consumes <0.001% CPU. Filters out pure silence so heavy neural network
 * VAD (Silero) only awakens when audio energy exceeds the ambient noise threshold.
 */
class RmsEnergyGate {
public:
    explicit RmsEnergyGate(float thresholdDb = -45.0f, float noiseAdaptAlpha = 0.05f);

    // Calculates RMS level in dBFS for a frame of 16-bit PCM samples
    float calculateRmsDb(const int16_t* samples, size_t count);

    // Returns true if audio energy exceeds dynamic threshold (i.e. potential speech present)
    bool isAudioActive(const int16_t* samples, size_t count);

    void setThresholdDb(float db) { thresholdDb_ = db; }
    float getThresholdDb() const { return thresholdDb_; }
    float getNoiseFloorDb() const { return noiseFloorDb_; }

private:
    float thresholdDb_;
    float noiseAdaptAlpha_;
    float noiseFloorDb_;
};

} // namespace itantra
