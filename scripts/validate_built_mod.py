#!/usr/bin/env python3
"""Verify that a built Beryllium jar is pinned to its matrix Minecraft version."""

from __future__ import annotations

import json
import sys
import zipfile
from pathlib import Path


def check_declared_files(jar: Path, metadata: dict) -> list[str]:
    """Every file the mod metadata declares must actually be inside the jar.

    A jar that names an access widener or a mixin config it does not contain loads
    differently (or not at all) on the user's machine, and nothing else in the build would
    notice: the generated widener is written during configuration, and a `clean` in the same
    invocation used to be able to delete it before it was packaged.
    """
    problems: list[str] = []
    with zipfile.ZipFile(jar) as archive:
        names = set(archive.namelist())

        widener = metadata.get("accessWidener")
        if widener and widener not in names:
            problems.append(f"declares access widener {widener!r}, which is not in the jar")

        for entry in metadata.get("mixins", []):
            config = entry.get("config") if isinstance(entry, dict) else entry
            if config and config not in names:
                problems.append(f"declares mixin config {config!r}, which is not in the jar")

        for kind, entries in metadata.get("entrypoints", {}).items():
            for entry in entries:
                value = entry.get("value") if isinstance(entry, dict) else entry
                if not isinstance(value, str) or "/" in value or "." not in value:
                    continue
                if value.replace(".", "/") + ".class" not in names:
                    problems.append(f"declares {kind} entrypoint {value!r}, which is not in the jar")

        for cls in ("com/endiq/beryllium/Beryllium.class", "com/endiq/beryllium/BerylliumMixinPlugin.class"):
            if cls not in names:
                problems.append(f"missing expected class {cls.replace('/', '.').removesuffix('.class')}")
    return problems


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
        for problem in check_declared_files(jar, metadata):
            print(f"{jar}: {problem}", file=sys.stderr)
            return 1
        matching += 1

    print(f"validated {matching} artifact(s) pinned to Minecraft {expected_version}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
