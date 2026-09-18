package com.microsoft.foundrylocal.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabModelCapabilityTest {
    @Test
    fun `whisper aliases are audio even when task metadata is blank`() {
        val capability = LabModelCapability.classify("", "openai-whisper-tiny-generic-cpu")

        assertEquals(LabModelCapability.AUDIO, capability)
        assertTrue(capability.isRunnableInCurrentBuild)
    }

    @Test
    fun `nemotron streaming aliases are live audio models`() {
        val capability = LabModelCapability.classify("", "nemotron-speech-streaming-en-0.6b-generic-cpu:3")

        assertEquals(LabModelCapability.AUDIO, capability)
        assertTrue(capability.isRunnableInCurrentBuild)
    }

    @Test
    fun `chat and text tasks are runnable`() {
        assertEquals(LabModelCapability.CHAT, LabModelCapability.classify("chat-completion", "phi-4"))
        assertEquals(LabModelCapability.CHAT, LabModelCapability.classify("text-generation", "qwen3"))
        assertTrue(LabModelCapability.CHAT.isRunnableInCurrentBuild)
    }

    @Test
    fun `unknown non-text tasks stay visible but unavailable`() {
        assertEquals(LabModelCapability.OTHER, LabModelCapability.classify("embedding", "embedding-model"))
    }
}
