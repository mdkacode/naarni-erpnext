# kotlinx.serialization — keep generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.naarni.service.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.naarni.service.**$$serializer { *; }
-keepclassmembers class com.naarni.service.** {
    *** Companion;
}

# Keep all @Serializable DTOs (fields + companions) so JSON (de)serialization works under R8.
-keep @kotlinx.serialization.Serializable class com.naarni.service.** { *; }
-keep class com.naarni.service.data.dto.** { *; }

# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
# Keep the Retrofit API interface + its annotated methods (proxied at runtime).
-keep interface com.naarni.service.core.network.FrappeApi { *; }
-keepclassmembers,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keepattributes Signature, RuntimeVisibleAnnotations, AnnotationDefault
-keep class kotlin.coroutines.Continuation

# CameraX + Coil (stamped photo capture + image loading)
-dontwarn androidx.camera.**
-keep class androidx.camera.** { *; }
-dontwarn coil.**
