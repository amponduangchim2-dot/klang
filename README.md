# Klang

A music player for Android with a Dieter Rams–inspired bento interface.
Every tool has its own tile: a slotted speaker grille that is also the visualizer,
a light clock-style LCD, a glowing tuning scale for seeking, sage push buttons,
a wood cheek and one orange key.

Feature target: parity with Droidamp 0.46 (see the feature map in the design concept).

## Status

**Round 1 (this build)**: local music library, background playback with notification,
play / pause / stop / previous / next, seek on the tuning scale, volume knob,
balance knob, shuffle, repeat (all / one), EQ on/off with the built-in 10-band DSP,
spectrum and oscilloscope visualizer in the grille, elapsed / remaining time,
kbps / kHz / stereo readout, sleep timer (15–90 min, end of song), playlist view.

**Next rounds**: equalizer screen and presets (.eqf), playlist editing
(add / rem / sel / misc / list, .m3u), YouTube search and playback, library sections,
lyrics, skins, cast, share card, widget.

## Build

GitHub Actions builds `klang.apk` on every push and publishes it under
Releases → "latest". To build locally: Android Studio, or `gradle :app:assembleRelease`
with Gradle 8.10 and JDK 17.

## License

GPL-3.0-or-later (later rounds use NewPipeExtractor, which is GPL-3.0).
