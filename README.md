# InstaTube Toolkit by XboidX

A community-branded Android app for saving publicly accessible Instagram reels, posts, photos, and carousel media to your device. Includes copy-caption and copy-hashtag actions when caption metadata is available.

## Features
- Paste Instagram post/reel URLs or share links to the app.
- Download directly exposed public reel/video/photo media and carousel items.
- Preview available media before saving.
- Copy caption and hashtags when the public response includes caption text.
- Quick download from Android share menu.
- Light/dark/system theme and download quality settings.
- No API key or third-party download server.

## Build APK with GitHub Actions
1. Create a GitHub repository and upload this folder's contents (the root contains `.github/` and `InstaDownload/`).
2. Open **Actions → Build InstaTube Toolkit APK → Run workflow** or push to `main`.
3. Open the workflow run, then download the `InstaTube-Toolkit-debug-apk` artifact.
4. Extract the artifact ZIP and install the APK on Android. This is a debug-signed APK for testing, not a Play Store release.

## Local build
Requires JDK 17 and Android SDK.
```bash
cd InstaDownload
./gradlew assembleDebug
```
APK output: `InstaDownload/app/build/outputs/apk/debug/app-debug.apk`.

## Important limitations
Instagram can change its private webpage response or block requests. Public media, caption text, carousel entries, and direct audio URLs are only available when Instagram exposes them to the app. This fork does not bypass private/login-gated content, does not extract a separate audio track from a video, and does not guarantee every link will work. Download/reuse only content you own or have permission to save.

## Open-source attribution
This project is a modified fork of **InstaDownload** by Orang-Studio. The upstream source is licensed under **GNU GPL v3**; the GPL license and upstream attribution are retained. See `LICENSE` and `NOTICE-XBOIDX.md`. This fork is not affiliated with or endorsed by Instagram/Meta or the upstream maintainers.
