# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.mariocart.app.data.model.** { *; }
-dontwarn okhttp3.**
-dontwarn retrofit2.**

# Gson
-keep class com.google.gson.** { *; }
-keepattributes AnnotationDefault,RuntimeVisibleAnnotations
