# Background music (optional)

AutoTube AI works without any files here: when no track is available, music is generated on the
phone by the procedural synthesizer (royalty-free, deterministic per project).

To use your own royalty-free tracks, drop audio files into one folder per mood:

```
assets/music/suspense/
assets/music/happy/
assets/music/sad/
assets/music/exciting/
assets/music/calm/
assets/music/dramatic/
assets/music/inspiring/
assets/music/funny/
```

- Formats: mp3, m4a/aac, ogg/opus, flac, wav.
- Only use music you have the rights to publish on YouTube (e.g. YouTube Audio Library, CC0).
- Tracks are chosen deterministically per project, trimmed of leading/trailing silence, looped with
  2 s crossfades and loudness-normalized automatically, so levels do not need to match.
- Only the first 3 minutes of a track are used (longer beds loop that part) to save memory.
- At least 4 seconds of non-silent audio is required.
- Files users import inside the app (filesDir/music/<mood>/) take priority over bundled assets.
- This README is ignored by the app.
