# Making a Klang lyric plugin

Plugin API 1 uses JSON. No Kotlin, JavaScript, Android SDK, or compilation is needed. A plugin describes HTTPS requests and how to read lyrics from the response. Start by copying [lrclib-klang](https://github.com/lynlioss/lrclib-klang).

## Create a repository

Put `plugin.json` at the repository root. Use a unique, permanent `id`, a stable `major.minor.patch` version, and your repository URL. Here is a small provider:

```json
{
  "apiVersion": 1,
  "id": "my-lyrics",
  "name": "My lyrics",
  "version": "1.0.0",
  "type": "lyrics",
  "author": "Your name",
  "repository": "https://github.com/you/my-lyrics",
  "summary": "Lyrics from my service.",
  "description": "Loads synchronized lyrics from my HTTPS API.",
  "privacy": "Sends song title and artist to lyrics.example.com.",
  "buttons": [
    {"label": "Homepage", "url": "https://lyrics.example.com", "icon": "website"},
    {"label": "Source", "url": "https://github.com/you/my-lyrics", "icon": "source"}
  ],
  "settings": [
    {"id": "cacheLyrics", "type": "boolean", "title": "Keep lyrics offline", "default": true},
    {"id": "timingOffset", "type": "range", "title": "Timing adjustment", "default": 0, "min": -3000, "max": 3000, "step": 100, "unit": "ms"}
  ],
  "lyrics": {
    "requests": [{
      "url": "https://lyrics.example.com/api/lyrics",
      "query": {"title": "{title}", "artist": "{artist}"},
      "response": {"format": "json", "plain": "text", "synced": "lrc"}
    }]
  }
}
```

## Requests and responses

Requests are HTTPS GETs. Klang encodes query values. Templates support `{title}`, `{artist}`, `{album}`, `{durationSeconds}`, `{durationMs}`, and `{settings.settingId}` in `query` and `headers`. Blank values are omitted. Use `headers` for API keys, such as `"Authorization": "Bearer {settings.apiKey}"`.

`response.format` supports `json`, `plain`, `lrc`, and `lyricsfile`. LRC can include enhanced word timestamps. Lyricsfile uses LRCLIB's YAML format, with `lines`, millisecond start/end times, and optional `words`.

For JSON, `plain`, `synced`, `lyricsfile`, and `instrumental` map to fields. Their defaults are `plainLyrics`, `syncedLyrics`, `lyricsfile`, and `instrumental`. Use dot paths for nested fields or an empty string to disable a mapping. `records` selects a nested object or array; leave it empty for the root. Optional `title`, `artist`, `album`, and `duration` mappings let Klang match search results to the song. Duration is in seconds; `durationToleranceMs` defaults to 2000.

Define up to four requests, run in order. `when` is `always`, `missing`, or `noWordTiming`. Klang keeps the best usable result, preferring real word timings. A failed optional request keeps existing lyrics. HTTP 404/204 means no match. HTTP 429/503 applies a retry delay. `requestIntervalMs` defaults to 300 and accepts 100–5000. Responses are limited to 2 MB, requests have timeouts, and redirects are rejected. Use the service's final URL.

## Settings and links

Each setting needs `id`, `type`, `title`, and `default`. `description` is optional.

| Type | Extra fields |
| --- | --- |
| `boolean` | Boolean default |
| `range` | Integer `min`, `max`, `step`; optional `unit`. The step must divide the range and default. |
| `text` | String default; `secret: true` masks the field for API keys |
| `choice` | `options: [{"value": "en", "label": "English"}]`; default must match an option |

Klang renders these controls on the plugin detail page. `cacheLyrics` controls offline caching, which is on by default. `timingOffset` adjusts lyric timing in milliseconds. Other settings are available through request templates. Settings stay on the device; secret fields are masked, stored in app-private preferences, and only sent where the plugin explicitly templates them.

Buttons have a label, HTTPS URL, and `website`, `source`, or `donate` icon. Omit a button or set `enabled: false` to hide it. Update checks use `repository` even if the source button is hidden.

## Publish and update

1. Change `version` in `plugin.json`, commit, and push.
2. Create a stable GitHub release tagged `v1.0.0`, matching the manifest version.
3. Attach `plugin.json` and `plugin.json.sha256`. Generate the checksum with `sha256sum plugin.json > plugin.json.sha256`, or copy the example repository's manual Release workflow.
4. Share the repository URL. Users paste it into Klang's + sheet, review, install, and enable the plugin.

Klang checks stable releases at startup. An available update shows a snackbar linking to Plugins and an update button on the plugin. Updates keep settings and enabled state. Uninstall removes the plugin, settings, and cached lyrics. IDs and repository URLs must stay unchanged across versions. The checksum detects a damaged download; repository ownership determines who publishes updates.

API 1 does not execute downloaded code or support scraping, OAuth, POST requests, or arbitrary scripts. Services with those needs can expose a compatible HTTPS endpoint. API 2 music providers run in a separately installed Android service under their own UID. Unknown API versions are rejected so an app update can introduce new capabilities without breaking API 1 providers.

## Music download plugins (API 2)

API 1 lyrics providers stay unchanged. API 2 is an independently installed Android
service, not executable code loaded into Klang. A download plugin manifest uses
`apiVersion: 2`, `type: "musicImport"` and:

```json
"musicImport": {
  "package": "com.example.provider",
  "service": "com.example.provider.DownloadService",
  "hosts": ["music.example.com"],
  "schemes": ["example-music"]
}
```

The exported service advertises `io.github.lynlioss.klang.MUSIC_IMPORT_V2` and embeds
its JSON manifest as the `klang.plugin` metadata resource. Klang discovers it
through PackageManager, validates the package/service match and provides enable,
settings and update controls. An uninstall in Klang removes the integration;
remove the separate APK in Android settings to reclaim its runtime storage.

API 2 releases attach `plugin.apk` and `plugin.apk.sha256` as well as the API 1
manifest/checksum assets. Klang verifies both checksums and APK package/version
before handing installation to Android. Android enforces signing continuity for
updates. No native downloader binaries are in the host APK.

The protocol is documented in the reference plugin's
[API.md](https://github.com/lynlioss/music-import-klang/blob/main/API.md).
Resolve accepts a link and returns metadata for review. Download accepts one
opaque metadata payload and a host-owned output descriptor, streams progress,
then returns file type and final metadata. Large metadata responses use a pipe
to avoid Binder size limits. The host owns duplicate checks, queue order,
foreground scheduling and atomic file publication. Providers own source-specific
links, metadata requests, matching and audio downloading.
