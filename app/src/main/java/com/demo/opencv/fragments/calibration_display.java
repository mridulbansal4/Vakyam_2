package com.demo.opencv.fragments;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.demo.opencv.vision.DetectionOutput;
import com.demo.opencv.R;
import com.demo.opencv.UIViewModel;
import com.demo.opencv.UserDataManager;

import org.opencv.android.Utils;
import org.opencv.core.Mat;

public class calibration_display extends Fragment {
    UserDataManager userDataManager;
    private UIViewModel viewModel;
    Activity mActivity;
    TextView instruction;
    Handler handler;
    Runnable updateUI;
    ImageView[] calibrationTemplateViews;
    Button calibrationButton, switchEyesButton;
    ImageView calibrationImage;
    TextView calibrationDescription;
    ProgressBar holdProgress;
    TextView calibrationStatus;
    int calibrationTemplates = 6;
    int[] calibrationMessageID = {R.string.Calibration_LookStraight, R.string.Calibration_LookLeft, R.string.Calibration_LookRight, R.string.Calibration_LookUp,  R.string.Calibration_LookLeftUp, R.string.Calibration_LookRightUp};

    private OnButtonClickListener buttonClickListener;
    boolean leftEyesDisplayed = true;
    public calibration_display() {
        // Required empty public constructor
    }

    private Bitmap matToBitmap(Mat mat) {
        Bitmap bm = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(mat,bm);
        return bm;
    }

    public interface OnButtonClickListener {
        void onCalibrationButtonClick();
    }

    public void onCalibrationButtonClick() { // runs when the "continue" button is pressed
        if (buttonClickListener != null) {
            Log.d("Calibration", "button clicked");
            int calibrationState = userDataManager.getCalibrationState();

            if (calibrationState == userDataManager.calibrationTemplateNum) { // finished calibration, going to reset
                toggleInfoPage(true);
                toggleCalibrationPage(false);

            } else if (calibrationState == -1) { // other states
                toggleInfoPage(false);
                toggleCalibrationPage(true);
            }
            buttonClickListener.onCalibrationButtonClick();
        }
    }

    private void toggleInfoPage(boolean state) {
        int visibility = state ? View.VISIBLE : View.INVISIBLE;
        if (calibrationDescription != null) calibrationDescription.setVisibility(visibility);
        if (calibrationImage != null) calibrationImage.setVisibility(visibility);
    }

    private void toggleCalibrationPage(boolean state) {
        int visibility = state ? View.VISIBLE : View.INVISIBLE;
        if (calibrationTemplateViews != null) {
            for (int i = 0; i < calibrationTemplates && i < calibrationTemplateViews.length; i++) {
                if (calibrationTemplateViews[i] != null) {
                    calibrationTemplateViews[i].setVisibility(visibility);
                }
            }
        }
        if (switchEyesButton != null) {
            switchEyesButton.setVisibility(visibility);
        }
    }

    private void updateButtonAndProgress(int calibrationState, float progress, int statusRes) {
        boolean calibrating = calibrationState >= 0 && calibrationState < userDataManager.calibrationTemplateNum;
        if (calibrationButton != null) {
            if (calibrationState == -1) {
                calibrationButton.setText(R.string.Calibration_Start);
            } else if (calibrating) {
                calibrationButton.setText(R.string.Calibration_CaptureNow); // manual fallback for carers
            } else {
                calibrationButton.setText(R.string.Calibration_Restart);
            }
        }
        if (holdProgress != null) {
            holdProgress.setVisibility(calibrating ? View.VISIBLE : View.INVISIBLE);
            holdProgress.setProgress(Math.round(progress * 100));
        }
        if (calibrationStatus != null) {
            boolean show = calibrating && statusRes != 0;
            calibrationStatus.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
            if (show) calibrationStatus.setText(statusRes);
        }
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        try {
            buttonClickListener = (OnButtonClickListener) context;
            Log.d("Calibration", "Button listener implemented");
        } catch (ClassCastException e) {
            throw new ClassCastException(context + "must implement listener");
        }
    }
    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {

        userDataManager = (UserDataManager) requireActivity().getApplication();
        calibrationDescription = view.findViewById(R.id.CalibrationDescription);
        calibrationImage = view.findViewById(R.id.CalibrationImage);
        instruction = view.findViewById(R.id.calibrationInstruction);
        calibrationTemplateViews = new ImageView[calibrationTemplates];
        calibrationTemplateViews[0] = view.findViewById(R.id.calibrationStraight);
        calibrationTemplateViews[1] = view.findViewById(R.id.calibrationLeft);
        calibrationTemplateViews[2] = view.findViewById(R.id.calibrationRight);
        calibrationTemplateViews[3] = view.findViewById(R.id.calibrationUp);
        calibrationTemplateViews[4] = view.findViewById(R.id.calibrationLeftUp);
        calibrationTemplateViews[5] = view.findViewById(R.id.calibrationRightUp);

        calibrationButton = view.findViewById(R.id.calibrationButton);
        switchEyesButton = view.findViewById(R.id.switchEyesButton);
        holdProgress = view.findViewById(R.id.calibrationHoldProgress);
        calibrationStatus = view.findViewById(R.id.calibrationStatus);
        updateButtonAndProgress(userDataManager.getCalibrationState(), 0f, 0);
        Log.d("Calibration", "here");
        calibrationButton.setOnClickListener(v -> onCalibrationButtonClick());
        switchEyesButton.setOnClickListener(v -> leftEyesDisplayed = !leftEyesDisplayed);

        if (userDataManager.getCalibrationState() == -1) {
            instruction.setText(R.string.Calibration_Title);
            toggleInfoPage(true);
            toggleCalibrationPage(false);
        } else {
            toggleInfoPage(false);
            toggleCalibrationPage(true);
        }

        viewModel.getSelectedItem().observe(getViewLifecycleOwner(), item -> {
            if (item == null) return;
            DetectionOutput detection = item.DetectionOutput;
            int calibrationState = item.calibrationState;
            Log.d("CalibrationInterface", "Current State: " + calibrationState);
            updateButtonAndProgress(calibrationState, item.calibrationProgress, item.calibrationStatusRes);
            if (calibrationState == -1) { // pre-calibration page
                instruction.setText(R.string.Calibration_Title);
            } else { // calibration page
                Bitmap[] images;
                if (leftEyesDisplayed) {
                    images = item.leftTemplates;
                } else {
                    images = item.rightTemplates;
                }

                if (images != null) {
                    for (int i = 0; i < calibrationTemplates; i++) {
                        if (i >= calibrationTemplateViews.length || calibrationTemplateViews[i] == null) continue;
                        if (i >= images.length || images[i] == null || i == calibrationState) {  // show the live camera
                            if (detection != null && detection.testingMats != null && detection.testingMats.length > 0 &&
                                    detection.testingMats[0] != null && !detection.testingMats[0].empty()) {
                                calibrationTemplateViews[i].setImageBitmap(matToBitmap(detection.testingMats[0]));
                            }
                        } else { // show the calibration frame
                            calibrationTemplateViews[i].setImageBitmap(images[i]);
                        }
                    }
                    if (calibrationState == userDataManager.calibrationTemplateNum) {
                        instruction.setText(R.string.Calibration_Finished);
                    } else if (calibrationState >= 0 && calibrationState < calibrationMessageID.length) {
                        instruction.setText(calibrationMessageID[calibrationState]);
                    }
                }
            }

        });
    }
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mActivity = requireActivity();
        viewModel = new ViewModelProvider(requireActivity()).get(UIViewModel.class);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.calibration_fragment, container, false);
    }
}