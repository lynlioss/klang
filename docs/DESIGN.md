# Klang 0.2 design

The first phone trial exposed an oversized non-native typeface, overly large secondary-page titles, a cramped player, and navigation that reserved an opaque strip of the screen. Version 0.2 addresses those directly.

From Material 3: Android sans serif uses the standard role scale. Emphasis is selective. Body regions use surface, navigation uses surfaceContainer, and controls use matching on-color roles. Cover colors are extracted with Palette and transformed into complete Material HCT schemes by MaterialKolor. Connected toggle-button shapes organize related choices. ShortNavigationBar, the expressive motion scheme, native ripples, loading indicator, and accessible Slider interactions are used directly.

Klang design decisions: navigation is an inset overlay over full-height content. Lists have a trailing spacer to keep their final rows reachable. The collage is the home hero; the player is a second hero with cover-dependent color. The artwork occupies at most 31% of the available player height, capped at 320dp. The custom wavy seek track uses native Slider input/semantics, with a 16dp visual thumb and a moving wave while playing. The wave is decoration, not amplitude analysis. Its 1.4-second travel cycle is a product decision. When Android animator duration is disabled it does not travel.

From M3 motion tokens: geometry uses fast spatial springs (0.6 damping / 800 stiffness) or slow spatial springs (0.8 / 200); palette/opacity changes use slow effects springs (1.0 / 800). Settings and the player enter with interruptible spatial springs; the player cover transitions as the song changes. Native settings now occupy a full page. Search uses a compact title and filled search field. Promotional slogans are removed.

The reference screenshots inspire composition rather than supply assets. Cover art comes from user-supplied music. MaterialKolor's license and Material icon credits are included in docs. Minimum/target Android is API 36, and the app compiles with SDK 37.2.
