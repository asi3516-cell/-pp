# Room generates the DAO/Entity implementations by name, and the entities are
# read reflectively by the generated code, so the whole data package is kept.
-keep class com.openhands.tvplayer.data.** { *; }

# Model objects are built from JSON by hand, but keeping them costs nothing and
# protects against a future reflective parser.
-keep class com.openhands.tvplayer.model.** { *; }

# App widget providers are referenced from the manifest only.
-keep class com.openhands.tvplayer.RadioWidget* { *; }

# LibVLC loads its JNI layer and media modules reflectively by class name, so
# the whole library has to survive shrinking untouched.
-keep class org.videolan.libvlc.** { *; }
-keep class org.videolan.libvlc.interfaces.** { *; }
-dontwarn org.videolan.libvlc.**

-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**
# VLC's androidx.media dependency pulls in optional renderer classes.
-dontwarn androidx.mediarouter.**
-dontwarn androidx.media.**
