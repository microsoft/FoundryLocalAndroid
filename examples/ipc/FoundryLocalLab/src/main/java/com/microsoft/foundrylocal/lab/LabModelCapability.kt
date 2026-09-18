package com.microsoft.foundrylocal.lab

enum class LabModelCapability(val label: String) {
    CHAT("Chat"),
    AUDIO("Audio transcription"),
    OTHER("Other task");

    val isRunnableInCurrentBuild: Boolean
        get() = this != OTHER

    val availabilityNote: String
        get() = when (this) {
            CHAT -> "Available in this IPC build"
            AUDIO -> "File and live transcription are available in this IPC build"
            OTHER -> "Catalog visible; no compatible client in this build"
        }

    companion object {
        fun classify(task: String, alias: String): LabModelCapability {
            val normalizedTask = task.trim().lowercase()
            val normalizedAlias = alias.trim().lowercase()
            return when {
                normalizedAlias.contains("whisper") ||
                    normalizedAlias.contains("nemotron") ||
                    listOf("audio", "speech", "transcri").any(normalizedTask::contains) -> AUDIO

                normalizedTask.isBlank() ||
                    listOf("chat", "text", "completion", "generat").any(normalizedTask::contains) -> CHAT

                else -> OTHER
            }
        }
    }
}
