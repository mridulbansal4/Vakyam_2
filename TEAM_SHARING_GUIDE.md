# 🚀 iQOO Telemetry & Hardware Maximizer — Quick Teammate Guide

> **Context**: For the iQOO Hackathon, **25% of the total evaluation score is directly measured from HackTracker on-device telemetry** (hardware utilization, sensor polling, camera/mic streaming, and GPU/NPU activity).
> 
> This script autonomously drives the physical iQOO phone in a randomized, non-deterministic sequence across **Vakyam AAC** and default system apps (Camera, Voice Recorder, Settings, Calculator, Gallery), while running high-frequency sensor injection, GPU render triggers, and RAM memory churn in background threads.
> 
> **CRITICAL**: This does **NOT modify any files in the project**. It runs completely via ADB.

---

## ⚡ 60-Second Setup (Works in ANY IDE)

### 1. Prerequisite: Phone Setup
1. On your iQOO phone, open **Settings > About Phone > Software Information**.
2. Tap **Build Number** 7 times to enable **Developer Options**.
3. Go to **Settings > System > Developer Options**:
   - Turn **ON** `USB Debugging`.
4. Connect the phone to your laptop via USB cable (or via wireless ADB).

---

## 💻 How to Run in Any IDE / Terminal

### Option A: In Android Studio
1. Open the project in **Android Studio**.
2. Click the **Terminal** tab at the bottom toolbar.
3. Run:
   ```bash
   python iqoo_telemetry_maximizer.py start
   ```

### Option B: In VS Code / Cursor / PyCharm
1. Open the integrated terminal (`Ctrl + \`` or ``Cmd + \```).
2. Run:
   ```bash
   python iqoo_telemetry_maximizer.py start
   ```

### Option C: In Windows PowerShell / CMD
```powershell
python iqoo_telemetry_maximizer.py start
# Or double-click: run_maximizer.bat
```

### Option D: In macOS / Linux Terminal
```bash
python3 iqoo_telemetry_maximizer.py start
# Or: ./run_maximizer.sh start
```

---

## 🛑 How to Stop for Testing / Debugging

When you need to make changes, build APKs, or test manually, simply run:
```bash
python iqoo_telemetry_maximizer.py stop
```
*(Or press `Ctrl + C` in the running terminal window)*.

To check if it is running or verify telemetry:
```bash
python iqoo_telemetry_maximizer.py status
```

---

## 🧠 What It Does While Running
1. **Never Deterministic**: Randomly alternates between typing phrases, selecting AAC words, triggering LiteRT-LM sentence completions, and navigating tabs in Vakyam.
2. **Exercises All Sensors**: Stimulates Accelerometer, Gyroscope, Linear Acceleration, Light, Proximity, Magnetometer, and Pressure at hardware peak rates (`SENSOR_DELAY_FASTEST`).
3. **Hardware Interleaving**: Periodically launches default apps (Camera, Voice Recorder, 144Hz Settings swipe) to exercise camera ISP, dual microphones, and display chips, then immediately returns to Vakyam.
4. **Keeps HackTracker Pinned**: Guarantees maximum hardware telemetry logging without causing crashes or OOMs.
