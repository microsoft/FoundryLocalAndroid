package com.microsoft.foundrylocal.lab

import com.microsoft.foundrylocal.api.AudioTranscriptionRequest
import com.microsoft.foundrylocal.api.ChatCompletionRequest
import com.microsoft.foundrylocal.api.ChatMessage
import java.util.Locale

enum class LabInputMode(val label: String, val modelHint: String) {
    TEXT("Text", "Choose a chat model to generate a response."),
    FILE("Audio file", "Choose a Whisper model to transcribe a file."),
    MICROPHONE("Microphone", "Choose a Nemotron streaming model to transcribe speech.");

    fun accepts(model: LabModelChoice?): Boolean = when (this) {
        TEXT -> model?.capability == LabModelCapability.CHAT
        FILE -> model?.supportsFileTranscription == true
        MICROPHONE -> model?.supportsLiveTranscription == true
    }

    companion object {
        fun forModel(model: LabModelChoice?): LabInputMode = when {
            model?.supportsLiveTranscription == true -> MICROPHONE
            model?.supportsFileTranscription == true -> FILE
            else -> TEXT
        }
    }
}

/** Blank advanced fields deliberately omit the corresponding SDK option. */
data class LabChatParameters(
    val topP: String = "",
    val topK: String = "",
    val stopSequences: String = "",
    val presencePenalty: String = "",
    val frequencyPenalty: String = ""
) {
    val errors: Map<String, String>
        get() = buildMap {
            if (topP.isNotBlank() && topP.finiteFloat()?.let { it in 0f..1f } != true) {
                put("Top P", "Enter a number between 0 and 1.")
            }
            if (topK.isNotBlank() && topK.trim().toIntOrNull()?.let { it > 0 } != true) {
                put("Top K", "Enter a positive whole number.")
            }
            if (presencePenalty.isNotBlank() && presencePenalty.finiteFloat() == null) {
                put("Presence penalty", "Enter a finite number.")
            }
            if (frequencyPenalty.isNotBlank() && frequencyPenalty.finiteFloat() == null) {
                put("Frequency penalty", "Enter a finite number.")
            }
        }

    fun request(messages: List<ChatMessage>, temperature: Float, maxTokens: Int): ChatCompletionRequest {
        require(errors.isEmpty()) { errors.values.first() }
        return ChatCompletionRequest(
            messages = messages,
            temperature = temperature,
            maxTokens = maxTokens,
            topP = topP.finiteFloat(),
            topK = topK.trim().toIntOrNull(),
            stop = stopSequences.lineSequence().filter(String::isNotBlank).distinct().toList().ifEmpty { null },
            presencePenalty = presencePenalty.finiteFloat(),
            frequencyPenalty = frequencyPenalty.finiteFloat()
        )
    }
}

data class LabAudioParameters(val language: String = "", val temperature: String = "") {
    val errors: Map<String, String>
        get() = buildMap {
            if (language.isNotBlank() && !Regex("[a-zA-Z]{2,3}(-[a-zA-Z]{2,4})?").matches(language.trim())) {
                put("Language", "Use a language code such as en or ur, or leave blank for the model default.")
            }
            if (temperature.isNotBlank() && temperature.finiteFloat()?.let { it >= 0f } != true) {
                put("Temperature", "Enter a finite number of 0 or higher.")
            }
        }

    fun request(path: String): AudioTranscriptionRequest {
        require(errors.isEmpty()) { errors.values.first() }
        return AudioTranscriptionRequest(
            filePath = path,
            language = language.trim().lowercase(Locale.ROOT).ifBlank { null },
            temperature = temperature.finiteFloat()
        )
    }
}

private fun String.finiteFloat(): Float? = trim().toFloatOrNull()?.takeIf(Float::isFinite)
