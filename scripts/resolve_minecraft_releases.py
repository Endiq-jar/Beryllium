#!/usr/bin/env python3
"""Emit the Minecraft version matrix for GitHub Actions.

Two modes, because two different questions get asked of it:

  (default)    every *release* from 1.19.4 through Mojang's latest release.
               This is what Beryllium ships jars for, and what gates a merge.

  --development the development line of the next, not-yet-released version
               (26.4-snapshot-N today). This is forward coverage: it proves the
               mod still compiles against the upcoming release before that
               release exists, and it must never gate anything, because a
               snapshot breaking is Mojang's schedule and not a defect here.

The list is derived from Mojang's version manifest rather than copied into
workflow YAML. That is what makes a newly released version visible to CI on the
next run instead of silently leaving a gap in coverage: the day 26.4 ships,
`latest.release` becomes 26.4, the default matrix picks it up, and no edit to
this file or to any workflow is needed.

`--manifest` reads a saved copy instead of fetching, which is what makes the
version-selection logic testable offline -- Mojang's servers are not reachable
from every environment that needs to check this script is still correct.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from typing import Any
from urllib.error import URLError
from urllib.request import Request, urlopen

MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
MINIMUM_VERSION = (1, 19, 4)

# How many of the upcoming version's development builds to compile. Weekly
# snapshots accumulate fast over a drop's life; building all of them buys nothing
# over building the newest few, and each one is a full Gradle run.
DEFAULT_DEVELOPMENT_LIMIT = 3

# A development build id is "<version>-<kind><number>", e.g. 26.4-snapshot-5,
# 26.3-pre-2, 26.3-rc-1. The version part is what identifies the line.
_DEVELOPMENT_ID = re.compile(r"^(\d+\.\d+(?:\.\d+)?)-(?:snapshot|pre|rc)-?\d+$")


def parse_release_version(value: str) -> tuple[int, int, int] | None:
    """Return a sortable numeric release tuple, or None for snapshots/pre-releases."""
    pieces = value.split(".")
    if len(pieces) not in (2, 3) or any(not piece.isdecimal() for piece in pieces):
        return None
    numbers = tuple(int(piece) for piece in pieces)
    return numbers if len(numbers) == 3 else (numbers[0], numbers[1], 0)


def development_line_of(version_id: str) -> str | None:
    """The release this development build is working towards, e.g. '26.4-snapshot-5' -> '26.4'."""
    match = _DEVELOPMENT_ID.match(version_id)
    return match.group(1) if match else None


def fetch_manifest(path: str | None = None) -> dict[str, Any]:
    if path:
        with open(path, encoding="utf-8") as handle:
            return json.load(handle)

    request = Request(MANIFEST_URL, headers={"User-Agent": "Beryllium-release-matrix/0.3"})
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


def loom_pipeline(parsed: tuple[int, int, int]) -> str:
    """Minecraft 26.1 began shipping unobfuscated game jars; Loom has a different
    plugin pipeline for that format."""
    return "unobfuscated" if parsed[0] >= 26 else "remapped"


def matrix_entry(version: str, parsed: tuple[int, int, int]) -> dict[str, str]:
    return {
        "minecraft": version,
        "id": "mc-" + version.replace(".", "-"),
        "loom_pipeline": loom_pipeline(parsed),
    }


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

    matrix = [
        matrix_entry(version, parsed)
        for version, parsed in sorted(releases.items(), key=lambda entry: entry[1])
    ]
    return latest, matrix


def resolve_development_matrix(
    manifest: dict[str, Any], limit: int = DEFAULT_DEVELOPMENT_LIMIT
) -> tuple[str, list[dict[str, str]]]:
    """The development builds of the next version that has not been released yet.

    Only that one line is taken. The manifest also carries every snapshot, pre-release
    and release candidate of versions that *have* shipped -- 26.3-snapshot-7 and so on --
    and compiling those would re-test history nobody can install.

    Returns an empty matrix, without error, when there is no upcoming line: between a
    release and its first snapshot there is nothing to build against, and that is a
    normal state rather than a failure.
    """
    latest_release = manifest.get("latest", {}).get("release")
    latest_snapshot = manifest.get("latest", {}).get("snapshot")
    if not isinstance(latest_snapshot, str):
        return "", []

    upcoming = development_line_of(latest_snapshot)
    if upcoming is None:
        # latest.snapshot is itself a release id, so nothing is in development.
        return "", []
    if upcoming == latest_release:
        # The snapshot line belongs to a version that has already shipped.
        return "", []
    if parse_release_version(upcoming) is None:
        return "", []

    candidates: list[tuple[str, str]] = []
    for candidate in manifest.get("versions", []):
        version = candidate.get("id")
        if not isinstance(version, str) or candidate.get("type") != "snapshot":
            continue
        if development_line_of(version) != upcoming:
            continue
        released_at = candidate.get("releaseTime")
        candidates.append((released_at if isinstance(released_at, str) else "", version))

    if not candidates:
        return upcoming, []

    # Newest first: when the limit trims, it should trim the oldest builds.
    candidates.sort(reverse=True)
    chosen = candidates[: max(1, limit)]

    parsed = parse_release_version(upcoming)
    assert parsed is not None  # guarded above
    matrix = [matrix_entry(version, parsed) for _, version in chosen]
    return upcoming, matrix


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--github-output",
        action="store_true",
        help="write matrix and latest keys to the GITHUB_OUTPUT file",
    )
    parser.add_argument(
        "--development",
        action="store_true",
        help="emit the upcoming version's development builds instead of the release matrix",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=DEFAULT_DEVELOPMENT_LIMIT,
        help=f"how many development builds to emit (default {DEFAULT_DEVELOPMENT_LIMIT})",
    )
    parser.add_argument(
        "--manifest",
        help="read a saved manifest from this path instead of fetching Mojang's",
    )
    args = parser.parse_args()

    manifest = fetch_manifest(args.manifest)

    if args.development:
        upcoming, matrix = resolve_development_matrix(manifest, args.limit)
        latest = upcoming
    else:
        latest, matrix = resolve_matrix(manifest)

    payload = json.dumps({"include": matrix}, separators=(",", ":"))

    if args.github_output:
        output_path = os.environ.get("GITHUB_OUTPUT")
        if not output_path:
            parser.error("--github-output requires the GITHUB_OUTPUT environment variable")
        with open(output_path, "a", encoding="utf-8") as output:
            output.write(f"matrix={payload}\n")
            output.write(f"latest={latest}\n")
            # An empty matrix is a legal answer -- there is simply nothing in
            # development right now -- but GitHub Actions rejects a matrix with no
            # vectors outright, so a caller needs to know to skip the job rather
            # than let the run fail on a normal state of the world.
            output.write(f"has_targets={'true' if matrix else 'false'}\n")
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
