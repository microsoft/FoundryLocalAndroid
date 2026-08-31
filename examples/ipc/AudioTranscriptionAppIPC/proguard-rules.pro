# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Suppress warnings for kotlinx.parcelize
-dontwarn kotlinx.parcelize.Parcelize

# Keep all Foundry Local SDK classes so release builds do not depend on the
# consumed AAR's consumer-rules.pro being applied in every packaging path.
-keep class com.microsoft.foundrylocal.** { *; }

# Keep parcelable classes and their CREATOR fields
-keep class com.microsoft.foundrylocal.** implements android.os.Parcelable { *; }
-keepclassmembers class com.microsoft.foundrylocal.** implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
