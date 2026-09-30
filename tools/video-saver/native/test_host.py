import io
import json
import os
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from host import choose_pair, probe_candidates, read_message, safe_title, save_media, validate_url, verify_av


class HelperTests(unittest.TestCase):
    def test_candidate_probes_are_bounded_parallel_and_deduplicated(self):
        active = maximum = 0
        seen = []
        lock = threading.Lock()
        def probe(url, ffprobe):
            nonlocal active, maximum
            with lock:
                seen.append(url)
                active += 1
                maximum = max(maximum, active)
            time.sleep(.05)
            with lock:
                active -= 1
            return {"url": url}
        base = "https://example.com/episode/"
        candidates = [base + f"video_{i}.m3u8" for i in range(6)] + [base + "video_0.mp4", "https://example.com/other/ad.m3u8"]
        with patch("host.probe_source", side_effect=probe):
            results = probe_candidates(base + "audio.m3u8", candidates, "ffprobe")
        self.assertEqual(len(results), 6)
        self.assertEqual(len(seen), 6)
        self.assertGreater(maximum, 1)
        self.assertLessEqual(maximum, 4)

    def test_duplicate_audio_representations_and_ambiguous_languages(self):
        video = {"url": "https://example.com/episode/video.mp4", "video": True, "audio": False, "duration": 1200, "height": 720, "width": 1280, "bitrate": 1}
        audio = {**video, "url": "https://example.com/episode/audio_en.m3u8", "audio": True, "video": False}
        duplicate = {**audio, "url": "https://example.com/episode/audio_en.mp4"}
        self.assertEqual(choose_pair(video, [audio, duplicate])[1], audio)
        with self.assertRaises(ValueError):
            choose_pair(video, [audio, {**audio, "url": "https://example.com/episode/audio_fr.m3u8"}])
        with self.assertRaises(ValueError):
            choose_pair(video, [{**audio, "duration": 30}])
        with self.assertRaises(ValueError):
            choose_pair(video, [{**audio, "url": "https://example.com/another/audio_en.m3u8"}])

    def test_native_framing(self):
        body = json.dumps({"url": "https://example.com/master.m3u8"}).encode()
        self.assertEqual(read_message(io.BytesIO(struct.pack("<I", len(body)) + body))["url"], "https://example.com/master.m3u8")
        with self.assertRaises(ValueError):
            read_message(io.BytesIO(struct.pack("<I", 1000000)))

    def test_url_and_names(self):
        for url in ["file:///secret", "ftp://example.com/a", "https://user:pass@example.com/a"]:
            with self.assertRaises(ValueError):
                validate_url(url)
        self.assertEqual(safe_title("../CON: 100%"), "_CON_ 100_")
        self.assertEqual(safe_title("NUL"), "_NUL")

    def test_installed_native_launcher(self):
        launcher = Path(__file__).with_name("launch.cmd")
        if os.name != "nt" or not launcher.exists():
            self.skipTest("Installed Windows helper required")
        config = json.loads(Path(__file__).with_name("config.json").read_text(encoding="utf-8-sig"))
        message = json.dumps({"url": "file:///not-allowed"}).encode()
        result = subprocess.run([str(launcher), f"chrome-extension://{config['extension_id']}/"], input=struct.pack("<I", len(message)) + message, capture_output=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Only HTTP(S)", read_message(io.BytesIO(result.stdout))["error"])

    def test_highest_variant_merged_with_audio(self):
        ffmpeg = shutil.which("ffmpeg")
        ffprobe = shutil.which("ffprobe")
        if not ffmpeg or not ffprobe:
            self.skipTest("ffmpeg and ffprobe required")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "episode"
            root.mkdir()
            for label, size in [("low", "160x90"), ("high", "320x180")]:
                subprocess.run([ffmpeg, "-v", "error", "-f", "lavfi", "-i", f"testsrc2=size={size}:rate=10", "-t", "4", "-c:v", "libx264", "-g", "10", "-an", "-f", "hls", "-hls_time", "1", str(root / f"{label}.m3u8")], check=True, capture_output=True)
            subprocess.run([ffmpeg, "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440", "-t", "4", "-c:a", "aac", "-vn", "-f", "hls", "-hls_time", "1", str(root / "audio.m3u8")], check=True, capture_output=True)
            (root / "master.m3u8").write_text(
                '#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",DEFAULT=YES,AUTOSELECT=YES,URI="audio.m3u8"\n'
                '#EXT-X-STREAM-INF:BANDWIDTH=100000,RESOLUTION=160x90,CODECS="avc1.64000b,mp4a.40.2",AUDIO="audio"\nlow.m3u8\n'
                '#EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=320x180,CODECS="avc1.64000c,mp4a.40.2",AUDIO="audio"\nhigh.m3u8\n', encoding="utf-8")
            class QuietHandler(SimpleHTTPRequestHandler):
                def log_message(self, *args): pass
                def do_GET(self):
                    if self.path.endswith(".ts"):
                        time.sleep(.1)
                    super().do_GET()
            server = ThreadingHTTPServer(("127.0.0.1", 0), partial(QuietHandler, directory=directory))
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                config = {"output": str(root / "downloads"), "ffmpeg": ffmpeg, "ffprobe": ffprobe}
                events = []
                base = f"http://127.0.0.1:{server.server_port}/episode/"
                result = save_media({"url": base + "master.m3u8", "title": "Test episode"}, config, events.append)
                verify_av(result, ffprobe)
                probe = subprocess.run([ffprobe, "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=height", "-of", "json", str(result)], check=True, capture_output=True, text=True)
                self.assertEqual(json.loads(probe.stdout)["streams"][0]["height"], 180)
                self.assertTrue(events[-1]["done"])
                timings = {}
                for workers in (1, 8):
                    started = time.monotonic()
                    benchmark = save_media({"url": base + "master.m3u8", "title": "Benchmark"}, {**config, "fragment_workers": workers}, events.append)
                    timings[workers] = time.monotonic() - started
                    verify_av(benchmark, ffprobe)
                print(f"\nLocal HLS benchmark (100ms fragment latency): serial={timings[1]:.2f}s, parallel={timings[8]:.2f}s", flush=True)
                paired = save_media({"url": base + "high.m3u8", "partner": base + "audio.m3u8", "title": "Explicit pair"}, config, events.append)
                verify_av(paired, ffprobe)
                auto = save_media({"url": base + "low.m3u8", "candidates": [base + "high.m3u8", base + "audio.m3u8"], "title": "Automatic pair"}, config, events.append)
                verify_av(auto, ffprobe)
                probe = subprocess.run([ffprobe, "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=height", "-of", "json", str(auto)], check=True, capture_output=True, text=True)
                self.assertEqual(json.loads(probe.stdout)["streams"][0]["height"], 180)
                with self.assertRaises(ValueError):
                    save_media({"url": base + "high.m3u8", "title": "Silent video"}, config, events.append)
            finally:
                server.shutdown()
                server.server_close()
                thread.join()


if __name__ == "__main__":
    unittest.main()
