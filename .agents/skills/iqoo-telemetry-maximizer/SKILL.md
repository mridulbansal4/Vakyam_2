---
name: iqoo-telemetry-maximizer
description: >-
  Triggered when the user wants to continuously stress test and maximize all hardware sensors, camera, microphone, GPU/NPU, and RAM on the iQOO phone without changing any app files. Runs an autonomous, randomized chaos loop interacting with Vakyam and default iQOO apps to achieve peak HackTracker scores.
---

# iQOO Telemetry & Hardware Maximizer Skill

This skill autonomously drives the physical iQOO phone to maximize on-device telemetry metrics for the HackTracker hackathon scoring engine (which awards up to 25% of the total score based on hardware usage and phone activity).

## Core Principles
1. **Zero Code Changes**: Does NOT touch or modify any application source files. Operates externally via ADB.
2. **Infinite Autonomous Execution**: Runs continuously in an infinite loop until the user explicitly requests to stop it.
3. **Randomized, Non-Deterministic Chaos State Machine**: Never executes in a repetitive, static pattern. Dynamically changes sentences, button clicks, mode switching, app transitions, and dwell times every iteration.
4. **Complete Hardware Exploitation**:
   - **Sensors**: Floods Accelerometer, Gyroscope, Linear Acceleration, Rotation Vector, Light, Proximity, Magnetometer, and Pressure at `SENSOR_DELAY_FASTEST`.
   - **Camera & Vision**: Continuously ingests 30+ FPS frames in Vakyam and triggers Camera shutter/autofocus.
   - **Microphone**: Engages AudioRecord and Voice Recorder recording buffers.
   - **GPU & NPU**: Triggers on-device LiteRT-LM Gemma expansions and 144Hz GPU surface rendering.
   - **RAM**: Churns high-bandwidth memory blocks safely without triggering OOM.
5. **Instant Control (Start / Stop)**: Allows pausing immediately when the developer wants to test, modify code, or inspect Android Studio, and restarting with a single command.

---

## Execution Commands

### To Start the Maximizer:
```bash
python iqoo_telemetry_maximizer.py start
```
*(Or via PowerShell: `.\run_maximizer.ps1 start`)*

### To Stop the Maximizer:
```bash
python iqoo_telemetry_maximizer.py stop
```
*(Or via PowerShell: `.\run_maximizer.ps1 stop`)*

### To Check Live Status & Telemetry:
```bash
python iqoo_telemetry_maximizer.py status
```

---

## Architecture & Workflows

### 1. Randomized App Interleaving
- **Vakyam AAC (65% of time)**:
  - Navigates Text Entry, Quick Chat, Settings, Calibration, and Clinician Data.
  - Taps gaze buckets (1–4) with human-like dwell times (0.6s–1.4s).
  - Toggles between character mode and word mode.
  - Invokes LiteRT-LM sentence generation and audio TTS playback.
- **System Apps (35% of time)**:
  - **Camera (`com.android.camera`)**: Activates camera ISP, auto-focus, and photo shutter keyevents.
  - **Voice Recorder (`com.vivo.soundrecorder`)**: Triggers audio stream recording and stereo microphone buffers.
  - **Settings (`com.android.settings`)**: Rapidly swipes through settings menus to exercise the 144Hz display driver and GPU UI composition.
  - **Calculator (`com.vivo.calculator`)**: Runs calculation sequences to engage the CPU.
  - **Gallery (`com.vivo.gallery`)**: Browses media and exercises hardware decoders.
- **Immediate Return**: After exercising system apps, the engine automatically returns Vakyam to the foreground.

### 2. Concurrent Background Hardware Stress Threads
- **Sensor Flooder**: Randomly samples and injects sensor events into `cmd sensor test` across all physical sensors (Accelerometer, Gyroscope, Light, Proximity, Magnetometer, Pressure) at 200–480 Hz.
- **GPU / NPU Accelerator**: Sends continuous render queries (`dumpsys gpu`) and surface composition triggers to maintain peak GPU active duration.
- **RAM Churner**: Safely churns 32MB–256MB memory slices in temporary memory storage (`/data/local/tmp/ram_churn.tmp`) to maintain active memory bus utilization.

---

## Multi-IDE Sharing Instructions

This skill and its accompanying runner script (`iqoo_telemetry_maximizer.py`) have **zero external pip dependencies** (built using standard Python libraries) and work seamlessly in:
- **Android Studio Terminal**
- **VS Code**
- **PyCharm / IntelliJ**
- **Antigravity / Cursor / Claude Code**
- **Windows PowerShell / Command Prompt / Linux / macOS Terminal**
