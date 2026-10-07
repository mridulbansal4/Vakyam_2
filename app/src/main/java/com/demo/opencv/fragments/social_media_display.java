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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.demo.opencv.R;
import com.demo.opencv.socialMedia.SocialMediaData;
import com.demo.opencv.UIViewModel;
import com.demo.opencv.UserDataManager;

import org.opencv.android.Utils;
import org.opencv.core.Mat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class social_media_display extends Fragment {

    private UIViewModel viewModel;
    Activity mActivity;
    Handler handler;
    Runnable updateUI;
    TextView textBox;
    TextView userNameBox;
    TextView cameraCountdown;
    ImageView imageBox;
    boolean logged_in = false;
    List<TextView> gazeButtons = new ArrayList<>();
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mActivity = requireActivity();
        viewModel = new ViewModelProvider(requireActivity()).get(UIViewModel.class);
    }

    public interface SocialMediaModeListener {
        void onSocialMediaButtonClicked(int input);
        void AuthenticationButtonClicked();
    }
    social_media_display.SocialMediaModeListener socialMediaModeListener;
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (handler != null && updateUI != null) {
            handler.removeCallbacks(updateUI);
        }
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        // Ensure that the container activity has implemented the callback interface
        try {
            socialMediaModeListener = (social_media_display.SocialMediaModeListener) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context + " must implement socialMediaModeListener");
        }
    }

    private Bitmap matToBitmap(Mat mat) {
        if (mat == null || mat.empty() || mat.cols() <= 0 || mat.rows() <= 0) return null;
        Bitmap bm = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(mat, bm);
        return bm;
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {

        UserDataManager userDataManager = (UserDataManager) requireActivity().getApplication();
        logged_in = !(Objects.equals(userDataManager.getAccessToken(), ""));
        textBox = view.findViewById(R.id.socialMediaText);
        imageBox = view.findViewById(R.id.socialMediaImage);
        cameraCountdown = view.findViewById(R.id.socialMediaCameraText);
        userNameBox = view.findViewById(R.id.socialMediaUsernameText);
        gazeButtons.clear();
        gazeButtons.add(view.findViewById(R.id.socialMediaDeleteTextButton)); // up
        gazeButtons.add(view.findViewById(R.id.socialMediaTakePictureButton)); // left
        gazeButtons.add(view.findViewById(R.id.socialMediaGenerateImageButton)); // right
        gazeButtons.add(view.findViewById(R.id.socialMediaRefineTextButton)); // closed
        gazeButtons.add(view.findViewById(R.id.socialMediaBackButton)); // left up
        gazeButtons.add(view.findViewById(R.id.socialMediaUploadButton)); // right up

        for (int i = 0; i < gazeButtons.size(); i++) { // add click listeners for the eye gesture buttons
            int finalI = i;
            TextView btn = gazeButtons.get(i);
            if (btn != null) {
                btn.setOnClickListener(v -> socialMediaModeListener.onSocialMediaButtonClicked(finalI));
            }
        }
        if (userNameBox != null) {
            userNameBox.setOnClickListener(v -> socialMediaModeListener.AuthenticationButtonClicked());
        }

        viewModel.getSelectedItem().observe(getViewLifecycleOwner(), item -> {
            if (item == null) return;
            SocialMediaData socialMediaData = item.socialMediaData;
            if (socialMediaData != null) {
                String finalText = "Tweet Text: " + (socialMediaData.textBox != null ? socialMediaData.textBox : "");
                if (textBox != null) textBox.setText(finalText); // update the text box
                if (socialMediaData.cameraImage != null && !socialMediaData.cameraImage.empty() && imageBox != null) { // update camera frame
                    Bitmap bm = matToBitmap(socialMediaData.cameraImage);
                    if (bm != null) imageBox.setImageBitmap(bm);
                }
                if (cameraCountdown != null) {
                    if (socialMediaData.cameraState == -1 || socialMediaData.cameraState == 0) {
                        cameraCountdown.setText("");
                    } else {
                        cameraCountdown.setText(String.valueOf(socialMediaData.cameraState));
                    }
                }
                if (gazeButtons.size() > 1 && gazeButtons.get(1) != null) {
                    if (socialMediaData.cameraState == -1) {
                        gazeButtons.get(1).setText(R.string.SocialMedia_TakePhoto);
                    } else if (socialMediaData.cameraState == 0) {
                        gazeButtons.get(1).setText(R.string.SocialMedia_CancelPhoto);
                    } else {
                        gazeButtons.get(1).setText(R.string.SocialMedia_TakingPhoto);
                    }
                }
                if (userNameBox != null) {
                    if (logged_in) {
                        userNameBox.setText(R.string.SocialMedia_LoggedIn);
                    } else {
                        userNameBox.setText(R.string.SocialMedia_ClickAuthenticate);
                    }
                }
            }
        });
    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.social_media_fragment, container, false);
    }
}