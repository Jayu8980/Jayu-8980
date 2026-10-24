# PianoScribe

**Turn music audio into piano notes, a piano roll, a note-by-note analysis and a MIDI file, fully offline on Android.**

- No `INTERNET` permission, no cloud APIs, no accounts. Every step runs on the device (CI checks the APK has no INTERNET permission).
- Kotlin, Jetpack Compose, Material 3, MVVM + coroutines/Flow, single activity, Room.
- minSdk 26 (Android 8.0), targetSdk 35, compileSdk 36. Package `com.pianoscribe.app`.

> **Accuracy note:** results are best for **solo piano or a single instrument**. Full-band songs (drums, vocals,
> bass, several instruments) give *approximate* results: expect extra and missing notes.

## Features

| Area | What you get |
|---|---|
| Input | Import MP3, WAV, M4A, AAC, OGG, FLAC with the system file picker (Storage Access Framework, no storage permission), or record from the microphone (live waveform, max 10 min). Trim start and end with a preview before converting. You can also "Share" or "Open with" audio into the app. |
| Transcription | MediaExtractor + MediaCodec decode → mono → 22 050 Hz (band-limited resampler) → Spotify **Basic Pitch** in overlapping 2 s windows on a background thread with progress and cancel → note extraction (onset, note and contour outputs) → pitch, start, duration, velocity, confidence and tuning in cents. |
| Settings | Onset threshold, note threshold, minimum note length (ms), pitch range limited to piano A0–C8 (MIDI 21–108), "piano only" quantisation to 1/8 or 1/16 with auto-detected tempo (editable BPM), RH/LH split point (default middle C). |
| Views | Piano roll (scroll, pinch-zoom, keyboard on the left, colour by hand, playhead), Synthesia-style falling notes with an on-screen keyboard and finger numbers, simple grand-staff sheet music, note list with Western (C D E) or Indian Sargam (Sa Re Ga) names. |
| Editing | Tap to select, drag to move, drag the end to resize, long-press to delete, double-tap to add (edit mode), undo/redo, transpose ±12, tempo change (re-grid or stretch), quantise, hand split. |
| Playback | Transcribed notes with the bundled piano SoundFont, the original audio, or both mixed (A/B). Speed 0.25x–2x (original audio is time-stretched, so pitch stays the same). A-B loop or "loop selected bar". |
| Export | Standard MIDI File (Type 1, conductor track with tempo + time signature, separate Right Hand / Left Hand tracks, Acoustic Grand Piano), MusicXML for MuseScore, piano roll PNG, note list PDF/TXT, analysis table CSV/PDF. Tap **MIDI** in the result screen's top bar: one tap saves to `Downloads/PianoScribe` (Android 10+), or open it in a MIDI app, share it, or "Save as…" to any folder. Other formats are under ⋮ → Export. Projects are stored in a local Room database. |
| Note-by-note analyser | Next/Previous buttons and swipe, plays just that note/chord, piano roll scrolls to it, 88-key keyboard lights the keys with suggested fingers. Detail card: note + octave, Sargam, MIDI number, Hz, start, duration in seconds and note value, Bar/Beat, velocity, hand, finger, confidence (low confidence highlighted), cents sharp/flat with a tuner needle, chord name + notes + inversion, key and per-section key with out-of-key marking, interval from the previous note and direction, spectrum view with harmonic markers plus the model's pitch contour. Fix-it actions: ±1 semitone, octave up/down, split, merge with next, delete, mark verified. Filters: next low-confidence note, next chord, RH/LH only. |
| Practice mode | The app plays the note; you play it on a real piano (microphone, YIN pitch detection) or a USB / Bluetooth LE MIDI keyboard (`android.media.midi`). Shows ✓ or ✗ with cents off and only moves on when correct. |

## Project layout

```
PianoScribe/
├── core/   Pure Kotlin/JVM engine, no Android dependencies, unit-tested
│   ├── audio/       StreamingResampler, FFT, YIN, WSOLA time-stretch, pitch accuracy (cents)
│   ├── transcribe/  Basic Pitch windowing/stitching + port of output_to_notes_polyphonic
│   ├── theory/      note names + Sargam, intervals, ChordNamer, KeyDetector, NoteValue/bar:beat, fingering
│   ├── rhythm/      tempo + beat phase detection, quantiser
│   ├── midi/        MidiWriter (SMF type 1), MidiReader, ScoreBuilder, MusicXmlWriter
│   ├── analysis/    analyser steps/filters, analysis table (CSV/TXT)
│   ├── edit/        undo/redo history, note operations
│   └── synth/       SF2 reader/writer, polyphonic sampler, piano sample generator
└── app/    Android app (Compose UI, Room, TFLite runner, MediaCodec decoder, AudioTrack engine, MIDI input)
    └── src/main/assets/
        ├── models/basic_pitch_nmp.tflite
        ├── soundfonts/pianoscribe_piano.sf2
        └── licenses/BASIC_PITCH_LICENSE.txt
```

The pipeline was built in this order, each step compiling before the next: project setup → audio decode →
model inference → note extraction → piano roll → MIDI export → note-by-note analyser → remaining features.

## Build

Requirements: JDK 17+, Android SDK with platform 36 (Android Studio Narwhal or newer works out of the box).

```bash
cd PianoScribe
./gradlew :core:test          # JVM unit tests for the engine
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

Without a release keystore, `assembleRelease` signs with the debug key so the APK is still installable.
In Android Studio: **File → Open → `PianoScribe/`**, wait for Gradle sync, then Run.

### No PC? Build on GitHub

`.github/workflows/pianoscribe-build.yml` runs the unit tests and builds both APKs on every push that touches
`PianoScribe/`. Download them from the run's **Artifacts** (`PianoScribe-apks`). Pushing a tag like
`pianoscribe-v1.0.0` also publishes a GitHub Release with the APKs. Without a tag: **Actions → Build PianoScribe APK →
Run workflow**, enter a `release_tag` such as `pianoscribe-v1.0.1`, and the release page gets the `.apk` files as
direct downloads you can open and install on a phone.

### Signed release APK

1. Create a keystore once (keep it and its passwords safe, you need them for every update):
   ```bash
   keytool -genkeypair -v -keystore pianoscribe.jks -alias pianoscribe -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Create `PianoScribe/keystore.properties` (it is git-ignored):
   ```properties
   storeFile=../pianoscribe.jks
   storePassword=YOUR_STORE_PASSWORD
   keyAlias=pianoscribe
   keyPassword=YOUR_KEY_PASSWORD
   ```
   `storeFile` is resolved relative to `PianoScribe/app/`.
3. `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`. Check with
   `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`.

CI alternative: add repository secrets `SIGNING_KEYSTORE_BASE64` (`base64 -w0 pianoscribe.jks`),
`SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`.
Android Studio alternative: **Build → Generate Signed App Bundle / APK → APK**.

## How the transcription works

1. **Decode** with `MediaExtractor` + `MediaCodec` (16-bit or float PCM), average channels to mono and stream through
   a Kaiser-windowed sinc resampler to 22 050 Hz, written to disk as float32 so long songs never sit in memory twice.
2. **Model**: the signal is padded with 3 840 zeros and cut into 43 844-sample windows with a hop of 36 164 samples
   (30 frames of overlap), exactly as `basic_pitch/inference.py`. Each window runs through TFLite (XNNPACK, 2–4 threads);
   15 frames are trimmed from each side and the outputs are stitched (86 frames/s, 88 pitches + 264 contour bins).
3. **Notes**: a Kotlin port of `output_to_notes_polyphonic` (onset peak picking, energy tolerance, inferred onsets,
   the "melodia trick"). It was checked against the Python reference on real model output: identical note events and
   amplitudes. Velocity = round(127 × mean activation); confidence blends activation and onset strength.
4. **Tuning**: FFT peak (with parabolic interpolation) near each note's fundamental gives cents sharp/flat; the model's
   contour is the fallback.
5. **Tempo, hands, key**: onset autocorrelation with a tempo prior plus beat-phase search, RH/LH split, and
   Krumhansl–Kessler key finding (whole song and per 16 s section). "Piano only" mode quantises to the grid.

Performance: a 5-minute song is about 185 model windows; on a mid-range phone conversion takes well under two minutes.
Audio is processed in chunks and read from disk by range to avoid running out of memory.

## Model and SoundFont sources and licenses

| Asset | Source | License |
|---|---|---|
| `models/basic_pitch_nmp.tflite` | Spotify Basic Pitch, ICASSP 2022 model, `basic_pitch/saved_models/icassp_2022/nmp.tflite` from the `basic-pitch` 0.4.0 package on PyPI ([github.com/spotify/basic-pitch](https://github.com/spotify/basic-pitch)). SHA-256 `3db297d54af8e01c6e5618245c956b1d71b6a2b978cb2dedb527173186552676`. | Apache License 2.0, © 2022 Spotify AB (full text in `assets/licenses/`) |
| `soundfonts/pianoscribe_piano.sf2` | Generated by this project's own additive piano model (`core/.../synth/PianoSampleGenerator.kt`): 22 samples, one every 4 semitones, 24 kHz. Regenerate with `./gradlew :core:generateSoundFont`. | Apache License 2.0 (part of this project) |

You can load any other General MIDI SoundFont (for example a Salamander or FluidR3 piano `.sf2`) in
**Settings → Piano sound**; bank 0 program 0 is used and only the samples that preset needs are loaded.

## Permissions

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | Recording, and microphone practice mode. Asked only when you use them. |
| `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` (Android 12+), location (Android 8–11) | Only to find Bluetooth LE MIDI keyboards, asked when you tap "Scan Bluetooth". |

No storage permission (the system file picker grants access per file) and **no internet permission**.

## Tests

`./gradlew :core:test` covers: MIDI writer (exact bytes, VLQ, round trip), resampler (length, frequency, level,
anti-aliasing, streaming = one-shot), note post-processing (window geometry, stitching, onsets, energy tolerance,
melodia trick, pitch range, inferred onsets), quantiser, tempo detection, chord naming and inversions, key detection,
cents calculation and measurement, bar:beat conversion, note values, intervals, Sargam, MusicXML well-formedness and
measure layout, analyser steps/filters, CSV export, fingering, undo/redo and edit operations, SF2 write/read and
playback, time-stretch and YIN.

## Limitations

- Polyphonic transcription of dense mixes is approximate; sustain-pedal heavy playing can merge or split notes.
- Microphone practice mode hears one pitch at a time: for chords any chord note counts. Use a MIDI keyboard for full chord checking.
- Sheet music is a basic rendering (one voice per staff, accidentals on every altered note). Export MusicXML to MuseScore for engraving.
- Tempo is detected as one constant tempo per song.
