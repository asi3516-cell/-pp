# R8 rules for the release APK.

# Media3 / ExoPlayer is reached reflectively by the media session and by
# playback controller internals; shrinking it breaks audio focus and the
# notification controls at runtime.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# NanoHTTPD runs the embedded local server that serves the WebView assets.
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# @JavascriptInterface methods are called by name from the WebView, so R8 must
# not rename or remove the bridge classes.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.openhands.tvplayer.MainActivity$WidgetBridge { *; }
-keep class com.openhands.tvplayer.MainActivity$PlayerBridge { *; }

# Room generates the *_Impl classes by name.
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# OkHttp / Conscrypt optional deps referenced but not shipped.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
