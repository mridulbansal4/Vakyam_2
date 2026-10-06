# Vakyam: Mobile Eye-Gesture Communication System with On-Device Gemma 4 E2B LLM

## Description
Vakyam is an advanced eye-gesture communication system for assistive AAC (Augmentative and Alternative Communication). The application includes an eye-gaze text-entry keyboard, calibration, settings adjustments, and on-device natural language sentence generation.

The app runs entirely **on-device** using Google's **Gemma 4 E2B** model via the **Google LiteRT-LM** runtime (`com.google.ai.edge.litertlm:litertlm-android:0.17.1`), completely removing the requirement for cloud Gemini API keys.

---

## On-Device LLM Setup (Gemma 4 E2B)

The application searches for `gemma-4-E2B-it.litertlm` in the following locations:
1. `/sdcard/Android/data/com.vakyam.app/files/gemma-4-E2B-it.litertlm` (default app-specific external storage)
2. `context.filesDir/gemma-4-E2B-it.litertlm`
3. `context.getExternalFilesDir(null)/gemma-4-E2B-it.litertlm`
4. `/data/local/tmp/gemma-4-E2B-it.litertlm`
5. `/sdcard/Download/gemma-4-E2B-it.litertlm`
6. `/sdcard/gemma-4-E2B-it.litertlm`

### Push Model to Connected Device (if not already present)

Push via ADB:
```bash
adb push Models/gemma-4-E2B-it.litertlm /sdcard/Android/data/com.vakyam.app/files/
```
Or to the temporary staging path:
```bash
adb push Models/gemma-4-E2B-it.litertlm /data/local/tmp/
```

---

## Building the Project

### Build Debug APK
```bash
.\gradlew.bat :app:assembleDebug
```

The APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### Run Unit Tests
```bash
.\gradlew.bat :app:testDebugUnitTest
```

---

## Architecture & Codebase Structure

- `app/src/main/java/com/demo/opencv/AI/GemmaManager.kt`: LiteRT-LM on-device Gemma 4 E2B engine manager. Performs local inference with GPU/CPU backends and broadcasts expanded sentences via `LocalBroadcastManager`.
- `app/src/main/java/com/demo/opencv/AI/GeminiManager.java`: Compatibility adapter delegating sentence generation directly to `GemmaManager`.
- `app/src/main/java/com/demo/opencv/textEntry/TextEntryManager.java`: Manages gaze-based keyboard text entry, word prediction, and calls sentence generation.
- `app/src/main/java/com/demo/opencv/vision/`: Real-time eye detection, OpenCV gaze tracking, and calibration.
- `opencv/`: OpenCV Android SDK 4.7.0 integration.

---

## Useful Links
- User Manual: [Gaze Link Manual.pdf](file:///d:/Vakyam/Gaze%20Link%20Manual.pdf)
- Original Upstream: https://github.com/Jonassunhk/Gaze-Link
- Original User Manual Doc: https://docs.google.com/document/d/1Fz6OAjbDfIS6dwi93-o9HvRD_0RV0ealPnbN5Y_3fEQ/edit
- YouTube Channel: https://www.youtube.com/@GazeLink-ru7em

