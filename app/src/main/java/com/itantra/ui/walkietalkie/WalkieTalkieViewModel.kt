package com.itantra.ui.walkietalkie

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itantra.ITantraApp
import com.itantra.ai.model.Language
import com.itantra.audio.NativeAudioBridge
import com.itantra.transport.TransportMode
import com.itantra.transport.dtn.DtnMessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

data class DiscoveredPeer(
    val id: String,
    val name: String,
    val role: String,
    val language: Language,
    val transport: TransportMode,
    val signalDbm: String,
    val isOnline: Boolean = true
)

data class WalkieTalkieUiState(
    val isTransmitting: Boolean = false,
    val connectedNodesCount: Int = 1,
    val activeTransport: TransportMode = TransportMode.WIFI_DIRECT,
    val spokenLanguage: Language = Language.HINDI,
    val receiverLanguage: Language = Language.TAMIL,
    val selectedPeer: DiscoveredPeer? = null, // null = Group Broadcast (All Nodes)
    val showPeerDiscoverySheet: Boolean = false,
    val discoveredPeers: List<DiscoveredPeer> = emptyList(),
    val lastSentText: String = "Ready (Press and hold PTT to speak)",
    val lastTranslatedText: String = "➔ Dual-Mesh Ready • 0ms Neural Voice Bridge",
    val waveformHeights: List<Float> = listOf(6f, 14f, 24f, 16f, 20f, 8f)
)

class WalkieTalkieViewModel : ViewModel() {

    private val app = ITantraApp.instance
    private val coordinator = app.meshCoordinator
    private val _uiState = MutableStateFlow(WalkieTalkieUiState())
    val uiState: StateFlow<WalkieTalkieUiState> = _uiState.asStateFlow()

    private val speechRecognizer = com.itantra.ai.stt.RealtimeSpeechRecognizer(app.applicationContext) { liveText ->
        _uiState.value = _uiState.value.copy(
            lastSentText = liveText,
            lastTranslatedText = "➔ Voice Recognized • Transcribing..."
        )
    }

    fun attachContext(context: android.content.Context) {
        speechRecognizer.updateContext(context)
        app.ttsEngine.attachContext(context)
    }

    val dtnMessagesFlow = app.dtnQueue.allMessagesFlow

    private var waveformJob: Job? = null

    init {
        speechRecognizer.onRmsUpdate = { rms ->
            if (_uiState.value.isTransmitting) {
                val factor = (rms / 600f).coerceIn(0.1f, 1.2f)
                val bars = listOf(
                    6f + factor * 14f,
                    10f + factor * 22f,
                    16f + factor * 30f,
                    12f + factor * 26f,
                    8f + factor * 18f,
                    6f + factor * 12f
                )
                _uiState.value = _uiState.value.copy(waveformHeights = bars)
            }
        }

        viewModelScope.launch {
            coordinator.preferredLanguage.collect { prefLang ->
                _uiState.value = _uiState.value.copy(receiverLanguage = prefLang)
            }
        }

        // Live Incoming Message Card Update on Receiver Device
        viewModelScope.launch {
            dtnMessagesFlow.collect { messages ->
                val latestIncoming = messages
                    .filter { it.senderNodeId != "Self" && it.senderNodeId != "Self_Emergency" }
                    .maxByOrNull { it.timestamp }
                if (latestIncoming != null && !_uiState.value.isTransmitting) {
                    _uiState.value = _uiState.value.copy(
                        lastSentText = "📥 Incoming: ${latestIncoming.originalText}",
                        lastTranslatedText = "➔ Translated: ${latestIncoming.translatedText}"
                    )
                }
            }
        }

        // Live Dynamic Peer Discovery across Wi-Fi Direct & Bluetooth
        viewModelScope.launch {
            coordinator.discoveredWifiDirectDevices.collect { p2pDevices ->
                val btDevices = coordinator.getDiscoveredBluetoothDevices()
                val peersList = mutableListOf<DiscoveredPeer>()

                // 1. Real Wi-Fi Direct devices discovered in physical range
                p2pDevices.forEach { dev ->
                    val rawName = dev.deviceName.trim()
                    val displayName = if (rawName.isNotBlank() && rawName != "[Unknown]") rawName else "Wi-Fi Direct (${dev.deviceAddress.takeLast(5)})"
                    peersList.add(
                        DiscoveredPeer(
                            id = "wfd_${dev.deviceAddress}",
                            name = displayName,
                            role = "Wi-Fi Direct (~150m)",
                            language = _uiState.value.receiverLanguage,
                            transport = TransportMode.WIFI_DIRECT,
                            signalDbm = "Direct Link",
                            isOnline = true
                        )
                    )
                }

                // 2. Real Bluetooth devices in range
                btDevices.forEach { btDev ->
                    val name = try { btDev.name } catch (e: SecurityException) { null } ?: "Bluetooth Device"
                    if (peersList.none { it.id.contains(btDev.address) }) {
                        peersList.add(
                            DiscoveredPeer(
                                id = "bt_${btDev.address}",
                                name = name,
                                role = "Bluetooth SPP Mesh",
                                language = _uiState.value.receiverLanguage,
                                transport = TransportMode.BLUETOOTH_SPP,
                                signalDbm = "SPP Paired",
                                isOnline = true
                            )
                        )
                    }
                }

                _uiState.value = _uiState.value.copy(
                    discoveredPeers = peersList,
                    connectedNodesCount = if (peersList.isNotEmpty()) peersList.size + 1 else 1
                )
            }
        }
    }

    fun setPreferredLanguage(lang: Language) {
        coordinator.setPreferredLanguage(lang)
        _uiState.value = _uiState.value.copy(spokenLanguage = lang)
    }

    fun setTransportMode(mode: TransportMode) {
        coordinator.setTransportMode(mode)
        _uiState.value = _uiState.value.copy(activeTransport = mode)
    }

    fun openPeerDiscoverySheet() {
        _uiState.value = _uiState.value.copy(showPeerDiscoverySheet = true)
    }

    fun closePeerDiscoverySheet() {
        _uiState.value = _uiState.value.copy(showPeerDiscoverySheet = false)
    }

    fun selectTargetPeer(peer: DiscoveredPeer?) {
        val targetReceiverLang = peer?.language ?: _uiState.value.receiverLanguage
        _uiState.value = _uiState.value.copy(
            selectedPeer = peer,
            receiverLanguage = targetReceiverLang,
            showPeerDiscoverySheet = false
        )
        if (peer != null) {
            coordinator.connectToPeer(peer.id)
        }
    }

    fun onPttPressed() {
        if (_uiState.value.isTransmitting) return

        _uiState.value = _uiState.value.copy(isTransmitting = true)
        // Exclusive mic access granted to SpeechRecognizer (NativeAudioBridge mic recording omitted to prevent ERROR_AUDIO)
        speechRecognizer.startListening(_uiState.value.spokenLanguage)

        // Start waveform animation
        waveformJob = viewModelScope.launch {
            while (_uiState.value.isTransmitting) {
                val randomBars = List(6) { Random.nextFloat() * 20f + 6f }
                _uiState.value = _uiState.value.copy(waveformHeights = randomBars)
                delay(80)
            }
        }
    }

    fun onPttReleased() {
        if (!_uiState.value.isTransmitting) return

        _uiState.value = _uiState.value.copy(
            isTransmitting = false,
            waveformHeights = listOf(6f, 14f, 24f, 16f, 20f, 8f)
        )
        waveformJob?.cancel()

        viewModelScope.launch(Dispatchers.IO) {
            // 1. Get live transcribed text directly from microphone SpeechRecognizer
            val spokenText = speechRecognizer.stopListeningAndGetResult(_uiState.value.spokenLanguage)
            var detectedLang = _uiState.value.spokenLanguage

            // Intelligent Script-based Language Auto-Detection (LID)
            if (spokenText.isNotBlank()) {
                val hasDevanagari = spokenText.any { it in '\u0900'..'\u097F' }
                val hasTamil = spokenText.any { it in '\u0B80'..'\u0BFF' }
                val hasTelugu = spokenText.any { it in '\u0C00'..'\u0C7F' }
                val hasBengali = spokenText.any { it in '\u0980'..'\u09FF' }
                val hasKannada = spokenText.any { it in '\u0C80'..'\u0CFF' }
                val hasMalayalam = spokenText.any { it in '\u0D00'..'\u0D7F' }
                val hasGujarati = spokenText.any { it in '\u0A80'..'\u0AFF' }
                val hasPunjabi = spokenText.any { it in '\u0A00'..'\u0A7F' }
                val hasOdia = spokenText.any { it in '\u0B00'..'\u0B7F' }
                val isLatin = spokenText.any { it in 'a'..'z' || it in 'A'..'Z' } && !hasDevanagari && !hasTamil && !hasTelugu

                detectedLang = when {
                    hasDevanagari -> if (detectedLang == Language.MARATHI) Language.MARATHI else Language.HINDI
                    hasTamil -> Language.TAMIL
                    hasTelugu -> Language.TELUGU
                    hasBengali -> Language.BENGALI
                    hasKannada -> Language.KANNADA
                    hasMalayalam -> Language.MALAYALAM
                    hasGujarati -> Language.GUJARATI
                    hasPunjabi -> Language.PUNJABI
                    hasOdia -> Language.ODIA
                    isLatin && detectedLang != Language.ENGLISH -> Language.ENGLISH
                    else -> detectedLang
                }

                _uiState.value = _uiState.value.copy(spokenLanguage = detectedLang)
                val targetPeer = _uiState.value.selectedPeer
                coordinator.transmitVoiceText(spokenText, detectedLang)
                
                val statusText = if (targetPeer != null) {
                    "➔ 1-to-1 to ${targetPeer.name} in ${targetPeer.language.nativeName} (Auto-Translated)"
                } else {
                    "➔ Broadcasted to ${_uiState.value.connectedNodesCount} Nodes (Auto-Translated)"
                }

                _uiState.value = _uiState.value.copy(
                    lastSentText = spokenText,
                    lastTranslatedText = statusText
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    lastSentText = "[No voice detected]",
                    lastTranslatedText = "➔ Please hold button and speak clearly into the mic"
                )
            }
        }
    }

    fun transmitQuickPhrase(phrase: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val detectedLang = _uiState.value.spokenLanguage
            coordinator.transmitVoiceText(phrase, detectedLang)
            val targetPeer = _uiState.value.selectedPeer
            val statusText = if (targetPeer != null) {
                "➔ 1-to-1 to ${targetPeer.name} in ${targetPeer.language.nativeName} (Auto-Translated)"
            } else {
                "➔ Broadcasted to ${_uiState.value.connectedNodesCount} Nodes (Auto-Translated)"
            }
            _uiState.value = _uiState.value.copy(
                lastSentText = phrase,
                lastTranslatedText = statusText
            )
        }
    }

    fun sendQuickAlert(alertTitle: String) {
        viewModelScope.launch(Dispatchers.IO) {
            coordinator.transmitEmergencyAlert(alertTitle, _uiState.value.spokenLanguage)
            _uiState.value = _uiState.value.copy(
                lastSentText = alertTitle,
                lastTranslatedText = "🚨 Emergency Fast-Path (0ms .wav Dispatched)"
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        speechRecognizer.destroy()
    }
}
