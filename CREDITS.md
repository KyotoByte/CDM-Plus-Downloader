# Credits — CDM+ Downloader

All code in this repository was written from scratch for CDM+. No code, resources,
icons or strings from any other download manager were used.

## Fonts (SIL Open Font License 1.1)
- **Press Start 2P** — Copyright 2012 The Press Start 2P Project Authors (cody@zone38.net).
  Source: https://github.com/google/fonts/tree/main/ofl/pressstart2p — license: `licenses/OFL-PressStart2P.txt`
- **Silkscreen** — Copyright 2001 The Silkscreen Project Authors.
  Source: https://github.com/google/fonts/tree/main/ofl/silkscreen — license: `licenses/OFL-Silkscreen.txt`

Body text uses the device's system sans-serif font (not bundled).

## Art
- App icon, splash glyph, notification icon and every in-app pixel icon: original pixel art
  drawn for CDM+ (defined as pixel grids in code / generated vector XML).

## Libraries (Apache License 2.0)
- AndroidX (Core, Activity, Lifecycle, Compose UI/Foundation/Material3, SplashScreen)
- Kotlin, kotlinx.coroutines, kotlinx.serialization (JetBrains)
- OkHttp (Square)
- **libtorrent4j** (MIT, Alden Torres) with **libtorrent** (BSD-3-Clause, Arvid Norberg): torrents and magnet links
- **NewPipeExtractor** (GPL-3.0, Team NewPipe): YouTube / SoundCloud / Bandcamp / PeerTube extraction.
  It brings nanojson (MIT-style), Mozilla Rhino (MPL-2.0) and protobuf-javalite (BSD-3-Clause).
  Because of NewPipeExtractor, CDM+ as a whole is distributed under GPL-3.0.
- **jsoup** (MIT): link crawler, reading mode
- **ZXing** core + **zxing-android-embedded** (Apache-2.0, JourneyApps): QR scanner
- Android desugar_jdk_libs (GPL-2.0 with Classpath Exception)
- Test only: JUnit 4 (EPL 1.0), Robolectric (MIT), Roborazzi (Apache 2.0)

## Data
- Built-in ad-block list: a short hand-picked list of well-known ad/tracker domains.
- 1.1.1.1 mode block lists are NOT bundled. The app downloads them from their publishers when you switch them on:
  StevenBlack hosts (MIT), AdGuard DNS filter (GPL-3.0), OISD small (oisd.nl terms), EasyList (GPL-3.0 / CC BY-SA 3.0),
  used here as domain lists only.
- Encrypted DNS resolvers you can pick: Cloudflare 1.1.1.1 / 1.1.1.2 / 1.1.1.3, AdGuard DNS, Quad9 (public services, not affiliated).

## Tools used in tests only
- ffmpeg / ffprobe (on the build machine) to create test HLS streams and verify CDM+'s MP4 output. Not bundled.
