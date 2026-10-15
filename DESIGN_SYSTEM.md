# InstaTube Toolkit v1.2.0 — Design and QA

## Interaction model
- Four persistent, text-labelled bottom destinations: Home, Library, Captions, Settings.
- Home uses an iOS-inspired segmented source picker for Instagram / YouTube.
- Inputs and primary buttons are grouped in one compact surface; fetch is explicit, loading is clear, and errors are human-readable.
- Instagram carousels preview as individual selectable tiles.
- YouTube previews show only metadata returned by public oEmbed (title, author name and thumbnail), with official open/copy/library actions.
- Caption Studio is a separate stateless screen with parent-owned saveable text. Merely entering the tab performs no network or save operation.

## Visual system
- Neutral system/dark surfaces; restrained pink/coral/violet accent; round grouped panels and a custom app mark.
- Platform-like single-purpose buttons and stable bottom navigation with labels.
- Large page titles, subordinate descriptions and compact uppercase section headers.

## QA checklist for the GitHub Actions debug build
1. Cold launch and Android share-to-app with Instagram URL.
2. Fetch a public Reel and a carousel; select/clear individual items and save.
3. Switch between Instagram and YouTube; values remain in their own fields.
4. Fetch public YouTube watch and Shorts links; title, author and thumbnail render; Open/Copy/Library actions work. Private/non-embeddable YouTube links show a useful error.
5. Open Caption Studio repeatedly with empty text, emoji text, hashtags and `{comma, separated keywords}`; switch tabs and back; copy buttons should not crash.
6. Library history persists after process restart and clear-history empties it.
7. System/Light/Dark theme, storage folder and quality options persist.
8. Test landscape, large font size and Android back/navigation.

## Link notes
The exact creator YouTube channel URL was not available in the brief, so the current branded YouTube link opens a YouTube search for “Xboy Rex” rather than assuming a handle.
