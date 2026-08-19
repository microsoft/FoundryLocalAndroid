/*
 * Copyright (c) Microsoft Corporation.
 * Licensed under the MIT License.
 */
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val androidMinSdk = providers.gradleProperty("android.minSdk").map(String::toInt).get()
val androidCompileSdk = providers.gradleProperty("android.compileSdk").map(String::toInt).get()
val androidTargetSdk = providers.gradleProperty("android.targetSdk").map(String::toInt).get()

// Load signing properties from the root local.properties (if present)
val localPropsFile = rootProject.file("local.properties")
val localProps = Properties().apply {
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { load(it) }
    }
}
fun localProp(name: String): String? = localProps.getProperty(name)

val releaseKeystorePath = localProp("signing.release.keystorePath")
val releaseStorePassword = localProp("signing.release.storePassword")
val releaseKeyAlias = localProp("signing.release.keyAlias")
val releaseKeyPassword = localProp("signing.release.keyPassword")
val hasReleaseSigning = listOf(releaseKeystorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }
val isReleaseTaskRequested = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
val isTestTaskRequested = gradle.startParameter.taskNames.any { it.contains("test", ignoreCase = true) }

android {
    namespace = "com.microsoft.foundrylocal.chatapp"
    compileSdk = androidCompileSdk

    defaultConfig {
        applicationId = "com.microsoft.foundrylocal.chatapp"
        minSdk = androidMinSdk
        targetSdk = androidTargetSdk
        versionCode = 1
        versionName = "1.0"
    }
    
    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias!!
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Uses default Android Studio debug keystore implicitly
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else if (isReleaseTaskRequested && !isTestTaskRequested) {
                throw GradleException("Missing signing.* properties in root local.properties. Refusing to produce unsigned release APK.")
            }
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // Binary IPC SDK AAR downloaded from GitHub Releases.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    
    // Jetpack Compose BOM
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    
    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
