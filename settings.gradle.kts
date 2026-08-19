/*
 * Copyright (c) Microsoft Corporation.
 * Licensed under the MIT License.
 */
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FoundryLocalAndroid"

include(":ApiExplorerApp")
project(":ApiExplorerApp").projectDir = file("examples/ApiExplorerApp")

include(":ChatApp")
project(":ChatApp").projectDir = file("examples/ChatApp")

include(":AudioTranscriptionApp")
project(":AudioTranscriptionApp").projectDir = file("examples/AudioTranscriptionApp")

include(":EmbeddedChatApp")
project(":EmbeddedChatApp").projectDir = file("examples/EmbeddedChatApp")

include(":EmbeddedAudioTranscriptionApp")
project(":EmbeddedAudioTranscriptionApp").projectDir = file("examples/EmbeddedAudioTranscriptionApp")
