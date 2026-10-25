# CDM+ Downloader

Fast multi-connection download manager for Android, with a pixel-minimalist UI.

Developer: https://github.com/KyotoByte

Kotlin + Jetpack Compose · package `io.github.kyotobyte.cdmplus` · Android 7.0+ (minSdk 24) · targetSdk 36 · v1.0

> If you had an older test build of CDM+ installed, uninstall it first (the package name changed).

## Download

Get the APK from [Releases](../../releases):

| APK | For |
|---|---|
| `CDM-Plus-Downloader-v1.0-arm64-v8a.apk` | Almost every phone from the last 6+ years. **Pick this one if unsure.** |
| `CDM-Plus-Downloader-v1.0-armeabi-v7a.apk` | Old 32-bit ARM phones |
| `CDM-Plus-Downloader-v1.0-x86_64.apk` | x86_64 tablets / Chromebooks / emulators |
| `CDM-Plus-Downloader-v1.0-x86.apk` | 32-bit x86 devices (torrents not supported there) |
| `CDM-Plus-Downloader-v1.0-universal.apk` | Any device (bigger file, all CPU types inside) |
| `CDM-Plus-Downloader-v1.0-debug.apk` | Debug build for testing/bug reports (debug-signed, separate app) |

## Features

**Download engine**
- Multi-connection segmented downloads (1–32 connections, default 8) with dynamic splitting, so every connection stays busy to the end
- Pause / resume (also after the app is closed or the phone restarts), retry, queue, speed limits
- HLS / m3u8 streams joined into one .mp4 (AES-128 streams the server openly keys are decrypted; DRM is never downloaded)
- Torrents and magnet links (pick files, optional seeding with limits), FTP
- Correct file names, checksums (MD5/SHA-1/SHA-256), zip extract, refresh an expired link

**Grabbers**
- YouTube (video, Shorts, playlists), SoundCloud, Bandcamp, PeerTube (NewPipeExtractor)
- Instagram public posts / reels / carousels and TikTok public posts, with "Extract music"
- MEGA file links and folder links (pick files or select all, folder structure kept, decrypted on the phone). MEGA's own transfer limits apply.

**Browser**
- In-app browser with GRAB (detects downloadable media on the page), tabs, incognito (own cookie jar, wiped on close), ad-block, reading mode, link crawler, password manager (Android Keystore + fingerprint)

**App**
- Native high refresh rate (120 Hz+ where the screen supports it)
- 1.1.1.1 mode: DNS-only VPN with DNS-over-HTTPS and ad/tracker block lists
- Dark / AMOLED / light themes, custom accent, floating speed window, quick-settings tiles, QR scan
- Saves to Downloads/CDM+ or any folder / SD card you pick

## Not included (on purpose)
- DRM-protected streams (Netflix, Prime, etc.)
- Bypassing a site's login or download limits

## Build
```
./gradlew :app:assembleRelease      # needs keystore/signing.properties (not included)
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```
Signing: create `keystore/signing.properties` with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.
Without it the release build is unsigned.

## Layout
- `engine/` — pure Kotlin download engine: segmented/HLS/FTP/torrent downloaders, queue, persistence
- `grab/` — YouTube/Instagram/TikTok/MEGA grabbers
- `service/` — foreground service, MediaStore/SAF publishing, alarms, notifications
- `ui/` — Compose screens and pixel components
- `data/` — settings, browser data, ad-block, password vault
- `vpn/` — 1.1.1.1 mode (DNS packets, DoH resolver, block lists, VpnService)

## License
GNU GPL v3.0 (it includes NewPipeExtractor, GPL-3.0). See LICENSE and CREDITS.md for fonts and libraries.
