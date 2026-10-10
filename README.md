# Vakyam: Mobile Eye-Gesture Communication System with On-Device Gemma 4 E2B LLM

## Description
Vakyam is an advanced eye-gesture communication system for assistive AAC (Augmentative and Alternative Communication). The application includes an eye-controlled text-entry keyboard, calibration, settings adjustments, and on-device natural language sentence generation.

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

## Offline Speech-to-Text Setup (Whisper via sherpa-onnx)

The record button uses Android's `SpeechRecognizer` when the phone is online. When it is offline, has no system recognizer, or the system recognizer reports a network/server/language failure, the app records locally and transcribes on-device with a Whisper model (English and Hindi). Without the model on the device the app behaves as before.

The app looks for `*encoder(.int8).onnx`, `*decoder(.int8).onnx` and `*tokens.txt` in:
1. `/sdcard/Android/data/com.vakyam.app/files/models/asr/` (recommended)
2. `context.filesDir/models/asr/`
3. `/sdcard/Models/asr/`
4. `/data/local/tmp/asr/`

### Download and push the model (~150 MB on device)
```bash
mkdir -p Models/asr && cd Models/asr
curl -L -O https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2
tar -xjf sherpa-onnx-whisper-base.tar.bz2 --strip-components=1 --wildcards '*/base-encoder.int8.onnx' '*/base-decoder.int8.onnx' '*/base-tokens.txt'
adb push base-encoder.int8.onnx base-decoder.int8.onnx base-tokens.txt /sdcard/Android/data/com.vakyam.app/files/models/asr/
```
`sherpa-onnx-whisper-small.tar.bz2` is a drop-in replacement with better Hindi accuracy at about 3x the size and decode time.

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
- `app/src/main/java/com/demo/opencv/textEntry/TextEntryManager.java`: Manages eye-controlled keyboard text entry, word prediction, and calls sentence generation.
- `app/src/main/java/com/demo/opencv/vision/`: Real-time eye detection, OpenCV eye tracking, and calibration.
- `opencv/`: OpenCV Android SDK 4.7.0 integration.

---

## Useful Links
- User Manual: [Vakyam Manual.pdf](Vakyam%20Manual.pdf)
- Original User Manual Doc: https://docs.google.com/document/d/1Fz6OAjbDfIS6dwi93-o9HvRD_0RV0ealPnbN5Y_3fEQ/edit

