#!/usr/bin/env python3
"""Serve the newest built test APK to the phone's browser; no workspace file viewer needed."""
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
import os
import re
import shutil

ARTIFACTS = Path(__file__).resolve().parents[1] / 'artifacts'

def newest_apk() -> Path:
    override = os.environ.get('KLANG_APK')
    if override:
        return Path(override).expanduser().resolve()
    versioned = []
    for candidate in ARTIFACTS.glob('Klang-*.apk'):
        match = re.fullmatch(r'Klang-(\d+(?:\.\d+)*)\.apk', candidate.name)
        if match:
            versioned.append((tuple(int(part) for part in match.group(1).split('.')), candidate))
    if versioned:
        return max(versioned, key=lambda item: item[0])[1]
    # No versioned build yet: serve the raw Gradle output.
    return ARTIFACTS.parent / 'app' / 'build' / 'outputs' / 'apk' / 'release' / 'app-release.apk'

APK = newest_apk()

class Download(BaseHTTPRequestHandler):
    def serve(self, body=True):
        if self.path.split('?')[0] not in ('/', '/klang.apk', '/' + APK.name):
            self.send_error(404)
            return
        if not APK.exists():
            self.send_error(503, 'Updated APK build is in progress')
            return
        self.send_response(200)
        self.send_header('Content-Type', 'application/vnd.android.package-archive')
        self.send_header('Content-Disposition', f'attachment; filename="{APK.name}"')
        self.send_header('Content-Length', str(APK.stat().st_size))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        if body:
            with APK.open('rb') as source:
                shutil.copyfileobj(source, self.wfile)
    def do_GET(self):
        self.serve()
    def do_HEAD(self):
        self.serve(body=False)

if __name__ == '__main__':
    print(f'Klang download: http://127.0.0.1:8765/klang.apk ({APK.name})', flush=True)
    ThreadingHTTPServer(('0.0.0.0', 8765), Download).serve_forever()
