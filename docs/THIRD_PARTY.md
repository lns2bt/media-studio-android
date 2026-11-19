# Third-party components

Media Studio's own source is licensed under [GPL-3.0-only](../LICENSE). The app uses the following independently maintained projects; their code and license notices remain those of their respective authors.

| Component | Role | Upstream source |
| --- | --- | --- |
| youtubedl-android 0.18.1 (`library` and `ffmpeg` AARs) | Android wrappers, packaged Python, QuickJS, yt-dlp, FFmpeg and FFprobe | [Repository](https://github.com/yausername/youtubedl-android), [releases](https://github.com/yausername/youtubedl-android/releases), [published artifacts](https://repo.maven.apache.org/maven2/io/github/junkfood02/youtubedl-android/) |
| yt-dlp | Link extraction and retrieval | [Source and license information](https://github.com/yt-dlp/yt-dlp#license) |
| FFmpeg and codec libraries | Local stream analysis, trimming, muxing and encoding | [FFmpeg source](https://ffmpeg.org/download.html), [licensing by build configuration](https://ffmpeg.org/legal.html), [wrapper's native build instructions](https://github.com/yausername/youtubedl-android/blob/master/BUILD_FFMPEG.md) |
| Kotlin, coroutines and AndroidX / Jetpack Compose | Android runtime and interface | [Kotlin](https://github.com/JetBrains/kotlin), [coroutines](https://github.com/Kotlin/kotlinx.coroutines), [AndroidX](https://github.com/androidx/androidx) |

The app builds against the published native artifacts, not a locally customized FFmpeg build. Exact direct dependency versions are in [`app/build.gradle.kts`](../app/build.gradle.kts).
