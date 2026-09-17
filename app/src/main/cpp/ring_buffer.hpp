#pragma once

#include <vector>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <algorithm>

namespace itantra {

/**
 * Lock-Free Single-Producer Single-Consumer (SPSC) Circular Pre-Roll Ring Buffer.
 * Continuously records the last N milliseconds of 16kHz 16-bit PCM audio.
 * When speech is detected by VAD, the buffered pre-roll audio is prepended
 * to the speech segment so the first consonant/syllable is never truncated.
 */
class PreRollRingBuffer {
public:
    explicit PreRollRingBuffer(size_t capacitySamples = 4800) // Default 300ms @ 16kHz = 4800 samples
        : capacity_(capacitySamples),
          buffer_(capacitySamples, 0),
          writeIndex_(0),
          availableSamples_(0) {}

    // Push new audio samples from mic stream (called from Oboe Audio Callback thread)
    void write(const int16_t* data, size_t count) {
        if (!data || count == 0) return;

        size_t currentWrite = writeIndex_.load(std::memory_order_relaxed);
        for (size_t i = 0; i < count; ++i) {
            buffer_[currentWrite] = data[i];
            currentWrite = (currentWrite + 1) % capacity_;
        }
        writeIndex_.store(currentWrite, std::memory_order_release);

        size_t avail = availableSamples_.load(std::memory_order_relaxed);
        size_t newAvail = std::min(capacity_, avail + count);
        availableSamples_.store(newAvail, std::memory_order_release);
    }

    // Read the most recent N samples into output buffer (called from Processing thread)
    size_t readLatest(int16_t* outData, size_t maxSamples) {
        if (!outData || maxSamples == 0) return 0;

        size_t avail = availableSamples_.load(std::memory_order_acquire);
        size_t samplesToRead = std::min(avail, maxSamples);
        if (samplesToRead == 0) return 0;

        size_t currentWrite = writeIndex_.load(std::memory_order_acquire);
        
        // Calculate the starting position (going backwards by samplesToRead)
        size_t startIdx = (currentWrite + capacity_ - samplesToRead) % capacity_;

        for (size_t i = 0; i < samplesToRead; ++i) {
            outData[i] = buffer_[startIdx];
            startIdx = (startIdx + 1) % capacity_;
        }

        return samplesToRead;
    }

    void reset() {
        writeIndex_.store(0, std::memory_order_release);
        availableSamples_.store(0, std::memory_order_release);
        std::fill(buffer_.begin(), buffer_.end(), 0);
    }

    size_t getCapacity() const { return capacity_; }
    size_t getAvailable() const { return availableSamples_.load(std::memory_order_relaxed); }

private:
    const size_t capacity_;
    std::vector<int16_t> buffer_;
    std::atomic<size_t> writeIndex_;
    std::atomic<size_t> availableSamples_;
};

} // namespace itantra
