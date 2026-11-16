#!/usr/bin/env python3
"""Klang's APK must not bundle optional service plugins or download runtimes."""
import argparse
import json
import zipfile
from pathlib import Path


def verify_apk(path):
    with zipfile.ZipFile(path) as apk:
        for name in apk.namelist():
            assert not any(runtime in name for runtime in ('libpython', 'libffmpeg', 'libqjs', 'yt-dlp', 'youtubedl')), \
                f'Optional download runtime leaked into Klang: {name}'
            assert not name.lower().endswith('.apk'), f'Plugin APK bundled in Klang: {name}'
            if name.startswith('assets/plugins/') and name.endswith('.json'):
                manifest = json.loads(apk.read(name))
                assert manifest.get('type') == 'lyrics', f'Optional service plugin bundled in Klang: {name}'


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('apk', type=Path)
    args = parser.parse_args()
    verify_apk(args.apk)
    print('Klang APK contains no optional download runtime')
