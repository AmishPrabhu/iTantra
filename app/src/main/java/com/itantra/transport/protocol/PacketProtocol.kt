package com.itantra.transport.protocol

import com.itantra.ai.model.Language
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Compact Binary Packet Protocol for Disaster Mesh Networking.
 *
 * Packet Header Structure (8 Bytes total header):
 * [0]     : Packet Type (1 Byte: 0x00=EMERGENCY, 0x01=VOICE_TEXT, 0x02=HANDSHAKE, 0x03=ACK, 0x04=PING)
 * [1]     : Source Language ID (1 Byte: 0x00 to 0x0A)
 * [2..3]  : Sequence Number / Message ID (2 Bytes unsigned short, Big-Endian)
 * [4..7]  : CRC32 Checksum of Payload (4 Bytes integer, Big-Endian)
 * [8..N]  : UTF-8 Payload Data (N Bytes)
 */
object PacketProtocol {

    const val TYPE_EMERGENCY_ALERT: Byte = 0x00
    const val TYPE_VOICE_TEXT: Byte = 0x01
    const val TYPE_HANDSHAKE: Byte = 0x02
    const val TYPE_ACK: Byte = 0x03
    const val TYPE_HEARTBEAT_PING: Byte = 0x04

    const val HEADER_SIZE = 8

    data class DecodedPacket(
        val type: Byte,
        val sourceLanguage: Language,
        val sequenceId: Int,
        val payloadText: String,
        val isCrcValid: Boolean
    )

    /**
     * Serializes text message into a compact binary frame with CRC32 integrity checksum.
     */
    fun encodePacket(
        type: Byte,
        sourceLang: Language,
        sequenceId: Int,
        textPayload: String
    ): ByteArray {
        val payloadBytes = textPayload.toByteArray(Charsets.UTF_8)

        val crc = CRC32()
        crc.update(payloadBytes)
        val crcValue = crc.value.toInt()

        val buffer = ByteBuffer.allocate(HEADER_SIZE + payloadBytes.size)
        buffer.order(ByteOrder.BIG_ENDIAN)

        buffer.put(type)
        buffer.put(sourceLang.id)
        buffer.putShort(sequenceId.toShort())
        buffer.putInt(crcValue)
        buffer.put(payloadBytes)

        return buffer.array()
    }

    /**
     * Deserializes and validates incoming binary packet from radio socket.
     */
    fun decodePacket(rawBytes: ByteArray): DecodedPacket? {
        if (rawBytes.size < HEADER_SIZE) return null

        val buffer = ByteBuffer.wrap(rawBytes)
        buffer.order(ByteOrder.BIG_ENDIAN)

        val type = buffer.get()
        val langId = buffer.get()
        val seqId = buffer.short.toInt() and 0xFFFF
        val expectedCrc = buffer.int

        val payloadLength = rawBytes.size - HEADER_SIZE
        val payloadBytes = ByteArray(payloadLength)
        buffer.get(payloadBytes)

        val crc = CRC32()
        crc.update(payloadBytes)
        val actualCrc = crc.value.toInt()
        val isCrcValid = (expectedCrc == actualCrc)

        val text = String(payloadBytes, Charsets.UTF_8)
        val sourceLang = Language.fromId(langId)

        return DecodedPacket(
            type = type,
            sourceLanguage = sourceLang,
            sequenceId = seqId,
            payloadText = text,
            isCrcValid = isCrcValid
        )
    }

    fun createAckPacket(sequenceId: Int): ByteArray {
        return encodePacket(TYPE_ACK, Language.ENGLISH, sequenceId, "ACK")
    }

    fun createHeartbeatPacket(): ByteArray {
        return encodePacket(TYPE_HEARTBEAT_PING, Language.ENGLISH, 0, "PING")
    }
}
