# Klang Audio

A music player for Android in the spirit of Winamp and Droidamp, with a face after Braun
and the Teenage Engineering OP-1: one face plate split into modules by black seams, a dot
speaker grille that is also the visualizer, a black OLED display, a dark red tuning strip and
round OP-1 style keys.

## Features

- Local music library, background playback, notification and lock-screen controls
- 10-band equalizer with preamp, 16 built-in presets, your own presets, Winamp `.eqf` import/export,
  a peak limiter so boosts never clip, and support for outside equalizer apps (Wavelet, JamesDSP…)
- Playlist editing (add / rem / sel / misc / list), `.m3u` import/export, saved lists
- YouTube search, links, playlists and Mixes, Opus audio with AAC fallback, SponsorBlock skipping
- Library: favorites, most played, history, saved lists, bookmarks
- Synced lyrics from LRCLIB
- Skins, share card (square / story), sleep timer, fade between songs, big controls
- Android Auto (browse, search, voice), home-screen turntable widget, in-app update check

## Build

GitHub Actions builds `klang.apk` on every push and publishes it under Releases → "latest".
Locally: Android Studio, or `gradle :app:assembleRelease` with Gradle 8.10 and JDK 17.

### Signing

The release key is **not** in this repository. CI signs with three repository secrets
(Settings → Secrets and variables → Actions):

| Secret | Value |
| --- | --- |
| `KLANG_KEYSTORE_B64` | the keystore file, base64-encoded |
| `KLANG_KEYSTORE_PASSWORD` | keystore and key password |
| `KLANG_KEY_ALIAS` | key alias |

Without them the build still succeeds but the APK is unsigned.

## License

Klang Audio is free software under the GNU General Public License v3.0 or later (see `LICENSE`),
because it uses NewPipe Extractor (GPL-3.0).

Third-party parts:

- NewPipe Extractor — GPL-3.0
- AndroidX Media3 / ExoPlayer, Jetpack Compose — Apache-2.0
- Klang Sans font: Outfit (© The Outfit Project Authors) + IBM Plex Sans Thai (© IBM Corp.),
  merged; SIL Open Font License 1.1 (texts in `app/src/main/assets/licenses/`)
- Lyrics data: LRCLIB (lrclib.net). Skip segments: SponsorBlock (CC BY-NC-SA 4.0)

Playing audio from YouTube is against YouTube's Terms of Service; this app is for personal use
and is not distributed through app stores.
