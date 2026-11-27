# Slicemark Production ProGuard / R8 Rules
-keepattributes SourceFile,LineNumberTable

# Media3 / ExoPlayer / Transformer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# AndroidX Security Crypto / Tink
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}
-dontwarn com.google.crypto.tink.**

# Coil SVG
-keep class coil.decode.SvgDecoder** { *; }
