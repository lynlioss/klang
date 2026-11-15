#!/usr/bin/env python3
"""Report aggregate GitHub APK download counters from CI, independently of app consent."""

import datetime
import json
import os
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import uuid


def download_snapshot(releases):
    assets = [asset for release in releases if not release.get("draft", False)
              for asset in release.get("assets", []) if asset["name"] == "klang.apk"]
    return {"total_downloads": sum(asset["download_count"] for asset in assets), "apk_count": len(assets)}


def payload(snapshot, token, day, timestamp):
    return {
        "api_key": token,
        "batch": [{
            "event": "github_downloads_snapshot",
            "distinct_id": "github-release-statistics",
            "timestamp": timestamp,
            "uuid": str(uuid.uuid5(uuid.NAMESPACE_URL, f"https://github.com/lynlioss/klang/downloads/{day}")),
            "properties": {**snapshot, "source": "github_releases", "$process_person_profile": False,
                           "$geoip_disable": True},
        }],
    }


def main():
    token = os.environ.get("POSTHOG_PROJECT_TOKEN", "").strip()
    if not token:
        raise SystemExit("POSTHOG_PROJECT_TOKEN repository secret is required")
    host = os.environ.get("POSTHOG_HOST", "").strip() or "https://eu.i.posthog.com"
    parsed = urllib.parse.urlsplit(host)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise SystemExit("POSTHOG_HOST must be an HTTPS ingestion host")
    output = subprocess.check_output([
        "gh", "api", "--paginate", "--slurp", "repos/lynlioss/klang/releases",
    ], text=True)
    releases = [release for page in json.loads(output) for release in page]
    snapshot = download_snapshot(releases)
    now = datetime.datetime.now(datetime.timezone.utc)
    body = payload(snapshot, token, now.date().isoformat(), now.isoformat())

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None

    request = urllib.request.Request(host.rstrip("/") + "/batch/", data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=30) as response:
            if not 200 <= response.status < 300:
                raise SystemExit(f"PostHog returned HTTP {response.status}")
    except urllib.error.HTTPError as error:
        raise SystemExit(f"PostHog returned HTTP {error.code}") from None
    except urllib.error.URLError:
        raise SystemExit("Could not reach the PostHog ingestion host") from None

    summary = f"APK downloads: {snapshot['total_downloads']} across {snapshot['apk_count']} release assets."
    print(summary)
    if summary_path := os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(summary_path, "a", encoding="utf-8") as report:
            report.write(summary + "\n")


if __name__ == "__main__":
    main()
