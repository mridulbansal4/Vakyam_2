package com.demo.opencv;


import android.content.Context;
import android.graphics.Bitmap;
import android.media.ToneGenerator;
import android.util.Log;

import com.demo.opencv.other.AudioManager;
import com.demo.opencv.metrics.TypingTest;
import com.demo.opencv.other.ClinicalData;
import com.demo.opencv.personal.PersonalLexicon;
import com.demo.opencv.textEntry.KeyboardData;
import com.demo.opencv.textEntry.KeyboardManager;
import com.demo.opencv.textEntry.QuickChatManager;
import com.demo.opencv.textEntry.TextEntryManager;
import com.demo.opencv.vision.CalibrationStabilityGate;
import com.demo.opencv.vision.CalibrationTemplateCheck;
import com.demo.opencv.vision.DetectionOutput;

import org.opencv.android.Utils;
import org.opencv.core.Mat;

import java.io.IOException;
import java.util.Calendar;
import java.util.Objects;

public class Presenter implements ContractInterface.Presenter, AudioManager.AudioManagerListener {
    Context mContext;
    Context applicationContext;
    UserDataManager userDataManager;
    public boolean presenterBusy = false;
    public String mode = ""; // Calibration, Text, Dev, Clinician, QuickChat
    boolean recording = false;
    private final ContractInterface.View mainView; // creating object of View Interface
    private final ContractInterface.Model model; // creating object of Model Interface
    private final KeyboardManager keyboardManager = new KeyboardManager();
    private final TextEntryManager textEntryManager = new TextEntryManager();
    private final QuickChatManager quickChatManager = new QuickChatManager();
    AppLiveData appliveData = new AppLiveData();
    AudioManager audioManager = new AudioManager();
    public ClinicalData clinicalData = new ClinicalData();
    ToneGenerator toneGenerator;
    // Left and right are calibrated looking down: those options sit in the bottom corners of the text entry screen
    private final String[] calibrationMessages = {"Look straight", "Look left and down", "Look right and down", "Look up", "Look left and up", "Look right and up"};
    // The gate only checks the sideways movement for the two down steps; the vertical ratio barely moves when looking down
    private final CalibrationStabilityGate.Direction[] calibrationDirections = {
            CalibrationStabilityGate.Direction.STRAIGHT, CalibrationStabilityGate.Direction.LEFT,
            CalibrationStabilityGate.Direction.RIGHT, CalibrationStabilityGate.Direction.UP,
            CalibrationStabilityGate.Direction.LEFT_UP, CalibrationStabilityGate.Direction.RIGHT_UP};
    private final CalibrationStabilityGate calibrationGate = new CalibrationStabilityGate();
    private static final long CALIBRATION_REPROMPT_MS = 4000L;
    private long lastCalibrationPromptMs = 0L;
    private long lastCalibrationLogMs = 0L;
    // Eye images of the steps captured in this calibration run, to reject a step that looks like an earlier one
    private final float[][] capturedLeftEyes = new float[6][], capturedRightEyes = new float[6][];
    private float[] previousLeftEye, previousRightEye;
    // instantiating the objects of View and Model Interface
    public Presenter(ContractInterface.View mainView, ContractInterface.Model model) {
        this.mainView = mainView;
        this.model = model;
    }
    Mat prevMat;

    private static float[] grayPixels(Mat gray) { // 0..1, null when the mat is not a usable grey image
        if (gray == null || gray.empty() || gray.channels() != 1) return null;
        byte[] raw = new byte[(int) gray.total()];
        gray.get(0, 0, raw);
        float[] pixels = new float[raw.length];
        for (int i = 0; i < raw.length; i++) pixels[i] = (raw[i] & 0xFF) / 255f;
        return pixels;
    }

    private Bitmap matToBitmap(Mat mat) {
        Bitmap bm = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(mat,bm);
        return bm;
    }

    @Override
    public void initialize(Context mContext, Context applicationContext) throws IOException {

        Log.d("MVPPresenter", "Model Initialized");
        this.mContext = mContext;
        this.applicationContext = applicationContext;
        userDataManager = (UserDataManager) applicationContext;
        //userDataManager.setTextEntryContext("¿Qué te gustaría comer?");

        model.initialize(mContext, applicationContext); // MVP model initialization

        keyboardManager.initialize(); // keyboard initialization
        keyboardManager.setPersonalLexicon(PersonalLexicon.get(applicationContext));

        textEntryManager.initialize(mContext); // text prediction initialization with language setting
        textEntryManager.updateSettings(applicationContext); // update settings for text entry
        quickChatManager.initialize(mContext);

        clinicalData = new ClinicalData();
        appliveData.calibrationInstruction = "Eye Calibration";

        // audio + speech recognition
        try {
            toneGenerator = new ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100);
        } catch (Exception e) {
            Log.w("Presenter", "ToneGenerator could not be created", e);
            toneGenerator = null;
        }
        audioManager.initialize(mContext);
        audioManager.setAudioManagerListener(this);

        prevMat = new Mat();
    }

    @Override
    public synchronized void updateCalibration() { // called by the start/restart button and by auto-capture
        int calibrationState = userDataManager.getCalibrationState();
        long now = System.currentTimeMillis();

        if (calibrationState == -1) { // begin calibration
            audioManager.speakText(calibrationMessages[0]);
            appliveData.calibrationInstruction = calibrationMessages[0];
            calibrationState = 0;
            java.util.Arrays.fill(capturedLeftEyes, null);
            java.util.Arrays.fill(capturedRightEyes, null);
            calibrationGate.clearReference();
            calibrationGate.startStep(now, calibrationDirections[0]);
            lastCalibrationPromptMs = now;

        } else if (calibrationState == userDataManager.calibrationTemplateNum) { // restart calibration
            appliveData.calibrationInstruction = "EYE CALIBRATION";
            calibrationState = -1;
            calibrationGate.clearReference();

        } else if (calibrationState >= 0) { // during calibration
            if (appliveData.DetectionOutput != null && appliveData.DetectionOutput.testingMats != null) {
                Mat[] eyeMats = appliveData.DetectionOutput.testingMats;
                if (eyeMats.length > 1 && eyeMats[0] != null && eyeMats[1] != null && !eyeMats[0].empty() && !eyeMats[1].empty()) { // the images are collectible
                    Log.d("CalibrationInterface", "Recorded successfully");
                    userDataManager.setLeftCalibrationData(matToBitmap(eyeMats[0]), calibrationState); // collect left frame
                    userDataManager.setRightCalibrationData(matToBitmap(eyeMats[1]), calibrationState); // collect right frame
                    if (calibrationState < capturedLeftEyes.length) {
                        capturedLeftEyes[calibrationState] = grayPixels(eyeMats[0]);
                        capturedRightEyes[calibrationState] = grayPixels(eyeMats[1]);
                    }
                    if (calibrationState == 0) { // later steps must move away from straight in their own direction
                        if (calibrationGate.getStatus() == CalibrationStabilityGate.Status.CAPTURED) {
                            calibrationGate.setReference(calibrationGate.getLastMeanX(), calibrationGate.getLastMeanY());
                        } else if (appliveData.DetectionOutput.leftNIC != null) {
                            calibrationGate.setReference((float) appliveData.DetectionOutput.leftNIC.x, (float) appliveData.DetectionOutput.leftNIC.y);
                        }
                    }
                    calibrationState += 1;
                    playBeepTone();

                    if (calibrationState >= userDataManager.calibrationTemplateNum) { // finished calibration
                        model.updateCalibrationTemplates();
                        appliveData.calibrationInstruction = "CALIBRATION FINISHED!";
                        Log.d("CalibrationInterface", "Finished calibration");
                    } else if (calibrationState < calibrationMessages.length) { // continue
                        audioManager.speakText(calibrationMessages[calibrationState]);
                        appliveData.calibrationInstruction = calibrationMessages[calibrationState];
                        calibrationGate.startStep(now, calibrationDirections[calibrationState]);
                        lastCalibrationPromptMs = now;
                    }
                } else {
                    Log.d("Calibration", "Detection failed, please try again");
                }
            } else {
                Log.d("Calibration", "Detection output not ready");
            }
        }
        userDataManager.setCalibrationState(calibrationState);
        appliveData.calibrationState = calibrationState;
        appliveData.calibrationProgress = 0f;
        appliveData.calibrationStatusRes = 0;
    }

    private void autoCaptureCalibration(DetectionOutput detectionOutput) { // captures a calibration step once the gaze is held steady
        int state = userDataManager.getCalibrationState();
        if (state < 0 || state >= userDataManager.calibrationTemplateNum || detectionOutput == null) {
            appliveData.calibrationProgress = 0f;
            appliveData.calibrationStatusRes = 0;
            return;
        }
        long now = System.currentTimeMillis();
        Mat[] eyeMats = detectionOutput.testingMats;
        boolean eyesVisible = eyeMats != null && eyeMats.length > 1
                && eyeMats[0] != null && !eyeMats[0].empty()
                && eyeMats[1] != null && !eyeMats[1].empty()
                && detectionOutput.leftNIC != null;
        float x = eyesVisible ? (float) detectionOutput.leftNIC.x : Float.NaN;
        float y = eyesVisible ? (float) detectionOutput.leftNIC.y : Float.NaN;

        boolean capture = calibrationGate.update(x, y, detectionOutput.eyesOpen, !detectionOutput.phoneShaking, eyesVisible, now,
                detectionOutput.phonePitch, detectionOutput.phoneYaw);
        appliveData.calibrationProgress = calibrationGate.progress(now);
        appliveData.calibrationStatusRes = calibrationStatusMessage(calibrationGate.getStatus(), detectionOutput.faceDetected);

        if (now - lastCalibrationLogMs > 500) {
            Log.i("AutoCalibration", String.format(java.util.Locale.US,
                    "step=%d status=%s face=%b rot=%d x=%.3f y=%.3f iris=%b open=%b shaking=%b pitch=%.3f yaw=%.3f visible=%b spread=%.3f motionFix=%.3f distFromStraight=%.3f leniency=%d progress=%.2f",
                    state, calibrationGate.getStatus(), detectionOutput.faceDetected, detectionOutput.frameRotation,
                    x, y, detectionOutput.irisFound, detectionOutput.eyesOpen, detectionOutput.phoneShaking,
                    detectionOutput.phonePitch, detectionOutput.phoneYaw, eyesVisible,
                    calibrationGate.getLastSpread(), calibrationGate.getLastMotionCorrection(),
                    calibrationGate.getLastDistanceFromReference(), calibrationGate.getLeniency(), appliveData.calibrationProgress));
            lastCalibrationLogMs = now;
        }

        float[] leftEye = eyesVisible ? grayPixels(eyeMats[0]) : null;
        float[] rightEye = eyesVisible ? grayPixels(eyeMats[1]) : null;
        if (capture) {
            // The gate only sees the iris position, which is unreliable looking up or into a corner and is not
            // checked at all once it has been re-prompted twice. Judge the eye image itself before storing it.
            int sameAs = CalibrationTemplateCheck.indistinctFrom(leftEye, rightEye, capturedLeftEyes, capturedRightEyes, state);
            boolean settled = CalibrationTemplateCheck.isSettled(leftEye, rightEye, previousLeftEye, previousRightEye);
            if (sameAs >= 0 || !settled) {
                Log.i("AutoCalibration", "Rejected step " + state + (sameAs >= 0 ? ": looks like step " + sameAs : ": eye image not settled"));
                appliveData.calibrationProgress = 0f;
                appliveData.calibrationStatusRes = sameAs >= 0 ? R.string.Calibration_StatusLookFurther : R.string.Calibration_StatusHoldGaze;
                capture = false;
            }
        }
        previousLeftEye = leftEye;
        previousRightEye = rightEye;

        if (capture) {
            Log.i("AutoCalibration", "Captured step " + state + " (leniency " + calibrationGate.getLeniency() + ")");
            appliveData.setDetectionOutput(detectionOutput);
            updateCalibration();
        } else if (now - lastCalibrationPromptMs > CALIBRATION_REPROMPT_MS && state < calibrationMessages.length) {
            audioManager.speakText(calibrationMessages[state]);
            calibrationGate.escalate(); // each re-prompt loosens the capture rules so a step can't block forever
            Log.i("AutoCalibration", "Re-prompted step " + state + ", leniency now " + calibrationGate.getLeniency());
            lastCalibrationPromptMs = now;
        }
    }

    private int calibrationStatusMessage(CalibrationStabilityGate.Status status, boolean faceDetected) {
        switch (status) {
            case SETTLING: return R.string.Calibration_StatusListen;
            case NO_EYES: return faceDetected ? R.string.Calibration_StatusNoEyes : R.string.Calibration_StatusNoFace;
            case EYES_CLOSED: return R.string.Calibration_StatusEyesClosed;
            case PHONE_MOVING: return R.string.Calibration_StatusPhoneMoving;
            case TOO_CLOSE_TO_REFERENCE: return R.string.Calibration_StatusLookFurther;
            case HOLDING:
            case CAPTURED: return R.string.Calibration_StatusHolding;
            case NOT_STEADY:
            default: return R.string.Calibration_StatusHoldGaze;
        }
    }

    @Override
    public boolean getPresenterState() {
        return presenterBusy;
    }

    @Override
    public ClinicalData getClinicalData() {
        return clinicalData;
    }

    @Override
    public void setMode(String value) {
        mode = value;
        if (Objects.equals(mode, "Text")) { // when the mode is text entry
            textEntryManager.updateSettings(applicationContext); // always update settings when opened
            if (userDataManager.getTextEntryMode() == 0) {
                KeyboardData keyboardData = keyboardManager.getDisplays();
                appliveData.setKeyboardDisplays(keyboardData);
            } else {
                textEntryManager.updateDisplays();
                KeyboardData keyboardData = textEntryManager.getDisplays();
                appliveData.setKeyboardDisplays(keyboardData);
            }
        }
    }

    @Override
    public String getMode() { return mode; }

    private void playBeepTone() {
        if (toneGenerator != null) {
            try {
                toneGenerator.startTone(ToneGenerator.TONE_CDMA_PIP, 150);
            } catch (Exception e) {
                Log.e("Presenter", "Failed to play beep tone", e);
            }
        }
    }

    @Override
    public void onGazeButtonClicked(int input) { // when the user clicks a gaze button (for text mode and quick chat)
        playBeepTone();
        if (Objects.equals(mode, "Text")) {
            TypingTest.onSelection();
            if (userDataManager.getTextEntryMode() == 0) {
                keyboardManager.processInput(input, false);
                KeyboardData keyboardData = keyboardManager.getDisplays();
                appliveData.setKeyboardDisplays(keyboardData);
            } else {
                textEntryManager.manageUserInput(input, false);
            }
        } else if (Objects.equals(mode, "QuickChat")) {
            Log.d("QuickChatFragment", "Button Pressed: " + input);
            quickChatManager.manageUserInput(input, false);
        }
    }

    @Override
    public void onRecordButtonClicked() {
        if (!recording) {
            recording = true;
            playBeepTone();
            audioManager.startListening();
        } else {
            audioManager.stopListening();
            recording = false;
        }
    }

    @Override
    public void onAudioUpdated(String key, String value) {
        recording = false;
        if (Objects.equals(key, "Context")) {
            Log.d("AudioManager", "Context received by presenter: " + value);
            userDataManager.setTextEntryContext(value);
            textEntryManager.updateContext(value);
        }
    }

    @Override
    public void onDestroy() {
        if (toneGenerator != null) {
            try {
                toneGenerator.release();
            } catch (Exception ignored) {}
            toneGenerator = null;
        }
        if (textEntryManager != null) {
            textEntryManager.onDestroy();
        }
        if (quickChatManager != null) {
            quickChatManager.onDestroy();
        }
        if (audioManager != null) {
            audioManager.destroy();
        }
    }

    @Override
    public void setFaceRoiDetection(boolean enabled) { model.setFaceRoiDetection(enabled); }

    @Override
    public void speakCaregiverMessage(String text) { audioManager.speakText(text); }

    @Override
    public void clearText() {
        keyboardManager.reset();
        textEntryManager.clearText();
    }

    @Override
    public void finishTypingTest() { // the caregiver ends the test with whatever has been typed so far
        if (userDataManager.getTextEntryMode() == 0) {
            TypingTest.onSpoken(keyboardManager.getTextInput(), TypingTest.MODE_GAZE_LETTER);
        } else {
            textEntryManager.finishTypingTest();
        }
    }

    @Override
    public void onFrame(Mat rgbMat) {
        presenterBusy = true;
        prevMat = rgbMat;
       // Log.d("MVPPresenter", "Frame loaded");
        DetectionOutput detectionOutput = model.classifyGaze(rgbMat); // get detection output from model
     //   Log.d("MVPPresenter", "Frame processed");

        if (detectionOutput != null && detectionOutput.leftNIC != null) {
            clinicalData.addNICPoint((float) detectionOutput.leftNIC.x, (float) detectionOutput.leftNIC.y);
        }

        if (Objects.equals(mode, "Calibration")) {
            autoCaptureCalibration(detectionOutput);
        }

        if (detectionOutput != null && detectionOutput.AnalyzedData != null) { // when the input is valid
            if (Objects.equals(mode, "Text") || Objects.equals(mode, "Dev") || Objects.equals(mode, "QuickChat")) { // not calibrating or in clinician mode, process the input
                int gazeType = detectionOutput.gestureOutput;

                if (gazeType != 0) { // gaze input is meaningful
                    Log.d("IrisDetection", "Gesture Output: " + gazeType);
                    playBeepTone();

                    if (Objects.equals(mode, "Text")) { // only do text input in text mode
                        String gazeLogInput = Calendar.getInstance().getTime() + ": " + userDataManager.gazeIndexToString(gazeType);
                        clinicalData.addGazeLog(gazeLogInput);
                        TypingTest.onSelection();

                        if (userDataManager.getTextEntryMode() == 0) { // letter-by-letter mode
                            keyboardManager.processInput(gazeType);
                            KeyboardData keyboardData = keyboardManager.getDisplays();
                            appliveData.setKeyboardDisplays(keyboardData);
                        } else { // other two modes
                            textEntryManager.LLMEnabled = (userDataManager.getTextEntryMode() == 2);
                            textEntryManager.manageUserInput(gazeType, true);
                        }
                    } else if (Objects.equals(mode, "QuickChat")) { // quick chat mode
                        quickChatManager.manageUserInput(gazeType, true);
                    }
                }
            }
        }

        // setting the app live data for the fragment displays
        if (Objects.equals(mode, "Text")) {
            if (userDataManager.getTextEntryMode() == 0) {
                KeyboardData keyboardData = keyboardManager.getDisplays();
                appliveData.setKeyboardDisplays(keyboardData);
            } else {
                textEntryManager.updateDisplays();
                KeyboardData keyboardData = textEntryManager.getDisplays();
                appliveData.setKeyboardDisplays(keyboardData);
            }
        } else {
            textEntryManager.updateDisplays();
            KeyboardData keyboardData = textEntryManager.getDisplays();
            appliveData.setKeyboardDisplays(keyboardData); // update text entry information
        }

        // quick chat manager
        quickChatManager.updateDisplays();
        appliveData.setQuickChatData(quickChatManager.quickChatData);

        appliveData.isRecording = recording;
        appliveData.setDetectionOutput(detectionOutput);
        appliveData.leftTemplates = userDataManager.getLeftCalibrationData(); // templates shown in calibration screen
        appliveData.rightTemplates = userDataManager.getRightCalibrationData();
        mainView.updateLiveData(appliveData); // display the gaze data and testing mats

        presenterBusy = false;
    }
}
