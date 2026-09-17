package com.itantra.transport.dtn

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class MessageStatus {
    PENDING_OUTGOING,
    DELIVERED,
    ACKNOWLEDGED,
    RECEIVED_INCOMING
}

@Entity(tableName = "dtn_messages")
data class DtnMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sequenceId: Int,
    val packetType: Byte,
    val sourceLanguageId: Byte,
    val senderNodeId: String,
    val originalText: String,
    val translatedText: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.PENDING_OUTGOING,
    val retryCount: Int = 0
)
