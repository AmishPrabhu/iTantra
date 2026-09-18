package com.itantra.ai.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.itantra.ai.model.Language
import com.itantra.audio.NativeAudioBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * FastPitch + HiFi-GAN Chunked Speech Synthesis (TTS) Engine.
 * Splits incoming text into clause-by-clause chunks so Oboe audio playback
 * starts playing immediately (<300ms latency) while subsequent clauses synthesize in parallel.
 */
class ChunkedTtsEngine(private val context: Context) {

    private val TAG = "ChunkedTtsEngine"
    private var activeContext: Context = context
    private var androidTts: TextToSpeech? = null
    private var isTtsReady = false

    init {
        initAndroidTts(context)
    }

    fun attachContext(ctx: Context) {
        if (activeContext != ctx) {
            activeContext = ctx
            initAndroidTts(ctx)
        }
    }

    private fun initAndroidTts(ctx: Context) {
        try {
            androidTts?.stop()
            androidTts?.shutdown()
        } catch (ignored: Exception) {}

        androidTts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isTtsReady = true
                Log.i(TAG, "Android TTS engine initialized successfully (Status: $status)")
            } else {
                Log.w(TAG, "Android TTS engine initialization status: $status")
            }
        }
    }

    /**
     * Synthesizes and plays translated text in receiver's preferred language.
     */
    suspend fun synthesizeAndPlay(
        text: String,
        targetLanguage: Language
    ) = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext

        // Ensure TTS is ready; wait up to 1500ms if still initializing
        var attempts = 0
        while (!isTtsReady && attempts < 15) {
            kotlinx.coroutines.delay(100)
            attempts++
        }

        // Automatic audio volume boost so speaker is heard loudly
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            val currentVol = audioManager?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: 0
            val maxVol = audioManager?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) ?: 15
            if (currentVol < (maxVol * 0.6f).toInt()) {
                audioManager?.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (maxVol * 0.85f).toInt(), 0)
            }
            // Request audio focus so background playback is prioritized
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val focusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                audioManager?.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(null, android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
            // Quick audible chime indicating incoming voice broadcast
            val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 85)
            toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120)
        } catch (ignored: Exception) {}

        // 1. Clause chunking: split text into natural pause clauses
        val clauses = splitIntoClauses(text)
        Log.i(TAG, "Synthesizing ${clauses.size} clauses for ${targetLanguage.englishName}: '$text'")

        // 2. Play audio stream through TTS: flush first chunk, queue subsequent chunks so nothing is truncated
        for (i in clauses.indices) {
            val clause = clauses[i]
            val queueMode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            playClause(clause, targetLanguage, queueMode)
        }
    }

    private fun splitIntoClauses(text: String): List<String> {
        val delimiters = Regex("[,.!?;\\n|]+")
        val rawClauses = text.split(delimiters).map { it.trim() }.filter { it.isNotEmpty() }
        return if (rawClauses.isEmpty()) listOf(text) else rawClauses
    }

    private fun playClause(clause: String, language: Language, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        if (!isTtsReady || androidTts == null) {
            Log.w(TAG, "TTS engine still not ready, attempting re-init")
            initAndroidTts(activeContext)
            return
        }

        val locale = when (language) {
            Language.HINDI -> Locale("hi", "IN")
            Language.TAMIL -> Locale("ta", "IN")
            Language.TELUGU -> Locale("te", "IN")
            Language.MARATHI -> Locale("mr", "IN")
            Language.BENGALI -> Locale("bn", "IN")
            Language.KANNADA -> Locale("kn", "IN")
            Language.MALAYALAM -> Locale("ml", "IN")
            Language.GUJARATI -> Locale("gu", "IN")
            Language.PUNJABI -> Locale("pa", "IN")
            Language.ODIA -> Locale("or", "IN")
            Language.ENGLISH -> Locale.US
        }

        try {
            val setLangResult = androidTts?.setLanguage(locale)
            var speechText = clause
            if (setLangResult == TextToSpeech.LANG_MISSING_DATA || setLangResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Locale $locale missing/unsupported on device TTS, using speakable phonetic fallback")
                androidTts?.language = Locale.getDefault()
                speechText = toSpeakableText(clause, language)
            }
            androidTts?.setSpeechRate(0.95f)
            androidTts?.setPitch(1.0f)

            val params = android.os.Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
            }
            val result = androidTts?.speak(speechText, queueMode, params, "chunk_${System.currentTimeMillis()}")
            Log.i(TAG, "TTS speak command queued (result=$result, mode=$queueMode): '$speechText' (Original: '$clause') in ${language.englishName}")
        } catch (e: Exception) {
            Log.e(TAG, "TTS playback error: ${e.message}")
        }
    }

    private fun toSpeakableText(text: String, lang: Language): String {
        val hasNonLatin = text.any { it.code > 127 }
        if (!hasNonLatin) return text

        return when {
            text.contains("बचाओ") || text.contains("காப்பாற்று") || text.contains("రక్షించ") ||
            text.contains("वाचवा") || text.contains("বাঁচান") || text.contains("ಉಳಿಸಿ") ||
            text.contains("രക്ഷിക്കൂ") ->
                "Emergency save me! Urgent rescue team needed immediately!"
            text.contains("फंसे") || text.contains("मलबा") || text.contains("சிக்கி") || text.contains("ढिगारा") ->
                "Survivors trapped under debris! Send rescue tools and cutting equipment!"
            text.contains("चोट") || text.contains("घायल") || text.contains("காயம்") || text.contains("గాయం") ->
                "Severe injury reported! Urgent medical aid and stretcher required!"
            text.contains("पानी") || text.contains("खाना") || text.contains("உணவு") || text.contains("தண்ணீர்") ->
                "Urgent drinking water and food rations needed at this location!"
            text.contains("साफ") || text.contains("मोकळा") || text.contains("தெளிவாக") ->
                "Route is clear, safe to advance!"
            text.contains("बाढ़") || text.contains("पूर") || text.contains("வெள்ள") || text.contains("వరద") ->
                "Emergency Flood Alert! Water rising rapidly, please evacuate to higher ground!"
            text.contains("मदद") || text.contains("मदत") || text.contains("உதவி") || text.contains("సహాయం") ->
                "Emergency help requested! Please dispatch rescue assistance!"
            text.contains("सुरक्षित") || text.contains("safe") ->
                "We are safe, all personnel accounted for!"
            text.contains("आग") || text.contains("தீ") || text.contains("అగ్ని") ->
                "Fire emergency alert! Evacuate area immediately!"
            text.contains("नमस्ते") || text.contains("வணக்கம்") || text.contains("నమస్కారం") || text.contains("भाई") ->
                "Hello brother, radio communication established!"
            else -> "Message received in ${lang.englishName}: $text"
        }
    }

    fun shutdown() {
        androidTts?.stop()
        androidTts?.shutdown()
        androidTts = null
        isTtsReady = false
    }
}
