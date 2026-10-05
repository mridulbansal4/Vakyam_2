# Agent context

Orientation for AI agents and new contributors. Read this first, then `ARCHITECTURE.md` for the
maths and the reasoning behind the numbers.

## What this is

An Android app that lets a person with no speech and no limb movement talk by looking at tiles.
Front camera → face landmarks → gaze point → dwell selection → keywords → Gemma sentence → speech.
Everything runs on the device.

The user of this app cannot touch the screen. **Every flow must be completable with the eyes
alone.** Touch controls exist only as a convenience for a caregiver. If you add a step that needs
a tap, you have locked the user out.

## Build and verify

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"   # JDK 17–21; JDK 26 breaks Gradle 8.13
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

- Gradle 8.13, AGP 8.13.0, Kotlin 2.4.0, compileSdk 34, minSdk 31.
- Kotlin must stay at 2.4 or newer: LiteRT-LM 0.17.1 pulls in `kotlin-stdlib` 2.4.0 and an older
  compiler cannot read it.
- Library versions are chosen to compile against SDK platform 34. Newer AndroidX releases need
  compileSdk 35 or 36; raise both together.
- If a build fails with a locked file under `app\build` or "Could not move temporary workspace",
  run `.\gradlew.bat --stop` and retry. It is Windows file locking, not a code problem.
- Renaming or moving a resource folder can leave stale merge state; delete `app\build` afterwards.

## Directory tree

```
Vakyam/
├── settings.gradle.kts            repositories, module list
├── build.gradle.kts               plugin versions (AGP, Kotlin, Compose compiler)
├── gradle.properties
├── Models/                        source model files; the .litertlm is git-ignored
├── app/
│   ├── build.gradle.kts           SDK levels, dependencies, LLM_PREFER_GPU, arm64 filter
│   ├── proguard-rules.pro         keeps MediaPipe and LiteRT-LM classes used over JNI
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/face_landmarker.task
│       │   ├── res/
│       │   └── java/com/vakyam/app/
│       │       ├── MainActivity.kt
│       │       ├── state/AppState.kt
│       │       ├── camera/CameraSource.kt
│       │       ├── vision/FaceLandmarks.kt
│       │       ├── vision/FaceLandmarkerHelper.kt
│       │       ├── gaze/OneEuroFilter.kt
│       │       ├── gaze/GazeEstimator.kt
│       │       ├── calibration/AffineCalibration.kt
│       │       ├── calibration/CalibrationManager.kt
│       │       ├── dwell/DwellDetector.kt
│       │       ├── aac/AacVocabulary.kt
│       │       ├── aac/AacIntentDispatcher.kt
│       │       ├── llm/AacPromptTemplate.kt
│       │       ├── llm/GemmaLlmManager.kt
│       │       ├── tts/TtsManager.kt
│       │       └── ui/
│       │           ├── GazeViewModel.kt
│       │           ├── GazeApp.kt
│       │           ├── CalibrationScreen.kt
│       │           ├── AacMainScreen.kt
│       │           ├── components/GazeTile.kt
│       │           ├── components/GazePointer.kt
│       │           └── theme/Theme.kt
│       └── test/java/com/vakyam/app/
│           ├── GazePipelineTest.kt        estimator, filter, affine solver, calibration flow
│           └── InteractionLogicTest.kt    dwell, AAC board, prompt cleanup, state machine
├── README.md
├── ARCHITECTURE.md
└── AGENT_CONTEXT.md
```

## File responsibilities

| File | Responsibility | Depends on Android? |
|---|---|---|
| `MainActivity.kt` | Edge-to-edge, keep screen on, hide system bars, host `GazeApp` | yes |
| `state/AppState.kt` | `AppState` enum and `AppStateMachine` with the allowed-transition table | no |
| `camera/CameraSource.kt` | Binds front-camera `ImageAnalysis`; turns each frame into an upright bitmap; always closes the `ImageProxy` | yes |
| `vision/FaceLandmarks.kt` | Plain holder for one frame's landmarks (normalised x/y arrays, image size, timestamp) | no |
| `vision/FaceLandmarkerHelper.kt` | Creates MediaPipe `FaceLandmarker` (LIVE_STREAM, CPU); `detectAsync`; result and error callbacks | yes |
| `gaze/OneEuroFilter.kt` | Speed-adaptive low-pass filter for one scalar | no |
| `gaze/GazeEstimator.kt` | Landmarks → `GazeSample` (raw and filtered iris offsets, eye openness, face position); `ScreenPoint` | no |
| `calibration/AffineCalibration.kt` | `AffineCalibration` (the map), `CalibrationQuality`, `CalibrationSample`, `AffineSolver` (`solve` = affine `fit` → `fitAxisAligned` → `defaultCalibration`) | no |
| `calibration/CalibrationManager.kt` | 5-point flow driven by `onSample`, with a per-point time budget; `useDefault` for skipping; holds the active calibration; `CalibrationStore` and its SharedPreferences implementation | store only |
| `dwell/DwellDetector.kt` | Target registry, dwell timer, hysteresis, grace, cooldown; `GazeRect`, `DwellState` | no |
| `aac/AacVocabulary.kt` | Quick needs, categories, keywords, letter groups – data only | no |
| `aac/AacIntentDispatcher.kt` | Board reducer: `AacIntent` → new `AacUiState` + `AacEffect`; builds the tiles of each page | no |
| `llm/AacPromptTemplate.kt` | System instruction, few-shot examples, user turn, output cleanup, fallback sentence | no |
| `llm/GemmaLlmManager.kt` | Finds and loads the model with LiteRT-LM; `expand(keywords): Flow<String>`; `LlmState` | yes |
| `tts/TtsManager.kt` | `TextToSpeech` wrapper; suspend `speak()` that returns when playback ends | yes |
| `ui/GazeViewModel.kt` | Owns every manager and the state machine; per-frame pipeline; selection → effect execution | yes |
| `ui/GazeApp.kt` | Permission request, state → route navigation, pointer overlay, error banner, permission screen | yes |
| `ui/CalibrationScreen.kt` | Countdown prompt with head-alignment outline, live eye-tracking indicator and the calibrate / use saved / skip buttons; moving calibration dot | yes |
| `ui/AacMainScreen.kt` | Sentence bar, status line, tile grid, action row | yes |
| `ui/components/GazeTile.kt` | One tile: reports bounds, draws the dwell ring, handles touch | yes |
| `ui/components/GazePointer.kt` | Animated gaze cursor overlay | yes |
| `ui/theme/Theme.kt` | High-contrast dark palette | yes |

## State machine

States: `IDLE`, `PERMISSION_CHECK`, `CALIBRATION_REQUIRED`, `CALIBRATING_5_POINT`, `GAZE_ACTIVE`,
`DWELL_TRIGGERED`, `LLM_EXPANDING`, `SPEAKING`.

| From | To | Trigger | Where |
|---|---|---|---|
| `IDLE` | `PERMISSION_CHECK` | UI composed | `onAppStarted` |
| `PERMISSION_CHECK` | `CALIBRATION_REQUIRED` | Camera permission granted | `onCameraPermissionResult` |
| `CALIBRATION_REQUIRED` | `CALIBRATING_5_POINT` | 5 s countdown with a face in view, or "Calibrate now" | `beginCalibration` |
| `CALIBRATION_REQUIRED` | `GAZE_ACTIVE` | Saved calibration resumed (3 s notice, or "Use saved calibration") | `enterGazeActiveFromPrompt` |
| `CALIBRATION_REQUIRED` | `GAZE_ACTIVE` | "Skip to Board (Use Default Calibration)" | `skipCalibration` |
| `CALIBRATING_5_POINT` | `GAZE_ACTIVE` | Run finished, whatever the quality | `onCalibrationSample` |
| `GAZE_ACTIVE` | `DWELL_TRIGGERED` | Dwell completed or tile tapped | `activateTile` |
| `GAZE_ACTIVE` | `CALIBRATION_REQUIRED` | Eyes closed 2–5 s then opened, or "Recalibrate" | `requestRecalibration` |
| `DWELL_TRIGGERED` | `GAZE_ACTIVE` | Intent had no effect to run (navigation, add keyword, delete) | `activateTile` finally block |
| `DWELL_TRIGGERED` | `SPEAKING` | Quick need | `speak` |
| `DWELL_TRIGGERED` | `LLM_EXPANDING` | Speak tile with keywords | `expandAndSpeak` |
| `LLM_EXPANDING` | `SPEAKING` | Sentence produced, or fallback chosen | `speak` |
| `LLM_EXPANDING` / `SPEAKING` | `GAZE_ACTIVE` | Done, failed or cancelled | `activateTile` finally block |

Rules:

1. Change state only through `AppStateMachine.transition`. Pass `from =` when two sources can race
   (dwell on the vision thread versus touch on the main thread).
2. A new edge must be added to `AppStateMachine.ALLOWED` and to this table and the diagram in
   `ARCHITECTURE.md`.
3. Selections are accepted only in `GAZE_ACTIVE`. While the app is in `DWELL_TRIGGERED`,
   `LLM_EXPANDING` or `SPEAKING` the pointer moves but dwell is not evaluated.
4. Every path out of `activateTile` returns to `GAZE_ACTIVE` through its `finally` block. Keep it
   that way; a stuck state means a user who cannot communicate.
5. The route shown is `routeFor(appState)`. Do not call `navController.navigate` anywhere else.
6. Calibration has no failure exit. `CALIBRATING_5_POINT` only leads to `GAZE_ACTIVE`; a poor
   result is reported through `CalibrationQuality`, never by sending the user back. Do not
   reintroduce a rejection loop: it traps a user who cannot press anything.
7. Permission denial is not a state: the app stays in `PERMISSION_CHECK` and `permissionDenied`
   drives the screen.

## Contracts worth knowing

- **Coordinate space.** Gaze points, calibration targets and tile bounds are all pixels in the
  Compose root (`boundsInRoot()`, and the size reported by the root `Box` in `GazeApp`). The
  calibration screen must fill the root with no padding.
- **Timestamps.** `SystemClock.uptimeMillis()` taken when the frame reaches the analyzer. The same
  value flows through the landmarker, filter, calibration and dwell. MediaPipe needs it strictly
  increasing; `FaceLandmarkerHelper` drops anything that is not.
- **Frame callbacks.** `GazeViewModel.onFace` runs on a MediaPipe thread for every frame, including
  frames without a face (`face == null`). Keep it allocation-light and never block in it.
- **`ImageProxy`.** Closed in `finally` in `CameraSource.analyze`. Do not hand the proxy to another
  thread; convert to a bitmap first.
- **Tile ids.** Unique among visible tiles and they include the page, so a tile that appears where
  another was is a different dwell target.
- **Disabled tiles** are not dwell targets (`GazeViewModel.setTileBounds` removes them).
- **`AacIntentDispatcher` does no I/O.** It returns an `AacEffect`; the ViewModel runs it.
- **LLM access is serialised** by a mutex in `GemmaLlmManager`; the engine supports one
  conversation at a time.
- **Model file name** is fixed: `gemma-4-E2B-it.litertlm`. Search order is in
  `GemmaLlmManager.candidateModelPaths`.

## Known edge cases

| Situation | Current behaviour |
|---|---|
| No face in frame | Pointer fades after 600 ms; dwell resets after its 180 ms grace; status line turns red; calibration countdown and sampling pause |
| Blink | Sample flagged `eyesClosed`; filter and pointer hold; dwell continues within grace |
| Eyes closed 2–5 s, then opened | Recalibration |
| Eyes closed longer than 5 s (rest, sleep) | Ignored |
| Low light or strong backlight | Landmark jitter grows; the filter hides some of it; calibration may come out `APPROXIMATE` |
| Affine fit degenerate or too inaccurate | Axis-aligned fallback (`APPROXIMATE`); status line says "Rough calibration" |
| Eyes did not move, or no point could be measured | Built-in gains (`DEFAULT`), not saved; status line says "Default calibration" |
| Eyes unreadable at one calibration target | That point ends after 8 s of face time and is left out of the fit |
| User mistakes the head guide for a gaze pointer | The guide is a labelled head outline; a separate "Eye tracking" indicator has pupils that follow the irises |
| Glasses with reflections | Iris landmarks can latch onto glare; no mitigation |
| Head moves after calibration | Pointer offset grows (no head-pose term); the user recalibrates with the eye gesture |
| Phone or head moved between launches | Saved calibration is resumed but may be off; same remedy |
| Frame takes too long | CameraX drops intermediate frames; the filter uses real time deltas, so smoothing stays correct at a lower rate |
| A frame fails to convert | Logged and skipped; the stream continues |
| Camera permission denied | Explanation screen with "Allow camera" and "Open app settings"; re-checked on resume |
| Model file missing | `LlmState.ModelMissing`; status line says so; keywords are spoken as they are |
| Model still loading when Speak fires | `expand` waits for loading to finish |
| Generation exceeds 30 s | Cancelled; the partial sentence is spoken if there is one, otherwise the keywords |
| No TTS engine or voice | Error banner; text still appears in the sentence bar |
| TTS never reports completion | 30 s cap, then back to `GAZE_ACTIVE` |
| App backgrounded | CameraX releases the camera with the lifecycle and reopens it on return |
| Screen size differs from the saved calibration | Saved calibration is ignored |
| Gaze rests where a new tile appears after a selection | 600 ms cooldown before any dwell can start |

Not handled yet: thermal throttling feedback, landscape or tablets, multiple faces (the first face
wins), users with one usable eye (both eyes are required and averaged).

## Extension points

### Add a quick need, keyword or category

Edit `aac/AacVocabulary.kt` only.

- Quick need: add a `QuickNeed(id, label, symbol, sentence)`. It is spoken verbatim.
- Keyword: add a `Keyword(label, symbol, keyword)` to a category. `keyword` is the text the model
  sees; it defaults to the lower-cased label.
- Category: add a `Category(id, label, symbol, keywords)`. Paging is automatic (five keywords plus
  a "More" tile per page).

Constraints: the home page is two columns, so quick needs plus categories should stay at eight or
fewer or the tiles become too short to hit. `InteractionLogicTest.everyCategoryKeywordIsReachable`
and `homeShowsQuickNeedsAndCategories` will check your change.

### Add a new kind of tile behaviour

1. Add a case to `AacIntent`.
2. Handle it in `AacIntentDispatcher.dispatch` (the `when` is exhaustive, so the compiler points
   to it).
3. If it needs I/O, add a case to `AacEffect` and handle it in `GazeViewModel.activateTile`.
4. If it needs a new app state, follow the state machine rules above.

### Add audio input (for example, listening to the conversation partner)

The intended shape, which keeps existing contracts intact:

1. New package `audio/` with an `AudioContextManager` exposing
   `val transcript: StateFlow<String>` and `start()` / `stop()`. Add `RECORD_AUDIO` to the manifest
   and request it next to the camera permission in `GazeApp`; treat denial as "feature off", never
   as a blocker.
2. Extend the prompt, not the pipeline: add an optional `context: String?` parameter to
   `AacPromptTemplate.userTurn` and `GemmaLlmManager.expand` (default `null`, so current callers
   and tests are unchanged), producing for example `Heard: "…"\nKeywords: …`. Add one or two
   few-shot examples in the same format.
3. In `GazeViewModel.expandAndSpeak`, pass the latest transcript.
4. Pause listening while in `SPEAKING` so the app does not transcribe its own voice.

For native audio understanding instead of a transcript: the Gemma 4 E2B bundle includes an audio
encoder. Set `audioBackend` in `EngineConfig` and send `Content.AudioBytes` alongside the text.
This loads extra weights, so re-check the memory budget in `ARCHITECTURE.md` first.

Do not route audio through `AacIntentDispatcher`; it reduces board selections and nothing else.

### Improve gaze accuracy

- Head-pose compensation: enable `setOutputFacialTransformationMatrixes(true)` in
  `FaceLandmarkerHelper`, carry yaw and pitch in `GazeSample`, and add them as regressors in
  `AffineSolver`. More parameters need more calibration points (nine instead of five).
- Any change to the feature definition in `GazeEstimator` invalidates stored calibrations; change
  the SharedPreferences file name in `SharedPrefsCalibrationStore` so old ones are not reused.
- `CalibrationManager.TARGETS` can be extended freely; the solver takes any number of points ≥ 3.

### Swap the LLM or runtime

Everything model-specific is in `GemmaLlmManager`. Keep `expand(keywords): Flow<String>` emitting
the full sentence so far and `LlmState` as the status surface, and nothing else changes.

### Other languages

Translate `AacVocabulary`, the instruction and examples in `AacPromptTemplate`, and pass a `Locale`
to `TtsManager`.

## Do not

- Do not add a flow that requires touch.
- Do not block or allocate heavily in `GazeViewModel.onFace`.
- Do not bind a camera `Preview` or raise the analysis resolution without re-checking memory.
- Do not load the model on the main thread, or create a second `Engine`.
- Do not add the `INTERNET` permission. On-device processing is a promise made to the user on the
  permission screen.
- Do not bundle the Gemma model in the APK or commit it to git.
