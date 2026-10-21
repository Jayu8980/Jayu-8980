# AutoTube AI

**Type a topic, get a finished, edited, ready-to-upload YouTube video, made entirely on your Android phone.**

AutoTube AI researches a topic, writes a script, records the voiceover, adds word-by-word captions,
music and sound design, cuts the video like a human editor, checks its own work, writes the
title, description, tags and thumbnail, and uploads to YouTube. It works with cloud AI services
when you add API keys, and it also runs **fully offline** with no keys at all: every step has an
on-device fallback. Only the YouTube upload needs internet.

- Kotlin, Jetpack Compose (Material 3), MVVM, Hilt, Coroutines/Flow, Room, WorkManager
- Media3 Transformer + Effects for on-device rendering (1080p H.264/AAC, hardware encoder)
- Android 8.0+ (minSdk 26), compile/target SDK 36, package name `com.autotube.ai`
- English and Hindi (हिन्दी) end to end
- License: Apache 2.0

Architecture details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

## Contents

1. [What it does](#1-what-it-does)
2. [Fully offline / local mode](#2-fully-offline--local-mode)
3. [Screens](#3-screens)
4. [Project structure](#4-project-structure)
5. [Guide 1 - API keys and Google Cloud OAuth](#5-guide-1---api-keys-and-google-cloud-oauth)
6. [Guide 2 - Build in Android Studio](#6-guide-2---build-in-android-studio)
7. [Guide 3 - Build without a PC using GitHub Actions](#7-guide-3---build-without-a-pc-using-github-actions)
8. [First run walkthrough](#8-first-run-walkthrough)
9. [Settings explained](#9-settings-explained)
10. [Phone limits and troubleshooting](#10-phone-limits-and-troubleshooting)
11. [Costs per video](#11-costs-per-video)
12. [Privacy](#12-privacy)
13. [License and content responsibility](#13-license-and-content-responsibility)

---

## 1. What it does

You enter a niche or topic (for example "deep sea creatures"), choose **Shorts** (9:16, up to 60 s)
or **Long-form** (16:9, 1 to 20 min) and a language. The app then runs this pipeline in the
background, with a progress notification, and you can watch every step live:

| Stage | What happens | Cloud provider (optional) | On-device fallback |
|---|---|---|---|
| **Research** | Finds what is trending in your niche (views per day, engagement, recency) and proposes the 5 best fresh topic ideas with a score and reason | YouTube Data API v3 + Claude / OpenAI | Gemma on-device, or idea templates |
| **Script** | Writes a scene-by-scene script: a pattern-breaking hook in the first 5-10 s, intro, main sections, call to action. Each scene has narration, visual description, stock keywords, on-screen text, emotion, intensity and key words | Claude (default `claude-opus-5-5`) or OpenAI | Gemma, or offline script templates |
| **Length management** | Hard limits: Shorts never exceed 59 s, long-form lands within ±5 % of the target. Estimates before the voiceover, measures afterwards, then fine-tunes voice speed (0.9-1.1, pitch preserved), trims pauses or rewrites scenes (max 3 attempts) | LLM rewrites | Template rewrites, on-device time-stretch |
| **Voice** | One clean WAV per scene with emotion-matched delivery, emphasis on key words, natural breaths, long silences trimmed, loudness evened out | OpenAI TTS (`gpt-4o-mini-tts`) or ElevenLabs | Android text-to-speech with word timings |
| **Captions** | Word-level timing, 1-4 word animated chunks, the spoken word highlighted, keywords coloured, sparse emojis, styles Bold pop / Karaoke / Minimal | OpenAI Whisper word timestamps | TTS word timings or smart estimates |
| **Visuals** | Several distinct shots per scene: stock video, AI video, stock photos, AI images, your gallery, on-device art; a vision model checks that each visual matches the narration; never repeats a clip; wide / medium / close-up variety | Pexels, Pixabay, Replicate, OpenAI Images, Claude/OpenAI vision | Procedural on-device art + your own gallery media |
| **Human-style editing** | An "AI editor" plans every cut: varied shot lengths, cuts on breaths, J/L-cuts, pattern interrupts, mostly hard cuts, eased Ken Burns and pans, subtle punch-ins on key words, light handheld feel, text pop-ups exactly when numbers and names are spoken, colour grade per mood, vignette and film grain | LLM edit plan | Built-in heuristic editor (same rules) |
| **Music & sound design** | Mood-matched music that changes with the story (crossfades), drops out before the big line, ducks under the voice, whooshes, risers, impacts and pops on the edit, room tone, loudness normalized to -14 LUFS with a -1 dBTP limiter | none needed | Procedural music/SFX synth, bundled or imported tracks |
| **Render** | Media3 Transformer: 1080p H.264 + AAC with the hardware encoder; automatic 720p retry and a safe muxing fallback | none | Always on-device |
| **Quality check** | Black frames, audio clipping, caption sync, repeated clips, text overflow, visual mismatches, pacing statistics, length; then an AI reviewer rates "human feel" and pacing and requests fixes; up to 2 automatic fix-and-re-render passes | LLM reviewer | Heuristic scores and fixes |
| **SEO + thumbnail** | Title (curiosity, no clickbait lies), description with chapter timestamps, exactly 15 tags, hashtags (#Shorts for Shorts), and a bold-text thumbnail (1280x720 or 1080x1920) | LLM | Built from the script, Canvas thumbnail |
| **Upload** | Saved to your gallery (`Movies/AutoTube AI`), then resumable upload to YouTube: publish now, schedule, or private for review | YouTube Data API v3 (OAuth) | Not possible offline: upload later or manually |

With **Review mode** (on by default) the app pauses twice: to let you pick one of the 5 topic
ideas, and before uploading so you can watch the video, edit the title/description/tags,
regenerate single scenes or re-render.

## 2. Fully offline / local mode

You can use AutoTube AI with **no API keys and no internet**. Leave the keys empty (the Home screen
shows an "Offline mode" chip) or turn on **Settings -> AI providers -> Run fully offline** to stay
on the phone even when keys are saved.

| Part | What runs on the phone |
|---|---|
| Topic ideas and script | The on-device LLM (Gemma via MediaPipe) if you imported a model, otherwise deterministic templates in English and Hindi |
| Voice | Android text-to-speech (for example "Speech Services by Google"), with word timings reported by the engine |
| Captions | Word timings from the TTS engine, or estimates from word length and pauses |
| Music and sound effects | Procedurally generated, royalty-free music for every mood plus synthesized whooshes, risers, impacts, pops and room tone. You can also import your own tracks per mood, or drop files into `app/src/main/assets/music/<emotion>/` and `assets/sfx/<type>/` before building |
| Visuals | Generated on the device (mood gradients, bokeh, light streaks, keyword typography, made for Ken Burns/parallax motion). Optionally your own gallery photos and videos (**Settings -> Visuals -> Use my gallery media**) |
| Editing, mixing, rendering, QC, SEO, thumbnail | Always on the device |
| **Upload** | **Needs internet** and a Google sign-in. Offline videos are saved to the gallery; upload them later from the Review screen or with the YouTube app |

### On-device LLM (Gemma) - optional, better offline scripts

Templates work, but an on-device model writes much more natural scripts. AutoTube AI uses
**MediaPipe LLM Inference** (`tasks-genai` 0.10.24), which loads a Gemma model in **`.task`** format.
Recommended: **Gemma 3 1B instruction-tuned, int4** (about 0.5 GB, works on most phones with 4 GB+ RAM;
6 GB+ RAM recommended).

Download it (you need a free account and must accept the Gemma license once):

- **Hugging Face:** open <https://huggingface.co/litert-community/Gemma3-1B-IT>, sign in, accept
  the license on the model page, open **Files and versions** and download a file ending in
  **`.task`** with `int4`/`q4` in the name (for example `gemma3-1b-it-int4.task`). Pick the `.task`
  file, not `.litertlm`.
- **Kaggle:** open <https://www.kaggle.com/models/google/gemma-3>, accept the license, choose the
  **LiteRT** (TFLite) framework and the `gemma3-1b-it-int4` variation, download. Kaggle gives you
  an archive: extract it (Files app, ZArchiver, or `tar xzf` in Termux) to get the `.task` file.

Import it: **Settings -> AI providers -> Import on-device model (.task)**, pick the file (it is
copied into the app's private storage, so you can delete the download afterwards), then select
**On-device LLM (Gemma, offline)** as the LLM provider or switch on **Run fully offline**.

Notes: the on-device engine runs with a 4096-token context, so prompts are shortened
automatically. The 1B model is strongest in English; for Hindi a larger model (more RAM) or the
offline templates may give better results. If the model's JSON is unusable, the app falls back to
templates instead of failing.

### Hindi offline voice

Install the Hindi voice data once: **Android Settings -> System -> Languages (& input) ->
Text-to-speech output -> Speech Services by Google -> (gear) -> Install voice data -> Hindi (India)**
(on Samsung: Settings -> General management -> Text-to-speech). Then pick the voice in
**Settings -> Voice -> On-device voice**.

## 3. Screens

Bottom navigation: **Create**, **Projects**, **Settings**. Dark Material 3 design.

| Screen | What you do there |
|---|---|
| **Create (Home)** | Topic/niche (+ optional notes under "Advanced"), Long-form / Shorts toggle, length (Shorts 15/30/45/60 s, long-form 1-20 min), language English / हिन्दी, chips showing which providers will be used or "Offline mode", warnings (low storage, battery saver, notifications off), the **Create Video** button and your 3 latest projects |
| **Progress** | The 7 stages (Research, Script, Length check, Voice, Music, Editing, Upload) with sub-steps, progress bars and messages; overall progress ring; live preview (current image and narration); live log; Cancel. In review mode this is where you **pick one of the top 5 topics** |
| **Review** | Video player, editable title (100 chars), description, tags (count/15), hashtags, thumbnail, QC report (human-feel and pacing scores, issues), scene list with **Regenerate** per scene, **Re-render**, upload options (Publish now / Schedule / Private for review), **Approve & Upload**, upload progress and YouTube link, Open in gallery, Share |
| **Projects** | All projects with thumbnail, format, length, language, status and progress; tap to continue, delete with confirmation |
| **Settings** | API keys, AI providers, Voice, Music, Visuals, Look, Defaults, Quality, Device, YouTube (see [Settings explained](#9-settings-explained)) |
| **Error** | What went wrong in plain words, which step failed, **Retry** (resumes at the failed step, finished steps are not redone or re-billed), Open Settings, technical details, View log, Share log |

Notifications: an ongoing progress notification (with Cancel) while a video is being made, and
alerts for "pick a topic", "video ready for review", "failed" and "uploaded"; tapping one opens the
right screen.

## 4. Project structure

```
.
├── .github/workflows/android-build.yml   Cloud build: debug + release APKs, SHA-1, GitHub Release on tags
├── app/
│   ├── build.gradle.kts                  App module: SDK levels, signing (keystore.properties / env), dependencies
│   ├── proguard-rules.pro                R8 rules for release (serialization, Retrofit, Media3, MediaPipe)
│   ├── schemas/                          Room schema export (generated at build time)
│   └── src/main/
│       ├── AndroidManifest.xml           Permissions, foreground service types, FileProvider, no backup
│       ├── assets/music/<emotion>/       Optional bundled royalty-free music per mood (see README inside)
│       ├── assets/sfx/<type>/            Optional bundled sound effects (see README inside)
│       ├── java/com/autotube/ai/         All Kotlin sources (below)
│       └── res/                          Strings, theme, icons, backup rules, FileProvider paths
├── docs/ARCHITECTURE.md                  Layers, DI modules, pipeline, EDL, render graph, audio chain
├── gradle/libs.versions.toml             Version catalog (AGP 8.13, Kotlin 2.2.21, Media3 1.8.0, ...)
├── gradle/wrapper/                       Gradle 8.14.3 wrapper
├── build.gradle.kts, settings.gradle.kts, gradle.properties
├── gradlew, gradlew.bat                  Command-line builds
├── keystore.properties.example           Template for local release signing
├── LICENSE                               Apache License 2.0
└── README.md
```

Kotlin packages under `app/src/main/java/com/autotube/ai`:

```
com/autotube/ai/
├── AutoTubeApp.kt, MainActivity.kt   Hilt application (WorkManager factory, notification channels), single activity
├── core/
│   ├── model/        Serializable domain models: request, script/scenes, audio, captions, visuals, EDL, QC, SEO, project, settings
│   └── common/       Shared Json (AppJson), readable exceptions, withRetry, intent keys, notification channel ids
├── domain/           Interfaces only: repositories, infrastructure, remote APIs, every pipeline step
├── data/
│   ├── local/        Room database (projects stored as JSON), ProjectRepository
│   ├── settings/     Settings + API keys in EncryptedSharedPreferences
│   ├── files/        Per-project folders, log files, secret redaction
│   ├── device/       Storage, battery saver, network and Wi-Fi checks
│   ├── media/        Saving videos/thumbnails to the gallery (MediaStore)
│   ├── google/       Google authorization (youtube.upload) and the resumable YouTube uploader
│   └── remote/
│       ├── http/     Shared OkHttp client, error mapping, header redaction
│       ├── llm/      Claude, OpenAI, on-device MediaPipe/Gemma, provider selection and fallback
│       ├── tts/      OpenAI TTS, ElevenLabs (with timestamps)
│       ├── stt/      OpenAI Whisper word timestamps
│       ├── stock/    Pexels and Pixabay video/photo search
│       ├── aivideo/  Replicate and custom text-to-video endpoints
│       ├── aiimage/  OpenAI Images and Replicate image generation
│       ├── youtube/  YouTube Data API trend research
│       └── download/ Streaming downloads that respect "Wi-Fi only"
├── pipeline/
│   ├── research/     Trending topic research and idea scoring (+ offline idea templates)
│   ├── script/       Scene-by-scene script writer, rewrite/shorten/expand (+ offline templates)
│   ├── length/       Duration estimate and the hard length limits
│   ├── voice/        Voice synthesis, silence trimming, speed changes, Android on-device TTS engine
│   ├── captions/     Word alignment and the animated caption track
│   ├── timeline/     Places scene audio with natural gaps
│   ├── audio/        Audio decode/encode, DSP, loudness, procedural music/SFX, the mixer, Media3 limiter
│   ├── visuals/      Visual sourcing chain, vision check, on-device art, gallery source
│   ├── editor/       AI editor (EDL), heuristic planner, rule normalizer, QC fix application
│   ├── render/       Media3 Transformer render, effects (motion, transitions, grade, captions, overlays)
│   ├── qc/           Automatic quality checks + AI "human feel" review
│   ├── seo/          Title, description, chapters, tags, hashtags
│   ├── thumbnail/    Thumbnail drawing
│   └── orchestrator/ Runs all steps, saves artifacts, resumes, retries, pauses for review
├── work/             WorkManager workers (pipeline, upload), scheduler, notifications
├── di/               Hilt modules: Network, RemoteBindings, Data, Content, Audio, Visuals, Render, Work
└── ui/               theme, navigation, components, home, progress, review, projects, settings, error
```

## 5. Guide 1 - API keys and Google Cloud OAuth

All keys are optional. Paste them in **Settings -> API keys** (each field shows Saved / Missing and
has a link to the provider's page). Keys can be changed at any time; they are encrypted on the
phone (see [Privacy](#12-privacy)).

| Key | Used for | Without it |
|---|---|---|
| Claude (Anthropic) | Research ideas, script, edit plan, vision check, QC review, SEO | OpenAI, Gemma or templates |
| OpenAI | Script/vision (GPT), voice (`gpt-4o-mini-tts`), captions (Whisper), images (`gpt-image-1`) | Other providers or on-device |
| ElevenLabs | Premium voice with timestamps | OpenAI TTS or on-device voice |
| Pexels / Pixabay | Free stock videos and photos | AI or on-device visuals |
| YouTube Data API v3 key | Trend research | Ideas from the LLM or templates |
| Replicate | Optional AI video and AI images | Stock / on-device |
| Google OAuth (not a key) | Uploading to your channel | Manual upload from the gallery |

### Anthropic (Claude)

1. Go to <https://console.anthropic.com> and sign up / sign in.
2. **Settings -> Billing**: add credit (the API is pay as you go).
3. **API Keys -> Create Key**, name it "AutoTube", copy the `sk-ant-...` value (shown once).
4. Paste it in **Settings -> API keys -> Claude (Anthropic) API key**.

The default model is `claude-opus-5-5`; change it in **Settings -> AI providers -> Claude model**
(for example `claude-sonnet-5-5` or `claude-haiku-5-5` to save money, see [Costs](#11-costs-per-video)).

### OpenAI

1. Go to <https://platform.openai.com>, sign in, add a payment method under **Settings -> Billing**.
2. Open <https://platform.openai.com/api-keys> -> **Create new secret key**, copy the `sk-...` value.
3. Paste it in **Settings -> API keys -> OpenAI API key**.

OpenAI is used for GPT scripts and vision (`gpt-4o` by default), TTS `gpt-4o-mini-tts` (voices
alloy, ash, ballad, coral, echo, fable, nova, onyx, sage, shimmer, verse), Whisper word-level
captions, and `gpt-image-1` images when you choose OpenAI as the image provider. Image generation
may require organization verification in the OpenAI console.

### ElevenLabs

1. Sign in at <https://elevenlabs.io>.
2. Click your **profile (bottom left) -> API Keys** (or **Developers -> API Keys**) -> create a key, copy it.
3. Choose a voice in the **Voice Library**, add it to "My Voices", open it and copy its **Voice ID**.
4. Paste the key in Settings and the voice id in **Settings -> Voice -> ElevenLabs voice id**
   (default `21m00Tcm4TlvDq8ikWAM`; model `eleven_multilingual_v2` handles Hindi).

### Pexels

1. Create a free account at <https://www.pexels.com>, open <https://www.pexels.com/api/>.
2. Click **Your API Key** / request access (describe the app briefly), copy the key.

### Pixabay

1. Create a free account at <https://pixabay.com> and stay logged in.
2. Open <https://pixabay.com/api/docs/>: your key is shown in the **Parameters** section (`key`).

**Note on music:** Pixabay's public API covers images and videos only, not its music library. Music
therefore comes from the built-in procedural generator, from tracks bundled in `assets/music/`, or
from royalty-free tracks you import per mood in **Settings -> Music -> Import music for a mood**.

### YouTube Data API key (trend research)

1. Open <https://console.cloud.google.com>. Top bar project picker -> **New project** (name it
   "AutoTube AI") -> **Create**, then select it.
2. **APIs & Services -> Library** -> search **YouTube Data API v3** -> **Enable**.
3. **APIs & Services -> Credentials -> Create credentials -> API key**. Copy the `AIza...` key.
4. Click the key to edit it:
   - **API restrictions -> Restrict key -> YouTube Data API v3** -> Save. Always do this.
   - **Application restrictions -> Android apps -> Add** package `com.autotube.ai` + your SHA-1
     (see [How to get the SHA-1](#how-to-get-the-sha-1)). Caution: Google only accepts calls from a
     key restricted this way when the request carries Android identification headers. If research
     logs **HTTP 403 "Requests from this Android client application ... are blocked"**, set
     Application restrictions to **None** (the API restriction still limits what a leaked key can do).
5. Paste the key in **Settings -> API keys -> YouTube Data API v3 key**.

### Replicate (optional: AI video and images)

1. Sign in at <https://replicate.com>, add billing, open <https://replicate.com/account/api-tokens>,
   create a token (`r8_...`).
2. Paste it, then choose **Settings -> Visuals -> Text-to-video provider: Replicate** (default model
   `minimax/video-01`) and/or **Image provider: Replicate** (default `black-forest-labs/flux-schnell`).
   AI video is only used when stock footage has no good match, and it is the most expensive option.
   A **Custom endpoint** option accepts any service that takes `{prompt, aspect_ratio, duration_sec}`
   and returns `video_url`, `url` or a `status_url` to poll.

### Google OAuth for uploading to YouTube

Uploading uses your Google account through Google Play services. You create an **Android OAuth
client** in Google Cloud; nothing is copied into the app, because Google recognizes the app by its
**package name + SHA-1 signing certificate**. Use the same project as above.

1. Make sure **YouTube Data API v3** is enabled (step 2 above).
2. **APIs & Services -> OAuth consent screen** (newer consoles call this **Google Auth Platform**, with
   pages Branding / Audience / Data Access / Clients). Click **Get started**:
   - **App information:** app name "AutoTube AI", user support email = your Gmail.
   - **Audience:** **External**.
   - **Contact information:** your email -> agree to the policy -> **Create**.
3. **Data Access -> Add or remove scopes**: add `https://www.googleapis.com/auth/youtube.upload`
   (required), plus `https://www.googleapis.com/auth/youtube.readonly` and `email` / `userinfo.email`,
   which the app also requests. (Type the URL into "Manually add scopes" if it isn't listed.) Save.
4. **Audience -> Test users -> Add users**: add the Gmail address of the account that owns your
   YouTube channel. While the app is in **Testing** status only test users can sign in (up to 100).
5. **Credentials -> Create credentials -> OAuth client ID** (or **Clients -> Create client**):
   - Application type: **Android**
   - Package name: **`com.autotube.ai`** (the same for debug and release builds)
   - SHA-1 certificate fingerprint: the SHA-1 of the key that signed the APK you install
   - **Create**. Create one Android client per signing key you use (debug key, your release key,
     the CI key), each with package `com.autotube.ai`.
6. In the app: **Settings -> YouTube -> Sign in** (or "Approve & Upload" on the Review screen),
   pick your account. You will see "Google hasn't verified this app": tap **Continue** (you are a
   test user), allow YouTube access. New settings can take a few minutes to become active at Google.

The Google account needs a YouTube channel (create one in the YouTube app first). While the
consent screen is in Testing, Google may ask you to approve again after about 7 days; just sign in again.

#### How to get the SHA-1

The SHA-1 **must match the key that signed the APK installed on your phone**. Debug builds,
your own release key and CI builds without your keystore are three different keys with three
different SHA-1 values.

- **Android Studio:** Gradle panel (elephant icon, right side) -> **app -> Tasks -> android ->
  signingReport**, double-click; the Run window prints `SHA1:` for the `debug` and `release`
  variants. If the task list is empty, run `signingReport` from the panel's "Execute Gradle task"
  box, or enable **Settings -> Experimental -> Configure all Gradle tasks during Gradle Sync**.
- **Command line:** `./gradlew signingReport` (Windows: `gradlew.bat signingReport`).
- **Debug keystore with keytool:**
  ```bash
  keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android
  # Windows: keytool -list -v -keystore "%USERPROFILE%\.android\debug.keystore" -alias androiddebugkey -storepass android
  ```
  `keytool` ships with Android Studio (`<Android Studio>/jbr/bin/keytool`).
- **Your release keystore:** `keytool -list -v -keystore autotube.jks -alias autotube`
- **Any APK:** `apksigner verify --print-certs app-release.apk` (in the SDK `build-tools/36.0.0/`).
- **GitHub Actions builds:** open the workflow run; the summary shows **"Release APK signing
  certificate"** with the SHA-1 and SHA-256 of the release APK from that run.

#### "DEVELOPER_ERROR (10)"

If sign-in fails with *"Google sign-in is not configured ... (code 10)"* / `DEVELOPER_ERROR`,
Google Play services found no Android OAuth client matching **package name + SHA-1 of the
installed APK** in a project with the YouTube Data API enabled. Typical causes: the SHA-1 belongs
to a different key (you installed a CI build but registered your debug SHA-1, or vice versa), a typo
in the package name, the client was created a few minutes ago (wait and retry), or the API/consent
screen is in a different Google Cloud project. Fix: read the SHA-1 of the APK you actually installed
and add an Android client for it.

#### Quotas

The YouTube Data API gives every project **10,000 units per day** (reset at midnight Pacific
time). One upload costs about **1,600 units**, setting a thumbnail 50, each research search about 100.
That is roughly 5-6 uploads per day. See usage in **APIs & Services -> YouTube Data API v3 -> Quotas**;
over quota you get `403 quotaExceeded` until the reset.

#### Audit for public uploads (important)

Videos uploaded through the API by an **unverified API project** (projects created after
28 July 2020 that have not passed Google's compliance audit) are **restricted to private**,
even if you chose "Publish now", and you may not be able to make them public afterwards. To lift
this, the API project must pass the YouTube API Services audit:

1. Read the YouTube API Services Terms of Service and Developer Policies.
2. Prepare a short description of your use (personal channel automation), and a privacy policy URL
   if asked.
3. Submit the **YouTube API Services - Audit and Quota Extension Form**:
   <https://support.google.com/youtube/contact/yt_api_form>. The same form requests more quota.
4. Wait for Google's reply (days to weeks).

Until then: use **Private for review** (the default) and check videos in YouTube Studio, or upload
the gallery copy manually with the YouTube app. This audit is separate from OAuth app verification,
which you only need if people other than your test users should sign in.

## 6. Guide 2 - Build in Android Studio

**Requirements**

- Latest stable **Android Studio** (AGP 8.13 needs Narwhal 3 Feature Drop 2025.1.3 or newer).
- **JDK 17+** (the JDK bundled with Android Studio works; CI uses Temurin 17).
- **SDK Platform 36** (Android 16) and Build-Tools 36: **Tools -> SDK Manager -> SDK Platforms**
  (check Android 16 / API 36) and **SDK Tools** (Build-Tools).
- About 5 GB free disk space for Gradle and dependencies. A phone with Android 8.0+ (4 GB+ RAM).

**Steps**

1. **File -> Open**, select the project folder (the one containing `settings.gradle.kts`).
2. Wait for **Gradle sync** (the first sync downloads Gradle 8.14.3 and all libraries). If asked,
   trust the project and accept SDK licenses.
3. **Run on your phone:** on the phone open **Settings -> About phone**, tap **Build number** 7 times,
   then **Developer options -> USB debugging** on. Connect by USB, allow the computer, select the phone
   in the device dropdown and press **Run** (green triangle). Wireless debugging (Android 11+) also works.
4. **Build an APK file:** **Build -> Generate App Bundles or APKs -> Generate APKs** (older versions:
   **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**). Click **locate** in the notification:
   `app/build/outputs/apk/debug/app-debug.apk`.
5. **Signed release APK:** **Build -> Generate Signed App Bundle or APK...** -> **APK** -> Next ->
   **Create new...** key store: choose a path outside the repository (or the project root, where
   `*.jks` is git-ignored), set passwords, alias `autotube`, validity 25+ years, your name -> OK ->
   Next -> variant **release** -> **Create**. The wizard shows the destination folder (default
   `app/release/app-release.apk`). **Keep the .jks file and passwords safe and back them up**: every
   future update must be signed with the same key. Register this key's SHA-1 in Google Cloud.
6. **Alternative, reproducible release signing:** copy `keystore.properties.example` to
   `keystore.properties` in the project root, fill in `storeFile`, `storePassword`, `keyAlias`,
   `keyPassword` (relative `storeFile` paths are resolved from the `app/` folder, e.g. `../autotube.jks`),
   then build with `./gradlew assembleRelease`. Never commit `keystore.properties`. Without it (or if
   the file path is wrong) the release build is signed with the debug key so it still installs.

**Command-line equivalents** (Windows: `gradlew.bat` instead of `./gradlew`):

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk (R8-minified)
./gradlew installDebug         # build and install on the connected phone
./gradlew signingReport        # SHA-1 / SHA-256 of the debug and release signing keys
adb install -r app/build/outputs/apk/release/app-release.apk
```

Signing can also come from environment variables (used by CI): `SIGNING_STORE_FILE`,
`SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`; `VERSION_CODE` and
`VERSION_NAME` set the version (defaults 1 and 1.0.0). For command-line builds without Android
Studio, set `ANDROID_HOME` or `sdk.dir` in `local.properties`.

Debug and release builds share the package name `com.autotube.ai`, so only one of them can be
installed at a time (one Google OAuth package name covers both).

## 7. Guide 3 - Build without a PC using GitHub Actions

You can build the APK entirely from your phone's browser; GitHub's servers do the work for free.

1. **GitHub account:** open <https://github.com/signup> in the phone browser and create an account.
   Tip: if buttons are missing, use the browser menu -> **Desktop site**.
2. **Put the project in a repository:**
   - It is already your repository: nothing to do.
   - **Fork** it: open the project page -> **Fork** -> Create fork. Then open your fork's
     **Actions** tab and tap **"I understand my workflows, go ahead and enable them"** (forks have
     Actions disabled by default).
   - Or **Import**: <https://github.com/new/import> with the project's Git URL.
   - Or upload from Termux: `pkg install git`, then `git init`, `git add .`, `git commit -m init`,
     `git remote add origin https://github.com/<you>/<repo>.git`, `git push -u origin main`
     (use a personal access token as the password).
3. The workflow file is **`.github/workflows/android-build.yml`** (full content below).
4. **Actions tab -> "Build APK" -> Run workflow -> Run workflow** (green button). Any push of code
   also starts it (changes to `*.md` and `docs/` only do not, use Run workflow then).
5. Wait about **10 minutes** until the run has a green check.
6. Open the run -> scroll to **Artifacts** -> download **AutoTubeAI-release-apk** (a zip; you must be
   signed in). Artifacts are kept for 90 days.
7. Open the zip with the **Files** app -> **Extract** -> tap **AutoTubeAI-release.apk**.
8. Android asks to allow **Install unknown apps** for the Files app (or browser): allow it, go back,
   tap **Install**. If Play Protect warns about an unknown developer, tap **More details -> Install anyway**.

The run summary also shows the **"Release APK signing certificate"** SHA-1: register it as an
Android OAuth client (see [Guide 1](#google-oauth-for-uploading-to-youtube)).

### The workflow file

```yaml
# Builds AutoTube AI in the cloud: no PC needed.
# Every push builds a debug APK and a release APK and attaches them to the run as downloadable artifacts.
# Pushing a tag like v1.0.0 also publishes a GitHub Release with the APKs attached.
#
# Optional repository secrets (Settings -> Secrets and variables -> Actions) for a properly signed release APK:
#   SIGNING_KEYSTORE_BASE64  base64 of your release .jks file
#   SIGNING_STORE_PASSWORD   keystore password
#   SIGNING_KEY_ALIAS        key alias
#   SIGNING_KEY_PASSWORD     key password
# Without them the release APK is signed with the CI debug key (still installable).
name: Build APK

on:
  push:
    branches: ["**"]
    tags: ["v*"]
    paths-ignore: ["**.md", "docs/**"]
  pull_request:
  workflow_dispatch:

permissions:
  contents: write

concurrency:
  group: build-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 45
    steps:
      - name: Check out code
        uses: actions/checkout@v5

      - name: Set up JDK 17
        uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: "17"

      - name: Install Android SDK platform 36 and build tools
        # GitHub's ubuntu runners ship the Android SDK at $ANDROID_HOME with licenses accepted.
        run: |
          SDKMANAGER="$(ls -d "$ANDROID_HOME"/cmdline-tools/*/bin | sort -V | tail -1)/sdkmanager"
          yes | "$SDKMANAGER" --licenses > /dev/null || true
          "$SDKMANAGER" "platforms;android-36" "build-tools;36.0.0" "platform-tools"

      - name: Set up Gradle (with caching)
        uses: gradle/actions/setup-gradle@v4

      - name: Make gradlew executable
        run: chmod +x ./gradlew

      - name: Decode release keystore (if provided)
        env:
          SIGNING_KEYSTORE_BASE64: ${{ secrets.SIGNING_KEYSTORE_BASE64 }}
        run: |
          if [ -n "$SIGNING_KEYSTORE_BASE64" ]; then
            echo "$SIGNING_KEYSTORE_BASE64" | base64 --decode > "$RUNNER_TEMP/release.jks"
            echo "SIGNING_STORE_FILE=$RUNNER_TEMP/release.jks" >> "$GITHUB_ENV"
            echo "Release keystore found: the release APK will be signed with your key."
          else
            echo "No SIGNING_KEYSTORE_BASE64 secret: the release APK will use the debug key."
          fi

      - name: Build debug and release APKs
        env:
          SIGNING_STORE_PASSWORD: ${{ secrets.SIGNING_STORE_PASSWORD }}
          SIGNING_KEY_ALIAS: ${{ secrets.SIGNING_KEY_ALIAS }}
          SIGNING_KEY_PASSWORD: ${{ secrets.SIGNING_KEY_PASSWORD }}
          VERSION_CODE: ${{ github.run_number }}
        run: ./gradlew --no-daemon --stacktrace assembleDebug assembleRelease

      - name: Rename APKs
        run: |
          mkdir -p dist
          cp app/build/outputs/apk/debug/*.apk dist/AutoTubeAI-debug.apk
          cp app/build/outputs/apk/release/*.apk dist/AutoTubeAI-release.apk
          ls -la dist

      - name: Print signing certificate SHA-1 (register it in Google Cloud for YouTube sign-in)
        run: |
          APKSIGNER="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)/apksigner"
          echo "### Release APK signing certificate" >> "$GITHUB_STEP_SUMMARY"
          "$APKSIGNER" verify --print-certs dist/AutoTubeAI-release.apk | grep -E "SHA-1|SHA-256" | tee -a "$GITHUB_STEP_SUMMARY"

      - name: Upload debug APK
        uses: actions/upload-artifact@v4
        with:
          name: AutoTubeAI-debug-apk
          path: dist/AutoTubeAI-debug.apk

      - name: Upload release APK
        uses: actions/upload-artifact@v4
        with:
          name: AutoTubeAI-release-apk
          path: dist/AutoTubeAI-release.apk

      - name: Publish GitHub Release (tags only)
        if: startsWith(github.ref, 'refs/tags/v')
        uses: softprops/action-gh-release@v2
        with:
          files: |
            dist/AutoTubeAI-release.apk
            dist/AutoTubeAI-debug.apk
```

### Signed release from the phone (recommended)

Without signing secrets, CI signs the release APK with a temporary debug key that is usually
**different on every run**. Then the SHA-1 changes every build (Google sign-in breaks) and Android
refuses to install a new build over the old one. Create your own key once:

1. Install **Termux** (from F-Droid; the Play Store version is outdated) and run:
   ```bash
   pkg update && pkg install openjdk-17
   keytool -genkeypair -v -keystore autotube.jks -keyalg RSA -keysize 4096 -validity 10000 -alias autotube
   base64 -w0 autotube.jks > autotube.b64
   termux-setup-storage
   cp autotube.jks autotube.b64 ~/storage/downloads/
   ```
   `keytool` asks for a password and your name; it uses the store password for the key too.
   **Back up `autotube.jks` and the password** (cloud drive + password manager).
2. Open `autotube.b64` from Downloads in a text editor and copy all of its text.
3. In your repository: **Settings -> Secrets and variables -> Actions -> New repository secret**, add:

   | Secret | Value |
   |---|---|
   | `SIGNING_KEYSTORE_BASE64` | the whole text of `autotube.b64` |
   | `SIGNING_STORE_PASSWORD` | your keystore password |
   | `SIGNING_KEY_ALIAS` | `autotube` |
   | `SIGNING_KEY_PASSWORD` | the same password (PKCS12 keystores use one password) |

4. Run the workflow again. The log says "Release keystore found"; the summary shows your key's
   SHA-1, which now stays the same for every build. Register it in Google Cloud (Android OAuth client,
   package `com.autotube.ai`), then delete `autotube.b64` from Downloads.

**GitHub Release:** push a tag such as `v1.0.0` (on the web: **Releases -> Draft a new release ->
Choose a tag -> type `v1.0.0` -> Create new tag -> Publish release**; or `git tag v1.0.0 && git push origin v1.0.0`).
The workflow attaches `AutoTubeAI-release.apk` and `AutoTubeAI-debug.apk` to that release. If the
release step fails with a permission error, set **Settings -> Actions -> General -> Workflow
permissions -> Read and write**.

**Updating:** install a newer APK signed with the **same key** over the old one; your projects,
settings and keys are kept. CI sets the version code to the run number, so newer runs always
update older ones. A different key ("App not installed" / package conflict) requires uninstalling
first, which deletes all app data.

## 8. First run walkthrough

1. **Install and open** the app. On Android 13+ allow **notifications** (needed to see progress and
   to keep long renders running in the background).
2. **Choose your mode:**
   - **Offline:** do nothing; the Home screen shows "Offline mode". Optionally import a Gemma model
     and install Hindi voice data ([section 2](#2-fully-offline--local-mode)).
   - **Cloud:** add keys in **Settings -> API keys** (a good start: Claude or OpenAI + Pexels).
   - **Upload:** set up OAuth ([Guide 1](#google-oauth-for-uploading-to-youtube)) and sign in under
     **Settings -> YouTube**.
3. **Create your first Short:** on **Create** type a niche, keep **Shorts**, **60 s** and **English**
   (the defaults), tap **Create Video**.
4. On **Progress**, after research, **pick one of the top 5 topics** (review mode).
5. Wait for the stages to finish (a Short usually takes a few minutes; long-form much longer). You
   can leave the app; the notification shows progress.
6. On **Review**, watch the video, edit the text, regenerate any weak scene, then choose
   **Private for review** and **Approve & Upload**. The video is already in your gallery
   (`Movies/AutoTube AI`) either way.

Permissions: notifications (progress); photos and videos only if you turn on "Use my gallery
media"; storage on Android 8/9 to save into the gallery. Nothing else.

## 9. Settings explained

| Section | Settings (defaults) |
|---|---|
| **API keys** | One field per service, masked, with a Saved / Missing chip and a link to get the key |
| **AI providers** | LLM provider: Claude (default) / OpenAI / On-device LLM / Offline templates; Claude model `claude-opus-5-5`; OpenAI model `gpt-4o`; Import on-device model (.task); **Run fully offline** (off) |
| **Voice** | TTS provider: OpenAI TTS (default) / ElevenLabs / On-device; OpenAI voice `onyx`; ElevenLabs voice id; on-device voice per language; speech-to-text: Whisper (default) / On-device timing |
| **Music** | Style: Auto (match the mood) / Cinematic / Lo-fi / Electronic / Acoustic / Ambient; music volume (0.8); sound effects (on); ambient room tone (on); Import music for a mood |
| **Visuals** | Stock footage (on); text-to-video provider (Off) + Replicate model + custom endpoint; image provider (Off = on-device art) + models; use my gallery media (off); vision check (on), minimum match score (6/10) |
| **Look** | Colour grading, film grain, vignette, handheld shake (all on); caption style Bold pop (word by word) / Karaoke / Minimal / Off; emojis in captions (on) |
| **Defaults** | Format **Shorts**; Shorts length **60 s**; long-form length **8 min**; language **English** (Hindi supported); **Review mode** (on) |
| **Quality** | Quality check (on); max fix passes 0-2 (2); minimum human-feel score (7/10) |
| **Device** | Wi-Fi only (off); minimum free storage (1500 MB); delete temp files after export (on); storage used + Clear all temporary files |
| **YouTube** | Sign in / Sign out with Google (shows the email); default upload mode **Private (review on YouTube)** / Publish now / Schedule; made for kids (off) |

Fixed defaults: loudness target -14 LUFS, trend region US, YouTube category 22 (People & Blogs).
Schedule needs a time at least 15 minutes in the future (uploaded private with a publish time).

## 10. Phone limits and troubleshooting

**Storage.** Each step checks free space (default minimum 1500 MB). A 60 s Short needs a few hundred
MB while rendering; an 8-minute video can need 2-4 GB (stock clips, voice, a ~700 MB render at
12 Mbps). Keep "Delete temp files after export" on and use **Settings -> Device -> Clear all
temporary files**. Error "Not enough storage": free space and tap **Retry**.

**Battery saver.** Rendering is slower and Android may delay background work; the app warns you.
Charge or turn battery saver off for long videos.

**Wi-Fi only.** Downloads and uploads wait for Wi-Fi ("Waiting for Wi-Fi"); uploads are scheduled
for an unmetered network. Turn the option off to use mobile data.

**Background limits.** Video creation runs as a foreground service with a notification; don't
dismiss or block that notification. Some phones still kill background apps:

- **Xiaomi / Redmi / POCO (MIUI, HyperOS):** Settings -> Apps -> Manage apps -> AutoTube AI ->
  **Battery saver -> No restrictions**, turn **Autostart** on, and lock the app in Recents
  (long-press -> lock).
- **Samsung (One UI):** Settings -> Apps -> AutoTube AI -> **Battery -> Unrestricted**; Settings ->
  Battery -> **Background usage limits -> Never sleeping apps -> add AutoTube AI**.
- **Pixel / stock Android:** Settings -> Apps -> AutoTube AI -> App battery usage -> **Unrestricted**.
- Other brands: <https://dontkillmyapp.com>.

If work was stopped, open the project and tap **Retry**: finished steps are kept.

**Encoder failures.** If the hardware encoder rejects 1080p, the render automatically retries at
720p with a lower bitrate, and a separate audio/video muxing fallback is used if needed. If it still
fails: close camera/video apps, restart the phone, try Shorts, or share the log.

**Logs.** Each project keeps a log at `files/projects/<id>/logs/project.log` in the app's private
storage (rotated at 2 MB). Open it from **Progress** (live log) or the **Error** screen (**View log**,
**Share log**). API keys are redacted. Developers: `adb logcat -s AutoTube`.

**Common API errors**

| Error | Meaning | What to do |
|---|---|---|
| 401 / 403 | Key wrong, revoked, or not allowed (YouTube: wrong restriction, sign-in expired) | Re-copy the key; for YouTube check restrictions or sign in again |
| 402 | No credit left on the account | Top up billing (ElevenLabs/Replicate) |
| 400 "credit balance is too low" (Anthropic) | No Claude credit | Add credit in the Anthropic console |
| 429 | Rate limit (OpenAI `insufficient_quota` = no credit) | Retried automatically with backoff; otherwise wait, add credit or slow down |
| 5xx / 529 | Provider overloaded | Retried automatically; tap Retry later |
| 404 model not found | Model name typo in Settings | Fix the model id |
| 403 `quotaExceeded` (YouTube) | Daily quota used up | Wait for the reset at midnight Pacific time |
| DEVELOPER_ERROR (10) | OAuth client / SHA-1 mismatch | See [DEVELOPER_ERROR](#developer_error-10) |
| "Hindi voice data missing" | On-device Hindi TTS not installed | Install voice data ([section 2](#hindi-offline-voice)) |
| "App not installed" | Different signing key, or older version code | Uninstall the old app (data is lost) or install a build signed with the same key |

## 11. Costs per video

Rough ballpark at list prices when this was written; check each provider's pricing page. Offline
mode, Pexels, Pixabay and the YouTube API (within quota) are free.

| Item | 60 s Short | 8 min long-form |
|---|---|---|
| Claude `claude-opus-5-5` ($4 / $20 per million input/output tokens): research, script, edit plan, vision checks, QC, SEO | ~$0.30-1.00 | ~$2-5 (vision checks dominate) |
| ... with `claude-sonnet-5-5` ($2 / $10) or `claude-haiku-5-5` ($0.10 / $0.50) | about half / a few cents | about half / ~$0.10-0.20 |
| OpenAI TTS `gpt-4o-mini-tts` (~$0.015 per minute) | ~$0.02 | ~$0.15 |
| ElevenLabs (~1,000 characters per minute of speech, from plan credits) | ~1,000 credits | ~8,000 credits |
| Whisper captions (~$0.006 per minute) | <$0.01 | ~$0.05 |
| OpenAI images `gpt-image-1` (only when stock fails) | ~$0.02-0.25 per image | same |
| Replicate AI video (e.g. `minimax/video-01`) | ~$0.50 per clip | can add several dollars |
| Replicate images (`flux-schnell`) | ~$0.003 per image | same |

Typical totals: Claude + OpenAI TTS + Whisper + stock footage is about **$0.50-1.20 per Short** and
**$3-6 per 8-minute video**. Turning off the vision check, using a cheaper Claude model or the
on-device voice lowers this a lot. Regenerating scenes and QC passes add some cost. ElevenLabs' free
plan requires attribution and is non-commercial.

## 12. Privacy

- API keys are stored **encrypted on the device** with EncryptedSharedPreferences (AES-256, master
  key in the Android Keystore), separate from the other settings. They are never written to the
  database, logs or exports, and are redacted from logs. (On rare phones with a broken keystore the
  app falls back to private app storage and logs a warning.)
- **Nothing is backed up**: `allowBackup` is off and cloud backup/device transfer exclude all app
  data. After reinstalling or changing phones, enter your keys again.
- Each key is sent only to its own service over HTTPS. What leaves the phone depends on the
  providers you enable: topic and script text to the LLM, preview frames to the vision model,
  narration to the TTS service, voice audio to Whisper, search keywords to Pexels/Pixabay, the final
  video to YouTube. In offline mode nothing leaves the phone.
- Gallery media you allow is used only to build your video on the phone.
- There is no analytics or tracking, and no server of ours.

## 13. License and content responsibility

AutoTube AI is licensed under the **Apache License 2.0**, see [LICENSE](LICENSE). Libraries keep
their own licenses; Gemma models are under the Gemma Terms of Use.

You are responsible for what you publish:

- **Stock media:** Pexels and Pixabay content is free to use under their licenses (attribution not
  required but appreciated; no portraying identifiable people offensively, no reselling unaltered
  copies). The app records the source of each stock clip; crediting creators in the description is
  good practice. Imported music must be royalty-free or licensed by you.
- **YouTube policies:** follow the Community Guidelines, copyright rules and the YouTube Partner
  Program policies. Mass-produced, repetitive videos may not be monetized ("inauthentic content");
  review each video and add your own value. Set "made for kids" honestly.
- **AI-content disclosure:** YouTube requires you to disclose realistic altered or synthetic content
  (for example realistic AI-generated people, places or events). In **YouTube Studio -> Content ->
  your video -> Details -> Altered content**, answer **Yes** when it applies. Clearly unrealistic or
  animated visuals and AI help with scripts or captions generally don't need it; if in doubt, disclose.
- Check facts in AI-written scripts before publishing.
