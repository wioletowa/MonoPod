# Monopod

**English** · [Русский](README.ru.md)

An iPod Classic–style music player for Android, driven entirely by the click wheel.

## Features

**Library**
- Music from folders you pick (mp3, flac, m4a, ogg, opus, wav…), tags and covers, `.m3u` playlists.
- iPod menus: Playlists, Artists → Albums → Songs, Albums, Songs, Genres, Cover Flow. Album lists show the cover on the right half.
- Smart playlists (Top 25, Top Rated, Recently Played, Recently Added), play counts, ★★★★★ ratings.
- Optional merging of albums split by artist credits ("A" and "A, B" → one album), in Developer Mode.

**Playing**
- Now Playing: the wheel is volume; the center button cycles scrubbing, rating and lyrics.
- Lyrics from `.lrc` files (UTF-8 or Windows-1251) or tags; synced lyrics follow the song.
- Cover Flow: 3D covers with reflections; the cover spins out to an iPod nano–style playing screen. Turn the phone for full-screen Cover Flow.
- Sound: gapless, crossfade, ReplayGain, iPod EQ presets plus your own, sleep timer.
- Notification, lock screen, headset and Bluetooth buttons.
- Quick settings on a long press of ⏯: shuffle, repeat, sleep timer, crossfade, EQ — and Like / Add to playlist for online music.

**Online music** (Settings → Developer Mode → Online Music turns each one on or off)
- **Spotify** through the official Web API and App Remote SDK: your library and the Spotify home page as Cover Flows. Playback happens in the Spotify app (Premium required).
- **Yandex Music** through its unofficial API (as in [Cassette](https://altlinux.space/rirusha/Cassette)): My Wave, your library, personal mixes. Songs play in Monopod's own player, with EQ, crossfade and lyrics.

**Looks**
- iPod or Material style; Material takes its colors from the album cover or the wallpaper.
- Bodies, accent colors like macOS, screen effects (color LCD, CRT, monochrome), two layouts (Classic and Big Screen), three fonts, dark screen.
- **Custom bodies from theme files**: a CSS-like file (or a `.zip` with body and main-menu pictures). Settings → Appearance → Body → Download Template / + Add. The template, with every field explained in English and Russian, is [`app/src/main/assets/monopod-theme-template.css`](app/src/main/assets/monopod-theme-template.css). Or build one visually in the theme editor, [`web/theme-editor/index.html`](web/theme-editor/index.html): open it in a browser, pick colors and pictures with a live preview, and download the `.css` or `.zip`.
- Stickers on the body, and six look presets (Appearance → Presets) that remember settings and stickers.

## Install

Get the APK from [Releases](../../releases). Android 16 (API 36) or newer.

## Build

JDK 21 and the Android SDK (platform 37). Put the SDK path in `local.properties` (`sdk.dir=...`).

```bash
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`. It is signed with the debug key; put your own signing config in `app/build.gradle.kts` to publish your own builds.

Unit tests (tags, playlists, lyrics, sound, wheel, album merging, theme files):

```bash
./gradlew testDebugUnitTest
```

### Spotify

Monopod has no Spotify app of its own built in: everyone signs in through their own free Spotify developer app (Spotify only lets a development-mode app be used by accounts added to it).

**In the app:** Spotify → Set Up Spotify shows everything to register at [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard) — the redirect URI `monopod://spotify-callback`, the package `io.github.whyoolw.monopod` and the SHA-1 of the installed APK, with copy buttons — and takes the Client ID. In the dashboard tick the Web API and Android, and add your Spotify account under User Management. Sign-in uses PKCE, so there is no client secret.

**In your own build** you can build the Client ID in instead, in `local.properties` (not committed):

```properties
spotify.clientId=0123456789abcdef0123456789abcdef
```

## Note on Yandex Music

The Yandex Music support uses an unofficial API with the official app's client id, like other third-party clients. It is not approved by Yandex, may stop working at any time, and may go against Yandex's terms. Use it at your own risk; you can switch it off in Developer Mode.

## License

[GPL-3.0](LICENSE). Third-party parts and their licenses: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Monopod is not affiliated with Apple, Spotify or Yandex. iPod, Cover Flow and Click Wheel are trademarks of Apple Inc.
