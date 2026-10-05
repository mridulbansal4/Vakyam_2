# Vakyam

Vakyam is an on-device eye-gaze AAC (Augmentative and Alternative Communication) app for Android.
A person who cannot speak or move selects large tiles by looking at them through the phone's front
camera; an on-device Gemma model turns the selected keywords into a natural sentence, and Android
text-to-speech says it aloud. Nothing leaves the device: there is no network permission.

It is inspired by [Gaze-Link](https://ai.google.dev/competition/projects/gaze-link)
([source](https://github.com/Jonassunhk/Gaze-Link)).

## Features

- **Eye tracking from the front camera** – MediaPipe Face Landmarker (478 landmarks including the
  irises), One-Euro filtered.
- **5-point calibration** that starts by itself, fits an affine map from iris offsets to screen
  pixels, and is saved between launches. It cannot fail: a poor measurement falls back to a
  simpler mapping and the board still opens.
- **Dwell selection** – look at a tile for 0.7 s; a ring fills to show progress.
- **AAC board** – four instant quick needs (Water, Pain, Lights Off, Adjust Bed), three keyword
  categories (Physical Needs, Emotions, Quick Chat) and a two-step alphabet for spelling.
- **Sentence expansion** – `water, cold, please` becomes "Could you please bring me some cold
  water?" using Gemma 4 E2B through LiteRT-LM, streamed into the sentence bar.
- **Speech** – Android `TextToSpeech`.
- **Hands-free recalibration** – close both eyes for 2–5 seconds, then open them.
- **Works without the LLM** – if the model file is missing, quick needs still speak and keyword
  selections are spoken as they are.

Touch also works everywhere, for caregivers and for testing.

## Requirements

| | |
|---|---|
| Device | Samsung Galaxy S23 FE or similar: arm64, Android 12+ (minSdk 31), 8 GB RAM |
| Android SDK | Platform 34, build-tools 34+ |
| JDK | 17 or newer to run Gradle. Android Studio's bundled JBR works. JDK 26 is too new for Gradle 8.13. |
| Models | `face_landmarker.task` (bundled in the APK) and `gemma-4-E2B-it.litertlm` (pushed with adb) |

The APK contains arm64 native libraries only, so it will not run on an x86_64 emulator. To change
that, edit `abiFilters` in `app/build.gradle.kts`.

## Model files

### Face landmarker (bundled)

`app/src/main/assets/face_landmarker.task` is already in place and ships inside the APK. If you
need to replace it, copy the new file over it:

```powershell
Copy-Item Models\face_landmarker.task app\src\main\assets\face_landmarker.task
```

### Gemma 4 E2B (pushed to the device)

The model is about 2.6 GB, too large to bundle. The app looks for
`gemma-4-E2B-it.litertlm` in these places, in order:

1. `/data/data/com.vakyam.app/files/` (app-private storage)
2. `/sdcard/Android/data/com.vakyam.app/files/` (app-specific external storage)
3. `/data/local/tmp/`

**Simplest: push to `/data/local/tmp`**

```powershell
adb push Models\gemma-4-E2B-it.litertlm /data/local/tmp/gemma-4-E2B-it.litertlm
adb shell chmod 644 /data/local/tmp/gemma-4-E2B-it.litertlm
```

**More robust: copy it into app-private storage** (debug builds only, since it uses `run-as`;
install and launch the app once first so the directory exists):

```powershell
adb push Models\gemma-4-E2B-it.litertlm /data/local/tmp/gemma-4-E2B-it.litertlm
adb shell run-as com.vakyam.app cp /data/local/tmp/gemma-4-E2B-it.litertlm files/gemma-4-E2B-it.litertlm
adb shell rm /data/local/tmp/gemma-4-E2B-it.litertlm
```

**For release builds**, or if the device's SELinux policy stops the app reading `/data/local/tmp`:

```powershell
adb push Models\gemma-4-E2B-it.litertlm /sdcard/Android/data/com.vakyam.app/files/gemma-4-E2B-it.litertlm
```

Restart the app after pushing. The status line under the sentence bar says "Language model not
installed" until the file is found.

## Build and run

From the project root in PowerShell:

```powershell
# Only needed if JAVA_HOME points at a JDK that Gradle 8.13 cannot use (for example JDK 26)
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

.\gradlew.bat :app:assembleDebug        # build app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat :app:installDebug         # build and install on the connected device
.\gradlew.bat :app:testDebugUnitTest    # JVM unit tests (gaze maths, calibration, dwell, AAC board)
.\gradlew.bat :app:lintDebug            # Android lint
.\gradlew.bat :app:assembleRelease      # minified build, signed with the debug key
```

`local.properties` must contain `sdk.dir` pointing at the Android SDK; Android Studio writes it
when it opens the project.

Launch and watch the logs:

```powershell
adb shell am start -n com.vakyam.app/.MainActivity
adb logcat -s GazeViewModel GemmaLlmManager FaceLandmarkerHelper CameraSource TtsManager
```

Other useful commands:

```powershell
adb shell dumpsys meminfo com.vakyam.app      # memory footprint (see ARCHITECTURE.md for the budget)
adb shell pm clear com.vakyam.app             # wipe the saved calibration (and any model copy in files/)
adb shell pm grant com.vakyam.app android.permission.CAMERA
```

## Using the app

1. Prop the phone upright, 30–45 cm from the face, front camera at about eye level. Even light on
   the face works best; avoid a bright window behind the user.
2. On launch the app asks for the camera, then shows a countdown with two indicators:
   - **Head alignment** – a head outline to line up with the dashed target. It follows the head,
     not the eyes.
   - **Eye tracking** – two drawn eyes that turn green when the irises are tracked; their pupils
     follow the user's eyes.

   Calibration starts by itself once a face is in view: follow the yellow dot to five positions
   with the eyes, keeping the head still. The board always opens afterwards and "Calibration
   complete" is shown and spoken. If the measurement was poor the status line says "Rough
   calibration" or "Default calibration"; redo it with the gesture in step 4. A caregiver can also
   tap **Skip to Board (Use Default Calibration)**, ideally while the user looks at the middle of
   the screen.
3. On the board, look at a tile until its ring completes.
   - **Yellow tiles** speak immediately.
   - **Blue tiles** open a category. Keyword tiles add a word to the sentence bar.
   - **Speak** (bottom right) turns the keywords into a sentence and says it.
   - **Delete** removes the last letter or keyword. **Home** returns to the first page.
4. If the pointer drifts, close both eyes for about two seconds and open them: calibration restarts.

On the next launch the saved calibration is reused after a 3-second notice. It is only accurate if
the phone and head are roughly where they were, so recalibrate whenever the setup has moved.

## Tuning

| What | Where |
|---|---|
| Dwell time, grace, cooldown, hysteresis | `DwellDetector` constructor defaults |
| Pointer smoothness versus lag | `GazeEstimator` (`minCutoffHz`, `beta`) |
| Calibration timing and target positions | `CalibrationManager` (`settleMs`, `collectMs`, `TARGETS`) |
| Vocabulary | `AacVocabulary.kt` |
| Prompt and examples | `AacPromptTemplate.kt` |
| LLM on GPU instead of CPU | `LLM_PREFER_GPU` in `app/build.gradle.kts` |

## Project status

The project compiles, the unit tests pass, and lint reports no errors. The camera, model loading,
speech and gaze accuracy on a physical device have not been verified yet; the memory and latency
figures in `ARCHITECTURE.md` are estimates until measured on the S23 FE.

See also:

- [`ARCHITECTURE.md`](ARCHITECTURE.md) – how it works, the calibration maths, memory and latency.
- [`AGENT_CONTEXT.md`](AGENT_CONTEXT.md) – orientation for contributors and AI agents.
