# Keep native accessor classes and methods used by secp256k1 JNI
-keep class fr.acinq.secp256k1.** { *; }
-keep class fr.acinq.secp256k1.* { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Okio/OkHttp native usages if any
-keep class okio.** { *; }
-keep class okhttp3.** { *; }
