# InstaTube Toolkit by Xboy Rex

A native Android app built with Kotlin, Jetpack Compose and Material 3, using an iOS-inspired visual language. Maintains the public Instagram media preview/save flow and adds a separate YouTube public-metadata preview.

## v1.2.0 redesign
- Refined grouped layout, compact toolbar, neutral surfaces and four labelled navigation destinations.
- Branding updated to **Built by Xboy Rex**.
- Instagram: public post/Reel/photo/carousel preview, individual carousel selection, save, caption copy and hashtag extraction.
- Caption Studio: isolated editor state, guarded text extraction, copy caption/hashtags/full text. Opening the tab does not start network activity.
- YouTube: public title, creator/channel name and thumbnail through YouTube oEmbed; open the video in YouTube, copy the link or save the source link to the local Library.
- Library: last 30 local app records (Instagram saves and YouTube links); no scan of unrelated device files.
- Settings: theme, quality, custom resolution, haptics, download folder, GitHub/source/license and YouTube creator search links.
- Custom adaptive launcher icon.

## Build APK
1. Open **Actions → Build InstaTube Toolkit APK**.
2. Wait for `build-debug-apk` to finish successfully.
3. Download artifact `InstaTube-Toolkit-debug-apk`; extract the ZIP and install the APK. It is a debug/test build, not a Play Store release.

Local build needs JDK 17 and Android SDK: `cd InstaDownload && ./gradlew assembleDebug`.

## YouTube limitation
YouTube URLs are validated and public metadata is fetched using the official oEmbed interface. This build does not extract or download protected YouTube audio/video streams, does not promise views/duration metadata from oEmbed, and directs playback to YouTube. To fetch more metadata through the YouTube Data API, a developer API key and corresponding configuration are required.

## Instagram limitation
Instagram can change public webpage responses or rate-limit requests. Media and caption information is only available when Instagram exposes it. The app does not bypass private/login-gated content and does not extract separate audio tracks. Download/reuse only content you own or have permission to save.

## Branding links
- GitHub: https://github.com/xboyrex
- Source repository: https://github.com/xboyrex/instatube-toolkit-by-xboidx
- YouTube search for Xboy Rex: https://www.youtube.com/results?search_query=Xboy+Rex (replace with the exact creator-channel URL when available).

## Attribution
Modified fork of InstaDownload by Orang-Studio. The upstream GNU GPL v3 license and attribution are retained in `LICENSE` and `NOTICE-XBOIDX.md`. Not affiliated with or endorsed by Instagram/Meta, YouTube/Google or upstream maintainers.
