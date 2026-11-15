# Music imports

The library's plus button opens Add music. Paste a public Spotify or YouTube
Music song or playlist link, review the songs, and choose Add. Local audio files
and creating a playlist are available from the same sheet.

Imports run in WorkManager with a foreground notification and an app status
card. Three songs download concurrently, with eight concurrent fragments per
song when the source uses fragmented transfers. Completed files become
available immediately. Imports wait for a network connection, and interrupted
work resumes through a persistent journal. Cancel stops unfinished downloads;
Retry remaining keeps songs that already completed. Playlists retain source
order even when downloads finish out of order.

## Audio and metadata

Klang bundles yt-dlp through youtubedl-android 0.18.1, together with FFmpeg,
Python and QuickJS. There is no separate downloader app or server. yt-dlp's
stable extractor is refreshed at most once daily when an import starts; an
unavailable update does not prevent using the bundled version.

The downloader selects the best available audio-only stream. AAC, Opus,
Vorbis, MP3 and FLAC are kept in their source codec. FFmpeg uses stream copy
for container changes and metadata. Unsupported codecs fail instead of being
transcoded. There is no FLAC conversion or bitrate upscaling. YouTube audio is
usually lossy; a FLAC container would not restore missing quality.

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

## CI / device checks

Run `./gradlew assembleDebug lintDebug testDebugUnitTest` on CI. ImportTest covers
URL normalization, validation, song-versus-playlist semantics, journal round trips,
recording matching and metadata selection.

On a device, test a YouTube Music song, a playlist and Spotify equivalents.
Check artwork and tags in another player; verify Opus/AAC stays in its original
codec. Close the sheet during downloads, reopen from the status card, interrupt
the network, cancel/retry, and verify playlist order. Check notification behavior
with permission granted and denied. Test large Spotify playlists for an explicit
error when pagination is unavailable. Run the release package verifier to check
native library packaging. Download speed and matching accuracy still require
real device testing; no performance guarantee is implied by the concurrency settings.
