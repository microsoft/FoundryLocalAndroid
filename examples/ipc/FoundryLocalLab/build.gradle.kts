plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val androidMinSdk = providers.gradleProperty("android.minSdk").map(String::toInt).get()
val androidCompileSdk = providers.gradleProperty("android.compileSdk").map(String::toInt).get()
val androidTargetSdk = providers.gradleProperty("android.targetSdk").map(String::toInt).get()

android {
    namespace = "com.microsoft.foundrylocal.lab"
    compileSdk = androidCompileSdk

    defaultConfig {
        applicationId = "com.microsoft.foundrylocal.lab"
        minSdk = androidMinSdk
        targetSdk = androidTargetSdk
        versionCode = 4
        versionName = "0.3.1"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Foundry Local IPC SDK AAR. Keep IPC and embedded SDKs in separate APKs.
    implementation(files("libs/foundry-local-ipc-sdk-0.1.6.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
}
