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

### Native UI (Kotlin)

The launcher is `BottomNavActivity` (see `ui/`). Layouts are:

- `activity_bottom_nav.xml` — `fragmentHost` + `BottomNavigationView`. The nav
  bar uses `bg_nav_bar` (hairline on top), `nav_item_tint` and the
  `NavItemIndicator` pill for the checked item.
- `fragment_channel_list.xml` — fixed header (`bg_header`: brand mark
  `bg_brand_mark` + `ic_brand_tv`, active tab name, search card `bg_search`
  with `ic_search` and a `searchClear` button) over the `ExpandableListView`.
  `listCount` shows the filtered total; `emptyState` is contextual (search
  miss / no favourites / empty list). `ChannelListFragment` fills `headerTitle`
  per tab and toggles `searchClear` from the text watcher. The bottom bar
  (`bottom_nav_menu.xml`) has four tabs: Canlı TV, Radyo, Listeler (`MODE_ALL`,
  live + radio grouped) and Favoriler.
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
FIT/FILL/ZOOM. It resolves that playlist through `UserChannelStore` — not
`ChannelRepository` — so a channel the user added or edited is the one that
plays, and it receives the tapped channel by URL (`EXTRA_URL`) rather than by
index. The activity opens straight into fullscreen and auto-hides the top and
bottom scrims after 4s; tapping the picture toggles them back. The fullscreen
button toggles the system bars via `WindowInsetsControllerCompat`.

Widgets work from a cold start: `PlayerService.ensureRadioLoaded()` loads the
radio list itself before handling PLAY/TOGGLE/NEXT/PREV, so a widget tap can
never start a TV stream.

TV boxes: the manifest declares touchscreen and leanback as optional and the
launcher activity carries `LEANBACK_LAUNCHER`, so the app installs and shows up
on a box/Android TV home screen. Remote control works through `dispatchKeyEvent`
in `PlayerActivity` (D-pad left/right and media keys change channel, centre and
play/pause toggle, back exits) and through focusable list rows: the channel
adapter needs `listView.setItemsCanFocus(true)` plus `focusable`/`clickable`
row cards, otherwise the D-pad stops on the list itself and never reaches a
row. The channel row's click listener lives on `rowCard`, not the outer row, so
touch selection keeps working.

Do not put `setArtworkUri` on `MediaItem` metadata. The legacy MediaSession stub
scales artwork bitmaps on the main thread while building `MediaMetadata`, which
blocks input long enough to trigger an ANR ("Input dispatching timed out") on
the first channel change. Titles and artists are enough for the notification;
logos are drawn by Glide in the UI.

Build: `cd android && ./gradlew :app:assembleRelease` (wrapper is checked in;
CI uses the same command). Do not run `build_apk.py` for the native app — it
only packages the WebView fallback.

Desktop build: `python build/make.py` produces `dist/hh` (Linux) or
`dist/hh.exe` (Windows) and `build_exe.bat` is the double-click Windows path;
`python build/package_source.py` refreshes `build/pkg/hh-Kaynak.zip`.
