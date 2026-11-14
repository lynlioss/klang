#!/usr/bin/env python3
"""Reject incomplete Android packages before serving them, including missing resource archives."""
import argparse
import json
import os
import platform
import re
import subprocess
import zipfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('apk', type=Path)
parser.add_argument('--compare', type=Path, help='Require the same signing certificate as this installed build')
args = parser.parse_args()
sdk = Path(os.environ.get('ANDROID_HOME', '/opt/android-sdk'))
build_tools = sdk / 'build-tools' / '37.0.0'
arm_tools = Path('/opt/android-sdk-tools-arm64/build-tools')
aapt2 = Path(os.environ.get('KLANG_AAPT2', str(arm_tools / 'aapt2' if platform.machine() == 'aarch64' else build_tools / 'aapt2')))
zipalign = arm_tools / 'zipalign' if platform.machine() == 'aarch64' else build_tools / 'zipalign'
java_home = os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-17-openjdk-arm64')
java = str(Path(java_home) / 'bin' / 'java')

def run(command):
    return subprocess.check_output(command, text=True, stderr=subprocess.STDOUT)

with zipfile.ZipFile(args.apk) as apk:
    names = apk.namelist()
    assert len(names) == len(set(names)), 'Duplicate APK entries'
    assert apk.testzip() is None, 'Corrupt ZIP entry'
    for name in ('AndroidManifest.xml', 'resources.arsc', 'classes.dex'):
        assert name in names and apk.getinfo(name).file_size > 0, f'Missing {name}'
    assert not any(name.startswith('assets/starter/') for name in names), 'Removed starter assets are still packaged'
    plugin = json.loads(apk.read('assets/plugins/lrclib.json'))
    assert plugin['id'] == 'lrclib' and plugin['type'] == 'lyrics', 'Missing LRCLIB plugin'

badging = run([str(aapt2), 'dump', 'badging', str(args.apk)])
package = next(line for line in badging.splitlines() if line.startswith('package:'))
assert "name='app.lynli.klang'" in package, package
assert re.search(r"^(?:sdkVersion|minSdkVersion):'36'$", badging, re.MULTILINE), 'Unexpected minimum Android version'
assert "targetSdkVersion:'36'" in badging, 'Unexpected target Android version'
run([str(zipalign), '-c', '-P', '16', '4', str(args.apk)])

def certificate(apk):
    report = run([java, '-jar', str(build_tools / 'lib' / 'apksigner.jar'), 'verify', '--verbose', '--print-certs', str(apk)])
    match = re.search(r'certificate SHA-256 digest: ([0-9a-f]+)', report)
    assert match, 'Verified signature report is missing its certificate digest'
    return match.group(1)

cert = certificate(args.apk)
if args.compare:
    assert cert == certificate(args.compare), 'Signing identity changed: APK cannot update the installed app'
print(package)
print(f'Complete, aligned, signature verified; LRCLIB plugin; {args.apk.stat().st_size:,} bytes')
print(f'Certificate SHA-256: {cert}')
