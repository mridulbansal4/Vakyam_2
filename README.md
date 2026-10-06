# GazeLink: Mobile Eye-Gesture Communication System with On-Device Gemma 4 E2B LLM

## Description
Gaze Link is a low-cost eye-gesture communication system intended for People with Amyotrophic Lateral Sclerosis (PALS) who develop severe motor and verbal disabilities. The application includes a text-entry keyboard that allows PALS to type grammatical sentences independently and other functionalities for calibration, settings adjustments, and data analysis.

This repository updates the original Gaze-Link app (`Jonassunhk/Gaze-Link`) to run entirely **on-device** using Google's **Gemma 4 E2B** model via the **Google LiteRT-LM** runtime (`com.google.ai.edge.litertlm:litertlm-android:0.17.1`), completely removing the requirement for cloud Gemini API keys.

---

## On-Device LLM Setup (Gemma 4 E2B)

The application searches for `gemma-4-E2B-it.litertlm` in the following locations:
1. `context.filesDir/gemma-4-E2B-it.litertlm`
2. `context.getExternalFilesDir(null)/gemma-4-E2B-it.litertlm`
3. `/data/local/tmp/gemma-4-E2B-it.litertlm`
4. `/sdcard/Android/data/com.demo.opencv/files/gemma-4-E2B-it.litertlm`
5. `/sdcard/Download/gemma-4-E2B-it.litertlm`
6. `/sdcard/gemma-4-E2B-it.litertlm`

### Push Model to Connected Device

Push via ADB:
```bash
adb push Models/gemma-4-E2B-it.litertlm /sdcard/Android/data/com.demo.opencv/files/
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
`Develop/app/build/outputs/apk/debug/app-debug.apk`

### Run Unit Tests
```bash
.\gradlew.bat :app:testDebugUnitTest
```

---

## Architecture & Codebase Structure

- `Develop/app/src/main/java/com/demo/opencv/AI/GemmaManager.kt`: LiteRT-LM on-device Gemma 4 E2B engine manager. Performs local inference with GPU/CPU backends and broadcasts expanded sentences via `LocalBroadcastManager`.
- `Develop/app/src/main/java/com/demo/opencv/AI/GeminiManager.java`: Compatibility adapter delegating sentence generation directly to `GemmaManager`.
- `Develop/app/src/main/java/com/demo/opencv/textEntry/TextEntryManager.java`: Manages gaze-based keyboard text entry, word prediction, and calls sentence generation.
- `Develop/app/src/main/java/com/demo/opencv/vision/`: Real-time eye detection, OpenCV gaze tracking, and calibration.
- `Develop/opencv/`: OpenCV Android SDK 4.7.0 integration.

---

## Useful Links
- User Manual: [Gaze Link Manual.pdf](file:///d:/Vakyam/Gaze%20Link%20Manual.pdf)
- Original Upstream: https://github.com/Jonassunhk/Gaze-Link
- Original User Manual Doc: https://docs.google.com/document/d/1Fz6OAjbDfIS6dwi93-o9HvRD_0RV0ealPnbN5Y_3fEQ/edit
- YouTube Channel: https://www.youtube.com/@GazeLink-ru7em
