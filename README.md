# Hassasin's Stream

Watch the films, shows and music stored on your phone (or a USB drive plugged into it) on any TV, Fire TV, tablet or other phone on the same Wi-Fi. No server, no account, no cloud: one phone shares, the others watch.

## What it does

- **Share from a phone:** pick the folders to share; the phone serves them over your Wi-Fi.
- **Watch anywhere at home:** Android TV, Fire TV, phones and tablets find the sharing phone by themselves.
- **Streaming-app look:** posters, details and episode lists from TMDB, continue watching, up next.
- **Full quality:** 4K, Dolby Vision, HDR10+, HDR10 and HLG; Dolby Atmos, Dolby Digital Plus and DTS sent untouched to your TV or soundbar.
- **A capable player:** audio and subtitle choices, online subtitles, chapters and skip intro, seek previews, sound tools with an equalizer, picture-in-picture.

## Install

1. Download `HassasinStream.apk` from the [latest release](../../releases/latest).
2. Install it on the phone that has your videos, and on every device you want to watch on (on a Fire TV, use an app such as Downloader to install APK files).
3. On the phone with the videos: open the app, tap **Share this device**, and pick your folders.
4. On the TV or other device: open the app; the sharing phone appears by itself.

Posters and details need a free [TMDB](https://www.themoviedb.org/settings/api) key, and online subtitles a free [OpenSubtitles](https://www.opensubtitles.com/en/consumers) key. Enter them once on the sharing phone (Share this device → API keys); watching devices use them through it. No key is built into the app.

## Requirements

- Sharing: an Android phone running Android 11 or newer.
- Watching: Android TV / Fire TV (Fire OS 7 or newer), or an Android phone or tablet.

## Building from source

Open the project in Android Studio, or run `gradlew assembleDebug` with the JDK that comes with Android Studio. The app is in the `receiver` module; the shared message format is in `protocol`.
