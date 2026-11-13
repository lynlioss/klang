# Next steps

The first milestone is the native local player. `MusicRepository` owns Android storage, `Track` supplies the Media3 item contract, and `PlaybackService` owns playback independently of Compose. Streaming sources can later share the queue without making the UI depend on a specific provider.

Potential next milestones:

- On-device UI/playback tests, TalkBack review, and localized strings.
- User playlists, richer album/artist browsing, equalizer and replay gain.
- Metadata editing and cover selection through Android's user-approved media write flow.
- Sleep timer owned by the playback service so it survives process recreation.
- A versioned source API with search, browse, resolve and capability descriptions.
- Explicitly permissioned plugin loading, provider settings and isolated failures.
- YouTube or other integrations only through a provider implementation after the base player is stable.

No plugin execution, remote search, or streaming provider is shipped in 0.1.0.
