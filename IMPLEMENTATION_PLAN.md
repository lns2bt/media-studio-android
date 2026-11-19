# Android Media Extractor — Implementation Plan

## Goal and scope

Build a native Android app for the Pixel 8a that saves media from a shared or pasted link. The user selects which tracks to keep, an optional time range, and the output format. Extraction and processing happen on the phone. The first sources to validate are YouTube, X/Twitter, ARD Mediathek, and ZDF. Other sites supported by the bundled extractor may work through the same interface.

Version 1 targets on-demand, downloadable media. It needs no account system, server, embedded player, or preview. Playlists, live recording, and batch downloads are outside the initial scope. A download action should always refer to one media item; if a link resolves to several items and no single item can be identified, ask for a direct item link.

## User flow and defaults

1. Accept a URL through Android's share menu (`ACTION_SEND`, `text/plain`) or a link field. Read metadata without downloading the entire media file.
2. Display the title and total duration. For a `12:43` item, prefill **From** with `0:00` and **To** with `12:43`. If duration is unknown, leave **To** empty with the hint **Until the end**. A late metadata response must neither overwrite a user's edits nor populate fields for a different URL.
3. Offer **Video with audio**, **Video only**, and **Audio only**. Default to video with audio when both tracks exist. Enable only modes supported by the source. A silent video must produce a file with no audio track, not a muted audio track.
4. Default to **Keep original**. When the user selects **Choose format**, preselect **MP4** for either video mode or **MP3** for audio only. An incompatible source codec is a reason to convert, not a reason to hide the target format.
5. Start the job, show its current stage, and allow cancellation. On success, show the filename and offer **Open** and **Share**.

Accept `m:ss` and `h:mm:ss`. Validate `0 ≤ start < end` when an end is specified and keep both bounds within the known duration. Unchanged full-length bounds mean a normal full download; do not invoke a trimming pass merely because the end field was prefilled.

## Phase 1 — Prove the backend on the Pixel

- Evaluate a maintained Android integration for `yt-dlp` and a compatible FFmpeg build. Verify ARM64 support, native binary loading, packaging size, runtime dependencies (including any JavaScript runtime required for YouTube), license obligations, and an extractor update path before committing to a dependency.
- For each initial source, test one public, non-DRM on-demand link. Record app/backend versions, available audio and video tracks, reported duration, and whether the link exposes direct or segmented streams. Keep expiring stream URLs out of test fixtures.
- On the phone, download a short range from a long item. Inspect output duration, first playable frame, track list, and transferred bytes. Compare with a full download to distinguish a short output file from an actual partial transfer.
- Verify MP3 encoding and H.264/AAC MP4 output with the packaged build, including a source whose codecs require conversion.

**First end-to-end milestone:** Share a YouTube link, see the detected duration, change the end time, select audio-only MP3, observe the conversion stage for a non-MP3 source, and open the result from Downloads.

## Phase 2 — App structure and metadata

Use Kotlin and Jetpack Compose for a single-screen interface. Keep three responsibilities separate:

| Component | Responsibility |
| --- | --- |
| Metadata resolver (`yt-dlp`) | Resolve the submitted web URL to a title, optional duration, and available formats and tracks. Return clear errors for unavailable items. |
| Job planner | Turn metadata and user choices into an immutable job: mode, range, selected streams, output container, track copy or encoding steps, and destination filename. |
| Job runner | Execute download and processing steps, emit progress, handle cancellation, and publish the completed file. Keep this lifecycle independent of the screen. |

Build the link input, metadata summary, time fields, mode and format controls, and job status in that order. Make loading and retry states explicit. Preserve form and job state on rotation; changing a link must invalidate its previous metadata. Pass source URLs and parameters as arguments to the backend rather than interpolating them into shell commands.

## Phase 3 — Track, format, and range handling

| Output | Processing rule |
| --- | --- |
| Keep original | Prefer the source container when compatible and preserve selected audio/video streams without re-encoding. If tracks arrive separately or the source container cannot hold the selected tracks, mux into a compatible container and show the actual output extension. |
| MP4 | Target H.264 video and AAC audio for broad playback compatibility. Copy compatible streams, encode only incompatible ones, and omit audio entirely in video-only mode. |
| MP3 | Prefer an audio-only download. Copy an existing compatible MP3 stream; otherwise encode the selected audio to MP3 using FFmpeg. |

For bounded jobs, first try section retrieval using `yt-dlp` with FFmpeg where the source supports it. Avoid downloading an unused track when a standalone stream is available. Some sources expose only a combined stream; in that case remove the unwanted track locally. If the backend can determine that it needs a full download before trimming, disclose that before proceeding. When transfer size is unknown, do not promise that only the selected bytes will be fetched.

In original mode, preserving the encoded streams takes precedence over a frame-exact video start. Explain near the range controls that stream-copy cuts can align with a nearby keyframe. Do not silently re-encode to hide this limitation.

## Phase 4 — Progress, background work, and storage

- Model stages separately: **Reading metadata**, **Downloading video/audio**, **Merging tracks**, **Converting to MP4/MP3**, **Saving**, then **Completed**, **Failed**, or **Cancelled**. Emit only stages actually executed. Remuxing compatible streams is not conversion.
- Show download progress from the backend when available. For conversion, use FFmpeg's processed media time relative to the expected output duration; if there is no reliable denominator, show the stage without a percentage. Download reaching 100% must not imply that conversion is finished.
- Use an Android-supported user-initiated background execution mechanism appropriate to the target SDK; verify locked-screen behavior on the Pixel. A notification should identify the current stage and allow cancellation.
- Use a private per-job temporary directory. Publish to `Downloads` via `MediaStore` with the correct extension and MIME type. Keep the entry pending until writes finish, handle filename collisions, and return its content URI for opening or sharing.
- On cancellation, stop the active backend process and remove temporary files and incomplete output entries. Handle low storage, network loss, expired media URLs, and unsupported sources with actionable errors. Recover or clean up abandoned jobs after process death.

## Phase 5 — Acceptance on the Pixel 8a

| Scenario | Pass condition |
| --- | --- |
| Share and paste | A representative link from each initial source resolves to the intended single item. Known duration fills the end field; unknown duration leaves it editable and optional. |
| Time fields | Full length, start-only, end-only, and bounded ranges work. Reversed or out-of-bounds input is rejected before downloading. |
| Track selection | Inspect saved streams: video with audio contains both; video only contains no audio; audio only contains no video. |
| Original format | Compatible streams are copied. Merging does not appear as conversion. File extension matches its actual container. |
| Explicit MP4 and MP3 | Compatible input avoids unnecessary encoding; incompatible input triggers visible conversion. Both outputs play on the device. |
| Range efficiency | For at least one direct and one segmented source, record transferred bytes and actual output boundaries. Document source-dependent behavior instead of assuming exact cuts or partial transfers. |
| Lifecycle and cleanup | Rotation does not duplicate a job. Screen lock does not interrupt a supported job. Cancellation during download or conversion leaves no partial file in Downloads. |
| Failure handling | Unavailable media, network interruption, and insufficient space produce a useful message and no falsely completed job. |

After the first end-to-end milestone, complete the original-format and MP4 paths, expand the source matrix, then finish background execution and failure recovery before treating the app as ready for everyday use.
