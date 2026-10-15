# InstaTube Toolkit — UI/UX refresh

## Information architecture
- **Home:** explicit URL paste, validation, fetch status, media previews, carousel selection and saving.
- **Library:** up to 12 recent in-app save records stored locally. This is not a file-system scanner.
- **Caption Lab:** editable caption text, copy action and extraction of hashtags literally present in the text.
- **Settings:** quality, custom resolution, haptics, system/light/dark appearance and download folder.

## Visual system
- Obsidian/plum surfaces; neon-pink primary actions; coral/violet gradients for identity and emphasis.
- Rounded surfaces, labelled actions and distinct loading/success/error feedback.
- Four-destination Material 3 bottom navigation on compact screens.
- Adaptive app icon uses a layered media frame and a download/play glyph.

## UX safeguards
- Explicit Fetch media button prevents repeated requests while a link is being typed.
- Carousel entries can be selected individually.
- Caption tool extracts literal hashtags first; if the source ends with a brace-wrapped comma-separated keyword list and has no literal tags, it converts those terms into labelled hashtag suggestions.
- Private/login-gated content and Stories are not represented as supported features.

## Verification
Run `./gradlew --no-daemon assembleDebug` through GitHub Actions, then smoke-test paste/fetch, preview, carousel selection, save, caption actions, library and settings on a device.
