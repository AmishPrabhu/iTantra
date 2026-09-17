#include "oboe_audio_engine.hpp"
#include <android/log.h>

#define TAG "iTantraAudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace itantra {

OboeAudioEngine::OboeAudioEngine()
    : ringBuffer_(4800), // 300ms @ 16kHz
      rmsGate_(-42.0f, 0.05f) {
    LOGI("OboeAudioEngine initialized with 300ms Pre-Roll Ring Buffer");
}

OboeAudioEngine::~OboeAudioEngine() {
    stopRecording();
    stopPlayback();
}

bool OboeAudioEngine::startRecording(std::function<void(const int16_t*, size_t)> onFrameCallback) {
    if (isRecording_) return true;

    onFrameCallback_ = onFrameCallback;
    ringBuffer_.reset();

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::I16)
        ->setChannelCount(kChannelCount)
        ->setSampleRate(kSampleRate)
        ->setInputPreset(oboe::InputPreset::VoiceRecognition)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    oboe::Result result = builder.openStream(recordingStream_);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open recording stream: %s", oboe::convertToText(result));
        return false;
    }

    result = recordingStream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start recording stream: %s", oboe::convertToText(result));
        recordingStream_->close();
        recordingStream_.reset();
        return false;
    }

    isRecording_ = true;
    LOGI("Oboe Recording Stream started at 16kHz 16-bit Mono");
    return true;
}

void OboeAudioEngine::stopRecording() {
    if (!isRecording_) return;

    if (recordingStream_) {
        recordingStream_->stop();
        recordingStream_->close();
        recordingStream_.reset();
    }

    isRecording_ = false;
    LOGI("Oboe Recording Stream stopped");
}

bool OboeAudioEngine::startPlayback() {
    if (isPlaying_) return true;

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setFormat(oboe::AudioFormat::I16)
        ->setChannelCount(kChannelCount)
        ->setSampleRate(kSampleRate)
        ->setUsage(oboe::Usage::VoiceCommunication);

    oboe::Result result = builder.openStream(playbackStream_);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open playback stream: %s", oboe::convertToText(result));
        return false;
    }

    result = playbackStream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start playback stream: %s", oboe::convertToText(result));
        playbackStream_->close();
        playbackStream_.reset();
        return false;
    }

    isPlaying_ = true;
    LOGI("Oboe Playback Stream started at 16kHz Mono");
    return true;
}

void OboeAudioEngine::stopPlayback() {
    if (!isPlaying_) return;

    if (playbackStream_) {
        playbackStream_->stop();
        playbackStream_->close();
        playbackStream_.reset();
    }

    isPlaying_ = false;
    LOGI("Oboe Playback Stream stopped");
}

void OboeAudioEngine::writePlaybackAudio(const int16_t* audioData, size_t numSamples) {
    if (!playbackStream_ || !isPlaying_ || !audioData || numSamples == 0) return;

    // Direct synchronous low-latency write to Oboe playback buffer
    int32_t framesWritten = 0;
    while (framesWritten < static_cast<int32_t>(numSamples)) {
        int32_t remaining = static_cast<int32_t>(numSamples) - framesWritten;
        auto writeResult = playbackStream_->write(
            audioData + framesWritten,
            remaining,
            100 * oboe::kNanosPerMillisecond
        );

        if (writeResult.value() <= 0) {
            LOGE("Playback write timeout or error");
            break;
        }
        framesWritten += writeResult.value();
    }
}

oboe::DataCallbackResult OboeAudioEngine::onAudioReady(
    oboe::AudioStream * /*audioStream*/,
    void *audioData,
    int32_t numFrames) {

    const int16_t* pcmSamples = static_cast<const int16_t*>(audioData);

    // 1. Continuous ring buffer recording (300ms pre-roll)
    ringBuffer_.write(pcmSamples, static_cast<size_t>(numFrames));

    // 2. Deliver frame to listener callback if registered
    if (onFrameCallback_) {
        onFrameCallback_(pcmSamples, static_cast<size_t>(numFrames));
    }

    return oboe::DataCallbackResult::Continue;
}

void OboeAudioEngine::onErrorAfterClose(oboe::AudioStream * /*stream*/, oboe::Result result) {
    LOGE("Audio stream error: %s. Attempting to recover...", oboe::convertToText(result));
}

} // namespace itantra
