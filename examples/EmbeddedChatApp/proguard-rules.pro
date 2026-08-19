# Suppress warnings for kotlinx.parcelize
-dontwarn kotlinx.parcelize.Parcelize

# Keep all Foundry Local SDK classes
-keep class com.microsoft.foundrylocal.** { *; }

# Keep parcelable classes and their CREATOR fields
-keep class com.microsoft.foundrylocal.** implements android.os.Parcelable { *; }
-keepclassmembers class com.microsoft.foundrylocal.** implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
