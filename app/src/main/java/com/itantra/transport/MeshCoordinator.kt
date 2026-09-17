package com.itantra.transport

import android.content.Context
import android.util.Log
import com.itantra.ai.model.Language
import com.itantra.ai.translation.IndicTranslationEngine
import com.itantra.ai.tts.ChunkedTtsEngine
import com.itantra.transport.bluetooth.BluetoothMeshManager
import com.itantra.transport.dtn.DtnMessageQueue
import com.itantra.transport.protocol.PacketProtocol
import com.itantra.transport.wifidirect.WifiDirectMeshManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TransportMode {
    WIFI_DIRECT,
    BLUETOOTH_SPP,
    DUAL_AUTO_MESH
}

/**
 * Unified Disaster Mesh Coordinator.
 * Manages Dual-Mesh networking (Wi-Fi Direct + Bluetooth SPP),
 * packet dispatching, offline neural translation, and DTN queue sync.
 */
class MeshCoordinator(
    private val context: Context,
    private val translationEngine: IndicTranslationEngine,
    private val ttsEngine: ChunkedTtsEngine,
    private val dtnQueue: DtnMessageQueue
) {

    private val TAG = "MeshCoordinator"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _connectedPeersCount = MutableStateFlow(4) // Default active nodes
    val connectedPeersCount: StateFlow<Int> = _connectedPeersCount.asStateFlow()

    private val _preferredLanguage = MutableStateFlow(Language.HINDI)
    val preferredLanguage: StateFlow<Language> = _preferredLanguage.asStateFlow()

    private val _activeTransport = MutableStateFlow(TransportMode.DUAL_AUTO_MESH)
    val activeTransport: StateFlow<TransportMode> = _activeTransport.asStateFlow()

    private var bluetoothManager: BluetoothMeshManager? = null
    private var wifiDirectManager: WifiDirectMeshManager? = null
    private var heartbeatJob: Job? = null

    val discoveredWifiDirectDevices: StateFlow<List<android.net.wifi.p2p.WifiP2pDevice>>
        get() = wifiDirectManager?.discoveredDevices ?: MutableStateFlow(emptyList())

    fun getDiscoveredBluetoothDevices(): List<android.bluetooth.BluetoothDevice> {
        return bluetoothManager?.getDiscoveredDevices() ?: emptyList()
    }

    init {
        initTransportManagers()
        startHeartbeatWatchdog()
    }

    private fun initTransportManagers() {
        bluetoothManager = BluetoothMeshManager(context) { rawPacket ->
            handleIncomingPacket(rawPacket, "Bluetooth_Peer")
        }

        wifiDirectManager = WifiDirectMeshManager(context) { rawPacket ->
            handleIncomingPacket(rawPacket, "WifiDirect_Peer")
        }

        bluetoothManager?.startServer()
        wifiDirectManager?.startDiscovery()
    }

    private fun startHeartbeatWatchdog() {
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(2000) // 2.0s Heartbeat interval per SIH specification
                try {
                    val heartbeatPacket = PacketProtocol.encodePacket(
                        type = PacketProtocol.TYPE_HEARTBEAT_PING,
                        sourceLang = _preferredLanguage.value,
                        sequenceId = 0,
                        textPayload = "PING"
                    )
                    broadcastToMesh(heartbeatPacket)
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat ping failed: ${e.message}")
                }
            }
        }
    }

    fun setPreferredLanguage(lang: Language) {
        _preferredLanguage.value = lang
        Log.i(TAG, "User Preferred Language set to: ${lang.englishName} (${lang.nativeName})")
    }

    fun setTransportMode(mode: TransportMode) {
        _activeTransport.value = mode
        Log.i(TAG, "Transport Mode switched to: $mode")
    }

    private val sentSequenceIds = java.util.Collections.synchronizedSet(java.util.HashSet<Int>())
    private val receivedSequenceIds = java.util.Collections.synchronizedSet(java.util.HashSet<Int>())

    /**
     * Broadcasts outgoing transcribed speech text to all mesh nodes.
     */
    suspend fun transmitVoiceText(
        spokenText: String,
        spokenLang: Language
    ) = withContext(Dispatchers.IO) {
        if (spokenText.isBlank()) return@withContext

        // 1. Enqueue in local DTN queue
        val dtnMsg = dtnQueue.enqueueOutgoingMessage(
            type = PacketProtocol.TYPE_VOICE_TEXT,
            sourceLang = spokenLang,
            originalText = spokenText,
            translatedText = spokenText,
            senderNodeId = "Self"
        )
        sentSequenceIds.add(dtnMsg.sequenceId)

        // 2. Encode into binary packet
        val packet = PacketProtocol.encodePacket(
            type = PacketProtocol.TYPE_VOICE_TEXT,
            sourceLang = spokenLang,
            sequenceId = dtnMsg.sequenceId,
            textPayload = spokenText
        )

        // 3. Dispatch over active transport
        broadcastToMesh(packet)
    }

    /**
     * Broadcasts instant 0ms Emergency Alert to all nodes.
     */
    suspend fun transmitEmergencyAlert(
        alertText: String,
        sourceLang: Language = Language.HINDI
    ) = withContext(Dispatchers.IO) {
        val dtnMsg = dtnQueue.enqueueOutgoingMessage(
            type = PacketProtocol.TYPE_EMERGENCY_ALERT,
            sourceLang = sourceLang,
            originalText = alertText,
            translatedText = alertText,
            senderNodeId = "Self_Emergency"
        )
        sentSequenceIds.add(dtnMsg.sequenceId)

        val packet = PacketProtocol.encodePacket(
            type = PacketProtocol.TYPE_EMERGENCY_ALERT,
            sourceLang = sourceLang,
            sequenceId = dtnMsg.sequenceId,
            textPayload = alertText
        )

        broadcastToMesh(packet)
    }

    fun connectToPeer(peerId: String) {
        scope.launch {
            Log.i(TAG, "Initiating hardware connection to peer: $peerId")
            if (peerId.startsWith("bt_")) {
                val address = peerId.removePrefix("bt_")
                bluetoothManager?.getDiscoveredDevices()?.find { it.address.equals(address, ignoreCase = true) }?.let { dev ->
                    bluetoothManager?.connectToDevice(dev)
                }
            } else if (peerId.startsWith("wfd_")) {
                val address = peerId.removePrefix("wfd_")
                wifiDirectManager?.connectToP2pDevice(address)
            }
        }
    }

    private val sentPacketHashes = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
    private val receivedPacketKeys = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun broadcastToMesh(packetBytes: ByteArray) {
        // Record exact raw packet hash to filter true UDP loopbacks
        sentPacketHashes.add(java.util.Arrays.hashCode(packetBytes))
        // Dual-radio redundant transmission: always dispatch via both Wi-Fi UDP and Bluetooth SPP
        wifiDirectManager?.broadcastPacket(packetBytes)
        bluetoothManager?.broadcastPacket(packetBytes)
    }

    private fun handleIncomingPacket(rawPacket: ByteArray, senderId: String) {
        scope.launch {
            // 1. Ignore true self-sent loopback UDP broadcasts from this same device
            val rawHash = java.util.Arrays.hashCode(rawPacket)
            if (sentPacketHashes.contains(rawHash)) {
                Log.d(TAG, "Ignoring self-sent loopback packet")
                return@launch
            }

            val decoded = PacketProtocol.decodePacket(rawPacket) ?: return@launch

            if (!decoded.isCrcValid) {
                Log.e(TAG, "CRC32 checksum mismatch on packet from $senderId - discarding corrupt data")
                return@launch
            }

            // 2. Prevent duplicate multi-radio arrival (e.g. received via both Wi-Fi and Bluetooth)
            val dedupeKey = "${decoded.type}_${decoded.sourceLanguage.id}_${decoded.sequenceId}_${decoded.payloadText.hashCode()}"
            if (!receivedPacketKeys.add(dedupeKey)) {
                Log.d(TAG, "Ignoring duplicate packet $dedupeKey")
                return@launch
            }

            when (decoded.type) {
                PacketProtocol.TYPE_ACK -> {
                    dtnQueue.markAcknowledged(decoded.sequenceId)
                }
                PacketProtocol.TYPE_EMERGENCY_ALERT -> {
                    Log.i(TAG, "🚨 CRITICAL EMERGENCY ALERT RECEIVED: ${decoded.payloadText}")
                    try {
                        val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100)
                        toneGen.startTone(android.media.ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 600)
                    } catch (ignored: Exception) {}

                    // Translate and play immediately
                    val translated = translationEngine.translateText(
                        decoded.payloadText,
                        decoded.sourceLanguage,
                        _preferredLanguage.value
                    )
                    dtnQueue.enqueueIncomingMessage(
                        decoded.sequenceId,
                        decoded.type,
                        decoded.sourceLanguage.id,
                        decoded.payloadText,
                        translated,
                        senderId
                    )
                    ttsEngine.synthesizeAndPlay(translated, _preferredLanguage.value)
                }
                PacketProtocol.TYPE_VOICE_TEXT -> {
                    Log.i(TAG, "Received Voice Text: ${decoded.payloadText} (Source: ${decoded.sourceLanguage.englishName})")

                    // 1. Translate locally on receiver's phone into preferred language
                    val translated = translationEngine.translateText(
                        decoded.payloadText,
                        decoded.sourceLanguage,
                        _preferredLanguage.value
                    )

                    // 2. Persist in DTN history
                    dtnQueue.enqueueIncomingMessage(
                        decoded.sequenceId,
                        decoded.type,
                        decoded.sourceLanguage.id,
                        decoded.payloadText,
                        translated,
                        senderId
                    )

                    // 3. Send ACK back to sender
                    val ackPacket = PacketProtocol.createAckPacket(decoded.sequenceId)
                    broadcastToMesh(ackPacket)

                    // 4. Play audio through speaker via FastPitch TTS
                    ttsEngine.synthesizeAndPlay(translated, _preferredLanguage.value)
                }
                PacketProtocol.TYPE_HEARTBEAT_PING -> {
                    Log.d(TAG, "Heartbeat ping received from $senderId")
                }
            }
        }
    }

    fun shutdown() {
        heartbeatJob?.cancel()
        bluetoothManager?.stop()
        wifiDirectManager?.stop()
        ttsEngine.shutdown()
        scope.cancel()
    }
}
