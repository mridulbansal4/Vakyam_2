package com.demo.opencv;
import android.content.Context;

import com.demo.opencv.other.ClinicalData;
import com.demo.opencv.vision.DetectionOutput;

import org.json.JSONException;
import org.opencv.core.Mat;

import java.io.IOException;

public interface ContractInterface {
    interface View {
        void updateLiveData(AppLiveData appLiveData);
        void openTextEntry();
        void openSettings();
        void openClinician();
        void openCalibration();
        void openQuickChat();
    }

    interface Model {
        void analyzeGazeOutput(); // method that runs with new user gaze input
        void initialize(Context context, Context applicationContext) throws IOException; // method to initialize models in the database (OpenCV, TensorFlow Lite, ChatGPT, etc.)
        void updateCalibrationTemplates();
        DetectionOutput classifyGaze(Mat rgbMat); // determine gaze code with output
        void setFaceRoiDetection(boolean enabled); // run ML Kit on a crop around the last face
    }

    interface Presenter {
        void onDestroy(); // method to destroy lifecycle of MainActivity
        void onFrame(Mat rgbMat); // runs every frame
        void initialize(Context context, Context applicationContext) throws IOException, JSONException; // method to initialize presenter/model
        void updateCalibration();
        boolean getPresenterState();
        ClinicalData getClinicalData();
        void setMode(String value);
        String getMode();
        void onGazeButtonClicked(int input);
        void onRecordButtonClicked();
        void setFaceRoiDetection(boolean enabled);
        void speakCaregiverMessage(String text);
        void finishTypingTest();
        void clearText(); // empty the text box of every gaze text-entry mode
    }
}