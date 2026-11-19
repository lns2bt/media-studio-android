"""Serve a deterministic audio/video source to an Android device via adb reverse."""

from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from subprocess import run
from tempfile import TemporaryDirectory


def main():
    with TemporaryDirectory(prefix="extractor-fixture-") as work:
        target = Path(work, "fixture.webm")
        run([
            "ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=15",
            "-f", "lavfi", "-i", "sine=frequency=440", "-t", "16", "-c:v", "libvpx-vp9",
            "-deadline", "realtime", "-cpu-used", "8", "-g", "15", "-c:a", "libopus", str(target)
        ], check=True)
        with ThreadingHTTPServer(("127.0.0.1", 8765), partial(SimpleHTTPRequestHandler, directory=work)) as server:
            print("Device URL after adb reverse tcp:8765 tcp:8765: http://127.0.0.1:8765/fixture.webm", flush=True)
            server.serve_forever()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
