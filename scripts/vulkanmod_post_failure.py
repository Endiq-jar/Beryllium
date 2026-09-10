#!/usr/bin/env python3
"""Post the tail of a failed vulkanmod universal build to diagnostics issue #9.

The Actions log/annotation endpoints have been unreachable from the session
that developed the universal build, so the workflow (which runs with
``issues: write``) reports failures through a transient issue instead.  This
script is deliberately dependency-free (urllib only) and never raises: it
prints what it could not do and exits 0, so the workflow's ``|| true`` keeps
the original build failure as the step outcome.
"""
import json
import os
import sys
import urllib.request

LOG = "/tmp/universal-build.log"
ISSUE_URL = "https://api.github.com/repos/Endiq-jar/Beryllium/issues/9/comments"
TOKEN = os.environ.get("GITHUB_TOKEN", "")


def main():
    try:
        with open(LOG, encoding="utf-8", errors="replace") as handle:
            tail = handle.read()[-7000:]
    except OSError:
        tail = "(no build log found at " + LOG + ")"
    sha = os.environ.get("GITHUB_SHA", "unknown")[:7]
    body = "Universal build failed on " + sha + "\n\n```\n" + tail + "\n```"
    if not TOKEN:
        print("could not post to issue #9: GITHUB_TOKEN not set", file=sys.stderr)
        return
    request = urllib.request.Request(
        ISSUE_URL,
        data=json.dumps({"body": body}).encode("utf-8"),
        headers={
            "Authorization": "token " + TOKEN,
            "Accept": "application/vnd.github+json",
            "User-Agent": "vulkanmod-ci",
            "Content-Type": "application/json",
        },
    )
    try:
        urllib.request.urlopen(request, timeout=30)
        print("posted failure details to issue #9")
    except Exception as exc:  # noqa: BLE001 - diagnostic path must never raise
        print("could not post to issue #9: %s" % exc, file=sys.stderr)


if __name__ == "__main__":
    main()
