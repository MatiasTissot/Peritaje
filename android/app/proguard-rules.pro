# kotlinx.serialization: conservar serializadores generados
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class es.peritaje.app.data.red.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
# Retrofit
-keepattributes Signature, Exceptions
-keep,allowobfuscation interface es.peritaje.app.data.red.PcApi
