#!/usr/bin/env python3
"""Bump the release version in app/build.gradle.kts.

Those two lines are the single source of truth: Gradle stamps them into the APK
manifest and the app reads them at runtime through BuildConfig, so bumping here
is enough for both the package and the Settings screen.

Usage: bump-version.py {current|patch|minor|major}
Prints: version=<name> and code=<code>, one per line.
"""
import re
import sys
from pathlib import Path

GRADLE = Path(__file__).resolve().parent.parent / "app" / "build.gradle.kts"
VERSION_NAME = re.compile(r'^(\s*versionName\s*=\s*)"([^"]+)"(\s*)$', re.MULTILINE)
VERSION_CODE = re.compile(r'^(\s*versionCode\s*=\s*)(\d+)(\s*)$', re.MULTILINE)
BUMPS = {"current", "patch", "minor", "major"}


def fail(message):
    print(f"bump-version: {message}", file=sys.stderr)
    raise SystemExit(1)


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in BUMPS:
        fail("usage: bump-version.py {current|patch|minor|major}")
    bump = sys.argv[1]

    text = GRADLE.read_text(encoding="utf-8")
    name_match = VERSION_NAME.search(text)
    code_match = VERSION_CODE.search(text)
    if name_match is None or code_match is None:
        fail(f"could not find versionName/versionCode in {GRADLE}")

    version = name_match.group(2)
    parts = version.split(".")
    if len(parts) != 3 or not all(part.isdigit() for part in parts):
        fail(f"versionName {version!r} is not major.minor.patch")
    major, minor, patch = (int(part) for part in parts)

    code = int(code_match.group(2))
    if bump != "current":
        if bump == "major":
            major, minor, patch = major + 1, 0, 0
        elif bump == "minor":
            minor, patch = minor + 1, 0
        else:
            patch += 1
        code += 1
        version = f"{major}.{minor}.{patch}"
        text = VERSION_NAME.sub(lambda match: f'{match.group(1)}"{version}"{match.group(3)}', text, count=1)
        text = VERSION_CODE.sub(lambda match: f"{match.group(1)}{code}{match.group(3)}", text, count=1)
        GRADLE.write_text(text, encoding="utf-8")

    print(f"version={version}")
    print(f"code={code}")


if __name__ == "__main__":
    main()
