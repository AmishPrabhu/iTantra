package com.itantra.audio

import android.util.Log

/**
 * JNI Bridge providing high-performance access to:
 * - Oboe 16kHz audio capture & playback
 * - 300ms lock-free circular pre-roll ring buffer
 * - Mathematical RMS silence gate
 * - mmap zero-copy ONNX model weight loader
 */
object NativeAudioBridge {

    private const val TAG = "NativeAudioBridge"
    private var isInitialized = false

    init {
        try {
            System.loadLibrary("itantra_native")
            isInitialized = nativeInit()
            Log.i(TAG, "Native audio engine loaded and initialized: $isInitialized")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load itantra_native library: ${e.message}")
        }
    }

    fun initEngine(): Boolean = isInitialized

    fun startRecording(): Boolean = if (isInitialized) nativeStartRecording() else false
    fun stopRecording() { if (isInitialized) nativeStopRecording() }

    fun startPlayback(): Boolean = if (isInitialized) nativeStartPlayback() else false
    fun stopPlayback() { if (isInitialized) nativeStopPlayback() }

    fun writePlaybackAudio(samples: ShortArray, count: Int) {
        if (isInitialized && count > 0) {
            nativeWritePlaybackAudio(samples, count)
        }
    }

    fun readPreRollBuffer(outBuffer: ShortArray, maxSamples: Int): Int {
        return if (isInitialized && maxSamples > 0) {
            nativeReadPreRollBuffer(outBuffer, maxSamples)
        } else {
            0
        }
    }

    fun isAudioActive(samples: ShortArray, count: Int): Boolean {
        return if (isInitialized && count > 0) {
            nativeIsAudioActive(samples, count)
        } else {
            false
        }
    }

    fun loadMmapModel(filePath: String): Boolean {
        return if (isInitialized) nativeLoadMmapModel(filePath) else false
    }

    fun releaseMmapModel() {
        if (isInitialized) nativeReleaseMmapModel()
    }

    // Native External C++ Methods
    private external fun nativeInit(): Boolean
    private external fun nativeStartRecording(): Boolean
    private external fun nativeStopRecording()
    private external fun nativeStartPlayback(): Boolean
    private external fun nativeStopPlayback()
    private external fun nativeWritePlaybackAudio(audioSamples: ShortArray, count: Int)
    private external fun nativeReadPreRollBuffer(outBuffer: ShortArray, maxSamples: Int): Int
    private external fun nativeIsAudioActive(samples: ShortArray, count: Int): Boolean
    private external fun nativeLoadMmapModel(filePath: String): Boolean
    private external fun nativeReleaseMmapModel()
}
