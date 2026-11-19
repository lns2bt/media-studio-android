# Pixel 8a device checks

These are manual acceptance checks, not a record of passing device tests. Record the app build, Android version, extractor version, source and date alongside results. Use public on-demand items available from the device's location. Source availability changes over time.

**Emulator result, 2026-11-19:** Native x86_64 debug build on Android 36 accepted the shared fixture URL, displayed its title, and published all four exports to Downloads. The direct file did not advertise a duration. Independent inspection found a 16.008 s original WebM with audio and video, a 16.007 s H.264/AAC MP4, a 16.000 s MP3, and a 4.994 s MP3 for the requested `0:03`–`0:08` excerpt. The ARM64 build launched on the emulator but its translation layer failed to load a native library; testing on physical ARM64 hardware and public providers is still outstanding.

| Case | Steps | Expected result |
| --- | --- | --- |
| First export | Share a YouTube URL. Set the range to `0:10`–`0:25`, choose audio only and MP3. | Duration is initially filled in. For a non-MP3 source, conversion is visible. A playable audio file of approximately 15 seconds appears in Downloads. |
| Source coverage | Repeat metadata and excerpt download with one X/Twitter, ARD and ZDF link each. | Correct single item; unsupported or unavailable items show a useful error. Note any source-specific failure. |
| Original tracks | Export each track mode with original format selected. | Result has exactly the requested stream types. Merging or copying is not reported as conversion. |
| Video compatibility | Choose MP4 with and without sound using a source with incompatible codecs. | Output opens on the phone. Video-only output has no audio track. |
| Lifecycle | Rotate during metadata loading; then start a download, lock the screen, unlock and reopen the app. | No duplicate job; progress still belongs to the original item. |
| Cancellation | Cancel during download and again during conversion on a sufficiently long item. | Status becomes cancelled, the notification goes away, no incomplete visible download remains. |
| Input errors | Try an unavailable link, reversed bounds and a timestamp beyond the known duration. | Meaningful error; invalid bounds do not launch a job. |

For independent stream verification, copy a saved output to a workstation and inspect it:

```sh
ffprobe -v error -show_entries stream=codec_type,codec_name:format=format_name,duration -of json output.mp4
```

To assert the expected streams, container, codecs and duration in one step:

```sh
python3 tools/check_export.py output.mp3 --mode audio --format mp3 --seconds 15
```

For partial-transfer testing, compare device app-data usage for an excerpt and the full item over the same network. Use the same track selection and avoid other concurrent downloads. Record the actual bytes and cut boundaries rather than assuming that the shorter output proves a partial transfer.

### Optional controlled source over USB

To isolate media processing from changing public sites, run `python3 tools/serve_fixture.py` on a workstation with FFmpeg. It generates a 16-second VP9/Opus WebM with a test pattern and a tone, then serves it on loopback. In another terminal, run `adb reverse tcp:8765 tcp:8765` and paste `http://127.0.0.1:8765/fixture.webm` into the app. Direct-file metadata can omit duration or codecs, unlike a provider-specific extractor.

Try a full original export, an MP4 video export and an MP3 audio export. Both explicit formats require an actual encoder for this source. Use `tools/check_export.py` on the exported files. The fixture server is for functional checks, not measuring partial transfer efficiency. Stop it with Ctrl+C and run `adb reverse --remove tcp:8765` when finished.

### Local converter checks

**0.2.0 acceptance, Android 36 x86_64:** Dragging the two markers selected
0:00.855–0:05.136 of a video. Enabling Audio only selected MP3; the exported
file had one MP3 stream, no video, and a duration of 4.281 seconds. A full
original-format export of an 8-second WAV matched the source SHA-256.
The waveform reflected the volume increase at four seconds. The first cold
import took over two minutes on this emulator; Pixel timing is not measured.

Use a local video with sound, an audio-only file and a silent video. Include a recording with alternating sound and silence so a uniform or decorative waveform cannot pass.

- Open each through Android's document picker. Check the duration, source name, audio preview and stream-appropriate formats; a silent video should still be exportable.
- Drag each waveform boundary, then edit each text field. Check that both representations agree, the range stays valid and preview playback stops at the end boundary. Rotate with a file selected.
- Select audio only on the video; check that MP3 is selected. Export a short excerpt, pull the result from Downloads and verify that only an audio stream exists and its duration matches the selection.
- Test one original-format export and one explicit video conversion. Check that the source is unchanged, that progress is shared when switching destinations, and that cancellation removes incomplete output.
