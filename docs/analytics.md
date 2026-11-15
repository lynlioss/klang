# Optional analytics

Klang finishes music setup, then shows a dedicated analytics page before plugin recommendations. It briefly describes the shared data and PostHog's EU region. The Yes and No buttons sit at the bottom with equal emphasis and no preselection; either choice continues immediately. Onboarding itself only has a short notice about optional PostHog analytics in the EU.

The pending page survives an app restart. Existing installs keep their saved analytics choice and do not repeat setup. Users can change sharing in **Settings → Privacy → Share analytics**, using a switch with a short description and no extra on/off text.

No SDK instance, event queue, analytics identity or PostHog request exists before explicit consent. Events while analytics is off are discarded rather than saved for later. Turning the switch off immediately closes the transport gate, cancels HTTP calls, persists the choice, resets the SDK identity and discards queued data. Enabling again creates a new anonymous consent period, so old queued data and identities cannot be reused. Previously delivered events are not deleted by the switch.

The client allows only POST requests to the configured PostHog `/batch` endpoint. Redirects, feature flag requests, remote configuration, logs, session replay, automatic screen views, deep links, push notification tracking and crash reports are disabled or blocked. Event properties are filtered to the documented schema; music titles, artists, file names, file paths, URLs, provider payloads, email, locale, network carrier and persistent device IDs are excluded. GeoIP enrichment and person profiles are disabled. The ingestion service necessarily receives the connection's IP address.

## Configuration

Use the project token (`phc_…`), not a personal API key. The Android SDK needs a token and ingestion host; it does not use a numeric project ID.

- `.env` at the repository root is ignored by Git. [.env.example](../.env.example) lists the supported values. Process environment variables override `.env`.
- GitHub Actions secret: `POSTHOG_PROJECT_TOKEN`.
- GitHub Actions variables: `POSTHOG_HOST=https://eu.i.posthog.com`, `POSTHOG_PROJECT_ID=351549`.
- The release workflow passes the secret and host into Gradle. Debug builds and CI work without configuration. The token is embedded in configured APKs as required for client event ingestion; it grants no access to read analytics or administer the project.
- PostHog Android is pinned to `3.77.1`. Recheck SDK configuration, storage hooks and transport tests before upgrading. Storage hooks isolate disk queues by consent period because the SDK's close method attempts a flush.

## Events and device properties

All events require consent and include `android_version`, `android_api_level`, `device_manufacturer`, `device_model`, `app_version` and `app_version_code`. They use a random anonymous identifier for the current consent period and SDK session metadata; no account or hardware identifier is used.

| Event | Meaning |
| --- | --- |
| `analytics_enabled` | An explicit Yes on the page after setup (`source=onboarding`) or switch-on action (`source=settings`). |
| `app_first_opened` | First consenting activation after initial setup, once per install. This is counted after Yes, not at download time. |
| `onboarding_completed` | The user chooses Yes on the page after completing initial setup. Sent once per install after consent; earlier setup interactions are never recorded or replayed. Enabling later in settings does not backfill this event. |
| `app_opened` | The activity enters the foreground, or consent is enabled, with current `onboarding_complete` status. Use unique users for daily or weekly activity. |
| `music_imported` | Local file import succeeds; includes `track_count`. |
| `playback_started` | A queue starts; includes `queue_size`, categorical `source_type`, and `shuffled`. |
| `audio_deleted` | A confirmed audio deletion succeeds. |
| `plugin_installed` / `plugin_uninstalled` | A plugin operation succeeds; includes categorical `plugin_type` and installation `operation`. |
| `favorite_toggled` | A favorite changes; includes `is_favorite`. |
| `playlist_created` / `playlist_deleted` | A playlist operation succeeds. |
| `sleep_timer_set` | Timer changes; includes `duration_minutes` and `is_active`. |

Users who decline analytics are absent from these counts. Changing consent resets the anonymous identifier; daily or weekly unique-user totals are estimates of consenting installations, not exact people. Reinstalls also create a new identifier. A foreground event does not mean background playback remained active.

## PostHog reports

In [project 351549](https://eu.posthog.com/project/351549), save these views to a dashboard:

| Report | Query |
| --- | --- |
| New consenting installs | Trends → `app_first_opened` → total count; daily or weekly interval. |
| Consenting setup completions | Trends → `onboarding_completed` → total count; daily or weekly interval. |
| Daily active Android versions | Trends → `app_opened` → unique users → breakdown `android_version` → daily interval. |
| Weekly active Android versions | Same query with weekly interval. |
| Android API distribution | `app_opened` → unique users → breakdown `android_api_level`. |
| Phone models | `app_opened` → unique users → breakdown `device_model` or `device_manufacturer`. |

Choose a 30- or 90-day date range. PostHog computes daily and weekly groupings from event timestamps; the phone does not need a scheduled background upload. Since consent now follows setup, `onboarding_started` is no longer recorded and a setup-start-to-completion funnel cannot measure users who abandon setup or decline analytics. The existing starter dashboard may still use the unchanged product event names.

## App download totals

An APK cannot report a download before it runs. Use GitHub release asset statistics to measure APK downloads independently of the app's analytics choice. Count only `klang.apk`, excluding checksum and other attachments:

```sh
gh api --paginate repos/lynlioss/klang/releases --jq '.[] | .tag_name as $release | .assets[] | select(.name == "klang.apk") | {release: $release, downloads: .download_count}'
```

Download totals stay on GitHub; no workflow copies them to PostHog. These counters measure download requests, not unique users or verified installs.

## Validation

The Checks workflow builds, lints and runs the JVM suite on GitHub, including consent transitions, persistence failures, completed setup before consent, completion deduplication, queued requests after revocation, rejection before DNS, redirect rejection, payload filtering and missing configuration. Tests use a local mock server and never send events to PostHog. Android builds and tests run in GitHub CI.

After CI passes, verify on a test device: finishing music setup shows the dedicated page with bottom Yes/No actions, and setup sends no PostHog traffic. No continues without telemetry; Yes records completion once after consent. Restarting before choosing resumes the analytics page. Turning sharing off cancels and blocks requests; relaunch remains off; turning it on again uses a new anonymous identifier without replaying old queued events or setup history. Confirm the Android version breakdown in PostHog with actual consenting traffic. No Android build or device check was run on the phone for this change.
