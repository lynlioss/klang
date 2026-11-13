# Klang 0.3 design

Home follows a familiar music layout: Favorites and Songs shortcuts, shuffle/add actions, an album shelf, and recently added tracks. The previous Klang wordmark/icon header and full-page Your Mix collage are removed. The sculptural collage is reserved for the Favorites collection. Albums/releases have dedicated artwork-led collection pages with track lists and play/shuffle actions; a mini player keeps playback accessible there.

From Material 3: display large/medium/small retain the published 57/45/36sp sizes and 64/52/44sp line heights. Body, titles, labels and controls keep native Android typography. Surface/container and paired on-color roles organize the screens; connected toggle groups, ShortNavigationBar, the expressive motion scheme, native ripples and accessible Slider interactions use Material components.

Klang design decisions: Roboto Flex display styles use weight 800 and width 125, with matching optical sizes and zero tracking. These are an editorial font treatment inspired by Lynli’s reference, rather than M3’s default emphasized weight. Expression stays in short display headings and collection artwork. Native secondary headings and song rows retain readability. Cards use rounded album art and modest corners on text containers; primary/secondary/tertiary colors distinguish actions and collections.

System theme is the initial appearance preference and album-derived colors are initially off. Explicit saved choices persist. Dark surface roles use neutral near-black tones, independent of accent hue. The mini player always gets a separate current-cover Material palette, even when album colors are off for the rest of the app. Optional cover colors use Palette plus MaterialKolor to generate a complete HCT scheme; wallpaper palettes remain optional. Palette transitions use effects springs.

The Plugins page is reachable from Home and Settings. Spotify uses its recognizable green vector logo, a disabled switch and a direct “Not implemented yet” explanation. This is a placeholder; no account connection, remote search, plugin loading, or Spotify playback exists yet.

Navigation stays inset over full-height content, with trailing list space to keep final rows reachable. Geometry uses fast spatial springs (0.6 damping / 800 stiffness) or slow spatial springs (0.8 / 200); palette/opacity use effects springs (1.0 / 800). The player cover is capped at 31% of height/320dp, and the custom wavy seek track keeps native Slider input and accessibility semantics. Wave travel follows Android’s animation setting.

Reference screenshots guide composition, not assets. Album artwork comes from user-supplied music. Font, Spotify and Material icon attribution is in the credits. Minimum/target Android is API 36; compilation uses SDK 37.2.
