# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Keep data classes used in JSON serialization
-keep class com.github.meetingrecorder.data.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# JNI names are bound by the native Whisper bridge.
-keep class com.github.meetingrecorder.data.WhisperNative { *; }

# sherpa-onnx JNI looks up Kotlin config fields by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
