# AGENTS.md

Portable Turkish-only IPTV/radio app ("hh" / TV Player). The Android launcher
is a native Kotlin UI; a WebView shell and a local HTTP server still ship as the
web/fallback layer.

## Layout

- `static/` — the web UI: `index.html`, `style.css`, `app.js`, embedded
  `channels-data.js` (`HH_CHANNELS`), `sw.js`, `icons/`, `vendor/hls.min.js`.
- `android/app/src/main/kotlin/` — the native UI: `ui/` screens, `player/`
  ExoPlayer + MediaSessionService, `data/` channel list + Room favourites,
  `model/` the `Channel` type.
- `android/` — Gradle project. `MainActivity.java` is the old WebView shell; it
  is no longer the launcher.
- `dist/` — release artifacts (`TV-Player.apk`).
- `server.py` — serves the app and the APK download endpoints.

## Build

```bash
cd android
export JAVA_HOME=/workspace/tools/jdk-17.0.20.1+1
export ANDROID_SDK_ROOT=/workspace/tools/android-sdk
/workspace/tools/gradle/gradle-8.7/bin/gradle :app:assembleRelease --no-daemon
cp app/build/outputs/apk/release/app-release.apk ../dist/TV-Player.apk
```

`app.js` is written in ES5 and must stay that way; validate with
`node --check static/app.js` and `npx acorn@8 --ecma5 static/app.js`.

## Player architecture

Two players share one UI, chosen at runtime:

- Browser: the `<video>` element plus `hls.js`.
- APK: the native Media3 ExoPlayer. `nativePlayer()` returns the
  `window.AndroidPlayer` bridge when present, and every control path checks it
  first. **Any new playback or widget code must go through this check** — the
  `<video>` element is inert in the APK.

`MainActivity` reports back with `window.tvNativeEvent(type, payload)`
(`state`, `retry`, `stopped`). There are no DOM events from the native player,
so anything that used to listen on `<video>` must also be driven from that
notification (this is how the radio widget is refreshed).

`stageBounds()` scales by `devicePixelRatio`: `getBoundingClientRect` is CSS
pixels while the native View is laid out in physical pixels. Without the scale
the native player opens as a small box.

## Permissions

Only `INTERNET` and `ACCESS_NETWORK_STATE`. No storage permission on any API
level — channel lists are embedded in the APK, not read from disk. Keep it that
way; do not add `READ_*`/`WRITE_*_STORAGE` or `MANAGE_EXTERNAL_STORAGE`.

## Radio widgets

`RadioWidgetBase` + Small/Medium/Large show the playing station with
prev/play/next. They are radio-only: a widget press while a TV channel is up
jumps to the last radio station. They read their state from the
`AndroidWidget` bridge, which the web UI feeds from the native player state.
A widget press cold-starts the activity, so `widgetCmd` queues the command in
`pendingCmd` until the page calls `ready()`.

## UI

- Bottom tab bar (phone): Canlı TV / Radyo / Favoriler, each with a distinct
  icon. Settings open from the gear button as a full pane.
- The channel browser is a tree (accordion): a group expands in place to list
  its channels, opening another group collapses the previous one
  (`state.openGroup`). The flat channel pane is only used for favourites/search
  (`.flat-list`).
- Selecting a channel opens the player full screen (`#app.playing`); the back
  button returns to the list.
- On the phone, show "TV Player" wherever the source says "hh".
