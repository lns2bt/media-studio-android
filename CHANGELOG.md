# Releases

## v0.2

Android's internal version code is now 7.

- Rename the export where the title is shown, in Extractor and Converter. A preview shows the file name the export will get; a custom name replaces the automatic time-range label.
- Clear Extractor or Converter with one tap (✕ in the link field, **Reset** next to the file). A snackbar offers **Undo**. A running download continues; cancel it separately.
- The bottom of the page shows the app, yt-dlp and FFmpeg versions; tap it to open the releases page.
- Sharing a link opens a quick sheet over the sending app: tap **Audio** (MP3) or **Video** (up to 1080p) to download without leaving it, or **More options…** for the full Extractor. The last choice is remembered.
- A finished export posts a notification with **Open**, **Share** and **Edit**. A failed link export offers **Retry**. While the app is on screen, these notifications stay silent.
- Extractor shows the thumbnail, channel and duration of a link, plus its chapters as one-tap time ranges.
- Choose a maximum video quality, and see an estimated download size before starting.
- A start time in the link (`?t=90`, `&t=1m30s`, `#t=1:30`) prefills **From**.
- Progress notifications use the app's own icon and show the link's thumbnail.

## v0.1 — First public release

This is the first GitHub release of Media Studio. It includes the features from the private development builds. Android's internal version code has advanced to 6 so earlier test installations signed with the same key can update in place.

- Extract a complete item or an excerpt from a shared link.
- Open, trim and convert local media with waveform boundaries, fine adjustments and audio preview.
- Keep source codecs, export video as MP4 or WebM, or extract a soundtrack as MP3, AAC, Opus or FLAC.
- Rename, open, share or continue editing an export directly from the result card.
- Launch the link and file workflows from home screen shortcuts.

**Artifact:** ARM64 debug APK, Android 10+. The release also includes its SHA-256 checksum.
