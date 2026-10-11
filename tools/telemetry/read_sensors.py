#!/usr/bin/env python3
"""
================================================================================
  iQOO 13 Live Hardware Sensor Monitor & Telemetry Inspector
================================================================================
  Reads live physical sensor streams from Android SensorManager / HAL over ADB.
  Displays real-time values for Accelerometer, Gyroscope, Linear Acceleration,
  Magnetometer, Light, Proximity, Temperature, and HackTracker status.

  Usage:
      python tools/telemetry/read_sensors.py          # Run continuous live sensor monitor
      python tools/telemetry/read_sensors.py --once   # Print single sensor snapshot
================================================================================
"""

import sys
import time
import re
import shutil
import subprocess
from pathlib import Path

def find_adb():
    adb_path = shutil.which("adb")
    if adb_path:
        return adb_path
    home = Path.home()
    candidates = [
        home / "AppData/Local/Android/Sdk/platform-tools/adb.exe",
        home / "AppData/Local/Android/sdk/platform-tools/adb.exe",
        home / "Library/Android/sdk/platform-tools/adb",
        home / "Android/Sdk/platform-tools/adb",
        Path("C:/Android/platform-tools/adb.exe"),
        Path("/usr/bin/adb"),
        Path("/usr/local/bin/adb"),
    ]
    for c in candidates:
        if c.exists():
            return str(c)
    return "adb"

ADB_BIN = find_adb()

def get_connected_device():
    res = subprocess.run([ADB_BIN, "devices"], capture_output=True, text=True)
    lines = [l.strip() for l in res.stdout.splitlines() if "\tdevice" in l]
    if not lines:
        print("[!] Error: No Android device found via ADB. Please check USB debugging.")
        sys.exit(1)
    return lines[0].split()[0]

def query_sensor_service(device_id):
    cmd = [ADB_BIN, "-s", device_id, "shell", "dumpsys", "sensorservice"]
    res = subprocess.run(cmd, capture_output=True, text=True, timeout=8)
    return res.stdout

def parse_sensors(dump_text):
    data = {}
    current_key = None

    # Sensor sections to track
    KEY_PATTERNS = {
        "ACCELEROMETER": re.compile(r'(?:lsm6dsvx\s+)?Accelerometer\s+Non-wakeup:\s+last', re.I),
        "GYROSCOPE": re.compile(r'(?:vsen_gyro\s+)?Gyroscope\s+Non-wakeup:\s+last', re.I),
        "LINEAR_ACCEL": re.compile(r'linear_acceleration:\s+last', re.I),
        "GRAVITY": re.compile(r'gravity\s+Non-wakeup:\s+last', re.I),
        "MAGNETOMETER": re.compile(r'qmc6309h\s+Non-wakeup:\s+last', re.I),
        "LIGHT": re.compile(r'tcs3743\s+Ambient\s+Light\s+Sensor.*:\s+last|lit_up.*:\s+last', re.I),
        "PROXIMITY": re.compile(r'tcs3743\s+Proximity\s+Sensor.*:\s+last', re.I),
        "TEMPERATURE": re.compile(r'Temperature\s+Sensor.*:\s+last', re.I),
    }

    lines = dump_text.splitlines()
    for line in lines:
        for k, pat in KEY_PATTERNS.items():
            if pat.search(line):
                current_key = k
                break

        if current_key and re.search(r'\d+\s+\(ts=.*wall=([0-9:]+(?:\.[0-9]+)?)\)\s+([0-9\.\,\s\-]+)', line):
            m = re.search(r'\d+\s+\(ts=.*wall=([0-9:]+(?:\.[0-9]+)?)\)\s+([0-9\.\,\s\-]+)', line)
            wall = m.group(1)
            raw = [float(v.strip()) for v in m.group(2).split(',') if v.strip()]
            data[current_key] = {"time": wall, "values": raw}
            current_key = None

    # Check HackTracker status
    hacktracker_active = "com.reskill.hacktracker" in dump_text
    data["HACKTRACKER"] = {"active": hacktracker_active}

    return data

def display_dashboard(data, device_id):
    header = f"=== [iQOO 13 SENSOR TELEMETRY MONITOR] Device: {device_id} ==="
    print("\n" + "=" * len(header))
    print(header)
    print("=" * len(header))

    ht_status = "ACTIVE & LOGGING (SensorTracker Registered)" if data.get("HACKTRACKER", {}).get("active") else "IDLE"
    print(f"[*] HackTracker Monitor : {ht_status}")
    print("-" * len(header))

    # Accelerometer
    acc = data.get("ACCELEROMETER")
    if acc and len(acc["values"]) >= 3:
        ax, ay, az = acc["values"][:3]
        mag = (ax**2 + ay**2 + az**2) ** 0.5
        print(f" [1] ACCELEROMETER       : X={ax:+6.2f} m/s^2 | Y={ay:+6.2f} m/s^2 | Z={az:+6.2f} m/s^2 (Total Mag: {mag:5.2f} m/s^2)")
    else:
        print(" [1] ACCELEROMETER       : Waiting for active frame...")

    # Linear Acceleration
    lacc = data.get("LINEAR_ACCEL")
    if lacc and len(lacc["values"]) >= 3:
        lx, ly, lz = lacc["values"][:3]
        lmag = (lx**2 + ly**2 + lz**2) ** 0.5
        print(f" [2] LINEAR ACCELERATION : X={lx:+6.2f} m/s^2 | Y={ly:+6.2f} m/s^2 | Z={lz:+6.2f} m/s^2 (Jerk / Motion: {lmag:5.2f} m/s^2)")
    else:
        print(" [2] LINEAR ACCELERATION : Stable / Idle")

    # Gyroscope
    gyro = data.get("GYROSCOPE")
    if gyro and len(gyro["values"]) >= 3:
        gx, gy, gz = gyro["values"][:3]
        print(f" [3] GYROSCOPE (ANGULAR) : Pitch={gx:+6.3f} | Roll={gy:+6.3f} | Yaw={gz:+6.3f} rad/s")
    else:
        print(" [3] GYROSCOPE (ANGULAR) : Stationary (0.0 rad/s)")

    # Magnetometer
    mag = data.get("MAGNETOMETER")
    if mag and len(mag["values"]) >= 3:
        mx, my, mz = mag["values"][:3]
        print(f" [4] MAGNETOMETER        : X={mx:+6.1f} uT | Y={my:+6.1f} uT | Z={mz:+6.1f} uT (Earth Magnetic Field)")
    else:
        print(" [4] MAGNETOMETER        : Ready")

    # Ambient Light
    light = data.get("LIGHT")
    if light and len(light["values"]) >= 1:
        lux = light["values"][0]
        print(f" [5] AMBIENT LIGHT (LUX) : {lux:6.1f} Lux")
    else:
        print(" [5] AMBIENT LIGHT (LUX) : Standby")

    # Proximity
    prox = data.get("PROXIMITY")
    if prox and len(prox["values"]) >= 1:
        dist = prox["values"][0]
        state = "FAR (>5cm)" if dist >= 5.0 else f"NEAR ({dist:.1f}cm)"
        print(f" [6] PROXIMITY SENSOR    : {dist:.1f} cm -> {state}")
    else:
        print(" [6] PROXIMITY SENSOR    : Standby")

    print("=" * len(header))

def main():
    device_id = get_connected_device()
    once = "--once" in sys.argv

    if once:
        dump = query_sensor_service(device_id)
        data = parse_sensors(dump)
        display_dashboard(data, device_id)
        return

    print(f"[*] Starting Real-Time Sensor Telemetry Loop for {device_id}...")
    print("    Press Ctrl+C to exit.\n")
    try:
        while True:
            dump = query_sensor_service(device_id)
            data = parse_sensors(dump)
            display_dashboard(data, device_id)
            time.sleep(1.0)
    except KeyboardInterrupt:
        print("\n[*] Sensor monitoring stopped.")

if __name__ == "__main__":
    main()
