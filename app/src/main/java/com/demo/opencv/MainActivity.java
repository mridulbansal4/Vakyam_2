package com.demo.opencv;

import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.ImageFormat;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.widget.TextViewCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;

import com.demo.opencv.emergency.EmergencyController;
import com.demo.opencv.features.blinkkeyboard.BlinkKeyboard;
import com.demo.opencv.features.blinkkeyboard.EyeClosureEstimator;
import com.demo.opencv.fragments.calibration_display;
import com.demo.opencv.fragments.clinician_mode_display;
import com.demo.opencv.fragments.dev_mode_display;
import com.demo.opencv.fragments.quick_chat_display;
import com.demo.opencv.fragments.text_mode_display;
import com.demo.opencv.metrics.TrialResult;
import com.demo.opencv.metrics.TypingTest;
import com.demo.opencv.metrics.TypingTrial;
import com.demo.opencv.other.ClinicalData;
import com.demo.opencv.sensors.SensorBanner;
import com.demo.opencv.sensors.SensorHealthController;
import com.demo.opencv.sensors.SensorHealthSnapshot;
import com.demo.opencv.sensors.SensorSimulation;
import com.demo.opencv.vision.DetectionOutput;
import com.demo.opencv.vision.ImageFormatUtils;
import com.demo.opencv.vision.Model;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONException;
import org.opencv.core.Mat;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity
        implements ContractInterface.View, calibration_display.OnButtonClickListener,
        text_mode_display.TextModeListener,
        quick_chat_display.QuickChatListener, dev_mode_display.SensorTestHost

{

    // creating object of Presenter interface in Contract
    ContractInterface.Presenter presenter;

    public ImageView[] images = new ImageView[4];
    private final int REQUEST_CODE_PERMISSIONS = 1001;
    private final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
    private final String[] REQUIRED_PERMISSIONS = new String[]{"android.permission.CAMERA", "android.permission.RECORD_AUDIO"};
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private UIViewModel viewModel;
    FragmentManager fragmentManager;
    Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile SensorHealthController sensorController;
    private TextView sensorBanner;
    @Nullable private SensorBanner shownSensorBanner;
    @Nullable private PopupWindow sensorMessage;
    private static final long SENSOR_MESSAGE_MS = 6000;
    private final Runnable dismissSensorMessageTask = this::dismissSensorMessage;
    private View warmTintOverlay, ringLightOverlay;
    private static final int WARM_TINT_RGB = 0xFFFF9329;
    private static final long RING_FADE_MS = 800;
    private static final int LOW_LIGHT_MIN_FPS = 15;
    private ProcessCameraProvider cameraProvider;
    private ImageAnalysis boundAnalysis;
    private boolean lowLightCamera = false;

    private volatile EmergencyController emergency;
    private final EyeClosureEstimator eyeClosure = new EyeClosureEstimator(); // camera thread only
    private View typingTestBar;
    private TextView typingTestText;
    private Button typingTestDone;
    private TypingTrial preparedTrial; // the test the text boxes were last emptied for
    private final TypingTest.Listener typingTestListener = () -> mainHandler.post(this::renderTypingTest);
    private final Runnable typingTestClock = new Runnable() {
        @Override public void run() {
            renderTypingTest();
        }
    };

    private final SensorHealthController.Listener sensorListener = new SensorHealthController.Listener() {
        @Override public void onBannerChanged(@Nullable SensorBanner banner) {
            shownSensorBanner = banner;
            dismissSensorMessage();
            // The brand gives way to the chip so every tab still fits on one line.
            findViewById(R.id.brandTitle).setVisibility(banner == null ? View.VISIBLE : View.GONE);
            if (banner == null) { sensorBanner.setVisibility(View.GONE); return; }
            sensorBanner.setText(banner.getLabelRes());
            sensorBanner.setBackgroundResource(banner.getUrgent() ? R.drawable.bg_status_chip_urgent : R.drawable.bg_status_chip);
            TextViewCompat.setCompoundDrawableTintList(sensorBanner, ColorStateList.valueOf(banner.getUrgent()
                    ? ContextCompat.getColor(MainActivity.this, R.color.vk_on_emergency)
                    : ContextCompat.getColor(MainActivity.this, R.color.vk_key_selecting)));
            sensorBanner.setVisibility(View.VISIBLE);
            // Explain once when it first appears, then stay out of the way as a chip.
            sensorBanner.post(MainActivity.this::showSensorMessage);
        }
        @Override public void onWarmTintChanged(float alpha) {
            warmTintOverlay.setBackgroundColor(ColorUtils.setAlphaComponent(WARM_TINT_RGB, Math.round(alpha * 255)));
        }
        @Override public void onRingLightChanged(boolean on) {
            setRingLight(on);
        }
        @Override public void onSnapshot(@NonNull SensorHealthSnapshot snapshot) {
            if (viewModel != null) viewModel.postSensorHealth(snapshot);
            if (TypingTest.isActive()) TypingTest.setConditions(snapshot.toConditionText());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UserDataManager userDataManager = (UserDataManager) getApplication();
        userDataManager.getSettings();
        setLocale(userDataManager.getLanguage());
        setContentView(R.layout.activity_main);
        // The screen timing out would pause the camera, and with it every eye input including the help signal.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // instantiating object of Presenter Interface
        presenter = new Presenter(this, new Model());

        Log.d("MainActivityVakyam", "initialized");
        try {
            presenter.initialize(this, getApplication()); // initialize presenter
        } catch (IOException | JSONException e) {
            throw new RuntimeException(e);
        }

        if(allPermissionsGranted()){
            startCamera(); //start camera if permission has been granted by user
        } else{
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }
        viewModel = new ViewModelProvider(this).get(UIViewModel.class);
        sensorBanner = findViewById(R.id.sensorBanner);
        sensorBanner.setOnClickListener(v -> {
            if (sensorMessage != null) dismissSensorMessage(); else showSensorMessage();
        });
        warmTintOverlay = findViewById(R.id.warmTintOverlay);
        ringLightOverlay = findViewById(R.id.ringLightOverlay);
        sensorController = new SensorHealthController(this, presenter, sensorListener);

        emergency = new EmergencyController(this);
        typingTestBar = findViewById(R.id.typingTestBar);
        typingTestText = findViewById(R.id.typingTestText);
        typingTestDone = findViewById(R.id.typingTestDone);
        typingTestDone.setOnClickListener(v -> presenter.finishTypingTest());
        findViewById(R.id.typingTestClose).setOnClickListener(v -> TypingTest.clear());
        TypingTest.addListener(typingTestListener);

        // home page set up
        TextView devButton, textButton, calibrateButton, clinicianButton, quickChatButton;
        devButton = findViewById(R.id.devModeButton);
        textButton = findViewById(R.id.textModeButton);
        calibrateButton =  findViewById(R.id.calibrateButton);
        clinicianButton = findViewById(R.id.clinicianModeButton);
        quickChatButton = findViewById(R.id.quickChatButton);

        fragmentManager = getSupportFragmentManager();

        openSettings();
        clinicianButton.setOnClickListener(v -> {
            if (!Objects.equals(presenter.getMode(), "Clinician")) {
                setLocale(userDataManager.getLanguage());
                openClinician();
            }
        });
        devButton.setOnClickListener(v -> {
                if (!Objects.equals(presenter.getMode(), "Dev")) {
                    setLocale(userDataManager.getLanguage());
                    openSettings();
                }
            }
        );
        textButton.setOnClickListener(v -> {
            if (!Objects.equals(presenter.getMode(), "Text")) {
                setLocale(userDataManager.getLanguage());
                openTextEntry();
            }
        });
        calibrateButton.setOnClickListener(v -> {
            if (!Objects.equals(presenter.getMode(), "Calibration")) {
                setLocale(userDataManager.getLanguage());
                openCalibration();
            }
        });
        quickChatButton.setOnClickListener(v -> {
            if (!Objects.equals(presenter.getMode(), "QuickChat")) {
                setLocale(userDataManager.getLanguage());
                openQuickChat();
            }
        });
        findViewById(R.id.blinkKeyboardButton).setOnClickListener(v -> BlinkKeyboard.open(this,
                word -> Toast.makeText(getApplicationContext(), "Spelled: " + word, Toast.LENGTH_LONG).show()));
    }

    private void showSensorMessage() {
        SensorBanner banner = shownSensorBanner;
        if (banner == null || isFinishing() || isDestroyed() || sensorBanner.getWindowToken() == null) return;
        dismissSensorMessage();
        float density = getResources().getDisplayMetrics().density;
        TextView card = new TextView(this);
        card.setText(banner.getMessageRes());
        card.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        card.setLineSpacing(0, 1.15f);
        card.setTextColor(ContextCompat.getColor(this, R.color.vk_ink));
        card.setBackgroundResource(R.drawable.bg_card);
        card.setMaxWidth(Math.round(380 * density));
        int pad = Math.round(16 * density);
        card.setPadding(pad, Math.round(12 * density), pad, Math.round(12 * density));
        card.setOnClickListener(v -> dismissSensorMessage());
        PopupWindow popup = new PopupWindow(card, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false);
        popup.setElevation(8 * density);
        popup.setOutsideTouchable(true);
        popup.setOnDismissListener(() -> { if (sensorMessage == popup) sensorMessage = null; });
        sensorMessage = popup;
        popup.showAsDropDown(sensorBanner, 0, Math.round(8 * density), Gravity.END);
        mainHandler.postDelayed(dismissSensorMessageTask, SENSOR_MESSAGE_MS);
    }

    private void dismissSensorMessage() {
        mainHandler.removeCallbacks(dismissSensorMessageTask);
        PopupWindow popup = sensorMessage;
        sensorMessage = null;
        if (popup != null && popup.isShowing()) popup.dismiss();
    }

    private void selectTopTab(int tabId) {
        ViewGroup bar = findViewById(R.id.topTabBar);
        for (int i = 0; i < bar.getChildCount(); i++) {
            View tab = bar.getChildAt(i);
            tab.setSelected(tab.getId() == tabId);
        }
    }

    private void setLocale(String language) {

        String languageCode;
        if (Objects.equals(language, "English")) {
            languageCode = "en-us";
        } else if (Objects.equals(language, "Hinglish")) {
            languageCode = "hi"; // values-hi holds the Hinglish (Roman-script) strings
        } else {
            languageCode = "en-us";
        }
        Log.d("LocaleSettings", "Current Code: " + languageCode);
        Locale locale = new Locale(languageCode);
        Locale.setDefault(locale);
        Resources resources = getResources();
        Configuration configuration = resources.getConfiguration();

        configuration.setLocale(locale);

        resources.updateConfiguration(configuration, resources.getDisplayMetrics());
    }

    @Override
    public void onCalibrationButtonClick() {
        presenter.updateCalibration();
    }

    @Override
    public void updateLiveData(AppLiveData appLiveData) {
        EmergencyController help = emergency;
        if (help != null) {
            DetectionOutput detection = appLiveData.DetectionOutput;
            boolean faceVisible = detection != null && detection.faceDetected;
            // detection.eyesOpen is too strict for this: it needs both eyes scored nearly shut in every single frame.
            boolean eyesClosed = faceVisible && eyeClosure.update(detection.eyeAspectRatio, detection.eyeOpenProbability);
            help.onEyeSample(faceVisible, eyesClosed);
        }
        Runnable myRunnable = () -> viewModel.selectItem(appLiveData);
        mainHandler.post(myRunnable);
    }


    @Override
    public void openTextEntry() {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            presenter.setMode("Text");
            selectTopTab(R.id.textModeButton);
            Fragment textFragment = text_mode_display.newInstance();
            fragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainerView, textFragment)
                    .setReorderingAllowed(true)
                    .addToBackStack("text_fragment")
                    .commitAllowingStateLoss();
        });
    }

    @Override
    public void openCalibration() {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            presenter.setMode("Calibration");
            selectTopTab(R.id.calibrateButton);
            fragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainerView, calibration_display.class, null)
                    .setReorderingAllowed(true)
                    .addToBackStack("calibrate_fragment")
                    .commitAllowingStateLoss();
        });
    }

    @Override
    public void openSettings() {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            presenter.setMode("Dev");
            selectTopTab(R.id.devModeButton);
            Fragment devFragment = dev_mode_display.newInstance();
            fragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainerView, devFragment)
                    .setReorderingAllowed(true)
                    .addToBackStack("dev_fragment")
                    .commitAllowingStateLoss();
        });
    }

    @Override
    public void openQuickChat() {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            presenter.setMode("QuickChat");
            selectTopTab(R.id.quickChatButton);
            fragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainerView, quick_chat_display.class, null)
                    .setReorderingAllowed(true)
                    .addToBackStack("quick_chat_fragment")
                    .commitAllowingStateLoss();
        });
    }

    @Override
    public void openClinician() {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            presenter.setMode("Clinician");
            selectTopTab(R.id.clinicianModeButton);
            ClinicalData clinicalData = presenter.getClinicalData();
            Fragment calibrationFragment = clinician_mode_display.newInstance(clinicalData);
            fragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainerView, calibrationFragment)
                    .setReorderingAllowed(true)
                    .addToBackStack("clinician_fragment")
                    .commitAllowingStateLoss();
        });
    }



    @Override
    protected void onResume() {
        super.onResume();
        if (sensorController != null) sensorController.start();
        if (emergency != null) emergency.start();
        renderTypingTest(); // a test may have been armed or finished while the blink keyboard was open
    }

    private void renderTypingTest() {
        if (typingTestBar == null) return;
        mainHandler.removeCallbacks(typingTestClock);
        TypingTrial trial = TypingTest.getTrial();
        TrialResult result = TypingTest.getLastResult();
        typingTestBar.setVisibility(trial == null && result == null ? View.GONE : View.VISIBLE);
        typingTestDone.setVisibility(trial != null && trial.isRunning() ? View.VISIBLE : View.GONE);
        if (trial != null) {
            if (trial != preparedTrial) { // every test starts from an empty text box, or leftovers would count as errors
                preparedTrial = trial;
                presenter.clearText();
            }
            String progress = trial.isRunning()
                    ? getString(R.string.TypingTest_Running, TypingTest.elapsedMs() / 1000, trial.getSelections())
                    : getString(R.string.TypingTest_Waiting);
            typingTestText.setText(getString(R.string.TypingTest_Banner, trial.getTarget(), progress));
            mainHandler.postDelayed(typingTestClock, 1000); // keeps the timer on the banner moving
        } else if (result != null) {
            typingTestText.setText(getString(R.string.TypingTest_Finished, result.getTyped(), result.summary()));
        }
    }

    @Override
    protected void onPause() {
        if (sensorController != null) sensorController.stop();
        if (emergency != null) emergency.stop();
        mainHandler.removeCallbacks(typingTestClock);
        dismissSensorMessage();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sensorController != null) sensorController.release();
        if (emergency != null) emergency.release();
        TypingTest.removeListener(typingTestListener);
        mainHandler.removeCallbacks(typingTestClock);
        if (presenter != null) {
            presenter.onDestroy();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }

    private void startCamera() {
        final ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCamera(cameraProvider);

            } catch (ExecutionException | InterruptedException e) {
                // No errors need to be handled for this Future.
                // This should never be reached.
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    void bindCamera(@NonNull ProcessCameraProvider cameraProvider) {

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build();

        ImageAnalysis.Builder newBuilder = new ImageAnalysis.Builder();

        Camera2Interop.Extender<ImageAnalysis> ext = new Camera2Interop.Extender<>(newBuilder);
        int TARGET_FRAME_RATE = 30;
        // In the dark, let auto-exposure drop to 15 fps (~66 ms exposures); still above the 10 fps gesture floor.
        int minFrameRate = lowLightCamera ? LOW_LIGHT_MIN_FPS : TARGET_FRAME_RATE;
        ext.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                new Range<>(minFrameRate, TARGET_FRAME_RATE)
        );
        ImageAnalysis imageAnalysis = newBuilder
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        imageAnalysis.setAnalyzer(cameraExecutor, new ImageAnalysis.Analyzer() {
            @Override
            @OptIn(markerClass = ExperimentalGetImage.class)
            public void analyze(@NonNull ImageProxy image) {
                long now = SystemClock.elapsedRealtime();
                SensorHealthController sensors = sensorController;
                if (presenter.getPresenterState()
                        || (sensors != null && !sensors.getFrameGovernor().shouldProcess(now))) {
                    image.close();
                    return;
                }
                if (image.getFormat() == ImageFormat.YUV_420_888 && image.getPlanes().length == 3) {
                    Image newImage = image.getImage();
                    Mat rgbaMat = null;
                    try {
                        if (newImage != null) {
                            rgbaMat = ImageFormatUtils.rgb(newImage);
                            long t0 = SystemClock.elapsedRealtime();
                            presenter.onFrame(rgbaMat);
                            if (sensors != null) sensors.onFrameProcessed(SystemClock.elapsedRealtime() - t0);
                        }
                    } catch (Exception e) {
                        Log.e("MVPView", "Error analyzing frame", e);
                    } finally {
                        if (newImage != null) newImage.close();
                        if (rgbaMat != null) rgbaMat.release();
                    }
                } else {
                    Log.d("MVPView", "Image format not correct");
                }
                image.close();
            }
        });
        cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis);
        boundAnalysis = imageAnalysis;
    }

    private void setRingLight(boolean on) {
        ringLightOverlay.animate().cancel();
        if (on) {
            ringLightOverlay.setAlpha(0f);
            ringLightOverlay.setVisibility(View.VISIBLE);
            ringLightOverlay.animate().alpha(1f).setDuration(RING_FADE_MS).start();
        } else {
            ringLightOverlay.animate().alpha(0f).setDuration(RING_FADE_MS)
                    .withEndAction(() -> ringLightOverlay.setVisibility(View.GONE)).start();
        }
        // Auto-brightness dims the screen in a dark room, which would make the ring useless.
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = on ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
                : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(params);

        if (lowLightCamera != on) {
            lowLightCamera = on;
            if (cameraProvider != null && boundAnalysis != null && !isFinishing() && !isDestroyed()) {
                cameraProvider.unbind(boundAnalysis);
                bindCamera(cameraProvider);
            }
        }
    }

    @Override
    public boolean isSensorSimulationOn(@NonNull SensorSimulation simulation) {
        return sensorController != null && sensorController.isSimulating(simulation);
    }

    @Override
    public void setSensorSimulation(@NonNull SensorSimulation simulation, boolean on) {
        if (sensorController != null) sensorController.setSimulation(simulation, on);
    }

    @Override
    public void clearSensorSimulations() {
        if (sensorController != null) sensorController.clearSimulations();
    }

    private boolean allPermissionsGranted(){
        for(String permission : REQUIRED_PERMISSIONS){
            if(ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED){
                return false;
            }
        }
        return true;
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {

        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show();
                this.finish();
            }
        }
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            Log.d("MVPView", "Record audio permission granted");
        }
    }

    @Override
    public void onTextModeButtonClicked(int input) {
        presenter.onGazeButtonClicked(input);
    }

    @Override
    public void recordButtonClicked() {
        presenter.onRecordButtonClicked();
    }


    @Override
    public void onQuickChatButtonClicked(int input) {
        presenter.onGazeButtonClicked(input);
    }

    @Override
    public void onQuickChatButtonContentChanged(String newText) {

    }
}
