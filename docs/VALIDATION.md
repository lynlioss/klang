# Validation

The first 106,004,738-byte APK was successfully tried on Lynli's phone. Its size included 68,434,456 compressed DEX bytes and 36,178,133 asset bytes. The asset pack accidentally included KYOTO twice. The source now contains exactly three M4As (about 23 MB), with matching embedded title, artist, album and artwork, and the full extended icon dependency has been replaced by selected vectors.

Version 0.2 is compiled with SDK 37.2/AGP 9.5.1 and requires Android 16. The distribution build uses R8 code/resource shrinking, omits Compose preview tooling, and keeps the existing debug signing identity for update installation. Music is preserved without transcoding.

Build, lint, focused metadata tests, package/signing verification, and the download response are checked before distributing the updated APK. No ADB-connected device is available from proot. The revised visual layout, touch seeking, cover colors and permission refresh require a new on-phone trial.
