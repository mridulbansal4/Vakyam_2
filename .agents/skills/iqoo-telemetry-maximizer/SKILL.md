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
3. **Randomized, Non-Deterministic Chaos State Machine**: Never executes in a repetitive, static pattern. Dynamically changes sentences, button clicks, calibration steps, and hardware pipelines every iteration.
4. **Focused Hardware Exploitation**:
   - **Selfie Camera & Eye Tracking**: Drives Vakyam's live OpenCV 30 FPS camera feed and actively runs the **Eye Calibration** feature to capture photo frames of the eyes across 6 directions, toggling left/right eyes.
   - **Native Camera App**: Periodically triggers autofocus, flips cameras, and fires the hardware camera shutter.
   - **Microphone**: Actively streams audio via Vakyam's speech recording button and the native Vivo Sound Recorder.
   - **ALL 53 Sensors at Max Level**: Continuously polls and excites HAL sensor pipelines for Accelerometer, Gyroscope, Linear Acceleration, Rotation Vector, Light, Proximity, Magnetometer, Gravity, and Pressure at `SENSOR_DELAY_FASTEST`.
   - **GPU & NPU**: Triggers on-device LiteRT-LM Gemma sentence expansions and high-rate GPU surface rendering.
   - **RAM**: Churns high-bandwidth memory blocks safely without triggering OOM.
5. **Instant Control (Start / Stop)**: Allows pausing immediately when the developer wants to test, modify code, or inspect Android Studio, and restarting with a single command.

---

## Execution Commands

### To Start the Maximizer:
```bash
python tools/telemetry/iqoo_telemetry_maximizer.py start
```

### To Stop the Maximizer:
```bash
python tools/telemetry/iqoo_telemetry_maximizer.py stop
```

### To Check Live Status & Telemetry:
```bash
python tools/telemetry/iqoo_telemetry_maximizer.py status
```
