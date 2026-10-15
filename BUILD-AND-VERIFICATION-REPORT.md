# InstaTube Toolkit v1.4.0 — source audit report

## Included changes
- Floating, inset rounded bottom navigation with translucent/glass styling.
- InstaTube/Xboy Rex branding in the app header and adaptive Android launcher icon resources, including mdpi through xxxhdpi fallbacks.
- YouTube public title, creator, thumbnail, and best-effort description preview.
- Separate **Copy title**, **Copy full description**, and **Copy link** actions; no YouTube keywords section.
- Best-effort public caption-track transcript loader and copy/paste into Caption Studio when YouTube exposes a caption track.
- Public Instagram media save path and an audio-track extraction option for selected video/Reel. Audio output is stored under `Music/InstaTube`, not `Download`.
- Build workflow for uploading a debug APK artifact when pushed to GitHub.

## Local checks completed
- `scripts/verify_source.py`: PASS.
- XML parse of every project XML resource: PASS.
- Icon resources and density fallbacks: present.
- ZIP packaging checks are reported separately in the generated SHA256/verification file.

## Not verified
An APK was **not** produced in this environment. Running `./gradlew --offline --no-daemon assembleDebug` failed before project configuration because the Gradle wrapper tried to fetch Gradle 8.11.1 from `services.gradle.org`, but this environment could not resolve the host (`UnknownHostException`). Kotlin/Android compilation, emulator/device smoke tests, and live Instagram/YouTube integration tests therefore remain unverified.

## Functional limits
- YouTube title/description and transcript availability depend on public page/caption data and may change or be unavailable. The app does not extract protected YouTube video/audio streams. For videos where YouTube permits downloads, use the built-in Download feature in YouTube; alternatively, use a creator-provided direct download.
- Transcript loading uses a public caption track if exposed; this does not transcribe arbitrary audio when captions are unavailable.
- Instagram parsing is limited to publicly accessible media and can be affected by Instagram changes/rate limits. Use only media you own or have permission to save.
- Audio extraction supports source tracks that Android's MP4 muxer can write; unsupported media will return an error instead of a false success.

## Next build step
Push this source to `main` (or a feature branch and merge after review), or upload the ZIP to a GitHub repository, then check **Actions → Build InstaTube Toolkit APK**. Install only the APK from a successful workflow artifact.
