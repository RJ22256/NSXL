# NSXL

A console-style home screen (launcher) for Android phones and handhelds: a row of big game tiles, a round-button dock, a clock/Wi-Fi/battery header and full controller support.

## Features

- **Three themes:** Basic White, Basic Black (pure black, good for OLED screens) and **Live Wallpaper**: pick any video file and it loops silently behind the home screen, with adjustable dimming and blur (blur needs Android 12+).
- **Game row:** your games (and emulators, streaming apps, anything you add) sorted by when you last played them, with an animated selection ring. Up to 12 show on Home; everything else is in **All Software**.
- **Cover art:** the tiles use each app's icon art by default. Press **+** (or long-press a tile) → *Change cover art…* to use any picture as the tile.
- **Dock:** All Software, Messages, Web, Play Store, Album, Music, Controllers (Bluetooth), Display, Settings and Power. Press **+** / long-press a dock button to make it open a different app.
- **Controller support:** D-pad, sticks, A/B/X/Y, L/R, Start (+) and Select (−). There's a *Swap A and B* setting for controllers that feel reversed.
- **Touch works too:** tap to start, long-press for options, swipe the row.
- Sound effects, vibration, a profile picture, 12/24-hour clock, optional battery percentage, landscape lock or auto-rotate, and hidden system bars.

## Getting the APK

Every push to this repo builds the app automatically:

1. Open the **Actions** tab on GitHub and tap the latest **Build APK** run.
2. Scroll to **Artifacts** and download **NSXL-apk** (a zip with the `.apk` inside).
3. Unzip it on your phone and open the `.apk`. Allow "install unknown apps" for your browser or file manager if Android asks.

To build it yourself instead, open the folder in Android Studio and press Run, or run `./gradlew assembleDebug`. The APK ends up in `app/build/outputs/apk/debug/`.

## Making it your Home screen

The first time you open NSXL it offers to set itself as your Home app. You can also do it later from **Settings → System → Home app**, or in Android's own settings under *Apps → Default apps → Home app*.

## Controls

| Button | Home | Lists and menus |
|---|---|---|
| D-pad / stick | Move | Move |
| A | Start / open | Select |
| B | Back to first tile | Back |
| + (Start) | Options for the selected item | Options |
| − (Select) | Launcher settings | |
| Y | All Software | |
| L / R | Jump to first / last tile | Switch tabs in All Software |

## How it's built

- `MainActivity.java`: a full-screen activity with a video layer at the back and a transparent WebView on top. It forwards controller input to the page and handles the file pickers.
- `assets/www/index.html`: the entire home-screen UI (HTML/CSS/JS). Open it in a desktop browser to preview with demo games; arrow keys, Enter, Esc, `+` and `-` act as the controller.
- `LauncherBridge.java`: what the page can ask Android to do (list apps, launch, battery, Wi-Fi, settings…).
- `LocalServer.java`: serves the page plus generated tile art and cover images from `https://launcher.local/`.
- `VideoWallpaperView.java`: the looping, centre-cropped live-wallpaper video.

Requires Android 8.0 (API 26) or newer.
