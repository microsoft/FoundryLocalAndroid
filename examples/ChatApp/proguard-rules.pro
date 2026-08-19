# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Suppress warnings for kotlinx.parcelize
-dontwarn kotlinx.parcelize.Parcelize

# Keep AIDL interfaces
-keep class com.microsoft.foundrylocal.IFoundryLocalManager { *; }
-keep class com.microsoft.foundrylocal.IFoundryModel { *; }
-keep class com.microsoft.foundrylocal.IFoundryChatCompletionClient { *; }
-keep class com.microsoft.foundrylocal.ICatalog { *; }
-keep class com.microsoft.foundrylocal.IFoundryOperationProgressCallback { *; }

# Keep parcelable classes
-keep class com.microsoft.foundrylocal.** implements android.os.Parcelable { *; }
-keepclassmembers class com.microsoft.foundrylocal.** implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep data classes
-keep class com.microsoft.foundrylocal.datamodels.chat.ChatMessage { *; }
-keep class com.microsoft.foundrylocal.datamodels.chat.ChatCompletion { *; }
-keep class com.microsoft.foundrylocal.datamodels.chat.ChatCompletionRequest { *; }
-keep class com.microsoft.foundrylocal.datamodels.FoundryModelInfo { *; }
-keep class com.microsoft.foundrylocal.datamodels.FLResult { *; }
-keep class com.microsoft.foundrylocal.datamodels.FLError { *; }
