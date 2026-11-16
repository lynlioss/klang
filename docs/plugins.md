# Making a Klang lyric plugin

The lyrics provider type in Plugin API 1 uses JSON. No Kotlin, JavaScript, Android SDK, or compilation is needed. A plugin describes HTTPS requests and how to read lyrics from the response. Start by copying [lrclib-klang](https://github.com/lynlioss/lrclib-klang).

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

API 1 is still being developed. Lyrics providers use declarative requests and do not execute downloaded code. Music providers run in a separately installed Android service under their own UID. Both provider types use `apiVersion: 1`; unknown API versions are rejected.

Spotify Integration, Jam and Music Import are optional recommendations. A fresh Klang install does not
bundle or download them automatically. If their APKs are already on the device,
Klang discovers them directly. Otherwise, add them using Install in Recommended
plugins or the plugin picker.

## Music sources (Plugin API 1)

API 1 also supports `type: "musicSource"` in a separate Android APK. Its service
advertises `io.github.lynlioss.klang.MUSIC_SOURCE_V1` and embeds the manifest as the
`klang.plugin` raw resource. The `musicSource` object declares package/service,
optional account settings activity, badge/link label, supported links and catalog,
library, playlist, streaming and download capabilities. API version remains 1.

Klang renders native catalog pages for home, search, library, albums, artists and
playlists. Plugins return opaque IDs and bounded pages; the host retains metadata,
preserves duplicate playlist occurrences, fetches complete collections and resolves
temporary HTTPS streams on Media3's loader thread. Downloads reuse the `musicImport`
contract and host-owned queue, duplicate checks and MediaStore publication.
Persistent source identities prefer downloaded files for offline playback after
restarting Klang.

Login, credentials, APIs and recording matching belong to the plugin. Account
revisions invalidate cached library pages after switching accounts. Messages are
checked against the installed service UID and a fresh token; invalid pagination,
unknown capabilities and unsafe playback headers are rejected. Future community
plugins can implement the same contract. See [Spotify Integration's API.md](https://github.com/lynlioss/klang-spotify/blob/main/API.md)
for the manifest, Messenger envelope and operations.

**Spotify Integration** appears in Recommended plugins with its Spotify icon.
Spotify supplies account/catalog/playlist features; YouTube Music or the connected
user's Qobuz account supplies matching recordings. Open its detail page's Accounts
& playback settings to log in and choose sources. The YouTube path does not require
Spotify Premium. The plugin has its own APK, releases and account storage and is
enabled explicitly.

## Music download plugins (API 1)

Music providers in API 1 use an independently installed Android service. Klang
binds to the service; provider code runs in its own process. A download plugin manifest uses
`apiVersion: 1`, `type: "musicImport"` and:

```json
"musicImport": {
  "package": "com.example.provider",
  "service": "com.example.provider.DownloadService",
  "hosts": ["music.example.com"],
  "schemes": ["example-music"]
}
```

The exported service advertises `io.github.lynlioss.klang.MUSIC_IMPORT_V1` and embeds
its JSON manifest as the `klang.plugin` metadata resource. Klang discovers it
through PackageManager, validates the package/service match and provides enable,
settings and update controls. An uninstall in Klang removes the integration;
remove the separate APK in Android settings to reclaim its runtime storage.

Music provider releases attach `plugin.apk` and `plugin.apk.sha256` as well as
the manifest/checksum assets. Klang verifies both checksums and APK package/version
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

## Native extensions (Plugin API 1)

API 1 also accepts `type: "extension"`. Extensions use independently installed
Android services under their own UID; API 1 is not renamed or replaced. A manifest
adds an `extension` object with `package`, fully qualified `service`,
`capabilities`, `nowPlayingActions`, `hosts` and `schemes`. The exported service
advertises `io.github.lynlioss.klang.EXTENSION_V1` and embeds its manifest in the
`klang.plugin` metadata resource. Releases use the same APK/checksum installer as
music providers. Extensions are opt-in and must be enabled explicitly.

Capabilities are `ui.nowPlaying`, `ui.panel`, `playback.observe`,
`playback.control`, `audio.read`, `audio.receive` and `links`. Unknown capabilities
are rejected. The host validates the service UID and a per-connection token on
all incoming Binder messages. Network code and business logic run in the plugin.
See [Klang Jam](https://github.com/lynlioss/klang-jam) and its
[API.md](https://github.com/lynlioss/klang-jam/blob/main/API.md) for the protocol.

Klang provides a native, bounded panel renderer using current Material color
roles, expressive typography, springs and loading indicators. Plugins describe
text, hero, field, button, toggle, member, progress and error nodes and receive
user actions. Hero nodes can include an `eyebrow`. Up to two button nodes with
`placement: "footer"` stay below the scrolling content; `presentation: "icon"`
renders an accessible icon button with the label as its tooltip. They can
contribute a Now Playing menu action, animated center
pill and contextual track action. The host owns native layout and accessibility;
plugins own the complete screen content and state. No HTML or downloaded classes
execute inside Klang.

Audio access is descriptor-based and limited to explicitly selected or native queued audio. Selection controls contain opaque session grants, so extensions can share a member’s local songs without receiving filesystem paths. `audio.register` maps a selected reference to its audio hash; `playback.queue` reconciles unique entry keys while preserving current playback and prepared successors. Queue and playback requests return a fresh playback snapshot, and observation includes queue/index, actual next index, advancement and speed. Plugin API 1 keeps its name and version.
Received audio is bounded, hashed and validated by container and MediaExtractor
before becoming playable. Output paths are host-generated. Saving to the library
is atomic and requires a user action. Cleanup restores the receiver's previous
paused queue and removes temporary audio. Playback services own extension
connections so sessions continue while the app is backgrounded.

`klang:` routes are dispatched to installed/enabled plugins declaring that scheme.
Verified HTTPS domains must appear in the Android manifest and serve matching
`assetlinks.json`; Android cannot add verified app domains dynamically. A web
fallback can open a generic custom-scheme plugin link for new domains. A Jam
invite opens a review panel; joining still requires a user action.
