# Keep kotlinx.serialization reflective helpers and generated classes
-keep class kotlinx.serialization.** { *; }
-keep class kotlinx.serialization.internal.** { *; }
-keepattributes *Annotation*

# Keep Retrofit converter classes
-keep class com.jakewharton.retrofit2.kotlinx.serialization.** { *; }

# Keep kotlinx.serialization generated serializers
-keep class **$$serializer { *; }
