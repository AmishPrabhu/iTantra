package com.itantra.ai.stt

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.itantra.ai.model.Language
import kotlinx.coroutines.*
import kotlin.math.sqrt

/**
 * 100% On-Device Neural Speech Recognizer for iTantra.
 *
 * Architecture:
 * - Persistent Warm Audio Engine: Keeps AudioRecord open and warm across PTT presses,
 *   preventing CoreAudio stream re-negotiation glitches and emulator 1kHz dummy tones.
 * - 300ms Pre-Roll Circular Buffer: Captures speech uttered right as or slightly before the PTT button is pressed.
 * - Automatic Gain Normalization (RMS): Normalizes faint microphone speech to conversational reference levels (0.15f RMS).
 * - Dual-Pass Neural STT: AI4Bharat IndicConformer (INT8 CTC) + NeMo Fast-Conformer (INT8 CTC).
 */
class RealtimeSpeechRecognizer(
    private val context: Context,
    private val onLiveSpeechUpdate: (String) -> Unit = {}
) {

    private val TAG = "OnDeviceSpeechRec"
    private var currentContext: Context = context
    private val sttEngine: IndicConformerSttEngine by lazy { IndicConformerSttEngine(currentContext) }

    private var activeLanguage: Language = Language.HINDI
    @Volatile private var isRecordingUtterance: Boolean = false
    @Volatile private var isEngineRunning: Boolean = false

    private var audioRecord: AudioRecord? = null
    private var audioLoopJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 300ms Circular Pre-Roll Buffer @ 16kHz MONO (4,800 samples)
    private val PRE_ROLL_CAPACITY = 4800
    private val preRollBuffer = ShortArray(PRE_ROLL_CAPACITY)
    private var preRollWriteHead = 0
    private var preRollFilledCount = 0
    private val preRollLock = Any()

    // Utterance accumulator for active PTT press
    private val audioChunks = mutableListOf<ShortArray>()
    private var totalSamplesRecorded = 0
    private val utteranceLock = Any()

    var onRmsUpdate: ((Float) -> Unit)? = null

    init {
        startPersistentAudioStream()
    }

    fun updateContext(newContext: Context) {
        currentContext = newContext
    }

    @SuppressLint("MissingPermission")
    private fun startPersistentAudioStream() {
        if (isEngineRunning) return
        isEngineRunning = true

        audioLoopJob = scope.launch {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = (minBufSize * 2).coerceAtLeast(4096)

            var record: AudioRecord? = null
            val audioSources = intArrayOf(
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.DEFAULT
            )

            for (source in audioSources) {
                try {
                    val candidate = AudioRecord(source, sampleRate, channelConfig, audioFormat, bufferSize)
                    if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                        record = candidate
                        Log.i(TAG, "⚡ AudioRecord hardware initialized using source: $source")
                        break
                    } else {
                        candidate.release()
                    }
                } catch (ignored: Exception) {}
            }

            if (record == null) {
                Log.e(TAG, "Failed to initialize AudioRecord hardware device.")
                isEngineRunning = false
                return@launch
            }

            audioRecord = record
            try {
                record.startRecording()
                Log.i(TAG, "⚡ Persistent warm audio stream active @ 16kHz PCM with 300ms Pre-Roll")

                val readBuffer = ShortArray(1024)

                while (isEngineRunning) {
                    val readCount = record.read(readBuffer, 0, readBuffer.size)
                    if (readCount > 0) {
                        // 1. Calculate Real-Time Acoustic Energy (RMS)
                        var sumSquares = 0.0
                        for (i in 0 until readCount) {
                            val s = readBuffer[i]
                            sumSquares += s * s
                        }
                        val rms = sqrt(sumSquares / readCount).toFloat()
                        if (isRecordingUtterance) {
                            onRmsUpdate?.invoke(rms)
                        }

                        // 2. Write to Pre-Roll Circular Buffer
                        synchronized(preRollLock) {
                            for (i in 0 until readCount) {
                                preRollBuffer[preRollWriteHead] = readBuffer[i]
                                preRollWriteHead = (preRollWriteHead + 1) % PRE_ROLL_CAPACITY
                                if (preRollFilledCount < PRE_ROLL_CAPACITY) preRollFilledCount++
                            }
                        }

                        // 3. Accumulate into active utterance if PTT is currently pressed
                        if (isRecordingUtterance) {
                            val chunkCopy = readBuffer.copyOf(readCount)
                            synchronized(utteranceLock) {
                                audioChunks.add(chunkCopy)
                                totalSamplesRecorded += readCount
                            }
                        }
                    } else if (readCount < 0) {
                        Log.w(TAG, "AudioRecord read error: $readCount")
                        delay(20)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in persistent audio stream: ${e.message}", e)
            } finally {
                try {
                    record.stop()
                    record.release()
                } catch (ignored: Exception) {}
                audioRecord = null
                isEngineRunning = false
            }
        }
    }

    fun startListening(language: Language) {
        if (!isEngineRunning || audioRecord == null) {
            startPersistentAudioStream()
        }
        activeLanguage = language
        onLiveSpeechUpdate("Listening (100% Offline AI4Bharat STT)...")

        synchronized(utteranceLock) {
            audioChunks.clear()
            totalSamplesRecorded = 0

            // Prepend 300ms pre-roll buffer so words uttered as the button was pressed are preserved
            synchronized(preRollLock) {
                if (preRollFilledCount > 0) {
                    val preRollData = ShortArray(preRollFilledCount)
                    val startPos = (preRollWriteHead - preRollFilledCount + PRE_ROLL_CAPACITY) % PRE_ROLL_CAPACITY
                    for (i in 0 until preRollFilledCount) {
                        preRollData[i] = preRollBuffer[(startPos + i) % PRE_ROLL_CAPACITY]
                    }
                    audioChunks.add(preRollData)
                    totalSamplesRecorded += preRollFilledCount
                }
            }
        }

        isRecordingUtterance = true
    }

    suspend fun stopListeningAndGetResult(fallbackLang: Language): String = withContext(Dispatchers.IO) {
        // Trailing grace window (180ms) preserves the final syllable before closing utterance
        delay(180)
        isRecordingUtterance = false

        // Extract utterance snapshot without interrupting persistent AudioRecord
        val allShorts: ShortArray
        synchronized(utteranceLock) {
            allShorts = ShortArray(totalSamplesRecorded)
            var offset = 0
            for (chunk in audioChunks) {
                System.arraycopy(chunk, 0, allShorts, offset, chunk.size)
                offset += chunk.size
            }
            audioChunks.clear()
            totalSamplesRecorded = 0
        }

        if (allShorts.size < 3200) {
            Log.w(TAG, "Utterance too short: ${allShorts.size} samples (<0.2s)")
            return@withContext ""
        }

        // Convert PCM 16-bit to Float [-1.0f, 1.0f]
        val floatSamples = FloatArray(allShorts.size) { i ->
            allShorts[i] / 32768.0f
        }

        // 1. Remove DC bias
        var sumSamples = 0.0
        for (s in floatSamples) sumSamples += s
        val dcOffset = (sumSamples / floatSamples.size).toFloat()
        for (i in floatSamples.indices) {
            floatSamples[i] -= dcOffset
        }

        // 2. Compute RMS Energy
        var sumSquares = 0.0
        for (s in floatSamples) {
            sumSquares += (s * s)
        }
        val rms = sqrt(sumSquares / floatSamples.size).toFloat()

        Log.i(TAG, "Utterance stats: samples=${floatSamples.size}, duration=${floatSamples.size / 16000.0}s, rmsEnergy=$rms")

        if (rms < 0.0003f) {
            Log.i(TAG, "Near-zero acoustic energy recorded (rms=$rms), returning empty result")
            return@withContext ""
        }

        // 3. RMS Gain Normalization (Target RMS = 0.15f)
        if (rms in 0.0003f..0.12f) {
            val targetRms = 0.15f
            val calculatedGain = (targetRms / rms).coerceIn(1.0f, 35.0f)
            for (i in floatSamples.indices) {
                floatSamples[i] = (floatSamples[i] * calculatedGain).coerceIn(-0.95f, 0.95f)
            }
            Log.i(TAG, "Applied RMS Gain Boost: ${calculatedGain}x (RMS boosted from $rms to ${rms * calculatedGain})")
        }

        Log.i(TAG, "Transcribing ${floatSamples.size / 16000.0}s with 100% Offline STT (lang=${activeLanguage.name})...")
        val transcribed = sttEngine.transcribeAudio(floatSamples, activeLanguage)

        Log.i(TAG, "Final On-Device STT Result: '$transcribed'")
        return@withContext transcribed
    }

    fun destroy() {
        isRecordingUtterance = false
        isEngineRunning = false
        audioLoopJob?.cancel()
        scope.cancel()
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            sttEngine.release()
        } catch (ignored: Exception) {}
    }
}
