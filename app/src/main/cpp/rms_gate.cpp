#include "rms_gate.hpp"
#include <cmath>
#include <algorithm>

namespace itantra {

RmsEnergyGate::RmsEnergyGate(float thresholdDb, float noiseAdaptAlpha)
    : thresholdDb_(thresholdDb),
      noiseAdaptAlpha_(noiseAdaptAlpha),
      noiseFloorDb_(-60.0f) {}

float RmsEnergyGate::calculateRmsDb(const int16_t* samples, size_t count) {
    if (!samples || count == 0) return -100.0f;

    double sumSquares = 0.0;
    for (size_t i = 0; i < count; ++i) {
        double normalized = static_cast<double>(samples[i]) / 32768.0;
        sumSquares += normalized * normalized;
    }

    double meanSquare = sumSquares / static_cast<double>(count);
    double rms = std::sqrt(meanSquare);

    if (rms < 1e-6) {
        return -120.0f;
    }

    float rmsDb = static_cast<float>(20.0 * std::log10(rms));
    return rmsDb;
}

bool RmsEnergyGate::isAudioActive(const int16_t* samples, size_t count) {
    float currentRmsDb = calculateRmsDb(samples, count);

    // If signal is below threshold, adaptively track noise floor
    if (currentRmsDb < thresholdDb_) {
        noiseFloorDb_ = (1.0f - noiseAdaptAlpha_) * noiseFloorDb_ + noiseAdaptAlpha_ * currentRmsDb;
        return false;
    }

    // Dynamic check: must exceed both static threshold and ambient noise floor + 6dB
    return (currentRmsDb > thresholdDb_) && (currentRmsDb > (noiseFloorDb_ + 6.0f));
}

} // namespace itantra
