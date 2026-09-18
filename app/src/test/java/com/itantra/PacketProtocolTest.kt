package com.itantra

import com.itantra.ai.model.Language
import com.itantra.transport.protocol.PacketProtocol
import org.junit.Assert.*
import org.junit.Test

class PacketProtocolTest {

    @Test
    fun testEncodeDecodeVoicePacket() {
        val originalText = "बाढ़ का पानी बढ़ रहा है, तुरंत सुरक्षित स्थान पर जाएं"
        val sequenceId = 1042
        val sourceLang = Language.HINDI

        val packetBytes = PacketProtocol.encodePacket(
            type = PacketProtocol.TYPE_VOICE_TEXT,
            sourceLang = sourceLang,
            sequenceId = sequenceId,
            textPayload = originalText
        )

        assertNotNull(packetBytes)
        assertTrue(packetBytes.size > PacketProtocol.HEADER_SIZE)

        val decoded = PacketProtocol.decodePacket(packetBytes)
        assertNotNull(decoded)
        assertEquals(PacketProtocol.TYPE_VOICE_TEXT, decoded!!.type)
        assertEquals(Language.HINDI, decoded.sourceLanguage)
        assertEquals(sequenceId, decoded.sequenceId)
        assertEquals(originalText, decoded.payloadText)
        assertTrue(decoded.isCrcValid)
    }

    @Test
    fun testCrcCorruptionDetection() {
        val packetBytes = PacketProtocol.encodePacket(
            type = PacketProtocol.TYPE_EMERGENCY_ALERT,
            sourceLang = Language.ENGLISH,
            sequenceId = 999,
            textPayload = "Emergency SOS Alert"
        )

        // Corrupt 1 byte in the payload
        packetBytes[packetBytes.size - 1] = (packetBytes[packetBytes.size - 1] + 1).toByte()

        val decoded = PacketProtocol.decodePacket(packetBytes)
        assertNotNull(decoded)
        assertFalse("Corrupted byte should fail CRC check", decoded!!.isCrcValid)
    }

    @Test
    fun testAckPacketCreation() {
        val ackBytes = PacketProtocol.createAckPacket(555)
        val decoded = PacketProtocol.decodePacket(ackBytes)

        assertNotNull(decoded)
        assertEquals(PacketProtocol.TYPE_ACK, decoded!!.type)
        assertEquals(555, decoded.sequenceId)
        assertEquals("ACK", decoded.payloadText)
        assertTrue(decoded.isCrcValid)
    }

    @Test
    fun testLanguageEnumMapping() {
        assertEquals(Language.HINDI, Language.fromId(0x01))
        assertEquals(Language.TAMIL, Language.fromId(0x02))
        assertEquals(Language.MARATHI, Language.fromId(0x04))
        assertEquals(Language.ENGLISH, Language.fromIso("en"))
        assertEquals(Language.BENGALI, Language.fromIso("bn"))
    }

    @Test
    fun testStructuredHeartbeatPayload() {
        val nodeId = "node_42a"
        val deviceName = "Pixel 7 Pro"
        val lang = Language.TAMIL

        val payload = PacketProtocol.encodeHeartbeatPayload(nodeId, deviceName, lang)
        assertTrue(payload.startsWith("PING|"))

        val info = PacketProtocol.parseHeartbeatPayload(payload)
        assertNotNull(info)
        assertEquals(nodeId, info!!.nodeId)
        assertEquals(deviceName, info.deviceName)
        assertEquals(Language.TAMIL, info.language)
    }

    @Test
    fun testStructuredVoicePayload() {
        val senderId = "nd_91"
        val senderName = "Galaxy S23"
        val targetId = "ALL"
        val text = "मुझे बचाओ मुझे बचाओ"

        val payload = PacketProtocol.encodeVoicePayload(senderId, senderName, targetId, text)
        val decoded = PacketProtocol.parseVoicePayload(payload)

        assertEquals(senderId, decoded.senderNodeId)
        assertEquals(senderName, decoded.senderDeviceName)
        assertEquals(targetId, decoded.targetNodeId)
        assertEquals(text, decoded.text)
    }
}
