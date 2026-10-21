# Build and install the APK

## Option A: Android Studio (recommended)

1. Install **Android Studio** (Ladybug or newer). The bundled JDK 17+ is fine.
2. *File → Open* and select the **`android/`** folder (not the repo root). Let Gradle sync. The
   first sync downloads the Android Gradle Plugin, Compose and Room.
3. If Android Studio asks for an SDK, install **Android SDK Platform 35** from *Tools → SDK Manager*.
4. Pick a device: a phone with *Developer options → USB debugging* on, or an emulator from the
   Device Manager (API 26+).
5. Press **Run ▶**. The app installs and opens with the sample bank, PYQ analysis and formula sheets
   ready offline.
6. To build an APK file: *Build → Build App Bundle(s) / APK(s) → Build APK(s)*. The file is
   `android/app/build/outputs/apk/debug/app-debug.apk`. Copy it to any phone and open it to install
   (allow "install unknown apps").

## Option B: command line

```bash
cd android
./gradlew :core:test            # core logic unit tests (no Android SDK needed for this module)
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease  # minified; signed with the debug key so it installs as-is
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

This needs `ANDROID_HOME` (or `android/local.properties` with `sdk.dir=...`) pointing at an Android
SDK with platform 35.

Point the app at your backend at build time:

```bash
./gradlew :app:assembleDebug -PbackendUrl=https://your-backend.example.com
```

The URL can also be changed at any time in the app: Settings → AI backend.

## Option C: GitHub Actions

Every push runs `.github/workflows/build.yml`. Download the **gate-ee-apk** artifact from the run
page; it contains the debug and release APKs.

## Release signing for the Play Store

Create a keystore (`keytool -genkey -v -keystore gate.jks -keyalg RSA -keysize 2048 -validity 10000 -alias gate`),
add a `signingConfigs { create("release") { ... } }` block in `app/build.gradle.kts`, and use it
instead of `signingConfigs.getByName("debug")`. Never commit the keystore (`*.jks` is git-ignored).

## Running the backend for the app

```bash
cd backend && npm install && cp .env.example .env   # set ANTHROPIC_API_KEY and APP_CLIENT_TOKEN
npm start
```

* Emulator → `http://10.0.2.2:8080`. This is the default and is allowed over plain HTTP.
* A real phone needs an HTTPS URL. Deploy the backend with the root `backend/Dockerfile`
  (`docker build -f backend/Dockerfile -t gate-ee-backend .`) to any container host (Cloud Run,
  Render, Fly.io, a VPS behind Caddy or nginx). Then enter that URL and the `APP_CLIENT_TOKEN` in
  Settings.

## Bundling the mock series in the APK

```bash
cd backend && npm run build-series     # writes data/generated/TT01.json ... FM10.json
```

The `data/` folder is the app's asset folder, so the next APK build includes these papers. On first
launch they are imported as ready-to-take series tests.
