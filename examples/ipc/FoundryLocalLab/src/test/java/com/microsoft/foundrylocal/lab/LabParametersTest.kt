package com.microsoft.foundrylocal.lab

import com.microsoft.foundrylocal.api.ChatMessage
import org.junit.Assert.*
import org.junit.Test

class LabParametersTest {
    @Test
    fun `blank advanced controls keep SDK defaults unset`() {
        val request = LabChatParameters().request(listOf(ChatMessage.user("hello")), 0.2f, 512)
        assertNull(request.topP)
        assertNull(request.topK)
        assertNull(request.stop)
        assertNull(request.presencePenalty)
        assertNull(request.frequencyPenalty)
    }

    @Test
    fun `edited controls reach the SDK request with whitespace in stop sequences intact`() {
        val request = LabChatParameters("0.85", "30", "END\n\n stop\nEND", "-0.2", "0.5")
            .request(listOf(ChatMessage.user("test")), 0.1f, 256)
        assertEquals(0.85f, request.topP)
        assertEquals(30, request.topK)
        assertEquals(listOf("END", " stop"), request.stop)
        assertEquals(-0.2f, request.presencePenalty)
        assertEquals(0.5f, request.frequencyPenalty)
        assertEquals(256, request.maxTokens)
    }

    @Test
    fun `nonfinite and malformed options cannot silently become defaults`() {
        listOf("NaN", "Infinity", "hello", "1.1").forEach {
            assertTrue(LabChatParameters(topP = it).errors.containsKey("Top P"))
        }
        assertTrue(LabChatParameters(topK = "3.5").errors.containsKey("Top K"))
        assertTrue(LabChatParameters(frequencyPenalty = "NaN").errors.containsKey("Frequency penalty"))
        assertThrows(IllegalArgumentException::class.java) {
            LabChatParameters(topP = "invalid").request(emptyList(), 0.2f, 512)
        }
    }

    @Test
    fun `file language and temperature use explicit values or model defaults`() {
        val defaults = LabAudioParameters().request("/test.wav")
        assertNull(defaults.language)
        assertNull(defaults.temperature)
        val request = LabAudioParameters(" UR ", "0.2").request("/test.wav")
        assertEquals("ur", request.language)
        assertEquals(0.2f, request.temperature)
        assertTrue(LabAudioParameters(temperature = "-1").errors.isNotEmpty())
        assertTrue(LabAudioParameters(language = "English").errors.isNotEmpty())
    }

    @Test
    fun `input modes reject an incompatible selected model`() {
        fun choice(alias: String) = LabModelChoice(alias, alias, "", 0, "", "", false, 512)
        val chat = choice("qwen2.5-0.5b")
        val file = choice("whisper-tiny")
        val mic = choice("nemotron-speech-streaming-en-0.6b")
        assertTrue(LabInputMode.TEXT.accepts(chat))
        assertFalse(LabInputMode.FILE.accepts(chat))
        assertTrue(LabInputMode.FILE.accepts(file))
        assertFalse(LabInputMode.MICROPHONE.accepts(file))
        assertEquals(LabInputMode.MICROPHONE, LabInputMode.forModel(mic))
    }
}
