"""Create a website-download ZIP from an explicit allowlist, never local state/secrets."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parent
files = ["manifest.json", "background.js", "media.mjs", "queue.mjs", "popup.html", "popup.css", "popup.js", "USAGE.md", "native/host.py", "native/install.ps1"]
args.output.parent.mkdir(parents=True, exist_ok=True)
with ZipFile(args.output, "w", ZIP_DEFLATED) as archive:
    for name in files:
        archive.write(root / name, "video-saver/" + name)
    archive.write(root.parents[1] / "LICENSE", "video-saver/LICENSE")
    archive.write(root.parents[1] / "THIRD_PARTY_NOTICES.md", "video-saver/THIRD_PARTY_NOTICES.md")
print(f"Packaged {args.output}. Manual Developer Mode installation required on ordinary Chrome Windows/macOS.")
