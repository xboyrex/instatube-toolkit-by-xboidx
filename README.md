# InstaTube Toolkit by XboidX

A community-branded Android app for saving publicly accessible Instagram reels, posts, photos, and carousel media to your device.

## UI refresh (v1.1.0)
- Native Jetpack Compose + Material 3 redesign with a custom adaptive launcher identity.
- Home flow separates link paste, fetch, preview, selection, and save states.
- Carousel items can be selected individually, or all selected/cleared together.
- Library keeps up to 12 recent save records locally (it does not scan all device files).
- Caption Lab lets you edit/copy captions, extract literal hashtags, and convert a trailing brace-wrapped comma-separated keyword list into clearly labelled hashtag suggestions.
- Settings exposes download quality, custom width, appearance, haptics, and the download folder.
- Share a public Instagram link to the app for quick handoff.

## Build APK with GitHub Actions
1. Open **Actions → Build InstaTube Toolkit APK**.
2. Run the workflow if it did not start automatically after the commit.
3. Download the `InstaTube-Toolkit-debug-apk` artifact from a successful run.
4. Extract the artifact ZIP and install the APK on Android. This is a debug-signed APK for testing, not a Play Store release.

## Important limitations
Instagram can change its public webpage response or block requests. Media and caption data are available only when Instagram exposes them to the app. This app does not bypass private/login-gated content, does not extract a separate audio track from video, and does not guarantee every link will work. Download/reuse only content you own or have permission to save.

## Open-source attribution
This project is a modified fork of **InstaDownload** by Orang-Studio. The upstream source is licensed under **GNU GPL v3**; the GPL license and upstream attribution are retained. See `LICENSE` and `NOTICE-XBOIDX.md`. This fork is not affiliated with or endorsed by Instagram/Meta or the upstream maintainers.
