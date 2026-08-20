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

include(":ApiExplorerAppIPC")
project(":ApiExplorerAppIPC").projectDir = file("examples/ipc/ApiExplorerAppIPC")

include(":ChatAppEmbedded")
project(":ChatAppEmbedded").projectDir = file("examples/embedded/ChatAppEmbedded")

include(":AudioTranscriptionAppEmbedded")
project(":AudioTranscriptionAppEmbedded").projectDir =
    file("examples/embedded/AudioTranscriptionAppEmbedded")
