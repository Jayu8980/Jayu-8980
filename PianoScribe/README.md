# PianoScribe

**Turn music audio into piano notes, a piano roll, a note-by-note analysis and a MIDI file, fully offline on Android.**

- No `INTERNET` permission, no cloud APIs, no accounts. Every step runs on the device (CI checks the APK has no INTERNET permission).
- Kotlin, Jetpack Compose, Material 3, MVVM + coroutines/Flow, single activity, Room.
- minSdk 26 (Android 8.0), targetSdk 35, compileSdk 36. Package `com.pianoscribe.app`.

> **Accuracy note:** results are best for **solo piano or a single instrument**. Full-band songs (drums, vocals,
> bass, several instruments) give *approximate* results. **Full-song mode** (picked automatically, or chosen on the
> trim screen) removes the drums first and cleans up the notes, which makes songs far cleaner, but expect some
> extra and missing notes.

## Features

| Area | What you get |
|---|---|
| Input | Import MP3, WAV, M4A, AAC, OGG, FLAC, video files (their sound) or MIDI files with the system file picker (Storage Access Framework, no storage permission), or record from the microphone (live waveform, max 10 min). Trim start and end with a preview before converting. You can also "Share" or "Open with" audio into the app. |
| Transcription | MediaExtractor + MediaCodec decode → mono → 22 050 Hz (band-limited resampler) → Spotify **Basic Pitch** in overlapping 2 s windows on a background thread with progress and cancel → note extraction (onset, note and contour outputs) → pitch, start, duration, velocity, confidence and tuning in cents. |
| Full songs | **Audio type: Auto / Solo piano / Full song.** Auto runs a quick harmonic/percussive separation on three excerpts and switches to full-song mode when drums are present. Full-song mode removes the drums before the model listens (median-filter HPSS), then drops overtone ghosts (octave / octave+fifth above a clearly louder note), faint background notes (below 55% of the loudest nearby note) and extra simultaneous notes (max 4 RH / 3 LH). If any audio gives too few notes, a more sensitive pass runs automatically. |
| Audio studio | **Edit & clean audio** on the trim screen. **Check** measures background noise, 50/60 Hz mains hum, room echo (decay time), clipping and level, and suggests what to fix. **Clean up**: remove hum, rumble filter, noise reduction, echo reduction, normalise. **Separate**: remove drums / drums only; remove vocals / vocals only for stereo songs (stereo centre); melody only, bass only, accompaniment only or remove the melody (finds the notes, then keeps or removes that part's harmonics). **Edit** the selection: cut, keep only, silence, fade in/out, volume, normalise; change tempo (50–200%, same pitch) or pitch (±12 semitones, same length). Every step is undoable, "Compare with before" plays the previous version, and the result can be saved or shared as WAV. Converting also removes mains hum and rumble automatically when it finds them (Settings → "Remove hum before converting"). |
| Settings | Onset threshold, note threshold, minimum note length (ms), pitch range limited to piano A0–C8 (MIDI 21–108), "piano only" quantisation to 1/8 or 1/16 with auto-detected tempo (editable BPM), RH/LH split point (default middle C). |
| Views | Piano roll (scroll, pinch-zoom, keyboard on the left, colour by hand, playhead), Synthesia-style falling notes with an on-screen keyboard and finger numbers, **engraved piano score** (see below), note list with Western (C D E) or Indian Sargam (Sa Re Ga) names. |
| Piano score | The **Sheet** tab engraves a real piano score, like a printed one: a grand staff (right hand treble, left hand bass) with brace, clefs, key and time signatures, notes spelled for the key (G# in A minor, Bb in F, F natural in G major), accidentals that carry through the bar, stems, flags and beams grouped by beat, rests, dots, ties (also across lines), ledger lines, staccato and dynamics (pp…ff from how loudly the notes are played), bar numbers and a final barline, using the music font **Bravura**. Rhythms are written the way a person reads them: each beat on the simplest grid that fits (quarters, eighths, then 16ths), small gaps read as legato, short detached notes as staccato, values split to show the beat. Systems fill the screen width (zoom − / +), the playhead follows playback and scrolls, tap a note to select it (and fix it: −1 / +1 semitone, delete) or tap a bar to play from there. **Export → Sheet music, printable (.pdf)** prints the same engraving on A4 pages; MusicXML uses the same spelling. |
| Guitar tab | The **Guitar Tab** tab shows the notes as tablature for a guitar in standard tuning: every chord fingered with one note per string within a four-fret reach (open strings free), and a Viterbi pass that keeps the hand in place where it can (an open E chord comes out as the open shape, 0 0 1 2 2 0). Notes out of range move by octaves; chords of more than six notes keep the bass and the top. Export as **plain-text tab** or as **MusicXML tablature** (TAB staff with string and fret for each note) for Guitar Pro and MuseScore. |
| MIDI files | **Import** also opens **MIDI files** (.mid): their notes become a project straight away (exact timing from the file's tempo map, time signature, title; drums left out; a two-track piano file keeps its right and left hands), with the score, tab, playback and every export. **Video files** (MP4, WebM, …) open like audio: their sound track is used. |
| Make a song | **⋮ → Make a song (chords, bass, drums)** turns the tune into a song: the melody (one note at a time, on the chosen instrument) with chords that fit it, a bass line and drums, in **Pop**, **Ballad**, **Lo-fi** (seventh chords, swung eighths, electric piano) or **Rock** (power chords on organ, driving eighths). Harmony follows the approach of MySong (Simon, Morris & Basu, CHI 2008): every bar gets one chord of the key, scored by how well the melody fits it (chord notes count by length and beat strength, other notes count against), and a Viterbi pass adds the usual progressions (I–IV–V–vi, ii–V, V–I) and starts and ends on the home chord; minor keys use the major V. Chords are voiced close around middle C with smooth voice leading; drums are synthesised on the phone (no samples). Listen in the app, save the mix as WAV, or save MIDI with separate Melody, Chords, Bass and General MIDI drum (channel 10) tracks. On "Ode to Joy" it chooses C G C G \| C G C C \| G C Dm G \| C G C C, the textbook harmony except one bar. |
| Single notes | **Notes: All notes / Single notes** on the trim screen. *Single notes* converts to the tune alone, one note at a time, as it is played (real pitch and timing): at each moment the top note, leaving out the accompaniment (a note starting under a tune note that is still ringing, or a chord well below the tune while it rests), faint overtones and notes the transcription is unsure of; each note ends where the next begins. Tempo and key still come from all the notes. On the device test recording Basic Pitch hears 28 notes for 14 played, and single notes keeps exactly the 8 tune notes. An audio-level melody tracker (Viterbi over the model's pitch salience, in the spirit of Melodia) was built and measured too, and was worse on every test condition (note F1 0.64 / 0.48 / 0.31 clean / phone / room, against 0.79 / 0.72 / 0.60 for this note-based choice), so it is not used. |
| Easy version | **Easy: one note at a time** (the chip above every result view) turns the song into an easy right-hand version for learning: the tune alone, one note at a time. It takes the top note of each moment and leaves out the accompaniment (a note starting under a tune note that is still ringing, or a chord well below the tune while it rests), faint overtones and notes the transcription is unsure of. It simplifies fast runs to at most one note per eighth note, keeping the notes on the stronger beats, and moves the line by octaves into middle C to C6. Piano roll, falling notes, sheet, analyser, practice mode, playback and exports (named "… (easy)") all use it; the full transcription is kept, and editing waits until it is off. On 6 evaluation pieces transcribed by Basic Pitch, 94% of the easy version's notes belong to the real tune (it keeps 66% of the tune, against 81% from exact notes, because fast runs are thinned on purpose). |
| Editing | Tap to select, drag to move, drag the end to resize, long-press to delete, double-tap to add (edit mode), undo/redo, transpose ±12, tempo change (re-grid or stretch), quantise, hand split. |
| Compare & auto-fix | **Compare** tab: the MIDI is turned back into sound and compared with your recording. Shows the share of notes the recording confirms (an estimate of how many are right), outlines the unconfirmed notes on a piano roll, lists notes that seem to be missing, and colours each second by how well it matches. **Auto-fix** removes notes the recording doesn't contain, adds ones it clearly does and joins split notes, keeping a change only if the MIDI sounds closer to the recording. Undoable; verified notes are never touched. |
| Instruments & human feel | Play the (edited) notes on **Piano, Electric piano, Flute, Clarinet, Violin, Cello, Nylon guitar, Harp or Church organ** (instrument chip above the transport bar). Flute, clarinet, violin and cello play one note at a time, so they get the melody line, moved by octaves into their range; guitar plays at most 6 notes at once. The **Human feel** slider (0 = exactly as written) adds what a player does: phrase dynamics, beat accents, melody voiced above the chords, small correlated timing drift, chords rolled low to high (harp) or strummed (guitar), legato, breaths between wind phrases, slurs between close notes (the synth glides instead of re-attacking), delayed vibrato on long wind/string notes, and a slow-down over the last bars. The performance can be saved as MIDI (with the instrument's General MIDI program) or as a WAV audio file. |
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

6. **Full songs**: before step 2, harmonic/percussive separation (median filtering along time and frequency, soft
   mask, 2048-point STFT) keeps tones and drops drum hits; after step 3 the note list is cleaned up as described above.

   Measured on two synthetic songs (piano chords + melody + drum kit, scored against the known notes: right pitch,
   onset within 50 ms). The second song was not used for tuning:

   | | Song 1 notes / correct / F1 | Song 2 (held out) notes / correct / F1 |
   |---|---|---|
   | Mix as-is (before) | 251 / 69 of 70 / 0.43 | 263 / 79 of 80 / 0.46 |
   | Full-song mode | 75 / 63 of 70 / 0.87 | 94 / 74 of 80 / 0.85 |

   Real recordings (vocals, reverb, mastering) are harder than these synthetic songs, so expect lower numbers there.

7. **Compare & auto-fix** (`core/compare`): two independent listeners.
   - The *note model* hears the recording and the MIDI rendered as piano; comparing the two (which keys sound when,
     where notes start) finds stretches the MIDI misses and notes split in two.
   - A *spectral judge* resynthesises each short stretch with and without a note, using the instrument whose sound is
     closest to the recording (piano, electric piano, guitar, harp, organ, or a wind/string for single-line music), and
     keeps the note only if the version with it is closer to the recording in a semitone-band log spectrogram. This
     catches notes the model imagined, which the model itself would "hear" again in its own output.
   - A round of fixes is accepted only if the whole-piece spectral difference goes down.

   Measured with the real model on synthetic test pieces with known notes (F1: right pitch, onset within 50 ms):

   | Test piece | Before | After auto-fix |
   |---|---|---|
   | Solo piano, song 1 / song 2 | 0.67 / 0.69 | 0.90 / 0.92 |
   | Full song (piano + drums), song 1 / song 2 | 0.87 / 0.85 | 0.95 / 0.94 |
   | Electric piano | 0.61 | 0.96 |
   | Piano with room reverb and EQ, song 1 / song 2 | 0.58 / 0.54 | 0.73 / 0.69 |

   The confirmed-notes share tracked the true precision within about ±0.1 before fixing. The reverb rows are the most
   realistic: expect real recordings to land nearer those numbers than the clean ones. 100% is not reachable in
   general: no resynthesis sounds exactly like a real instrument in a real room, and notes hidden under others or
   under drums can't always be recovered.

8. **High-accuracy piano model** (solo piano; Settings → "High-accuracy piano model", on by default): the
   ByteDance high-resolution piano transcription model (Kong et al. 2021; 96.7% note F1 on the MAESTRO real-piano
   test set in the paper) runs on the phone with ONNX Runtime, next to Basic Pitch. It takes 16 kHz audio in 10 s
   segments overlapping by half and regresses, for every 10 ms frame and key, how close an onset or offset is, which
   gives onset times finer than a frame. `core/.../transcribe/PianoModel.kt` is a port of the reference stitching and
   `RegressionPostProcessor`; CI checks it gives exactly the same notes as the official Python package on the same
   audio (18 of 18 recordings identical). The model is converted in CI from the published checkpoint
   (`scripts/piano_model/export_and_reference.py`) and checked against PyTorch (max difference 2e-4). It is fp32
   (99 MB): int8 quantisation was tried and wrecked the output without being faster. Up to 15 minutes of audio; it
   takes about 0.25 s per second of audio on a desktop CPU, more on a phone.

   **Combining the two.** They make different mistakes: the piano model's extra notes are overtones of notes really
   played (an octave, twelfth or two octaves above), Basic Pitch's are repeated onsets inside held notes. So the
   piano model leads and Basic Pitch is a second opinion: a piano-model note Basic Pitch also heard is kept, and an
   unconfirmed one is dropped only when it sits at an overtone interval from a confirmed note starting at the same
   moment (`PianoEnsemble.kt`). Basic Pitch's own extra notes are never added.

   Measured in CI on 6 piano pieces with known notes (`eval/`, 15 s each, with fast repeated notes and chords),
   each as a clean, a room-reverb and a "phone" recording (reverb, boxy EQ, noise at 25 dB SNR). Note F1, right
   pitch with the onset within 50 ms (precision / recall in brackets):

   | Recording | Basic Pitch | Piano model alone | **Combined (what the app does)** |
   |---|---|---|---|
   | Clean | 0.73 (0.59 / 0.98) | 0.78 (0.67 / 0.95) | **0.91** (0.90 / 0.93) |
   | Phone | 0.74 (0.64 / 0.89) | 0.69 (0.55 / 0.94) | **0.88** (0.84 / 0.92) |
   | Room reverb | 0.69 (0.60 / 0.82) | 0.67 (0.52 / 0.94) | **0.83** (0.79 / 0.88) |

   Read these with care. The test pieces are played by this project's synthetic SoundFont piano, whose bright tone
   makes the piano model (trained on real grand pianos) hear overtones as notes, so it scores far lower here than
   on real piano recordings; on a real piano expect it to be closer to its published accuracy, and the combination
   to help less. No transcription is 100% accurate: notes hidden under louder ones, heavy pedal and reverberant rooms
   still cause misses and extras.

### Audio clean-up and separation: what helps

Measured like the compare section: synthetic piano pieces with known notes, damaged on purpose, transcribed by the
real model before and after; numbers are note F1 (onset within 50 ms) averaged over 5 pieces that were **not** used
for tuning.

| Recording | As recorded | After the automatic clean-up |
|---|---|---|
| Clean | 0.72 | 0.72 (left alone) |
| 50 Hz hum at -20 dB | 0.64 | **0.72** |
| 60 Hz hum at -14 dB | 0.61 | **0.73** |
| White noise 15 dB / pink noise 10 dB below the music | 0.77 / 0.74 | unchanged (not needed) |
| Room echo 0.5 / 0.9 / 1.5 s | 0.69 / 0.57 / 0.45 | unchanged (see below) |

Noise and echo reduction are in the studio for listening, but they are not applied automatically: the model copes
with steady noise better than with the changes a noise reducer makes, and both spectral echo subtraction and
WPE de-reverberation (also tried) helped some pieces and hurt others, because they also remove the sustain of
held notes. Room echo is still the hardest case for transcription.

Separation, on a synthetic stereo song (stereo piano spread left/right plus a centre-panned bowed "voice" at the
same level), F1 against the part you want:

| Task | Without | With |
|---|---|---|
| Piano notes in a song with a lead voice | 0.50 | **0.59** (remove vocals) |
| The voice's notes | 0.20 | **0.60** (vocals only) |
| Melody of a piano piece | 0.36 | **0.60** (melody only) |
| Bass line of a piano piece | 0.10 | **0.22** (bass only: low notes share many harmonics with the rest) |

Centre separation needs a stereo file where the voice is in the middle and the instruments are not; a mono file
or a mono piano can't be separated this way.

Performance: a 5-minute song is about 185 model windows; on a mid-range phone conversion takes well under two minutes.
Audio is processed in chunks and read from disk by range to avoid running out of memory.

## Model and SoundFont sources and licenses

| Asset | Source | License |
|---|---|---|
| `models/basic_pitch_nmp.tflite` | Spotify Basic Pitch, ICASSP 2022 model, `basic_pitch/saved_models/icassp_2022/nmp.tflite` from the `basic-pitch` 0.4.0 package on PyPI ([github.com/spotify/basic-pitch](https://github.com/spotify/basic-pitch)). SHA-256 `3db297d54af8e01c6e5618245c956b1d71b6a2b978cb2dedb527173186552676`. | Apache License 2.0, © 2022 Spotify AB (full text in `assets/licenses/`) |
| `models/piano_transcription.onnx` (not in git; CI builds it) | ByteDance high-resolution piano transcription note model ([github.com/bytedance/piano_transcription](https://github.com/bytedance/piano_transcription), inference package `piano_transcription_inference` 0.0.6), checkpoint `CRNN_note_F1=0.9677_pedal_F1=0.9186.pth` from [Zenodo record 4034264](https://zenodo.org/record/4034264), converted to ONNX by `scripts/piano_model/export_and_reference.py`. | Code Apache License 2.0; weights Creative Commons Attribution 4.0 |
| Other instruments | No samples: synthesised on the phone by `core/.../synth/ModelVoices.kt` (wavetables with breath/bow noise and vibrato, Karplus-Strong plucked strings, FM electric piano, additive organ). With a General MIDI SoundFont loaded in Settings, its sample for that instrument is used instead. | Apache License 2.0 (part of this project) |
| `fonts/bravura.otf` | Bravura 1.392, the SMuFL reference music font by Steinberg ([github.com/steinbergmedia/bravura](https://github.com/steinbergmedia/bravura), via the `@vexflow-fonts/bravura` 1.0.2 package). SHA-256 `dca2d90c88437a701b1c2e71fa54e76f9fa41d7deee935d74dc871ea66ecfdd2`. | SIL Open Font License 1.1 (full text in `assets/licenses/`) |
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
cents calculation and measurement, bar:beat conversion, harmonic/percussive separation (tones kept, drums removed,
solo piano vs. piano + drums detection), song clean-up (overtone ghosts, quiet notes, polyphony limit), note values, intervals, Sargam, MusicXML well-formedness and
measure layout, analyser steps/filters, song making (chords that fit the melody in major and minor, voice leading, bass on the roots, backbeat and swing, multi-track MIDI with drums on channel 10, the mixed WAV), MIDI import (tempo maps, hands, drums), guitar fretting (playable shapes, the open E chord, octave moves) and tab text/MusicXML, single notes and the easy version (tune choice, accompaniment left out, fast runs on the beat, range, no overlaps), CSV export, fingering, undo/redo and edit operations, SF2 write/read and
playback, time-stretch and YIN, STFT resynthesis, filters, hum/noise/echo analysis and clean-up, stereo centre and
note-based separation, and the audio edits (cut, crop, silence, fades, gain, normalise, tempo, pitch, WAV).
On a device or emulator, `./gradlew connectedDebugAndroidTest` decodes a stereo test recording and runs the real
model, Room, every export, compare/auto-fix, playback, hum removal before converting, every studio operation with
undo/redo and WAV export, and the main screens including the audio studio.

## Limitations

- Polyphonic transcription of dense mixes is approximate; sustain-pedal heavy playing can merge or split notes.
- Microphone practice mode hears one pitch at a time: for chords any chord note counts. Use a MIDI keyboard for full chord checking.
- The score writes one voice per staff (chords share a stem; a held note under moving ones is tied). Tuplets, pedal marks, slurs and grace notes are not written. Export MusicXML to MuseScore to refine it further.
- Tempo is detected as one constant tempo per song.
