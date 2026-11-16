#!/usr/bin/env python3
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
spec = importlib.util.spec_from_file_location('guard', Path(__file__).with_name('verify-import-packaging.py'))
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)
class ImportPackagingTest(unittest.TestCase):
    def test_optional_runtime_must_stay_in_plugin(self):
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / 'app.apk'
            with zipfile.ZipFile(apk, 'w') as archive: archive.writestr('classes.dex', 'data')
            guard.verify_apk(apk)
            with zipfile.ZipFile(apk, 'a') as archive: archive.writestr('lib/arm64-v8a/libpython.so', 'data')
            with self.assertRaisesRegex(AssertionError, 'Optional download runtime'): guard.verify_apk(apk)
    def test_service_manifests_must_not_be_bundled(self):
        for plugin_type in ('extension', 'musicImport'):
            with self.subTest(plugin_type=plugin_type), tempfile.TemporaryDirectory() as folder:
                apk = Path(folder) / 'app.apk'
                with zipfile.ZipFile(apk, 'w') as archive:
                    archive.writestr('assets/plugins/optional.json', json.dumps({'type': plugin_type}))
                with self.assertRaisesRegex(AssertionError, 'Optional service plugin'): guard.verify_apk(apk)
    def test_plugin_apks_must_not_be_bundled(self):
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / 'app.apk'
            with zipfile.ZipFile(apk, 'w') as archive: archive.writestr('assets/jam.apk', 'data')
            with self.assertRaisesRegex(AssertionError, 'Plugin APK bundled'): guard.verify_apk(apk)
    def test_bundled_lyrics_are_allowed(self):
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / 'app.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('assets/plugins/lrclib.json', json.dumps({'type': 'lyrics'}))
            guard.verify_apk(apk)
if __name__ == '__main__': unittest.main()
