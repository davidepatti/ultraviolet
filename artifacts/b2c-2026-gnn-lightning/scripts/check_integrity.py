#!/usr/bin/env python3
"""Check the published file inventory using only the Python standard library."""

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def check_integrity(root=ROOT):
    root = root.resolve()
    manifest = json.loads((root / "checksums.json").read_text())
    if manifest["algorithm"] != "sha256":
        raise ValueError("Unsupported checksum algorithm")
    for relative, expected in manifest["files"].items():
        path = root / relative
        if not path.resolve().is_relative_to(root) or path.is_symlink():
            raise ValueError(f"Unsafe artifact path: {relative}")
        if not path.is_file() or path.stat().st_size != expected["bytes"]:
            raise ValueError(f"Missing or resized artifact: {relative}")
        if digest(path) != expected["sha256"]:
            raise ValueError(f"Artifact checksum mismatch: {relative}")
    return len(manifest["files"])


if __name__ == "__main__":
    print(f"PASS: {check_integrity()} published files match checksums.json")
