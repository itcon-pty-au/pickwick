"""Native messaging bridge; stdout is reserved for framed JSON messages."""
import json
from concurrent.futures import ThreadPoolExecutor
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import time
import threading
from urllib.parse import urlsplit
import uuid

_message_lock = threading.Lock()


def read_message(stream):
    header = stream.read(4)
    if len(header) != 4:
        raise ValueError("Missing native message")
    size = struct.unpack("<I", header)[0]
    if not 0 < size <= 524288:
        raise ValueError("Message too large")
    data = stream.read(size)
    if len(data) != size:
        raise ValueError("Incomplete native message")
    return json.loads(data)


def send(message):
    data = json.dumps(message).encode("utf-8")
    with _message_lock:
        sys.stdout.buffer.write(struct.pack("<I", len(data)) + data)
        sys.stdout.buffer.flush()


def validate_url(value):
    if not isinstance(value, str) or len(value) > 16384:
        raise ValueError("Invalid media URL")
    parsed = urlsplit(value)
    if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("Only HTTP(S) media URLs without embedded credentials are supported")
    return value


def safe_title(value):
    value = re.sub(r'[<>:"/\\|?*\x00-\x1f%]', "_", str(value))[:120].strip(" .") or "video"
    if re.match(r"^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)", value, re.I):
        value = "_" + value
    return value


def verify_av(path, ffprobe):
    result = subprocess.run(
        [ffprobe, "-v", "error", "-show_entries", "stream=codec_type", "-of", "json", str(path)],
        capture_output=True, text=True, timeout=60, check=True,
        creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
    )
    types = {stream.get("codec_type") for stream in json.loads(result.stdout).get("streams", [])}
    if not {"audio", "video"}.issubset(types):
        raise ValueError("The output is missing audio or video. Choose the matching companion feed and retry.")


def probe_source(url, ffprobe):
    result = subprocess.run(
        [ffprobe, "-v", "error", "-rw_timeout", "15000000", "-protocol_whitelist", "http,https,tcp,tls",
         "-show_entries", "stream=codec_type,width,height,bit_rate:format=duration", "-of", "json", url],
        capture_output=True, text=True, timeout=35, check=True,
        creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
    )
    data = json.loads(result.stdout)
    streams = data.get("streams", [])
    types = {item.get("codec_type") for item in streams}
    video = max((item for item in streams if item.get("codec_type") == "video"), key=lambda item: item.get("height", 0), default={})
    return {
        "url": url, "video": "video" in types, "audio": "audio" in types,
        "height": video.get("height", 0), "width": video.get("width", 0),
        "bitrate": int(video.get("bit_rate") or 0),
        "duration": float(data.get("format", {}).get("duration") or 0),
    }


def same_collection(left, right):
    a, b = urlsplit(left), urlsplit(right)
    folder = a.path.rsplit("/", 1)[0]
    return bool(folder and folder != "/" and a.netloc == b.netloc and folder == b.path.rsplit("/", 1)[0])


def choose_pair(selected, candidates):
    # Duration alone cannot establish identity. Require the same asset directory too.
    def matches(item):
        duration = selected["duration"]
        return duration > 0 and item["duration"] > 0 and abs(duration - item["duration"]) <= max(2, duration * .01) and same_collection(selected["url"], item["url"])
    unique = {}
    for item in [selected] + [item for item in candidates if matches(item)]:
        parsed = urlsplit(item["url"])
        identity = (parsed.netloc, re.sub(r"\.(m3u8|mp4|m4a)$", "", parsed.path))
        if identity not in unique or parsed.path.endswith(".m3u8"):
            unique[identity] = item
    compatible = list(unique.values())
    videos = [item for item in compatible if item["video"] and not item["audio"]]
    audios = [item for item in compatible if item["audio"] and not item["video"]]
    if len(audios) != 1 or not videos:
        raise ValueError("Could not confidently pair these feeds. Choose the matching audio/video source in Companion feed, then retry.")
    return max(videos, key=lambda item: (item["height"], item["width"], item["bitrate"])), audios[0]


def probe_candidates(url, candidates, ffprobe):
    unique = {}
    for candidate in candidates:
        validate_url(candidate)
        if candidate == url or not same_collection(url, candidate):
            continue
        parsed = urlsplit(candidate)
        identity = (parsed.netloc, re.sub(r"\.(m3u8|mp4|m4a)$", "", parsed.path))
        if identity not in unique or parsed.path.endswith(".m3u8"):
            unique[identity] = candidate

    def inspect(candidate):
        try:
            return probe_source(candidate, ffprobe)
        except (subprocess.SubprocessError, ValueError):
            return None

    with ThreadPoolExecutor(max_workers=4) as pool:
        return [item for item in pool.map(inspect, unique.values()) if item is not None]


def save_media(request, config, report=send):
    from yt_dlp import YoutubeDL

    url = validate_url(request.get("url"))
    output = Path(config["output"]).resolve()
    output.mkdir(parents=True, exist_ok=True)
    # A unique staging directory prevents overwriting episodes and hides incomplete files.
    job = uuid.uuid4().hex[:12]
    staging = output / ".incomplete" / job
    staging.mkdir(parents=True)
    last_report = 0
    progress_lock = threading.Lock()

    def progress(data):
        nonlocal last_report
        with progress_lock:
            if time.monotonic() - last_report < 1 and data["status"] != "finished":
                return
            last_report = time.monotonic()
        total = data.get("total_bytes") or data.get("total_bytes_estimate")
        amount = data.get("downloaded_bytes", 0)
        status = f"Downloading track: {amount * 100 / total:.0f}%" if total else "Downloading track..."
        if data.get("speed"):
            status += f" | {data['speed'] / 1048576:.1f} MiB/s"
        if data.get("eta") is not None:
            status += f" | {int(data['eta'])}s remaining"
        report({"status": "Track downloaded; preparing next track / merge..." if data["status"] == "finished" else status})

    class QuietLogger:
        def debug(self, message): pass
        def warning(self, message): pass
        def error(self, message): pass

    options = {
        "format": "bv*+ba/b",
        "format_sort": ["res", "fps", "br"],
        "merge_output_format": "mkv",
        "outtmpl": str(staging / "media.%(ext)s"),
        "ffmpeg_location": config["ffmpeg"],
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
        "logger": QuietLogger(),
        "progress_hooks": [progress],
        "socket_timeout": 30,
        "retries": 3,
        "fragment_retries": 3,
        "concurrent_fragment_downloads": max(1, min(8, int(config.get("fragment_workers", 8)))),
        "skip_unavailable_fragments": False,
        "allow_unplayable_formats": False,
        "geo_bypass": False,
        "cachedir": False,
    }
    report({"status": "Selecting highest-resolution video and best available audio..."})
    master_info = None
    if not request.get("partner") and urlsplit(url).path.endswith((".m3u8", ".mpd")):
        try:
            with YoutubeDL(options) as downloader:
                info = downloader.extract_info(url, download=False)
                formats = info.get("requested_formats") or []
                if any(f.get("vcodec") not in (None, "none") for f in formats) and any(f.get("vcodec") == "none" and f.get("acodec") != "none" for f in formats):
                    master_info = info
        except Exception:
            # A single-track playlist may not satisfy the combined format selector.
            pass
    selected = {"video": True, "audio": True} if master_info else probe_source(url, config["ffprobe"])
    pair = None
    if request.get("partner"):
        partner = probe_source(validate_url(request["partner"]), config["ffprobe"])
        if selected["video"] and partner["audio"]:
            pair = selected, partner
        elif partner["video"] and selected["audio"]:
            pair = partner, selected
        else:
            raise ValueError("The selected feeds do not contain a video track and an audio track.")
        if min(selected["duration"], partner["duration"]) > 0 and abs(selected["duration"] - partner["duration"]) > max(2, selected["duration"] * .01):
            raise ValueError("These feeds have different durations. Choose audio and video from the same episode.")
    elif not (selected["video"] and selected["audio"]):
        candidates = request.get("candidates", [])
        if not isinstance(candidates, list) or len(candidates) > 12:
            raise ValueError("Too many candidate feeds")
        report({"status": "Checking companion feeds for matching audio and video..."})
        inspected = probe_candidates(url, candidates, config["ffprobe"])
        pair = choose_pair(selected, inspected)
    if pair:
        tracks = []
        for kind, source in zip(("video", "audio"), pair):
            report({"status": f"Downloading {kind} track..."})
            track_dir = staging / kind
            track_dir.mkdir()
            track_options = {**options, "format": "bv/b" if kind == "video" else "ba/b", "outtmpl": str(track_dir / "track.%(ext)s")}
            with YoutubeDL(track_options) as downloader:
                info = downloader.extract_info(source["url"], download=False)
                if not info or info.get("is_live") or info.get("has_drm") or info.get("_type") in ("playlist", "multi_video"):
                    raise ValueError("Only individual, unprotected, non-live tracks are supported.")
                downloader.process_info(info)
            files = [p for p in track_dir.iterdir() if p.is_file() and p.suffix not in (".part", ".ytdl")]
            if len(files) != 1:
                raise ValueError("Could not identify the completed track.")
            tracks.append(files[0])
        report({"status": "Merging separate audio and video without re-encoding..."})
        subprocess.run(
            [config["ffmpeg"], "-v", "error", "-nostdin", "-i", str(tracks[0]), "-i", str(tracks[1]),
             "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", str(staging / "media.mkv")],
            capture_output=True, timeout=600, check=True,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
        )
    else:
        download_combined(url, options, YoutubeDL, master_info)
    candidates = [p for p in staging.iterdir() if p.is_file() and p.suffix.lower() in (".mkv", ".mp4", ".webm", ".mov", ".m4v") and not re.search(r"\.f\d", p.name)]
    if len(candidates) != 1:
        raise ValueError("Could not identify a single merged output. Incomplete files were kept for diagnosis.")
    result = candidates[0]
    verify_av(result, config["ffprobe"])
    destination = output / (safe_title(request.get("title", "video")) + " - " + job + result.suffix)
    result.rename(destination)
    report({"done": True, "path": str(destination)})
    return destination


def download_combined(url, options, YoutubeDL, info=None):
    with YoutubeDL(options) as downloader:
        info = info or downloader.extract_info(url, download=False)
        if not info or info.get("_type") in ("playlist", "multi_video"):
            raise ValueError("Select a single episode manifest, not a playlist of episodes.")
        if info.get("is_live"):
            raise ValueError("Live streams are not supported.")
        if info.get("has_drm"):
            raise ValueError("Protected media is not supported.")
        formats = info.get("requested_formats") or [info]
        # Unknown codecs are checked using ffprobe after download; known single tracks fail early.
        if all(item.get("acodec") == "none" for item in formats) or all(item.get("vcodec") == "none" for item in formats):
            raise ValueError("Only one track is available. Select the master manifest containing audio and video.")
        downloader.process_info(info)


def main():
    if os.name == "nt":
        import msvcrt
        msvcrt.setmode(sys.stdin.fileno(), os.O_BINARY)
        msvcrt.setmode(sys.stdout.fileno(), os.O_BINARY)
    try:
        config = json.loads(Path(__file__).with_name("config.json").read_text(encoding="utf-8-sig"))
        if len(sys.argv) < 2 or sys.argv[1] != f"chrome-extension://{config['extension_id']}/":
            raise ValueError("Unrecognized extension")
        save_media(read_message(sys.stdin.buffer), config)
    except Exception as error:
        # Signed media URLs may contain credentials. Never send extractor exceptions to the UI.
        text = str(error) if isinstance(error, ValueError) else "Download failed. The source may be protected, expired, unavailable, or require browser-only access. Try the master manifest after reloading."
        send({"error": text})


if __name__ == "__main__":
    main()
