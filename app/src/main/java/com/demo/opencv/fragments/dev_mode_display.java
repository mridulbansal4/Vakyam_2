package com.demo.opencv.fragments;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.demo.opencv.vision.DetectionOutput;
import com.demo.opencv.R;
import com.demo.opencv.UIViewModel;
import com.demo.opencv.UserDataManager;
import com.demo.opencv.emergency.CaregiverSms;
import com.demo.opencv.sensors.SensorSimulation;

import org.opencv.android.Utils;
import org.opencv.core.Mat;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public class dev_mode_display extends Fragment {
    private UIViewModel viewModel;
    Activity mActivity;
    ImageView[] images = new ImageView[4];
    TextView[] texts = new TextView[7];
    TextView gazeInputLog;
    Handler handler;
    Runnable updateUI;
    SeekBar sensitivitySeekBar;
    Spinner textEntrySpinner, languageSpinner;
    UserDataManager userDataManager;

    public dev_mode_display() {
        // Required empty public constructor
    }

    public static dev_mode_display newInstance() {
        dev_mode_display fragment = new dev_mode_display();
        Bundle args = new Bundle();
        return fragment;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        // Ensure that the container activity has implemented the callback interface
    }

    private Bitmap matToBitmap(Mat mat) {
        Bitmap bm = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(mat,bm);
        return bm;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mActivity = requireActivity();
        viewModel = new ViewModelProvider(requireActivity()).get(UIViewModel.class);

    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (handler != null && updateUI != null) {
            handler.removeCallbacks(updateUI);
        }
    }

    public void updateSettings() {
        int sensitivity = userDataManager.getSensitivity();
        int textEntryMode = userDataManager.getTextEntryMode();
        String language = userDataManager.getLanguage();


        Log.d("DevModeDisplay", "Settings updated " + sensitivity);
        sensitivitySeekBar.setProgress(sensitivity);
        texts[4].setText(String.valueOf(sensitivity));
        textEntrySpinner.setSelection(textEntryMode);

        languageSpinner.setSelection(Objects.equals(language, "Hindi") ? 1 : 0);
    }

    @Override
    public void onResume() {
        super.onResume();
    }
    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {

        // different image templates on the dev mode display
        userDataManager = (UserDataManager) requireActivity().getApplication();

        images[0] = view.findViewById(R.id.imageView1);
        texts[0] = view.findViewById(R.id.textView1);
        texts[1] = view.findViewById(R.id.textView2);
        texts[2] = view.findViewById(R.id.textView3);
        texts[3] = view.findViewById(R.id.textView4);
        texts[4] = view.findViewById(R.id.textView5);
        gazeInputLog = view.findViewById(R.id.gazeInputLog2);
        sensitivitySeekBar = view.findViewById(R.id.seekBar);
        textEntrySpinner = view.findViewById(R.id.textEntrySpinner);
        languageSpinner = view.findViewById(R.id.languageSpinner);

        Context ctx = getContext() != null ? getContext() : requireActivity();
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(ctx, R.array.language_options, R.layout.custom_spinner_item);
        adapter.setDropDownViewResource(R.layout.custom_spinner_item);
        languageSpinner.setAdapter(adapter);

        ArrayAdapter<CharSequence> adapter2 = ArrayAdapter.createFromResource(ctx, R.array.textEntry_options, R.layout.custom_spinner_item);
        adapter2.setDropDownViewResource(R.layout.custom_spinner_item);
        textEntrySpinner.setAdapter(adapter2);

        updateSettings();
        // set seek bars
        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                   if (seekBar == sensitivitySeekBar) {
                        String text = String.valueOf(progress);
                        texts[4].setText(text);
                    }
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int progress = seekBar.getProgress();
                if (seekBar == sensitivitySeekBar) {
                    userDataManager.setSensitivity(progress);
                }
            }
        };
        sensitivitySeekBar.setOnSeekBarChangeListener(listener);

        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String language = position == 1 ? "Hindi" : "English";
                Log.d("SpinnerTesting", "language changed to " + language);
                userDataManager.setLanguage(language);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Another interface callback
            }
        });

        textEntrySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Log.d("SpinnerTesting", "text entry mode changed to " + position);
                userDataManager.setTextEntryMode(position);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Another interface callback
            }
        });

        viewModel.getSelectedItem().observe(getViewLifecycleOwner(), item -> {
            if (item == null) return;
            DetectionOutput detection = item.DetectionOutput;
            if (detection == null) return;

            // set testing mat 1:
            if (detection.testingMats != null && detection.testingMats.length > 0 &&
                    detection.testingMats[0] != null && !detection.testingMats[0].empty() &&
                    detection.testingMats[0].width() > 0 && detection.testingMats[0].height() > 0) {
                images[0].setImageBitmap(matToBitmap(detection.testingMats[0]));
            }

            if (detection.prevInputs != null) {
                String listString = String.join(", ", detection.prevInputs);
                String text = "Gaze input log: " + listString;
                gazeInputLog.setText(text);
            }
            if (detection.AnalyzedData != null) {
                String s = "Overall Gaze Type: " + detection.AnalyzedData.getTypeString(detection.AnalyzedData.GazeType);
                texts[1].setText(s);

                s = "Overall Loss: " + String.format(java.util.Locale.US, "%.2f", detection.AnalyzedData.GazeProbability);
                texts[2].setText(s);

                if (detection.AnalyzedData.Success) { // check if the final output is successful
                    String text = "GAZE DETECTED";
                    texts[0].setText(text);
                } else {
                    texts[0].setText("---------------");
                }
            }
        });

        EditText caregiverPhone = view.findViewById(R.id.caregiverPhoneInput);
        caregiverPhone.setText(userDataManager.getCaregiverPhone());
        view.findViewById(R.id.caregiverPhoneSave).setOnClickListener(v -> {
            String number = caregiverPhone.getText().toString().trim();
            userDataManager.setCaregiverPhone(number);
            caregiverPhone.clearFocus();
            Toast.makeText(ctx, number.isEmpty() ? R.string.Settings_CaregiverCleared : R.string.Settings_CaregiverSaved, Toast.LENGTH_LONG).show();
            if (!number.isEmpty() && !CaregiverSms.hasPermission(ctx)) { // the alarm cannot ask for this in the middle of an emergency
                ActivityCompat.requestPermissions(requireActivity(), new String[]{android.Manifest.permission.SEND_SMS}, REQUEST_SEND_SMS);
            }
        });

        TextView sensorHealthText = view.findViewById(R.id.sensorHealthText);
        Map<SensorSimulation, Button> simButtons = new EnumMap<>(SensorSimulation.class);
        simButtons.put(SensorSimulation.DARK_ROOM, view.findViewById(R.id.simDarkRoomButton));
        simButtons.put(SensorSimulation.HOT_PHONE, view.findViewById(R.id.simHotPhoneButton));
        simButtons.put(SensorSimulation.CHARGING_HOT, view.findViewById(R.id.simChargingHotButton));
        simButtons.put(SensorSimulation.WARM_LIGHT, view.findViewById(R.id.simWarmLightButton));
        Button simReset = view.findViewById(R.id.simResetButton);
        SensorTestHost host = (requireActivity() instanceof SensorTestHost) ? (SensorTestHost) requireActivity() : null;

        for (Map.Entry<SensorSimulation, Button> entry : simButtons.entrySet()) {
            SensorSimulation sim = entry.getKey();
            Button button = entry.getValue();
            boolean on = host != null && host.isSensorSimulationOn(sim);
            styleSimButton(button, on);
            button.setOnClickListener(v -> {
                if (host == null) return;
                boolean next = !host.isSensorSimulationOn(sim);
                host.setSensorSimulation(sim, next);
                styleSimButton(button, next);
            });
        }
        styleSimButton(simReset, false);
        simReset.setOnClickListener(v -> {
            if (host != null) host.clearSensorSimulations();
            for (Button b : simButtons.values()) styleSimButton(b, false);
        });

        viewModel.getSensorHealth().observe(getViewLifecycleOwner(), s -> {
            if (s == null) return;
            if (sensorHealthText != null) sensorHealthText.setText(s.toDebugText());
            for (Map.Entry<SensorSimulation, Button> entry : simButtons.entrySet()) {
                styleSimButton(entry.getValue(), s.getSimulations().contains(entry.getKey()));
            }
        });
    }

    /** Implemented by MainActivity, which owns the SensorHealthController. */
    public interface SensorTestHost {
        boolean isSensorSimulationOn(@NonNull SensorSimulation simulation);
        void setSensorSimulation(@NonNull SensorSimulation simulation, boolean on);
        void clearSensorSimulations();
    }

    private static final int SIM_ON_COLOR = 0xFFEF6C00;
    private static final int REQUEST_SEND_SMS = 300;

    private void styleSimButton(Button button, boolean on) {
        if (button == null) return;
        int color = on ? SIM_ON_COLOR : ContextCompat.getColor(button.getContext(), R.color.vk_accent);
        button.setBackgroundTintList(ColorStateList.valueOf(color));
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        Log.d("devModeDisplay", "View Created");
        return inflater.inflate(R.layout.dev_mode_fragment, container, false);
    }
}