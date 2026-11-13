# Next steps

The first milestone is the native local player. `MusicRepository` owns Android storage, `Track` supplies the Media3 item contract, and `PlaybackService` owns playback independently of Compose. Streaming sources can later share the queue without making the UI depend on a specific provider.

Potential next milestones:

- On-device UI/playback tests, TalkBack review, and localized strings.
- Richer artist browsing, replay gain, and optional high-resolution DSP output.
- Metadata editing and cover selection through Android's user-approved media write flow.
- Sleep timer owned by the playback service so it survives process recreation.
- A versioned source API with search, browse, resolve and capability descriptions.
- Explicitly permissioned plugin loading, provider settings and isolated failures.
- YouTube or other integrations only through a provider implementation after the base player is stable.

Version 0.5.0 includes a Plugins page with unavailable Spotify and YouTube Music placeholders. No plugin execution, account linking, remote search, or streaming provider is shipped.
