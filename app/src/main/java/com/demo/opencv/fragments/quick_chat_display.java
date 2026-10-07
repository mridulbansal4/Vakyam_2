package com.demo.opencv.fragments;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.demo.opencv.R;
import com.demo.opencv.UIViewModel;
import com.demo.opencv.UserDataManager;
import com.demo.opencv.textEntry.QuickChatData;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class quick_chat_display extends Fragment {
    private UIViewModel viewModel;
    Activity mActivity;
    Handler handler;
    Runnable updateUI;
    List<TextView> gazeButtons = new ArrayList<>();
    public quick_chat_display() {
        // Required empty public constructor
    }
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mActivity = requireActivity();
        viewModel = new ViewModelProvider(requireActivity()).get(UIViewModel.class);
    }

    public interface QuickChatListener {
        void onQuickChatButtonClicked(int input);
        void onQuickChatButtonContentChanged(String newText);
    }
    QuickChatListener quickChatListener;

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
            quickChatListener = (QuickChatListener) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context + " must implement quickChatListener");
        }
    }

    private void setText(TextView textView, String string) {
        if (textView == null) return;
        if (string != null && !Objects.equals(string, "") && TextUtils.isDigitsOnly(string)) {
            try {
                textView.setText(Integer.parseInt(string)); // set string resource
                return;
            } catch (Exception ignored) {
                // Not a valid string resource ID, fall through
            }
        }
        textView.setText(string != null ? string : ""); // set the original string
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {

        UserDataManager userDataManager = (UserDataManager) requireActivity().getApplication();
        gazeButtons.clear();
        gazeButtons.add(view.findViewById(R.id.QuickChatUp));
        gazeButtons.add(view.findViewById(R.id.QuickChatLeftUp));
        gazeButtons.add(view.findViewById(R.id.QuickChatRightUp));
        gazeButtons.add(view.findViewById(R.id.QuickChatLeft));
        gazeButtons.add(view.findViewById(R.id.QuickChatClosed));
        gazeButtons.add(view.findViewById(R.id.QuickChatRight));

        for (int i = 0; i < gazeButtons.size(); i++) { // add click listeners for the eye gesture buttons
            int finalI = i;
            TextView btn = gazeButtons.get(i);
            if (btn != null) {
                btn.setOnClickListener(v -> quickChatListener.onQuickChatButtonClicked(finalI));
            }
        }

        viewModel.getSelectedItem().observe(getViewLifecycleOwner(), item -> {
            if (item == null) return;
            QuickChatData quickChatData = item.quickChatData;
            if (quickChatData != null && quickChatData.texts != null) {
                for (int i = 0; i < 6; i++) {
                    if (i < gazeButtons.size() && i < quickChatData.texts.length) {
                        setText(gazeButtons.get(i), quickChatData.texts[i]);
                    }
                }
            }
        });
    }
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.quick_chat_fragment, container, false);
    }
}