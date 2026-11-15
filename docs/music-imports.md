# Music import host

Klang's import UI is provider-neutral. The app sends a link to an enabled plugin,
reviews returned metadata, then invokes downloads only after a Download action.
The UI does not know Spotify or YouTube URL formats. Install the optional
[Music Import plugin](https://github.com/lynlioss/music-import-klang) from
Settings → Plugins → Recommended plugins. It includes the complete downloader;
Klang no longer bundles yt-dlp, Python, QuickJS or FFmpeg.

Albums and EPs use a provider's collection response, alongside songs and playlists.
Library publication is atomic and verifies readable title, artist, duration and
embedded artwork. Each download runs through WorkManager and a persistent journal. Completion
notifications are sent only when Klang is in the background (if notifications
are allowed); Android's mandatory foreground-work notice is silent.

Home and Library show a small circular wavy progress indicator beside Settings.
It is indeterminate until progress is known, becomes determinate, then animates
to a checkmark and clears after three seconds. Tapping opens the Downloads sheet.
There are no history tabs or past-download screens. Unresolved warnings persist.

Link previews allow five minutes in the provider, with a 30-second host grace period.
Downloads allow 45 minutes in the host; recording matching allows five minutes.
Provider metadata requests allow 90 seconds total and 60 seconds without a response.
Cancellation still closes the request and stops its downloader process.

The app checks all accessible device audio (including hidden tracks) before
queuing and again before downloading and publishing. Title and artist are
normalized; duration must be known and within the greater of three seconds or
2%. Same-recording downloads within the queue serialize to avoid a race.
Duplicates stay blocked and show Download anyway / Skip. Without broad music
access Android exposes only app-owned audio, so detecting every device duplicate
requires Music & audio permission. Unknown metadata does not claim a duplicate.

See [plugins.md](plugins.md) for API 1, and the plugin's
[research notes](https://github.com/lynlioss/music-import-klang/blob/main/RESEARCH.md)
for source quality and Spotify matching evidence.

CI builds/lints/tests the app. `scripts/verify-import-packaging.py` rejects optional
native download runtimes in Klang's APK. Device checks: plugin install/enable,
metadata preview, albums/EPs, duplicate Skip / Download anyway, concurrent queue,
network interruption, cancellation, process recreation, completion animation,
warning persistence, plugin updates/removal and screen-reader labels.
