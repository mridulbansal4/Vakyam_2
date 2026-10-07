package com.demo.opencv;


import android.content.Context;
import android.graphics.Bitmap;
import android.media.ToneGenerator;
import android.util.Log;

import com.demo.opencv.other.AudioManager;
import com.demo.opencv.other.ClinicalData;
import com.demo.opencv.socialMedia.SocialMediaData;
import com.demo.opencv.socialMedia.SocialMediaManager;
import com.demo.opencv.textEntry.KeyboardData;
import com.demo.opencv.textEntry.KeyboardManager;
import com.demo.opencv.textEntry.QuickChatManager;
import com.demo.opencv.textEntry.TextEntryManager;
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
    public String mode = ""; // Calibration, Text, Dev, Clinician, Social Media
    boolean recording = false;
    private final ContractInterface.View mainView; // creating object of View Interface
    private final ContractInterface.Model model; // creating object of Model Interface
    private final KeyboardManager keyboardManager = new KeyboardManager();
    private final TextEntryManager textEntryManager = new TextEntryManager();
    private final SocialMediaManager socialMediaManager = new SocialMediaManager();
    private final QuickChatManager quickChatManager = new QuickChatManager();
    AppLiveData appliveData = new AppLiveData();
    AudioManager audioManager = new AudioManager();
    public ClinicalData clinicalData = new ClinicalData();
    ToneGenerator toneGenerator;
    // instantiating the objects of View and Model Interface
    public Presenter(ContractInterface.View mainView, ContractInterface.Model model) {
        this.mainView = mainView;
        this.model = model;
    }
    Mat prevMat;

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

        textEntryManager.initialize(mContext); // text prediction initialization with language setting
        textEntryManager.updateSettings(applicationContext); // update settings for text entry
        socialMediaManager.initialize(mContext, applicationContext); // social media initialization
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
    public void updateCalibration() {
        String[] calibrationMessages = {"Look straight", "Look left and down", "Look right and down", "Look up", "Look left and up", "Look right and up"};

        int calibrationState = userDataManager.getCalibrationState();

        if (calibrationState == -1) { // begin calibration
            audioManager.speakText(calibrationMessages[0]);
            appliveData.calibrationInstruction = calibrationMessages[0];
            calibrationState = 0;

        } else if (calibrationState == userDataManager.calibrationTemplateNum) { // restart calibration
            appliveData.calibrationInstruction = "EYE CALIBRATION";
            calibrationState = -1;

        } else if (calibrationState >= 0) { // during calibration
            if (appliveData.DetectionOutput != null && appliveData.DetectionOutput.testingMats != null) {
                Mat[] eyeMats = appliveData.DetectionOutput.testingMats;
                if (eyeMats.length > 1 && eyeMats[0] != null && eyeMats[1] != null && !eyeMats[0].empty() && !eyeMats[1].empty()) { // the images are collectible
                    Log.d("CalibrationInterface", "Recorded successfully");
                    userDataManager.setLeftCalibrationData(matToBitmap(eyeMats[0]), calibrationState); // collect left frame
                    userDataManager.setRightCalibrationData(matToBitmap(eyeMats[1]), calibrationState); // collect right frame
                    calibrationState += 1;

                    if (calibrationState >= userDataManager.calibrationTemplateNum) { // finished calibration
                        model.updateCalibrationTemplates();
                        appliveData.calibrationInstruction = "CALIBRATION FINISHED!";
                        Log.d("CalibrationInterface", "Finished calibration");
                    } else if (calibrationState < calibrationMessages.length) { // continue
                        audioManager.speakText(calibrationMessages[calibrationState]);
                        appliveData.calibrationInstruction = calibrationMessages[calibrationState];
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
    public void onGazeButtonClicked(int input) { // when the user clicks a gaze button (for both social media and text mode)
        playBeepTone();
        if (Objects.equals(mode, "Text")) {
            if (userDataManager.getTextEntryMode() == 0) {
                keyboardManager.processInput(input, false);
                KeyboardData keyboardData = keyboardManager.getDisplays();
                appliveData.setKeyboardDisplays(keyboardData);
            } else {
                textEntryManager.manageUserInput(input, false);
            }
        } else if (Objects.equals(mode, "Social Media")) {
            socialMediaManager.manageUserInput(input, false);
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
    public void onFrame(Mat rgbMat) {
        presenterBusy = true;
        prevMat = rgbMat;
       // Log.d("MVPPresenter", "Frame loaded");
        DetectionOutput detectionOutput = model.classifyGaze(rgbMat); // get detection output from model
     //   Log.d("MVPPresenter", "Frame processed");

        if (detectionOutput != null && detectionOutput.leftNIC != null) {
            clinicalData.addNICPoint((float) detectionOutput.leftNIC.x, (float) detectionOutput.leftNIC.y);
        }

        if (detectionOutput != null && detectionOutput.AnalyzedData != null) { // when the input is valid
            if (Objects.equals(mode, "Text") || Objects.equals(mode, "Dev") || Objects.equals(mode, "Social Media") || Objects.equals(mode, "QuickChat")) { // not calibrating or in clinician mode, process the input
                int gazeType = detectionOutput.gestureOutput;

                if (gazeType != 0) { // gaze input is meaningful
                    Log.d("IrisDetection", "Gesture Output: " + gazeType);
                    playBeepTone();

                    if (Objects.equals(mode, "Text")) { // only do text input in text mode
                        String gazeLogInput = Calendar.getInstance().getTime() + ": " + userDataManager.gazeIndexToString(gazeType);
                        clinicalData.addGazeLog(gazeLogInput);

                        if (userDataManager.getTextEntryMode() == 0) { // letter-by-letter mode
                            keyboardManager.processInput(gazeType);
                            KeyboardData keyboardData = keyboardManager.getDisplays();
                            appliveData.setKeyboardDisplays(keyboardData);
                        } else { // other two modes
                            textEntryManager.LLMEnabled = (userDataManager.getTextEntryMode() == 2);
                            textEntryManager.manageUserInput(gazeType, true);
                        }
                    } else if (Objects.equals(mode, "Social Media")) { // social media mode
                        socialMediaManager.manageUserInput(gazeType, true);
                    } else if (Objects.equals(mode, "QuickChat")) { // quick chat mode
                        quickChatManager.manageUserInput(gazeType, true);
                    }
                }
            }
        }
        if (textEntryManager.openSocialMedia) { // action to open social media interface
            textEntryManager.openSocialMedia = false;
            socialMediaManager.addSentence(textEntryManager.getLLMSentence()); // add the sentence from the text entry manager
            mainView.openSocialMedia();
        }
        if (socialMediaManager.closeSocialMedia) { // action to close social media interface
            socialMediaManager.closeSocialMedia = false;
            mainView.openTextEntry();
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

        // social media manager
        socialMediaManager.updateCameraFrame(rgbMat); // update camera
        socialMediaManager.updateDisplays();
        SocialMediaData socialMediaData = socialMediaManager.getDisplays();
        appliveData.setSocialMediaData(socialMediaData); // update social media information

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
