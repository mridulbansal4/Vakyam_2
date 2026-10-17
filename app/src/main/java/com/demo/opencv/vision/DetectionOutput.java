package com.demo.opencv.vision;

import com.demo.opencv.vision.LookData;

import org.opencv.core.Mat;
import org.opencv.core.Point;

import java.util.ArrayList;

public class DetectionOutput {
    public LookData LeftData; public LookData RightData; public LookData AnalyzedData;
    public void setEyeData(int type, Boolean Success, int LookType, int LookLength, float LookProbability) {
        // 0: left, 1: right, 2: final
        if (type == 0) {
            // set left eye data
            this.LeftData.setLookData(Success, LookType, LookLength, LookProbability);
        } else if (type == 1) {
            // set right eye data
            this.RightData.setLookData(Success, LookType, LookLength, LookProbability);
        } else {
            // set analyzed look data
            this.AnalyzedData.setLookData(Success, LookType, LookLength, LookProbability);
        }
    }

    public void initialize(int MatNum) {
        this.RightData = new LookData();
        this.LeftData = new LookData();
        this.AnalyzedData = new LookData();
        testingMats = new Mat[MatNum];
        eyesOpen = false;
        motionStable = false;
        irisFound = false;
        faceDetected = false;
        eyeOpenProbability = 1f;
        eyeAspectRatio = Float.NaN;
        yunetFace = false;
        quality = null;
        qualityIssues = java.util.Collections.emptySet();
        qualityPrimary = null;
        qualityOkForCalibration = true;
    }

    public int gestureOutput; // an input from the user (not the raw data from the frame)
    public boolean eyesOpen; // neither eye reported closed by ML Kit in this frame
    public boolean motionStable; // phone not in a post-shock hold window
    public boolean irisFound; // leftNIC came from a detected iris, not the eye-centre fallback
    public boolean faceDetected;
    public float eyeOpenProbability = 1f; // ML Kit's score for the more-open eye, 1 when unknown
    public float eyeAspectRatio = Float.NaN; // lid opening over eye width for the more-open eye, NaN without contours
    public int frameRotation; // degrees the camera frame was rotated before detection
    public boolean phoneShaking; // gyro/accelerometer report a real shake, not ordinary hand tremor
    public float phonePitch = Float.NaN; // gyro-integrated phone rotation (radians), NaN without a gyroscope
    public float phoneYaw = Float.NaN;

    // OpenCV 5 pipeline only; the classic pipeline leaves these at their defaults
    public VisionPipeline pipeline = VisionPipeline.DEFAULT; // which pipeline produced this output
    public float processingMs; // time to classify this frame
    public boolean yunetFace; // YuNet found the face (otherwise ML Kit did, or nobody)
    public FaceQuality quality; // null when no face was measured
    public java.util.Set<QualityIssue> qualityIssues = java.util.Collections.emptySet(); // held issues, see FaceQualityGate
    public QualityIssue qualityPrimary; // the issue to tell the caregiver first, null when fine
    public boolean qualityOkForCalibration = true; // this frame is clean enough to store as a calibration template

    public Point leftNIC; // Normalized Iris Center for both eyes
    public Mat[] testingMats; // testing mats to show process of analysis
    public ArrayList<String> prevInputs; // array to store previous inputs, null until presenter
}
