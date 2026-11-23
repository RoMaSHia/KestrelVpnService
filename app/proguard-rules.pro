# Keep JNI entry points, callbacks, and Kotlinx Serialization models intact under R8
-keep class dev.kestrel.proxy.core.NativeBridge {
    native <methods>;
}
-keepclassmembers class dev.kestrel.proxy.core.NativeBridge {
    *;
}
-keep interface dev.kestrel.proxy.core.SocketProtector {
    public boolean protect(int);
}
-keep class * implements dev.kestrel.proxy.core.SocketProtector {
    public boolean protect(int);
}
-keep class dev.kestrel.proxy.core.CoreInfo { *; }
-keepclassmembers class dev.kestrel.proxy.core.CoreInfo { *; }
-keep class dev.kestrel.proxy.data.model.** { *; }
-keepclassmembers class dev.kestrel.proxy.data.model.** { *; }

# Kotlinx Serialization runtime support
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keep,includedescriptorclasses class dev.kestrel.proxy.**$$serializer { *; }
-keepclassmembers class dev.kestrel.proxy.** {
    *** Companion;
}
-keepclasseswithmembers class dev.kestrel.proxy.** {
    kotlinx.serialization.KSerializer serializer(...);
}
