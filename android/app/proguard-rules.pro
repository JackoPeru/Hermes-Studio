# Hermes Studio V1 — conserva adapter Moshi reflection + modelli
-keep class com.hermes.studio.** { *; }
-keep class com.squareup.moshi.** { *; }
-keepclasseswithmembers class * {
    @com.squareup.moshi.JsonClass <methods>;
}
-keep @com.squareup.moshi.JsonClass class * { *; }
# kotlinx-serialization (update feed)
-keepattributes Signature, InnerClasses, EnclosingMethod
-keep class kotlinx.serialization.** { *; }
-keep class com.hermes.studio.update.** { *; }
