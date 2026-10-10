package com.demo.opencv;

import android.graphics.Bitmap;

import com.demo.opencv.textEntry.KeyboardData;
import com.demo.opencv.textEntry.QuickChatData;
import com.demo.opencv.vision.DetectionOutput;

public class AppLiveData {
    public com.demo.opencv.vision.DetectionOutput DetectionOutput; // detection output from model
    public com.demo.opencv.textEntry.KeyboardData KeyboardData; // what to display for the keyboard
    public QuickChatData quickChatData;

    // calibration interface
    public Bitmap[] leftTemplates;
    public Bitmap[] rightTemplates;
    public int calibrationState = -1;
    public float calibrationProgress; // 0..1 hold progress towards the next automatic capture
    public int calibrationStatusRes; // string resource explaining what auto-capture is waiting for, 0 if none
    String calibrationInstruction;
    public boolean isRecording;
    void setQuickChatData(QuickChatData quickChatData) {this.quickChatData = quickChatData; }
    void setDetectionOutput(DetectionOutput detectionOutput) { this.DetectionOutput = detectionOutput; }
    void setKeyboardDisplays(KeyboardData keyboardData) {
        this.KeyboardData = keyboardData;
    }
}
