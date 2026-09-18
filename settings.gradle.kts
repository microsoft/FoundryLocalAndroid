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

include(":ChatAppIPC")
project(":ChatAppIPC").projectDir = file("examples/ipc/ChatAppIPC")

include(":AudioTranscriptionAppIPC")
project(":AudioTranscriptionAppIPC").projectDir =
    file("examples/ipc/AudioTranscriptionAppIPC")

include(":FoundryLocalLab")
project(":FoundryLocalLab").projectDir = file("examples/ipc/FoundryLocalLab")

include(":ChatAppEmbedded")
project(":ChatAppEmbedded").projectDir = file("examples/embedded/ChatAppEmbedded")

include(":AudioTranscriptionAppEmbedded")
project(":AudioTranscriptionAppEmbedded").projectDir =
    file("examples/embedded/AudioTranscriptionAppEmbedded")
