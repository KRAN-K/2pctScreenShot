# 2pctScreenShot

**2%ScreenShot** (a.k.a. 2pctScreenShot)

Screenshot and long scrolling-screenshot tool for **Marvel Contest of Champions** (Android 10+).
Made by **KRANK** - 2% Projects.

> Unofficial fan tool. Not affiliated with Kabam, Marvel or LINE.
> The app **never taps, swipes or controls the game**. It only takes pictures of your screen.

## Features
- Floating control panel (drag, collapse, close X) and notification-bar controls (on by default)
- Single screenshot and **Scroll shot**: a coloured frame marks the scroll area, capture it section by section, then stitch
- **Timer shots**: countdown, auto-capture while you scroll yourself, then Done
- Landscape support and sideways (horizontal) scrolling
- Preview popup after saving: Open, Open folder, LINE, Close (can be turned off)
- Themes incl. Transparent, selectable scroll-area colour, ON (green) / OFF (red) switches
- Save folder under `Pictures/`, PNG/JPEG, optional LINE share and optional upload URL
- No third-party libraries

## Install
Download `releases/2pctScreenShot-v1.1.4.apk` and open it on your phone (allow installs from that source).
It is debug-signed, which is fine for sideloading.

## Use
1. Open the app, tap **Start capture bubble**, allow "Display over other apps" and screen capture.
2. Open MCOC and use the floating panel or the notification buttons.
3. **Scroll shot**: drag the handles to fit the scroll area (leave out fixed bars), tap **Capture section**,
   scroll a little (keep some overlap), capture again, then **Done / Finish**.
   Or tap **Timer shots** and just scroll; tap **Done** when finished.

## Build
Open the folder in Android Studio (Gradle sync), or build with the Android SDK command-line tools.
Package `com.twopercent.screenshot`, minSdk 29, targetSdk 34, plain Java.

## Layout
- `app/src/main/java/com/twopercent/screenshot/` - source (`CaptureService`, `Stitcher`, `MainActivity`, ...)
- `releases/` - APK builds
- `docs/` - icon artwork
