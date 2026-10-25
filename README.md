# Teamrowing Display

An Android tablet app that shows the music of a team rowing training session to the participants:
the tempo (strokes per minute) of the current and the next track, title and artist, and the
playback progress.

The trainer plays the music with Spotify on their phone. The tablet only displays what is playing,
reading it from the Spotify Web API with the same Spotify account.

## Features

- **Display:** large tempo, title and artist of the current track, progress bar with position and
  duration, and the next track. Tap the screen to show the controls.
- **Session preparation:** each Spotify playlist is one training session. The app looks up the BPM
  of its tracks ([ReccoBeats](https://reccobeats.com) by Spotify ID, [Deezer](https://www.deezer.com)
  by ISRC as fallback), and the trainer corrects them to the rowing tempo with ÷2, ×2, −1 and +1,
  types a value, or marks a track as "No tempo", e.g. for stretching.
- **Display settings:** shown elements, tempo size, current/next split, color scheme and accent
  color, with a live preview.
- **Tablet mode:** the screen stays on and the system bars are hidden while the app is open.
- Spotify's autoplay tracks after the end of a playlist are not shown as next track.

BPM values and corrections are stored per Spotify track on the device, so a corrected song keeps
its tempo in every session. They are not synced between devices.

## Requirements

- A Spotify Premium account, and a Spotify app registered in the
  [Spotify Developer Dashboard](https://developer.spotify.com/dashboard) (Web API, redirect URI
  `teamrowing-display://callback`). In Development Mode, up to 5 Spotify users can be added there.
- An Android tablet with Android 8.0 (API 26) or newer.
- Turn off Spotify's *Autoplay* on the phone that plays the music (Settings → Playback), so no
  unrelated music plays after a session.

## Development setup

- [mise](https://mise.jdx.dev) provides the JDK (`mise.toml`) and puts the Android SDK tools on the
  `PATH`. Run `mise install` once in the project directory.
- [Android Studio](https://developer.android.com/studio) installs the Android SDK to `~/Android/Sdk`
  and provides the emulator.
- The Spotify client ID goes into `~/.gradle/gradle.properties`, outside the repository:

  ```properties
  teamrowingDisplay.spotifyClientId=<client ID from the Spotify dashboard>
  ```

No client secret is needed: the app logs in with the Authorization Code flow with PKCE.

Build and install a debug version on a running emulator or connected device:

```sh
./gradlew installDebug
```

## Release build

Android requires release APKs to be signed, and updates must be signed with the same key.

1. Create a signing key once, and keep the file and password safe (e.g. in a password manager).
   Without them, an update can only be installed after uninstalling the app, which deletes the
   prepared BPM values and the display settings.

   ```sh
   keytool -genkeypair -v -keystore ~/.android/teamrowing-release.jks \
     -alias teamrowing -keyalg RSA -keysize 4096 -validity 36500
   ```

2. Add the key to `~/.gradle/gradle.properties`:

   ```properties
   teamrowingDisplay.releaseKeystore=~/.android/teamrowing-release.jks
   teamrowingDisplay.releaseKeystorePassword=<keystore password>
   teamrowingDisplay.releaseKeyAlias=teamrowing
   ```

3. Build the APK:

   ```sh
   ./gradlew assembleRelease
   ```

   The signed APK is `app/build/outputs/apk/release/app-release.apk`. Without the signing
   properties, Gradle builds an unsigned `app-release-unsigned.apk`, which can't be installed.

4. Install it on the tablet, either with USB debugging enabled:

   ```sh
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```

   or by copying the APK to the tablet and opening it there, allowing the file manager to install
   unknown apps when asked.

For an update, increase `versionCode` (and `versionName`) in `app/build.gradle.kts` before
building.
