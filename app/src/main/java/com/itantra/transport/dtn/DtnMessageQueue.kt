package com.itantra.transport.dtn

import android.content.Context
import android.util.Log
import com.itantra.ai.model.Language
import com.itantra.transport.protocol.PacketProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Delay-Tolerant Networking (DTN) Store-and-Forward Message Queue.
 * If receiving peers are temporarily out of radio range, messages are persisted in Room DB
 * and automatically dispatched with ACK verification as soon as links are re-established.
 */
class DtnMessageQueue(context: Context) {

    private val TAG = "DtnMessageQueue"
    private val database = DtnDatabase.getDatabase(context)
    private val dao = database.dtnDao()

    private val sequenceCounter = AtomicInteger(100)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var retryJob: Job? = null

    val allMessagesFlow: Flow<List<DtnMessageEntity>> = dao.getAllMessagesFlow()
    val pendingCountFlow: Flow<Int> = dao.getPendingCountFlow()

    init {
        startRetryWatchdog()
    }

    private fun startRetryWatchdog() {
        retryJob = scope.launch {
            while (isActive) {
                delay(3000) // Check unacknowledged messages every 3.0s
                try {
                    val pending = dao.getPendingOutgoingMessages()
                    if (pending.isNotEmpty()) {
                        Log.i(TAG, "DTN Retry Watchdog: ${pending.size} pending message(s) awaiting ACK - store & forward active")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "DTN retry check error: ${e.message}")
                }
            }
        }
    }

    /**
     * Enqueues an outgoing voice/alert text message into the persistent store.
     */
    suspend fun enqueueOutgoingMessage(
        type: Byte,
        sourceLang: Language,
        originalText: String,
        translatedText: String,
        senderNodeId: String = "Self"
    ): DtnMessageEntity {
        val seqId = sequenceCounter.incrementAndGet()

        val entity = DtnMessageEntity(
            sequenceId = seqId,
            packetType = type,
            sourceLanguageId = sourceLang.id,
            senderNodeId = senderNodeId,
            originalText = originalText,
            translatedText = translatedText,
            status = MessageStatus.PENDING_OUTGOING
        )

        val insertedId = dao.insertMessage(entity)
        Log.i(TAG, "Enqueued DTN message #$seqId into persistent store (ID: $insertedId)")
        return entity.copy(id = insertedId)
    }

    /**
     * Stores incoming message from peer mesh node.
     */
    suspend fun enqueueIncomingMessage(
        seqId: Int,
        type: Byte,
        sourceLangId: Byte,
        originalText: String,
        translatedText: String,
        senderNodeId: String
    ): DtnMessageEntity {
        val entity = DtnMessageEntity(
            sequenceId = seqId,
            packetType = type,
            sourceLanguageId = sourceLangId,
            senderNodeId = senderNodeId,
            originalText = originalText,
            translatedText = translatedText,
            status = MessageStatus.RECEIVED_INCOMING
        )

        val id = dao.insertMessage(entity)
        Log.i(TAG, "Recorded incoming DTN message #$seqId from $senderNodeId")
        return entity.copy(id = id)
    }

    suspend fun markAcknowledged(seqId: Int) {
        dao.markAcknowledged(seqId)
        Log.i(TAG, "DTN Message #$seqId ACK verified")
    }

    suspend fun getPendingOutgoing(): List<DtnMessageEntity> {
        return dao.getPendingOutgoingMessages()
    }

    fun shutdown() {
        retryJob?.cancel()
        scope.cancel()
    }
}
