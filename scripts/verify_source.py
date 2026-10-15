#!/usr/bin/env python3
"""Fast structural checks before spending CI time on Gradle."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
checks = {
    "modern launcher activity exists": ROOT / "InstaDownload/app/src/main/java/com/vakarux/instadownload/ModernHomeActivity.kt",
    "YouTube public metadata resolver exists": ROOT / "InstaDownload/app/src/main/java/com/vakarux/instadownload/YouTubeResolver.kt",
    "manifest exists": ROOT / "InstaDownload/app/src/main/AndroidManifest.xml",
    "Gradle app config exists": ROOT / "InstaDownload/app/build.gradle.kts",
    "launcher adaptive icon exists": ROOT / "InstaDownload/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml",
    "Xboy Rex brand mark exists": ROOT / "InstaDownload/app/src/main/res/drawable-nodpi/instatube_brand_mark.png",
    "adaptive launcher foreground exists": ROOT / "InstaDownload/app/src/main/res/drawable-nodpi/instatube_launcher_foreground.png",
}
errors = []
for label, path in checks.items():
    if path.exists() and path.stat().st_size:
        print(f"PASS: {label}")
    else:
        errors.append(f"FAIL: {label}: missing or empty ({path})")

manifest = (ROOT / "InstaDownload/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
modern = (ROOT / "InstaDownload/app/src/main/java/com/vakarux/instadownload/ModernHomeActivity.kt").read_text(encoding="utf-8")
resolver = (ROOT / "InstaDownload/app/src/main/java/com/vakarux/instadownload/YouTubeResolver.kt").read_text(encoding="utf-8")
gradle = (ROOT / "InstaDownload/app/build.gradle.kts").read_text(encoding="utf-8")
for condition, label in [
    ('.ModernHomeActivity' in manifest and 'android.intent.action.MAIN' in manifest, "launcher points to redesign activity"),
    ('RexCaptionLabScreen' in modern and 'fetchCurrent' in modern, "caption editor and fetch flow exist"),
    ('youtube.com/oembed' in resolver and 'fetchMetadata' in resolver, "YouTube metadata path uses public oEmbed"),
    ('description: String' in resolver and 'fetchTranscript' in resolver, "public description and caption transcript paths exist"),
    ('.navigationBarsPadding().padding(horizontal = 16.dp' in modern and 'RoundedCornerShape(28.dp)' in modern and 'padding(start = 8.dp, end = 8.dp, top = 7.dp, bottom = 6.dp)' in modern, "floating rounded navigation style exists"),
    ('Copy full description' in modern and 'Copy title' in modern, "YouTube title and description copy actions exist"),
    ('saveAudioToMusic' in modern and 'Music/InstaTube' in (ROOT / 'InstaDownload/app/src/main/java/com/vakarux/instadownload/DownloadStorage.kt').read_text(encoding='utf-8'), "Instagram Reel audio saves under Music, not Downloads"),
    ('versionCode = 4' in gradle and 'versionName = "1.4.0"' in gradle, "version bumped to 1.4.0"),
    ('Xboy Rex' in (ROOT / "README.md").read_text(encoding="utf-8"), "Xboy Rex attribution present"),
]:
    if condition:
        print(f"PASS: {label}")
    else:
        errors.append(f"FAIL: {label}")

# Detect two previously relevant crash/build hazards in source.
for bad, label in [
    ('.getItemAt(0)?.getItemAt(0)' , "no invalid nested ClipData item access"),
    ('java.time.Duration.ofSeconds', "no java.time.Duration dependency on minSdk 24"),
    ('HorizontalDivider()', "Divider API compatible with project's Material 3 version"),
]:
    if bad in modern:
        errors.append(f"FAIL: {label}")
    else:
        print(f"PASS: {label}")

if errors:
    print("\n".join(errors), file=sys.stderr)
    sys.exit(1)
print("All structural checks passed. Gradle compilation and device smoke testing are still required.")
