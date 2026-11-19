# Media Studio for Android

A two-part media toolbox for the Pixel 8a: **Extractor** saves media from shared links; **Converter** trims or converts local media without uploading the file.

## Extractor

1. Share a media link as text to **Media Studio**, or paste the URL into Extractor.
2. Check the title and duration. Keep the prefilled bounds for the whole item, or edit the start and end times. For example, `0:30` to `1:00` saves about 30 seconds.
3. Choose video with audio, video only, or audio only. Keep the original format to avoid re-encoding. Choose MP4 for a compatible video file or MP3 for an audio file.
4. Start the download. The current stage distinguishes transferring data from processing it locally. When it finishes, open or share the file from Downloads.

Source formats and stream layouts vary. A section download can still transfer more data than the duration suggests, and lossless cuts can start at a nearby keyframe. The app is intended for on-demand media, not live recordings.

## Converter

1. Open **Converter** and choose a local audio or video document using Android's file picker.
2. Follow the waveform, preview the sound, then drag the start and end markers or enter precise times in the text fields. Video files use the same audio-only preview.
3. Keep the original format or choose a target codec. For a video with sound, turn on **Audio only** to select MP3 and switch the output menu to audio formats.
4. Export to Downloads. The original document is never overwritten. Both destinations share the active export and its cancellation control.

### File-to-file workflow

- Open or share an audio or video file from Android's Files app to send it directly to Converter. Shared web links still open Extractor.
- Tap a trim boundary to refine it. Step the selection in tenths of a second and audition just the start or the end of the clip.
- After an export, rename the file in Downloads, share it, or choose **Continue editing** to open the result as a new converter source. Excerpts include their selected time range in the suggested filename.
- Long-press the app icon for shortcuts to paste a link or pick a local file.

Media Studio keeps a bounded cache of waveform peaks for recently opened, identifiable files. It reads the current file descriptor and matches the file's identity, size and timestamps before reusing the peaks. Streaming providers without a stable identity are scanned on each open. For device timing, use `adb logcat -s StudioWave:D` to compare an initial open with a repeat; messages include elapsed times, not filenames.

## Build and install

The debug build targets ARM64, including the Pixel 8a, and requires Android 10 or newer. Open the project directory in Android Studio, sync Gradle and select the Pixel 8a as the run target. Alternatively, with JDK 17 or 21, Android SDK 36 installed, USB debugging enabled and the device authorized:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The [implementation plan](../IMPLEMENTATION_PLAN.md) contains the original architecture, format rules and device acceptance criteria. See [device checks](../DEVICE_CHECKS.md) for a reproducible first run.

## Backend projects

- [youtubedl-android](https://github.com/yausername/youtubedl-android) supplies the Android runtime and native packaging. Its upstream repository provides the [wrapper license](https://github.com/yausername/youtubedl-android/blob/master/LICENSE) and native build instructions.
- [yt-dlp](https://github.com/yt-dlp/yt-dlp) resolves links, selects streams and retrieves media sections.
- [FFmpeg](https://ffmpeg.org/) handles copying, merging and encoding streams. The licenses of native components depend on the configuration of the bundled build.

For a backend upgrade, update the pinned wrapper version in `app/build.gradle.kts` for **both** artifacts, rebuild and rerun the source matrix. A successful Gradle build alone does not establish provider compatibility.
