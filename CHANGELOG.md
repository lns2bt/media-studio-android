# Releases

## v0.3 — yt-dlp updates and clearer errors

Android's internal version code is now 8, so earlier installations update in place.

- **Update yt-dlp** at the bottom of the page downloads the latest stable yt-dlp from GitHub when you tap it. Media Studio never checks or updates on its own. The version line shows the yt-dlp in use, and the update is unavailable while an export runs.
- Failures are explained in plain words: private, removed, age-restricted, members-only or region-locked videos, unsupported sites, no connection, full storage, and site changes. **Details** shows the original message. Where a newer yt-dlp often helps, the message offers **Update yt-dlp**.
- In-app messages appear as snackbars instead of toasts.
- Before Android asks for notification permission, a short note explains what the notifications are for. **Not now** keeps the app from asking again.
- CI uses `actions/setup-java@v5`.

**Artifact:** ARM64 debug APK, Android 10+. The release also includes its SHA-256 checksum.

## v0.2 — Share sheet, notifications and richer links

Android's internal version code is now 7, so v0.1 installations update in place.

- Sharing a link opens a quick sheet over the sending app: tap **Audio** (MP3) or **Video** (up to 1080p) to download without leaving it, or **More options…** for the full Extractor. The last choice is remembered.
- A finished export posts a notification with **Open**, **Share** and **Edit**. A failed link export offers **Retry**. While the app is on screen, these notifications stay silent.
- Extractor shows the thumbnail, channel and duration of a link, plus its chapters as one-tap time ranges.
- Choose a maximum video quality, and see an estimated download size before starting.
- A start time in the link (`?t=90`, `&t=1m30s`, `#t=1:30`) prefills **From**.
- Rename the export where the title is shown, in Extractor and Converter. A preview shows the file name the export will get; a custom name replaces the automatic time-range label.
- Clear Extractor or Converter with one tap (✕ in the link field, **Reset** next to the file). A snackbar offers **Undo**. A running download continues; cancel it separately.
- Progress notifications use the app's own icon and show the link's thumbnail.
- The bottom of the page shows the app, yt-dlp and FFmpeg versions; tap it to open the releases page.

**Artifact:** ARM64 debug APK, Android 10+. The release also includes its SHA-256 checksum.

## v0.1 — First public release

This is the first GitHub release of Media Studio. It includes the features from the private development builds. Android's internal version code has advanced to 6 so earlier test installations signed with the same key can update in place.

- Extract a complete item or an excerpt from a shared link.
- Open, trim and convert local media with waveform boundaries, fine adjustments and audio preview.
- Keep source codecs, export video as MP4 or WebM, or extract a soundtrack as MP3, AAC, Opus or FLAC.
- Rename, open, share or continue editing an export directly from the result card.
- Launch the link and file workflows from home screen shortcuts.

**Artifact:** ARM64 debug APK, Android 10+. The release also includes its SHA-256 checksum.
