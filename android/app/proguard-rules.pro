# Room generates the DAO/Entity implementations by name, and the entities are
# read reflectively by the generated code, so the whole data package is kept.
-keep class com.openhands.tvplayer.data.** { *; }

# Model objects are built from JSON by hand, but keeping them costs nothing and
# protects against a future reflective parser.
-keep class com.openhands.tvplayer.model.** { *; }

# App widget providers are referenced from the manifest only.
-keep class com.openhands.tvplayer.RadioWidget* { *; }

# The local HTTP server is instantiated by name from the WebView bridge.
-keep class com.openhands.tvplayer.LocalServer { *; }
-keep class fi.iki.elonen.** { *; }

# Media3 builds its session/service plumbing reflectively in places.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**
