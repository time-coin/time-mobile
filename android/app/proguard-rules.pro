# Add project specific ProGuard rules here.

# Keep BouncyCastle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Keep Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# Keep OkHttp
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.timecoin.wallet.**$$serializer { *; }
-keepclassmembers class com.timecoin.wallet.** { *** Companion; }
-keepclasseswithmembers class com.timecoin.wallet.** { kotlinx.serialization.KSerializer serializer(...); }

# Keep Room entities
-keep class com.timecoin.wallet.db.** { *; }

# Keep Hilt
-keep class dagger.hilt.** { *; }
-dontwarn dagger.hilt.**
