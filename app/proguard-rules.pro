-keepclassmembers class app.harvester.companion.** {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses

-keep class org.mozilla.geckoview.** { *; }
-dontwarn org.mozilla.geckoview.**
-keep class org.mozilla.gecko.** { *; }
-dontwarn org.mozilla.gecko.**

-keep class mozilla.components.** { *; }
-dontwarn mozilla.components.**

-keep class com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**

-keep class androidx.media3.** { *; }

-keep class coil.** { *; }

-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

-keep class leakcanary.** { *; }
-keep class shark.** { *; }
-dontwarn leakcanary.**
-dontwarn shark.**
-keep class com.squareup.leakcanary.** { *; }
