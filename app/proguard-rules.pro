# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.ashtonhardy.piratesfilmcove.data.model.** { *; }
-dontwarn okhttp3.**
-dontwarn retrofit2.**

# Gson
-keep class com.google.gson.** { *; }
-keepattributes AnnotationDefault,RuntimeVisibleAnnotations

# Google Mobile Ads (AdMob) — keep the ads SDK classes so the interstitial
# loads and renders correctly in minified release builds.
-keep public class com.google.android.gms.ads.** { public *; }
-dontwarn com.google.android.gms.ads.**
