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

Playback remembers the last working mirror per channel (`tv.mirror.v1`) and
starts there next time; if a start shows no video after a few seconds a
watchdog advances to the next alternative URL (skipped for radio).

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
- `boot()` paints the embedded list first, then merges `/api/store` in the
  background, so a slow server never delays the first render. The flat list
  draws 200 rows and a "+N daha göster" row pages the rest in.
- Settings has an "İndir" card linking to `/download/apk`, `/download/windows-exe`,
  `/download/player` and `/download/source-zip`. Those routes only exist in
  `server.py`, so the card is hidden when `nativePlayer()` is present (APK).
- On the phone, show "TV Player" wherever the source says "hh".

### Native UI (Kotlin)

The launcher is `BottomNavActivity` (see `ui/`). Layouts are:

- `activity_bottom_nav.xml` — `fragmentHost` + `BottomNavigationView`. The nav
  bar uses `bg_nav_bar` (hairline on top), `nav_item_tint` and the
  `NavItemIndicator` pill for the checked item.
- `fragment_channel_list.xml` — fixed header (`bg_header`: brand mark
  `bg_brand_mark` + `ic_brand_mic`, active tab name, search card `bg_search`
  with `ic_search` and a `searchClear` button) over the `ExpandableListView`.
  `listCount` shows the filtered total; `emptyState` is contextual (search
  miss / no favourites / empty list). `ChannelListFragment` fills `headerTitle`
  per tab and toggles `searchClear` from the text watcher.
- `list_group_item.xml` / `list_child_item.xml` — rounded cards
  (`bg_group_card` / `bg_children_card`). The group row has an accent bar
  (`bg_group_accent`), a count pill (`bg_count_pill`) and a chevron rotated by
  `ExpandableChannelAdapter`. The child row has a logo tile (`bg_logo_tile`)
  with a letter fallback and a favourite star.
- `activity_player.xml` — `PlayerView` with `use_controller=false`, so the
  built-in controller never shows. A top scrim (`bg_player_top`) holds back,
  title, group, screen-mode and fullscreen; a bottom scrim
  (`bg_player_bottom`) holds prev / play-pause / next and the status line.

`PlayerActivity` reuses the single `PlaybackController` player, loads the whole
group as a playlist (so Next/Previous change channels) and cycles
FIT/FILL/ZOOM. The fullscreen button toggles the system bars via
`WindowInsetsControllerCompat`.

Widgets work from a cold start: `PlayerService.ensureRadioLoaded()` loads the
radio list itself before handling PLAY/TOGGLE/NEXT/PREV, so a widget tap can
never start a TV stream.

Build: `cd android && ./gradlew :app:assembleRelease` (wrapper is checked in;
CI uses the same command). Do not run `build_apk.py` for the native app — it
only packages the WebView fallback.

Release builds do **not** run R8 (`minifyEnabled false`, `shrinkResources
false`). Enabling it once shipped an APK that installed but crashed on launch on
Android 14; the unshrunk build is the known-good one. `android/app/proguard-rules.pro`
is kept for a future, verified attempt — if R8 is ever turned back on, keep
Media3, NanoHTTPD, the `@JavascriptInterface` bridges and Room (the rules
already do), and test a real launch on a device before shipping. Signing reads
`hh-release.jks` plus `TV_STORE_PASS` / `TV_KEY_PASS` / `TV_KEY_ALIAS` from the
environment (`android/sign_apk.bat` wraps `apksigner`); the keystore and
passwords are never committed, and a build without the env vars falls back to
the debug key. The CI `apk` job restores the key from the `HH_KEYSTORE_B64` /
`HH_STORE_PASS` repository secrets and verifies the certificate afterwards:
every build must share one signature (cert SHA-256
`a5d938073f428f13a73419cc34194b0c8c7ba30aeeca9d64e54a7cb2c40e3a6f`), otherwise
Android refuses to install an update over an existing install.

A working local toolchain lives in the sandbox at `/workspace/tools` (Temurin
JDK 17, Android SDK 34, build-tools 34.0.0). `android/local.properties` points at
it, so `cd android && JAVA_HOME=/workspace/tools/jdk-17 ANDROID_SDK_ROOT=/workspace/tools/android-sdk ./gradlew :app:assembleRelease`
builds an APK locally — no CI round-trip needed. The fixed key is kept outside
the repo at `/workspace/keys/hh-release.jks` (`pass.txt` next to it); copy it to
`android/app/hh-release.jks` and export `TV_STORE_PASS`/`TV_KEY_PASS`/`TV_KEY_ALIAS=hhkey`
before building a signed release.

`server.py` inspects mirrors in the `/api/probe` endpoint with a thread pool
(8 workers, 6 s each, HEAD then a 4 KB GET) and caches healthy results for an
hour. Keep probes bounded — a dead mirror must not stall the whole check.
