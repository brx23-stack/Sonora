# Independent Music Player

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
- **Play next / Add to queue** from each song's menu
- **Album artwork** in the list and now-playing bar (Android 10+) and in the notification

## Planned next
1. Full queue editing (reorder / remove) and gapless/crossfade options
2. Room-backed storage if playlists grow large
3. Equalizer and sleep timer
4. Spotify playlist/account companion integration (subject to Spotify API eligibility and playback restrictions)
5. Desktop edition using shared domain logic and a desktop-specific playback layer

## Build
1. Install Android Studio with a compatible Android SDK.
2. Open this folder in Android Studio.
3. Allow Gradle sync to finish (Android Studio will offer to create the Gradle wrapper).
4. Connect an Android device or start an emulator.
5. Run the `app` configuration.

The project uses Android Gradle Plugin 8.9.2 and Kotlin 2.0.21. Android Studio may recommend a compatible Gradle/JDK setup during import.

## Permissions
The app requests access to audio files so it can list and play music stored on the device. It does not upload local music.

## GitHub
Create an empty GitHub repository, then upload/push the contents of this folder. Do not commit `local.properties`, signing keys, or secrets.

## Important
This is the first runnable source milestone, not a finished commercial player. Test on a real Android device and add the remaining playlist, library-rescan, accessibility, and edge-case handling before release.

## Continuous integration
A GitHub Actions workflow is included at `.github/workflows/android-build.yml`. After pushing to `main` or `master`, GitHub Actions will attempt a debug build and publish the APK as a workflow artifact if the build succeeds.
