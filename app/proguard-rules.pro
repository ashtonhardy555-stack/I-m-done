# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.ashtonhardy.piratesfilmcove.data.model.** { *; }
-dontwarn okhttp3.**
-dontwarn retrofit2.**

# Gson
-keep class com.google.gson.** { *; }
-keepattributes AnnotationDefault,RuntimeVisibleAnnotations

# Google Mobile Ads (AdMob) — keep rules so banner ads load and render
# correctly in minified release builds. The SDK ships its own consumer-rules,
# but these extra rules guarantee nothing is stripped.
-keep public class com.google.android.gms.ads.** { public *; }
-keep class com.google.android.gms.internal.ads.** { *; }
-keep class com.google.android.gms.ads.AdView { *; }
-keep class com.google.android.gms.ads.AdRequest$Builder { *; }
-keep class com.google.android.gms.ads.AdSize { *; }
-keep class com.google.android.gms.ads.RequestConfiguration$Builder { *; }
-dontwarn com.google.android.gms.ads.**
-dontwarn com.google.android.gms.internal.ads.**

# Keep the AdManager class itself (it's an object so this is belt-and-braces).
-keep class com.ashtonhardy.piratesfilmcove.ui.AdManager { *; }
