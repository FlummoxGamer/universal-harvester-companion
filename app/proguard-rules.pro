-keepclassmembers class app.harvester.companion.** {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes SourceFile,LineNumberTable

-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgentHelper
-keep public class * extends android.preference.Preference
-keep public class com.android.vending.licensing.ILicensingService

-keep class app.harvester.companion.UHApp { *; }
-keep class app.harvester.companion.MainActivity { *; }
-keep class app.harvester.companion.SafeModeActivity { *; }
-keep class app.harvester.companion.HomeActivity { *; }
-keep class app.harvester.companion.DownloadService { *; }
-keep class app.harvester.companion.SessionService { *; }
-keep class app.harvester.companion.UpdateReceiver { *; }
-keep class app.harvester.companion.InstallReceiver { *; }

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
-keep class leakcanary.internal.AppWatcherInstaller { *; }
