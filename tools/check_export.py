"""Inspect a downloaded device export using ffprobe. Requires Python 3.10+."""

import argparse
import json
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("file")
    parser.add_argument("--mode", choices=("both", "video", "audio"), required=True)
    formats = {
        "mp4": ("mp4", {"video": "h264", "audio": "aac"}),
        "mp3": ("mp3", {"audio": "mp3"}),
        "aac": ("m4a", {"audio": "aac"}),
        "opus": ("ogg", {"audio": "opus"}),
        "flac": ("flac", {"audio": "flac"}),
        "vp9": ("webm", {"video": "vp9", "audio": "opus"}),
    }
    parser.add_argument("--format", choices=("original", *formats), default="original")
    parser.add_argument("--seconds", type=float, help="Expected output duration")
    parser.add_argument("--tolerance", type=float, default=2.0, help="Allowed duration error in seconds")
    args = parser.parse_args()
    result = subprocess.run(
        ["ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", args.file],
        capture_output=True, text=True, check=True,
    )
    media = json.loads(result.stdout)
    streams = [
        s for s in media["streams"]
        if s["codec_type"] in ("audio", "video") and not s.get("disposition", {}).get("attached_pic")
    ]
    actual = sorted(s["codec_type"] for s in streams)
    expected = ["audio", "video"] if args.mode == "both" else [args.mode]
    errors = []
    if actual != expected:
        errors.append(f"Expected {expected} tracks, found {actual}")
    container = media["format"]["format_name"].split(",")
    if args.format in formats:
        expected_container, codecs = formats[args.format]
        if expected_container not in container:
            errors.append(f"Expected {expected_container} container, found {container}")
        for stream in streams:
            if stream["codec_name"] != codecs.get(stream["codec_type"]):
                errors.append(f"Unexpected {stream['codec_type']} codec for {args.format}: {stream['codec_name']}")
    duration = media["format"].get("duration")
    if args.seconds is not None and (duration is None or abs(float(duration) - args.seconds) > args.tolerance):
        errors.append(f"Expected {args.seconds}s ± {args.tolerance}s, found {duration}s")
    print(f"{args.file}: {container}, {actual}, {duration}s")
    for message in errors:
        print(message, file=sys.stderr)
    return int(bool(errors))


if __name__ == "__main__":
    sys.exit(main())
