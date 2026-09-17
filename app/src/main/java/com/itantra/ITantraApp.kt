package com.itantra

import android.app.Application
import android.util.Log
import com.itantra.ai.lid.LanguageIdentificationEngine
import com.itantra.ai.stt.IndicConformerSttEngine
import com.itantra.ai.translation.IndicTranslationEngine
import com.itantra.ai.tts.ChunkedTtsEngine
import com.itantra.ai.vad.SileroVadEngine
import com.itantra.audio.NativeAudioBridge
import com.itantra.transport.MeshCoordinator
import com.itantra.transport.dtn.DtnMessageQueue

/**
 * iTantra Application Root.
 * Initializes the C++ audio engine, AI models, DTN queue, and Mesh Coordinator.
 */
class ITantraApp : Application() {

    lateinit var vadEngine: SileroVadEngine
        private set
    lateinit var lidEngine: LanguageIdentificationEngine
        private set
    lateinit var sttEngine: IndicConformerSttEngine
        private set
    lateinit var translationEngine: IndicTranslationEngine
        private set
    lateinit var ttsEngine: ChunkedTtsEngine
        private set
    lateinit var dtnQueue: DtnMessageQueue
        private set
    lateinit var meshCoordinator: MeshCoordinator
        private set

    var isDemoMode: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        Log.i("ITantraApp", "Initializing iTantra Disaster Communication System...")

        // 1. Initialize native C++ audio engine safely
        try {
            NativeAudioBridge.initEngine()
            Log.i("ITantraApp", "Native C++ Oboe Audio Engine initialized.")
        } catch (t: Throwable) {
            Log.w("ITantraApp", "Native audio library loading deferred or in simulation: ${t.message}")
            isDemoMode = true
        }

        // 2. Initialize AI engines
        vadEngine = SileroVadEngine(this)
        lidEngine = LanguageIdentificationEngine(this)
        sttEngine = IndicConformerSttEngine(this)
        translationEngine = IndicTranslationEngine(this)
        ttsEngine = ChunkedTtsEngine(this)

        // 3. Initialize DTN Queue & Mesh Coordinator
        dtnQueue = DtnMessageQueue(this)
        meshCoordinator = MeshCoordinator(this, translationEngine, ttsEngine, dtnQueue)

        Log.i("ITantraApp", "iTantra Core System Online & Ready (DemoMode=$isDemoMode).")
    }

    companion object {
        lateinit var instance: ITantraApp
            private set
    }
}
