# Material icons

Only icons used by Klang are included as generated Kotlin vectors from AndroidX material-icons-extended 1.7.8. Their original Android Open Source Project copyright and Apache 2.0 headers are retained. See ICON-LICENSE.txt. The small material-icons-core dependency provides shared vector builders and standard icons. This avoids shipping and dexing thousands of unused icons in a debug APK.

## Spotify

Spotify’s logo was obtained as an SVG from [Simple Icons](https://github.com/simple-icons/simple-icons/blob/develop/icons/spotify.svg) and converted losslessly to an Android VectorDrawable. Simple Icons distributes its icon collection under [CC0](https://github.com/simple-icons/simple-icons/blob/develop/LICENSE.md). The Spotify mark remains Spotify’s trademark. It identifies the explicitly unavailable integration on the Plugins page.

## Roboto Flex

[Roboto Flex from Google Fonts](https://github.com/google/fonts/tree/main/ofl/robotoflex) is used only for display roles, as bundled static instances at weight 900, width 125, and optical sizes 36/45/57 (other axes at their defaults), under the [SIL Open Font License](ROBOTO-FLEX-LICENSE.txt). Smaller type uses the native Android font.

The Plugins settings row uses the rounded [Material Symbol Extension](https://github.com/google/material-design-icons/blob/master/symbols/web/extension/materialsymbolsrounded/extension_24px.svg), under Apache 2.0, converted to an Android vector.

## YouTube Music

The YouTube Music mark comes from [Simple Icons](https://github.com/simple-icons/simple-icons/blob/develop/icons/youtubemusic.svg), under CC0, converted to a VectorDrawable. The trademark belongs to YouTube/Google and identifies an unavailable integration.

Output and equalizer actions use rounded Material Symbols [Speaker Group](https://github.com/google/material-design-icons/blob/master/symbols/web/speaker_group/materialsymbolsrounded/speaker_group_24px.svg) and [Tune](https://github.com/google/material-design-icons/blob/master/symbols/web/tune/materialsymbolsrounded/tune_24px.svg), under Apache 2.0.

Player actions also use Material Symbols rounded [Lyrics](https://github.com/google/material-design-icons/blob/master/symbols/web/lyrics/materialsymbolsrounded/lyrics_24px.svg) and [Playlist Add](https://github.com/google/material-design-icons/blob/master/symbols/web/playlist_add/materialsymbolsrounded/playlist_add_24px.svg), under Apache 2.0. Material Symbols use a negative Y view box; their VectorDrawables translate paths into the positive viewport.
