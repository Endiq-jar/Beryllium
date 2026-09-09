#!/usr/bin/env python3
"""Emit the complete supported Minecraft release matrix for GitHub Actions.

The list is intentionally derived from Mojang's version manifest instead of being
copied into workflow YAML.  That makes a newly released version visible to CI on
the next run rather than silently leaving a gap in Beryllium's coverage.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from typing import Any
from urllib.error import URLError
from urllib.request import Request, urlopen

MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
MINIMUM_VERSION = (1, 19, 4)


def parse_release_version(value: str) -> tuple[int, int, int] | None:
    """Return a sortable numeric release tuple, or None for snapshots/pre-releases."""
    pieces = value.split(".")
    if len(pieces) not in (2, 3) or any(not piece.isdecimal() for piece in pieces):
        return None
    numbers = tuple(int(piece) for piece in pieces)
    return numbers if len(numbers) == 3 else (numbers[0], numbers[1], 0)


def fetch_manifest() -> dict[str, Any]:
    request = Request(MANIFEST_URL, headers={"User-Agent": "Beryllium-release-matrix/0.2"})
    last_error: Exception | None = None
    for attempt in range(3):
        try:
            with urlopen(request, timeout=30) as response:
                return json.load(response)
        except (URLError, TimeoutError, json.JSONDecodeError) as error:
            last_error = error
            if attempt != 2:
                time.sleep(2**attempt)
    raise RuntimeError(f"could not download Mojang version manifest after 3 attempts: {last_error}")


def resolve_matrix(manifest: dict[str, Any]) -> tuple[str, list[dict[str, str]]]:
    latest = manifest.get("latest", {}).get("release")
    latest_tuple = parse_release_version(latest) if isinstance(latest, str) else None
    if latest_tuple is None:
        raise RuntimeError(f"manifest has no numeric latest release: {latest!r}")

    releases: dict[str, tuple[int, int, int]] = {}
    for candidate in manifest.get("versions", []):
        if candidate.get("type") != "release":
            continue
        version = candidate.get("id")
        if not isinstance(version, str):
            continue
        parsed = parse_release_version(version)
        if parsed is not None and MINIMUM_VERSION <= parsed <= latest_tuple:
            releases[version] = parsed

    if latest not in releases:
        raise RuntimeError(f"latest release {latest} was not found in the supported release set")
    if "1.19.4" not in releases:
        raise RuntimeError("minimum release 1.19.4 was not found in Mojang's manifest")

    matrix = []
    for version, parsed in sorted(releases.items(), key=lambda entry: entry[1]):
        matrix.append(
            {
                "minecraft": version,
                "id": "mc-" + version.replace(".", "-"),
                # Minecraft 26.1 began shipping unobfuscated game jars; Loom has a
                # different plugin pipeline for that format.
                "loom_pipeline": "unobfuscated" if parsed[0] >= 26 else "remapped",
            }
        )
    return latest, matrix


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--github-output",
        action="store_true",
        help="write matrix and latest keys to the GITHUB_OUTPUT file",
    )
    args = parser.parse_args()

    latest, matrix = resolve_matrix(fetch_manifest())
    payload = json.dumps({"include": matrix}, separators=(",", ":"))

    if args.github_output:
        output_path = os.environ.get("GITHUB_OUTPUT")
        if not output_path:
            parser.error("--github-output requires the GITHUB_OUTPUT environment variable")
        with open(output_path, "a", encoding="utf-8") as output:
            output.write(f"matrix={payload}\n")
            output.write(f"latest={latest}\n")
    else:
        print(payload)
        print(f"latest={latest}", file=sys.stderr)

    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)
