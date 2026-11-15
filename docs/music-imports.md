# Music imports

The library's plus button opens Add music. Paste a public Spotify or YouTube
Music song or playlist link, review the songs, and choose Add. Local audio files
and creating a playlist are available from the same sheet.

Lookups have a 45-second deadline. Spotify song previews use one public embed
request; album enrichment runs in the background after adding. Each metadata
request has a 20-second total timeout and cancellation closes its connection.

Imports run in WorkManager with a foreground notification and an app status
card. Three songs download concurrently, with eight concurrent fragments per
song when the source uses fragmented transfers. Completed files become
available immediately. Imports wait for a network connection, and interrupted
work resumes through a persistent journal. Cancel stops unfinished downloads;
Retry remaining keeps songs that already completed. Playlists retain source
order even when downloads finish out of order.

## Audio and metadata

Klang bundles yt-dlp through youtubedl-android 0.18.1, together with FFmpeg,
Python and QuickJS for ARM64 phones. Other-ABI development builds can use
`-Pklang.abis=x86_64` (or another comma-separated ABI list). The distributed APK
contains one architecture, not four complete runtimes. There is no separate
downloader app or server. yt-dlp's
stable extractor is refreshed at most once daily when an import starts; an
unavailable update does not prevent using the bundled version.

The downloader selects the best available audio-only stream. AAC, Opus,
Vorbis, MP3 and FLAC are kept in their source codec. FFmpeg uses stream copy
for container changes and metadata. Unsupported codecs fail instead of being
transcoded. There is no FLAC conversion or bitrate upscaling. YouTube audio is
usually lossy; a FLAC container would not restore missing quality.

Selection uses yt-dlp's sorted source formats and pins the selected format ID
for downloading. Original mixes take priority over YouTube's DRC (dynamic range
compression) variants. There is no hardcoded bitrate or itag ceiling, so an
accessible higher-quality or native FLAC source remains eligible. Import status
retains the selected codec and source bitrate. Properties distinguish lossless
and lossy encoding rather than applying MP3 bitrate thresholds to Opus.
Direct HTTP downloads use 1 MiB chunks; DASH/HLS uses fragment concurrency.
Temporary files are removed after failures and process recovery.

Tags and artwork are embedded before a file is published atomically to
Music/Klang through MediaStore. The publisher checks that Android can read
the duration, title, artist and embedded cover. Missing album information stays
unknown rather than using a playlist title as the album. The original source
metadata supplies album, release date, track number and disc number when available.

Spotify supplies metadata, not downloadable audio. Klang matches titles,
artists, duration and recording version to public YouTube recordings. Uncertain
matches fail with an instruction to use the exact YouTube Music link. Imports
never use Spotify's short audio previews as full songs. Private playlists,
login-required recordings and inaccessible regional content are unsupported.
Spotify's public page format and availability can change. Large playlists are
checked against the published count and paginated through Spotify when needed;
if the full list cannot be read, the import fails explicitly.

Matching first searches YouTube Music's public WEB_REMIX song catalog, using
structured artist, title, album, duration and explicit status. If the catalog
is unavailable or has no usable match, yt-dlp video search is a fallback.
Candidates are validated against full extraction; unavailable recordings or
matches without downloadable audio do not prevent trying another candidate.
Spotify album enrichment is optional, capped at six seconds, and runs alongside catalog search. Linked
YouTube videos retain their requested recording while gaining matching catalog
album/artwork and cleaned titles. Featured-artist credits do not prevent a title
match. Imports from the old pipeline are not reused when a song is imported
again; existing library files are not rewritten automatically.

## Research and verification (2026-11-15)

The reference checkout is `/tmp/klang-tryliner-desktop`. Liner's
[audio selection](https://github.com/tryliner/desktop/blob/main/src/shared/youtube/innertube/innertubeAudioFormats.ts)
prefers itags 251 (Opus), 140 (AAC), 250, 249 and 139. It uses YouTube Music
catalog matching, multiple client fallbacks, stream probes and parallel range
downloads. Its WEB_REMIX path depends on Electron's PO-token provider and
youtubei.js, so that component cannot simply run in this Android worker. Klang
keeps yt-dlp's maintained extractor and bundled QuickJS for stream resolution.
This change independently implements the catalog and metadata behavior; it
does not depend on Liner's services or ship its code.

Open-source protocol references:

- [ytmusicapi search](https://github.com/sigma67/ytmusicapi/blob/main/ytmusicapi/mixins/search.py)
  and [response parser](https://github.com/sigma67/ytmusicapi/blob/main/ytmusicapi/parsers/search.py) (MIT).
- [spotDL YouTube Music provider](https://github.com/spotDL/spotify-downloader/blob/master/spotdl/providers/audio/ytmusic.py):
  song-first catalog search with structured artist/duration matching.
- [yt-dlp YouTube extractor](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_video.py):
  source quality sorting, Premium formats and DRC distinction.
- [YouTube Music audio quality](https://support.google.com/youtubemusic/answer/9076559):
  higher settings can reach 256 kbps AAC/Opus. Those settings do not grant
  anonymous downloads access to Premium formats. Klang currently has no
  authenticated YouTube account/cookie import.

Live checks of the reported examples:

- `9PumlOWjXMM` (hey now): accessible best stream was itag 251, Opus,
  139.011 kbps, 48 kHz stereo. No FLAC or higher-rate stream was exposed.
  A standalone yt-dlp/FFmpeg import used GNX catalog metadata and cover art.
  All 10,875 compressed audio packet hashes matched between source WebM and
  tagged Opus. Tags and embedded artwork were checked with ffprobe; full
  decoding to a null sink completed without errors.
- Spotify `1EaKU4dMbesXXd3BrLCtYG` (ELEMENT.): embed returned Kendrick Lamar,
  208,733 ms and explicit status. A public catalog search returned `SwvzXM-EVrQ`,
  ELEMENT., Kendrick Lamar, 209 seconds, and DAMN. COLLECTORS EDITION.; full
  extraction agreed. Current flat search returned both channel and uploader,
  so the old failure was not reproducible with today's service response. The
  matched recording downloaded successfully with readable title/artist/album
  tags and embedded artwork in a standalone yt-dlp run.

These are standalone research checks, not an Android build or proof of device
playback. Captured minimal catalog responses cover these two cases in CI.

## CI / device checks

Run `./gradlew assembleDebug lintDebug testDebugUnitTest` on CI. ImportTest covers
URL normalization, validation, song-versus-playlist semantics, journal round trips,
recording matching and metadata selection.

On a device, test a YouTube Music song, a playlist and Spotify equivalents.
Check artwork and tags in another player; verify Opus/AAC stays in its original
codec. Close the sheet during downloads, reopen from the status card, interrupt
the network, cancel/retry, and verify playlist order. Check notification behavior
with permission granted and denied. Test large Spotify playlists for an explicit
error when pagination is unavailable. Run `scripts/verify-import-packaging.py` against the release APK and R8 mapping
to check native library packaging, size and reflected ZIP-handler constructors.
CI retains the R8 mapping for readable release crash reports. Download speed and matching accuracy still require
real device testing; no performance guarantee is implied by the concurrency settings.
