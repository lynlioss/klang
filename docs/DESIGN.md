# Klang's design

Design direction by Lynli, using [google-m3-expressive-design-skill](https://github.com/abhixv/google-m3-expressive-design-skill) and the supplied music-app reference.

From Material 3: paired semantic color roles; the body uses surface and navigation uses surfaceContainer. Display-large is 57sp/64sp, headline-large is 32sp/40sp. Selected song titles use emphasized weight. Compose Material 3's expressive motion scheme handles components. Custom geometry uses expressive spatial springs (fast: damping 0.6/stiffness 800; default: 0.8/380); selection color uses an effects spring (1.0/3800). Shape steps used include 12, 16, 20, 28, 32, 48dp and full. Controls retain accessible minimum touch targets, icon descriptions, and native ripple/selection behavior.

Klang design decisions, rather than M3 specifications: the home headline is an editorial 64sp/60sp with bold Space Grotesk and tight tracking. The reference-inspired floating navigation container has inset margins. The hero combines circular covers with a rotated capsule. Its dimensions and composition are product choices. Artwork expands slightly during playback, and the play button morphs from a circle to a rounded square. The waveform is decorative, not an audio-analysis visualization. Native system bars and content insets are preserved. Large windows switch to a navigation rail and a supporting player pane at 840dp; the phone player scrolls to support smaller screens and larger font sizes.

The reference is inspiration, not an asset: no screenshot fragments or iOS system chrome are used. Starter covers come from NCS release pages and the user's tagged M4A. Space Grotesk is distributed under the SIL Open Font License; see FONT-LICENSE.txt.

Material 3 is pinned to 1.5.0-rc01 because stable 1.4 hides the Expressive APIs. The project compiles against SDK 37.2 with AGP 9.5.1 and Java 17, and requires Android 16. This is a pre-release library dependency and should be updated to stable 1.5 when available.
