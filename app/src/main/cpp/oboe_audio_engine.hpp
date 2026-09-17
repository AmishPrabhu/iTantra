#pragma once

#include <oboe/Oboe.h>
#include <memory>
#include <functional>
#include "ring_buffer.hpp"
#include "rms_gate.hpp"

namespace itantra {

class OboeAudioEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    OboeAudioEngine();
    ~OboeAudioEngine();

    bool startRecording(std::function<void(const int16_t*, size_t)> onFrameCallback = nullptr);
    void stopRecording();

    bool startPlayback();
    void stopPlayback();
    void writePlaybackAudio(const int16_t* audioData, size_t numSamples);

    // AudioStreamDataCallback for Recording
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream *audioStream,
        void *audioData,
        int32_t numFrames) override;

    // AudioStreamErrorCallback
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result result) override;

    PreRollRingBuffer& getPreRollBuffer() { return ringBuffer_; }
    RmsEnergyGate& getRmsGate() { return rmsGate_; }

    bool isRecording() const { return isRecording_; }
    bool isPlaying() const { return isPlaying_; }

private:
    std::shared_ptr<oboe::AudioStream> recordingStream_;
    std::shared_ptr<oboe::AudioStream> playbackStream_;

    PreRollRingBuffer ringBuffer_;
    RmsEnergyGate rmsGate_;

    std::function<void(const int16_t*, size_t)> onFrameCallback_;

    bool isRecording_{false};
    bool isPlaying_{false};

    static constexpr int32_t kSampleRate = 16000;
    static constexpr int32_t kChannelCount = 1; // Mono
};

} // namespace itantra
