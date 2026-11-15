#!/usr/bin/env python3
import importlib.util
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
if __name__ == '__main__': unittest.main()
