# Privacy Policy — InstaTube Toolkit by Xboy Rex

Last updated: 2026-10-15.

## What the app collects

The app has no account system, advertising SDK, analytics, or app-operated backend. It does not automatically upload a history database. Recent source links and app preferences are stored locally on the device.

## Network requests

- **Instagram:** When you fetch an Instagram URL, the app contacts Instagram's public web endpoints to request media and any caption metadata Instagram exposes. Requests can be rate-limited or refused by Instagram.
- **YouTube:** When you preview a YouTube video, the app sends the normalized video URL to YouTube's public oEmbed endpoint to request title, creator name, and thumbnail metadata. Thumbnail images are then requested from the URL returned by YouTube.
- **External links:** Tapping Open in YouTube, GitHub, or another source link opens that site in an installed app or browser. That service's own privacy practices then apply.

The app does not send these requests to a private proxy operated by the app publisher.

## On-device data

- App preferences such as theme, quality, download folder, and haptic preference are held in Android app preferences.
- The in-app Library stores up to 30 recent source-link records in local app preferences. It stores each link's title/source and the time it was saved. Clearing Library history deletes these records.
- Downloaded Instagram media is saved to the destination configured by the user (Downloads/InstaDownload by default).
- The Library does not scan unrelated device files.

## Permissions

- **Internet:** request public Instagram media and YouTube metadata/thumbnail resources.
- **Network state:** choose download quality based on Android Data Saver.
- **Storage / folder access:** write files to the chosen destination, depending on Android version.
- **Vibrate:** optional haptic feedback for download actions.

## Platform limitations

The app does not sign into Instagram, bypass private/login-gated posts, or extract protected YouTube audio/video streams. Metadata availability depends on each service. Use and save only content you own or have permission to use.

## Third-party policies

- Meta Privacy Center: https://privacycenter.instagram.com/policy
- Google Privacy Policy: https://policies.google.com/privacy
- YouTube Terms of Service: https://www.youtube.com/t/terms

## Contact

Source code and issue tracker: https://github.com/xboyrex/instatube-toolkit-by-xboidx
