# InstaTube Toolkit v1.3.0 — Design and QA

## Interaction model
- Four persistent, text-labelled bottom destinations: Home, Library, Captions, Settings.
- Home uses an iOS-inspired segmented source picker for Instagram / YouTube.
- Inputs and primary buttons are grouped in one compact surface; fetch is explicit, loading is clear, and errors are human-readable.
- Instagram carousels preview as individual selectable tiles.
- YouTube previews show oEmbed title/author/thumbnail and best-effort public watch-page description, tags and duration, with official open/copy/library actions. Metadata can be unavailable when YouTube restricts or changes its public response.
- Caption Studio is a separate stateless screen with parent-owned saveable text, safe text extraction, copy actions and five caption ideas. Merely entering the tab performs no network or save operation; ideas are not a live like-count ranking.

## Visual system
- Neutral system/dark surfaces; restrained pink/coral accent; round grouped panels and the supplied InstaTube mark.
- Translucent floating capsule navigation with persistent labels and animated selection; Liquid Glass styling is limited to navigation rather than applied to every content panel.
- iOS green ON toggles, neutral OFF toggles, with pink reserved for brand actions.
- Large page titles, subordinate descriptions and compact uppercase section headers.

## QA checklist for the GitHub Actions debug build
1. Cold launch and Android share-to-app with Instagram URL.
2. Fetch a public Reel and a carousel; select/clear individual items and save.
3. Switch between Instagram and YouTube; values remain in their own fields.
4. Fetch public YouTube watch and Shorts links; title, author and thumbnail render; Open/Copy/Library actions work. Private/non-embeddable YouTube links show a useful error.
5. Open Caption Studio repeatedly with empty text, emoji text, hashtags and `{comma, separated keywords}`; switch tabs and back; copy buttons should not crash.
6. Select a public Reel video, extract its audio track to M4A, and confirm no re-encode is used; test photos and silent video report a readable error.
7. Public YouTube watch/Shorts link metadata includes description/tags when exposed; blocked/private links fall back or show an error.
8. Library history persists after process restart and clear-history empties it.
9. System/Light/Dark theme, storage folder and quality options persist.
10. Test landscape, large font size and Android back/navigation.

## Link notes
The exact creator YouTube channel URL was not available in the brief, so the current branded YouTube link opens a YouTube search for “Xboy Rex” rather than assuming a handle.
