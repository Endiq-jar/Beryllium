#!/usr/bin/env python3
"""Verify that a built Beryllium jar is pinned to its matrix Minecraft version."""

from __future__ import annotations

import json
import sys
import zipfile
from pathlib import Path


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: validate_built_mod.py <libs-dir> <minecraft-version>", file=sys.stderr)
        return 2

    libs_dir = Path(sys.argv[1])
    expected_version = sys.argv[2]
    jars = [jar for jar in libs_dir.glob("*.jar") if not jar.name.endswith("-sources.jar")]
    if not jars:
        print(f"no non-sources jar found in {libs_dir}", file=sys.stderr)
        return 1

    matching = 0
    for jar in jars:
        try:
            with zipfile.ZipFile(jar) as archive:
                metadata = json.loads(archive.read("fabric.mod.json"))
        except (KeyError, OSError, json.JSONDecodeError, zipfile.BadZipFile) as error:
            print(f"{jar}: invalid fabric.mod.json: {error}", file=sys.stderr)
            return 1

        actual_version = metadata.get("depends", {}).get("minecraft")
        if actual_version != expected_version:
            print(
                f"{jar}: expected fabric.mod.json minecraft dependency {expected_version!r}, "
                f"got {actual_version!r}",
                file=sys.stderr,
            )
            return 1
        matching += 1

    print(f"validated {matching} artifact(s) pinned to Minecraft {expected_version}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
