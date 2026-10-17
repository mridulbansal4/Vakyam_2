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
        void onSentenceForPatient(String sentence); // said into the microphone or typed as context
    }

    interface Model {
        void analyzeLookOutput(); // method that runs with new user look input
        void initialize(Context context, Context applicationContext) throws IOException; // method to initialize models in the database (OpenCV, TensorFlow Lite, ChatGPT, etc.)
        void updateCalibrationTemplates();
        DetectionOutput classifyLook(Mat rgbMat); // determine look code with output
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
        void onLookButtonClicked(int input);
        void onRecordButtonClicked();
        void setFaceRoiDetection(boolean enabled);
        void speakCaregiverMessage(String text);
        void speakPatientText(String text); // in the voice of the patient's language
        void setLookInputPaused(boolean paused); // eye gestures stop selecting while another screen reads the eyes
        void finishTypingTest();
        void clearText(); // empty the text box of every look text-entry mode
    }
}