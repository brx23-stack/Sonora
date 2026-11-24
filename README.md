# Sonora

An Android-first, offline-capable local music player starter project. The project is intentionally independent of JARVIS.

## Features
- Kotlin + Jetpack Compose interface (dark, glass-inspired)
- Local audio discovery through Android MediaStore (rescans on every app resume)
- Playback through AndroidX Media3 ExoPlayer, hosted in a `MediaSessionService`
  (background playback, notification and lock-screen controls, audio focus,
  pause when headphones are unplugged)
- Play/pause, next/previous, **seek bar**, **shuffle**, **repeat (off / all / one)**
- **Search** by title, artist or album
- **Favourites**, **playlists** (create, add, remove, delete) and **recently played**,
  stored locally on the device
- **Play next / Add to queue** from each song's menu, plus a **queue screen** to reorder, remove, jump to a song or clear what's upcoming
- **Equalizer** with presets, per-band sliders and bass boost (the Tune button at the top)
- **Add files from storage to a playlist** (the + button inside a playlist)
- **Native playback of Opus, M4A/AAC, OGG, FLAC, WAV and MP3** (including voice notes and recordings)
- **Convert to MP3** (128–320 kbps) from your library or any picked file; results are saved to `Music/Sonora`
- **Immersive Now Playing**: big cover art on top; scroll down for the player controls, the visualizer and a lyrics card
- **Artwork themes**: the Now Playing background, glows and accent colours are taken from the current album art and fade smoothly when the track changes. Text and accent colours are kept at WCAG AA contrast (4.5:1); tracks without artwork use a built-in indigo theme
- **Real-time audio visualizer**: spectrum bars, waveform or circular style, driven by the audio that is actually playing (no microphone permission). Switch it on or off and pick the style on the Now Playing screen; it only runs while visible and playing
- **Live lyrics**: synchronized, scrolling lyrics (tap a line to jump to it). Look them up once online (LRCLIB) or import a `.lrc` file; they are stored on the phone and work offline
- **Media notification and lock screen**: title, artist, album art, previous / play-pause / next and a progress bar (Android 13+) in the notification shade and on the lock screen, all from one Media3 notification. Tapping it opens Now Playing. Playback and controls keep working with the app minimized or the screen off
- **Sleep timer** (15–90 min) that keeps working even if the app is closed
- Unplayable or missing files are skipped instead of stalling playback
- **Album artwork** in the list and now-playing bar (Android 10+) and in the notification

## Planned next
1. Queue persistence, crossfade, ReplayGain and more playback-engine options
2. Room-backed storage if playlists grow large
3. Spotify playlist/account companion integration (subject to Spotify API eligibility and playback restrictions)
4. Desktop edition using shared domain logic and a desktop-specific playback layer

## Build
1. Install Android Studio with a compatible Android SDK.
2. Open this folder in Android Studio.
3. Allow Gradle sync to finish (Android Studio will offer to create the Gradle wrapper).
4. Connect an Android device or start an emulator.
5. Run the `app` configuration.

The project uses Android Gradle Plugin 8.9.2 and Kotlin 2.0.21. Android Studio may recommend a compatible Gradle/JDK setup during import.

## Permissions
The app requests access to audio files so it can list and play music stored on the device. It does not upload local music.

## Privacy
Sonora works offline. The only network access is the optional lyrics lookup, which sends the cleaned song title to lrclib.net when you tap **Find lyrics online**.

## Updating without losing data
Builds are signed with the fixed debug key in `app/debug.keystore`, so a new APK installs over the old one as an update and keeps playlists and favourites. (It is a debug key, so don't use it for the Play Store.)

## GitHub
Create an empty GitHub repository, then upload/push the contents of this folder. Do not commit `local.properties`, signing keys, or secrets.

## Important
This is the first runnable source milestone, not a finished commercial player. Test on a real Android device and add the remaining playlist, library-rescan, accessibility, and edge-case handling before release.

## Continuous integration
A GitHub Actions workflow is included at `.github/workflows/android-build.yml`. Run it from the **Actions** tab (or push to `main` / `master`) to build a debug APK. When the build succeeds, the APK is published as `sonora-debug.apk` under **Releases** (tag `latest`).

A second workflow, `.github/workflows/main.yml` ("Replace with new version"), replaces the project files with the contents of a `sonora.zip` uploaded to the repo root. The zip must contain a single top-level `sonora` folder. Pushes made by this workflow don't trigger the build automatically, so run **Android debug build** manually afterwards.
