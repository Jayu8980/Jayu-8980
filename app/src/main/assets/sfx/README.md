# Sound effects (optional)

AutoTube AI works without any files here: every effect has an on-device procedural version.

To use your own royalty-free sound effects, add audio files to one folder per type:

```
assets/sfx/whoosh/       transition whoosh, centred on the cut
assets/sfx/riser/        build-up; the END of the file lands on the cue time
assets/sfx/impact/       hit / boom at the cue time
assets/sfx/pop/          short pop for text pop-ups
assets/sfx/click/        UI-style click
assets/sfx/swoosh_soft/  gentle, short swoosh
```

- Formats: wav (preferred), mp3, m4a/aac, ogg/opus, flac. Keep effects short (under 10 s).
- One file per type is enough; with several, one is picked deterministically per cue.
- Levels are set automatically relative to the voice (-20 to -12 dB under it).
- This README is ignored by the app.
