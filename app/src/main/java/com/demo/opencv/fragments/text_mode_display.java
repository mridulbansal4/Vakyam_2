package com.demo.opencv.fragments;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.demo.opencv.textEntry.KeyboardData;
import com.demo.opencv.R;
import com.demo.opencv.UIViewModel;
import com.demo.opencv.UserDataManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class text_mode_display extends Fragment {
    /*
    Problem 1: Left and Right eye detection not consistent
    Problem 2: How to adjust to different lighting settings? (maybe without callibration?)
    Problem 3: Is there an efficient way to compare similarity between two images (currently use MSE)
     */
    private UIViewModel viewModel;
    Activity mActivity;
    Handler handler;
    Runnable updateUI;
    TextView textInput, sentenceBox;
    TextView contextBox;
    TextView finishBox;
    ImageButton recordButton;
    final int[] keyboardInputMat = {-1, 1, 2, 0, -1, 3, 4, 5}; // convert gaze type into keyboard input
    List<TextView> keyboardOptions = new ArrayList<>();
    List<TextView> finishedScreenTexts = new ArrayList<>();
    List<ImageView> finishedScreenImages = new ArrayList<>();
    private boolean useFirstLayout = false;
    private boolean finishedScreenDisplayed = false;
    public text_mode_display() {
        // Required empty public constructor
    }

    public static text_mode_display newInstance() {
        text_mode_display fragment = new text_mode_display();
        return fragment;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (handler != null && updateUI != null) {
            handler.removeCallbacks(updateUI);
        }
    }

    public interface TextModeListener {
        void onTextModeButtonClicked(int input);
        void recordButtonClicked();
        void contextTyped(String context);
    }
    TextModeListener textModeListener;

    public void switchLayout() {
        // Toggle the boolean flag
        useFirstLayout = !useFirstLayout;

        // Re-inflate the layout
        if (getView() != null) {
            ViewGroup parent = (ViewGroup) getView().getParent();
            parent.removeAllViews();
            View newView = onCreateView(getLayoutInflater(), parent, null);
            parent.addView(newView);
        }
    }

    private void setBackground(int index, int originalID, int newID, long delayMillis) {
        if (index < 0 || index >= keyboardOptions.size()) return;
        TextView target = keyboardOptions.get(index);
        if (target == null) return;
        int left = target.getPaddingLeft();
        int top = target.getPaddingTop();
        int right = target.getPaddingRight();
        int bottom = target.getPaddingBottom();
        target.setBackgroundResource(newID);
        target.setPadding(left, top, right, bottom);
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                target.setBackgroundResource(originalID);
                target.setPadding(left, top, right, bottom);
            }
        }, delayMillis); // delayMillis is the time after which the color will change back
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        // Ensure that the container activity has implemented the callback interface
        try {
            textModeListener = (text_mode_display.TextModeListener) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context + " must implement TextModeListener");
        }
    }

    private void toggleFinishedScreen(boolean state) {
        int visibility = state ? View.VISIBLE : View.INVISIBLE;
        for (TextView text: finishedScreenTexts) {
            if (text != null) {
                text.setVisibility(visibility);
            }
        }
        for (ImageView image: finishedScreenImages) {
            if (image != null) {
                image.setVisibility(visibility);
            }
        }
    }

    private void setText(TextView textView, String string) {
        if (textView == null) return;
        if (string != null && !Objects.equals(string, "") && TextUtils.isDigitsOnly(string)) {
            try {
                textView.setText(Integer.parseInt(string)); // set string resource
                return;
            } catch (Exception ignored) {
                // Not a valid resource ID, fall through
            }
        }
        textView.setText(string != null ? string : ""); // set the original string
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {

        UserDataManager userDataManager = (UserDataManager) requireActivity().getApplication();

        finishedScreenTexts.clear();
        finishedScreenImages.clear();
        keyboardOptions.clear();

        TextView speakBtn = view.findViewById(R.id.speakButton);
        if (speakBtn != null) finishedScreenTexts.add(speakBtn);
        TextView backBtn = view.findViewById(R.id.backButton);
        if (backBtn != null) finishedScreenTexts.add(backBtn);
        finishBox = view.findViewById(R.id.finishBox);
        if (finishBox != null) finishedScreenTexts.add(finishBox);
        TextView darkenLayer = view.findViewById(R.id.darkenLayer);
        if (darkenLayer != null) finishedScreenTexts.add(darkenLayer);

        ImageView speakImg = view.findViewById(R.id.speakButtonImage);
        if (speakImg != null) finishedScreenImages.add(speakImg);
        ImageView backImg = view.findViewById(R.id.backButtonImage);
        if (backImg != null) finishedScreenImages.add(backImg);
        toggleFinishedScreen(false); // closed finished screen

        if (useFirstLayout) {
            textInput = view.findViewById(R.id.textInput);
            contextBox = view.findViewById(R.id.contextBox);
            sentenceBox = view.findViewById(R.id.sentenceBox);
            keyboardOptions.add(view.findViewById(R.id.upGazeText)); // 0: up
            keyboardOptions.add(view.findViewById(R.id.leftGazeText)); // 1: left
            keyboardOptions.add(view.findViewById(R.id.rightGazeText)); // 2: right
            keyboardOptions.add(view.findViewById(R.id.closedGazeText)); // 3: closed gaze
            keyboardOptions.add(view.findViewById(R.id.leftUpGazeText)); // 4: left up
            keyboardOptions.add(view.findViewById(R.id.rightUpGazeText)); // 5: right up
        } else {
            textInput = view.findViewById(R.id.textInput2);
            contextBox = view.findViewById(R.id.contextBox2);
            sentenceBox = view.findViewById(R.id.sentenceBox2);
            keyboardOptions.add(view.findViewById(R.id.upGazeText2)); // 0: up
            keyboardOptions.add(view.findViewById(R.id.leftGazeText2)); // 1: left
            keyboardOptions.add(view.findViewById(R.id.rightGazeText2)); // 2: right
            keyboardOptions.add(view.findViewById(R.id.closedGazeText2)); // 3: closed gaze
            keyboardOptions.add(view.findViewById(R.id.leftUpGazeText2)); // 4: left up
            keyboardOptions.add(view.findViewById(R.id.rightUpGazeText2)); // 5: right up
            keyboardOptions.add(view.findViewById(R.id.leftGazeSubText)); // 6. left sub text
            keyboardOptions.add(view.findViewById(R.id.rightGazeSubText)); // 7. right sub text
            keyboardOptions.add(view.findViewById(R.id.leftUpGazeSubText)); // 8. left up sub text
            keyboardOptions.add(view.findViewById(R.id.rightUpGazeSubText)); // 9. right up sub text
            recordButton = view.findViewById(R.id.recordButton);
            if (recordButton != null) {
                recordButton.setOnClickListener(v -> textModeListener.recordButtonClicked());
            }
        }

        // add click listeners for finished screen
        if (speakBtn != null) speakBtn.setOnClickListener(v -> textModeListener.onTextModeButtonClicked(1));
        if (backBtn != null) backBtn.setOnClickListener(v -> textModeListener.onTextModeButtonClicked(5));

        for (int i = 0; i < Math.min(6, keyboardOptions.size()); i++) { // add click listeners for the eye gesture buttons
            int finalI = i;
            TextView opt = keyboardOptions.get(i);
            if (opt != null) {
                opt.setOnClickListener(v -> textModeListener.onTextModeButtonClicked(finalI));
            }
        }

        String context = userDataManager.getTextEntryContext();
        setText(contextBox, context);
        if (contextBox != null) {
            contextBox.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    userDataManager.setTextEntryContext(v.getText().toString());
                    textModeListener.contextTyped(v.getText().toString());
                }
                return false;
            });
        }

        viewModel.getSelectedItem().observe(getViewLifecycleOwner(), item -> {
            if (item == null) return;
            KeyboardData keyboardData = item.KeyboardData;

            if (keyboardData != null) {
                if (finishedScreenDisplayed != keyboardData.finished) { // check if finished screen should be displayed
                    finishedScreenDisplayed = keyboardData.finished;
                    toggleFinishedScreen(keyboardData.finished);
                }
                if (finishedScreenDisplayed && finishBox != null) {
                    setText(finishBox, keyboardData.sentence);
                }
                for (int i = 0; i < keyboardOptions.size(); i++) {
                    if (keyboardData.Options != null && keyboardData.Options.length > i) {
                        setText(keyboardOptions.get(i), keyboardData.Options[i]); // set string resource
                    }
                }
                setText(contextBox, keyboardData.context); // set context
                if (!useFirstLayout && contextBox != null) {
                    boolean heard = keyboardData.context != null && !keyboardData.context.isEmpty();
                    if (heard != (contextBox.getVisibility() == View.VISIBLE)) {
                        contextBox.setVisibility(heard ? View.VISIBLE : View.GONE);
                        if (recordButton != null) {
                            ConstraintLayout.LayoutParams lp = (ConstraintLayout.LayoutParams) recordButton.getLayoutParams();
                            lp.horizontalBias = heard ? 1f : 0.5f;
                            recordButton.setLayoutParams(lp);
                        }
                    }
                }
                setText(textInput, keyboardData.TextInput);
                setText(sentenceBox, keyboardData.sentence);

                if (!useFirstLayout && recordButton != null) {
                    if (item.isRecording) {
                        recordButton.setBackgroundResource(R.drawable.record_button_recording);
                    } else {
                        recordButton.setBackgroundResource(R.drawable.record_button_idle);
                    }
                }
            }

            if (!useFirstLayout && item.DetectionOutput != null) {
                int gesture = item.DetectionOutput.gestureOutput;
                int actual = -1;
                if (gesture >= 0 && gesture < keyboardInputMat.length) {
                    actual = keyboardInputMat[gesture];
                }

                if (actual != -1 && actual >= 0 && actual < keyboardOptions.size()) {
                    if (actual == 0 || actual == 3) {
                        setBackground(actual, R.drawable.gaze_option_bg_2, R.drawable.gaze_option_bg_selected, 300);
                    } else {
                        setBackground(actual, R.drawable.gaze_option_bg_1, R.drawable.gaze_option_bg_selected, 300);
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
        int layoutId = useFirstLayout ? R.layout.text_mode_fragment : R.layout.text_mode_fragment2;
        return inflater.inflate(layoutId, container, false);
    }
}