# Releasing Klang

Two independent things are involved:

1. **Signing** the APK with a key that never changes, so users can install updates.
2. **Registering** that key with Google, so Android does not treat every install as unverified.

## 1. Create the release keystore (once, ever)

Do this on a trusted machine, not in CI, and not inside the repository directory.

```sh
keytool -genkeypair -v \
  -keystore klang-release.jks \
  -alias klang \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Lynli, O=Lynli, C=DE"
```

Use a long generated password and store it in a password manager.

> **This key is permanent.** Android identifies an app by its signing certificate. If you
> lose the keystore or forget the password, no future build can update an installed copy —
> users would have to uninstall, losing their playlists and settings. If the key leaks,
> somebody else can sign updates for your package name.

Back up `klang-release.jks` and its passwords in at least two places, one offline. Keep it
out of Git — `.gitignore` already covers `*.jks`, `*.keystore` and `keystore.properties`.

Record the certificate digest once, so you can spot a wrong key later:

```sh
keytool -list -v -keystore klang-release.jks -alias klang | grep SHA256
```

## 2. Local release builds

```sh
cp keystore.properties.example keystore.properties
# fill in the absolute store path, passwords and alias
./scripts/build-phone.sh assembleRelease
```

`keystore.properties` is git-ignored. Without it, `assembleRelease` silently falls back to
the debug key, which is what the phone test loop has used so far. **The first public
release must not use that fallback**, because the debug key cannot be recovered from
another machine.

Note: switching signature on a device that already has Klang installed produces
`App not installed`. Uninstall the debug-signed build once, then install the release APK.

## 3. Configure GitHub Actions secrets

The release workflow reads four repository secrets:

| Secret | Contents |
| --- | --- |
| `KLANG_KEYSTORE_B64` | Base64 of `klang-release.jks` |
| `KLANG_KEYSTORE_PASSWORD` | Keystore password |
| `KLANG_KEY_ALIAS` | `klang` |
| `KLANG_KEY_PASSWORD` | Key password (falls back to the keystore password if omitted) |

Base64 encodes with no line wrapping:

```sh
base64 -w0 klang-release.jks > klang-release.jks.b64   # Linux
base64 -i klang-release.jks -o klang-release.jks.b64   # macOS
```

Add them through **Settings → Secrets and variables → Actions**, or:

```sh
gh secret set KLANG_KEYSTORE_B64 < klang-release.jks.b64
gh secret set KLANG_KEYSTORE_PASSWORD
gh secret set KLANG_KEY_ALIAS --body klang
gh secret set KLANG_KEY_PASSWORD
```

Then delete `klang-release.jks.b64` from disk.

Secrets are encrypted and not readable after upload, and they are not exposed to pull
requests from forks. The workflow still treats the keystore as sensitive: it is written to
`$RUNNER_TEMP` with mode `600` and never uploaded as an artifact.

## 4. Cut a release

Run **Actions → Release → Run workflow** and pick a bump:

| Choice | `0.9.3` becomes | Use for |
| --- | --- | --- |
| `patch` | `0.9.4` | fixes |
| `minor` | `0.10.0` | features |
| `major` | `1.0.0` | milestones or breaking changes |
| `current` | `0.9.3` | releasing a version you set by hand |

`current` moves nothing. It exists so the first release of a hand-set version does not
get bumped to something else, and it fails if the tag already exists, which is what stops
a duplicate release.

The workflow runs in this order, so a failure never leaves a half-released version:

1. Bumps `versionName` and `versionCode` with `scripts/bump-version.py`.
2. Builds, lints and tests.
3. Verifies the APK with `scripts/verify-apk.py`, checks the APK's own version string
   matches the bump, and renames it to `klang.apk`.
4. Commits the bump to `main` as `Release v<version>` and pushes it.
5. Tags `v<version>` and pushes the tag.
6. Publishes a GitHub release with `klang.apk` and `klang.apk.sha256`.

Version numbers live only in `app/build.gradle.kts`. Gradle stamps them into the APK
manifest and the app reads them through `BuildConfig.VERSION_NAME`, so the Settings screen
always matches the installed package. Do not edit them anywhere else.

Two constraints: the push uses `GITHUB_TOKEN`, so branch protection on `main` must allow
it, and a push made with that token does not trigger other workflows.

## 5. Register the signing identity


Publishing an APK on GitHub does not make Play Protect trust it. Registration is what
changes the install experience.

- **Android Developer Console** — <https://developer.android.com/developer-verification>.
  Registers your developer identity and signing certificate for apps distributed outside
  Google Play. A *limited-distribution account* is free, needs no government ID, and
  covers up to 50 devices — enough to share with testers, not to publish. The cap counts
  installations tied to your account, not downloads, so it is not a path to a public
  release; complete full verification before announcing the app.
- **Google Play Console** — <https://play.google.com/console>. One-time $25. Personal
  accounts need closed testing with 12 testers for 14 days before production. Play
  re-signs your app with Google's key, so the Play build and the GitHub build have
  different certificates and cannot update each other.

Register the same keystore you use for the GitHub releases. Generating a new key later
means a new install base from scratch.
