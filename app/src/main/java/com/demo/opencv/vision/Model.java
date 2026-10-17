package com.demo.opencv.vision;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;

import com.demo.opencv.features.blinkkeyboard.EyeAspectRatioAnalyzer;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.camera.core.ExperimentalGetImage;

import com.demo.opencv.ContractInterface;
import com.demo.opencv.UserDataManager;
import com.demo.opencv.vision.DetectionOutput;
import com.demo.opencv.vision.EyeDetection;
import com.demo.opencv.vision.LookData;
import com.demo.opencv.vision.GoogleFaceDetector;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;


public class Model implements ContractInterface.Model {
    Context mContext, ApplicationContext;
    UserDataManager userDataManager;
    private final EyeDetection detector = new EyeDetection(); // create eye detection object to analyze user input
    private final GoogleFaceDetector faceDetector = new GoogleFaceDetector();
    DetectionOutput detectionOutput = new DetectionOutput();
    private ArrayList<String> prevInputs = new ArrayList<>();
    int lookNum = 8; // number of types of look inputs
    // A look must be held this long to count as a selection; closed eyes longer, so a natural blink is never entered
    static final long LOOK_DWELL_MS = 300L, CLOSED_DWELL_MS = 450L;
    final LookDwellSelector dwellSelector = new LookDwellSelector(LOOK_DWELL_MS, CLOSED_DWELL_MS);
    int[] lookCount;
    static int IMAGE_WIDTH = 44, IMAGE_HEIGHT = 18;
    Integer[] tags = new Integer[]{0, 1, 2, 3, 6, 7}; // matches calibration template index to look tag
    Point[] corners = new Point[4]; // left, top, right, down
    double[] leftTemplateError, rightTemplateError;

    // Transit-robust look stabilization and gesture detection stack
    private MotionSensorStabilizer motionStabilizer;
    private PhoneMotionTracker phoneMotion;
    private final NormalizedIrisEstimator irisEstimator = new NormalizedIrisEstimator();
    private final OneEuroFilter filterX = new OneEuroFilter(1.0, 0.5, 1.0);
    private final OneEuroFilter filterY = new OneEuroFilter(1.0, 0.5, 1.0);
    private final BallisticGestureDetector gestureDetector = new BallisticGestureDetector(1.8f, 0.18f, 400L);

    private static final int[] FRAME_ROTATIONS = {0, 90, 270, 180};
    private static final int ROTATION_SEARCH_AFTER_FRAMES = 15;
    private static final int CONFIRMED_ROTATION_GRACE_FRAMES = 90; // stay on a working rotation through blinks and brief look-aways
    private int rotationIndex = 0;
    private boolean rotationConfirmed = false;
    private int framesWithoutFace = 0;

    // Thermal BALANCED/POWER_SAVER: ML Kit runs on a crop around the last face instead of the full frame
    private volatile boolean faceRoiDetection = false;
    private int framesSinceFullScan = 0;
    private static final int FULL_SCAN_EVERY_N_FRAMES = 30;

    // Experimental, default off: green-weighted grayscale for the iris detector only (never the template path)
    private volatile boolean warmLightIrisGray = false;
    private Mat warmGrayWeights;

    // OpenCV 5 pipeline (VisionPipeline.OPENCV5); the classic path never touches these
    private VisionPipeline activePipeline = VisionPipeline.DEFAULT;
    // Native OpenCV objects are made in initialize(), never at construction, so Model still builds in JVM tests
    private YuNetFaceDetector yunet;
    private FaceQualityMeter qualityMeter;
    final FaceQualityGate qualityGate = new FaceQualityGate();
    private final OneEuroFilter rollFilter = new OneEuroFilter(1.5, 1.0, 1.0); // steadies landmark jitter, follows a real tilt
    private CLAHE clahe;
    private Mat grayFrame, claheFace, cropTransform;
    private boolean faceFound = false; // this frame, by whichever detector found it
    private long lastBenchLogMs = 0;
    static final double CLAHE_CLIP_LIMIT = 2.0;
    static final int CLAHE_TILES = 4;

    @Override
    public void setFaceRoiDetection(boolean enabled) { faceRoiDetection = enabled; }

    public void setWarmLightIrisGrayscale(boolean enabled) { warmLightIrisGray = enabled; }

    private float moreOpenEyeAspectRatio() { // NaN when neither eye contour is available
        float ear = Float.NaN;
        for (List<PointF> points : Arrays.asList(faceDetector.leftEyePoints, faceDetector.rightEyePoints)) {
            if (points == null) continue;
            float eye = EyeAspectRatioAnalyzer.computeEAR(points);
            if (!Float.isNaN(eye) && (Float.isNaN(ear) || eye > ear)) ear = eye;
        }
        return ear;
    }

    private Rect detectionRoi(Mat frame) { // null = scan the full frame
        android.graphics.Rect face = faceDetector.faceBound;
        if (!faceRoiDetection || face == null || !faceDetector.faceDetected
                || ++framesSinceFullScan >= FULL_SCAN_EVERY_N_FRAMES) {
            framesSinceFullScan = 0;
            return null;
        }
        int half = (int) (Math.max(face.width(), face.height()) * 0.8);
        int x0 = Math.max(0, face.centerX() - half);
        int y0 = Math.max(0, face.centerY() - half);
        int x1 = Math.min(frame.cols(), face.centerX() + half);
        int y1 = Math.min(frame.rows(), face.centerY() + half);
        if (x1 - x0 < 96 || y1 - y0 < 96) return null;
        return new Rect(x0, y0, x1 - x0, y1 - y0);
    }

    private Mat irisGray(Mat rgbEye) {
        if (warmGrayWeights == null) {
            warmGrayWeights = new Mat(1, 3, CvType.CV_32F);
            warmGrayWeights.put(0, 0, 0.15, 0.70, 0.15);
        }
        Mat gray = new Mat();
        Core.transform(rgbEye, gray, warmGrayWeights); // 3-channel 8U -> 1-channel 8U
        return gray;
    }

    @Override
    public void initialize(Context context, Context applicationContext) throws IOException { // initialize models, libraries, and datastore

        mContext = context;
        ApplicationContext = applicationContext;
        userDataManager = (UserDataManager) applicationContext;

        leftTemplateError = new double[userDataManager.calibrationTemplateNum];
        rightTemplateError = new double[userDataManager.calibrationTemplateNum];

        if (!OpenCVLoader.initDebug()) { // check and initialize OpenCV
            Log.d("OpenCVDebug", "cannot init debug");
        } else {
            Log.d("OpenCVDebug", "success");
        }
        clahe = Imgproc.createCLAHE(CLAHE_CLIP_LIMIT, new Size(CLAHE_TILES, CLAHE_TILES));
        grayFrame = new Mat();
        claheFace = new Mat();
        cropTransform = new Mat(2, 3, CvType.CV_64F);
        qualityMeter = new FaceQualityMeter();
        yunet = new YuNetFaceDetector();
        yunet.initialize(applicationContext);
        activePipeline = userDataManager.getVisionPipeline();
        updateCalibrationTemplates();
        prevInputs = new ArrayList<>();
        lookCount = new int[lookNum]; // change based on how many detections there are

        faceDetector.initialize(); // initialize Google ML Kit

        // Initialize motion sensor stabilizer (circuit breaker for transit shock)
        motionStabilizer = new MotionSensorStabilizer(mContext, 35.0f, 300L);
        motionStabilizer.start();

        phoneMotion = new PhoneMotionTracker(mContext);
        phoneMotion.start();
    }

    @Override
    public void updateCalibrationTemplates() {
        detector.updateCalibrationTemplates(ApplicationContext,true);
        detector.updateCalibrationTemplates(ApplicationContext, false);
    }

    private boolean gazingLeft(LookData look) {
        return look.LookType == 1 || look.LookType == 6;
    }

    private boolean gazingRight(LookData look) {
        return look.LookType == 2 || look.LookType == 7;
    }

    @Override
    public void analyzeLookOutput() {
        analyzeLookOutput(System.currentTimeMillis(), true);
    }

    // steady = false while the phone is being shaken or bumped: the eye crops cannot be trusted then
    void analyzeLookOutput(long nowMs, boolean steady) { // determine whether input is valid or not based on raw data of both eyes

        if (!detectionOutput.LeftData.Success && !detectionOutput.RightData.Success) { // both eyes not available
            detectionOutput.AnalyzedData = detectionOutput.LeftData;

        } else if (detectionOutput.LeftData.Success && !detectionOutput.RightData.Success) { // only left eye available
            detectionOutput.AnalyzedData = detectionOutput.LeftData;

        } else if (!detectionOutput.LeftData.Success) { // only right eye available
            detectionOutput.AnalyzedData = detectionOutput.RightData;

        } else { // if both eyes are successful, add the two loss and take the lowest

            LookData leftLookData = detectionOutput.LeftData;
            LookData rightLookData = detectionOutput.RightData;

            if (leftLookData.LookType == rightLookData.LookType) {
                // If both eyes agree on look direction (straight, left, right, up, closed, etc.), take lowest error
                detectionOutput.AnalyzedData = (leftLookData.LookProbability <= rightLookData.LookProbability) ? leftLookData : rightLookData;

            } else if (leftLookData.LookType == 5 && rightLookData.LookType == 5) { // eyes closed
                detectionOutput.AnalyzedData = rightLookData;

            } else if (gazingLeft(leftLookData) && gazingLeft(rightLookData)) { // eyes looking left, take left eye
                detectionOutput.AnalyzedData = leftLookData;

            } else if (gazingRight(leftLookData) && gazingRight(rightLookData)) { // eyes looking right, take right eye
                detectionOutput.AnalyzedData = rightLookData;

            } else { // combine the two losses
                int index = -1;
                double minError = 1000000f, secondError = 1000000f;
                if (leftTemplateError != null && rightTemplateError != null) {
                    int maxTemplates = (userDataManager != null) ? userDataManager.calibrationTemplateNum : 6;
                    maxTemplates = Math.min(maxTemplates, Math.min(leftTemplateError.length, rightTemplateError.length));
                    for (int i = 0; i < maxTemplates; i++) {
                        double error = leftTemplateError[i] + rightTemplateError[i];
                        if (error < minError) {
                            index = i;
                            secondError = minError;
                            minError = error;
                        } else if (error < secondError) {
                            secondError = error;
                        }
                    }
                }
                double combinedSensitivity = detector.effectiveSensitivity() * 2.0;
                boolean success = (index >= 0) && (minError <= combinedSensitivity)
                        && EyeDetection.isClearMatch(minError, secondError);
                int selectedLook = (index >= 0 && index < tags.length) ? tags[index] : 0;
                detectionOutput.setEyeData(2, success, selectedLook, 1, (float)minError);
            }

        }

        detectionOutput.gestureOutput = 0;
        int observedLook = LookDwellSelector.UNRELIABLE;
        if (!steady) {
            dwellSelector.cancel();
        } else if (detectionOutput.AnalyzedData.Success) {
            observedLook = detectionOutput.AnalyzedData.LookType;
        }
        int selectedLook = dwellSelector.update(observedLook, nowMs);
        if (selectedLook != 0) { // the look was held long enough to be a deliberate input
            if (prevInputs.size() > 25) {
                prevInputs.clear();
            }
            prevInputs.add(detectionOutput.AnalyzedData.getTypeString(selectedLook));
            detectionOutput.prevInputs = prevInputs;
            detectionOutput.gestureOutput = selectedLook;
        }
    }

    private Rect getBoundingBox(List<PointF> points, Mat mat) {

        int INF = 100000;
        int maxX = -1, maxY = -1, minX = INF, minY = INF;
        int maxXIdx = -1, maxYIdx = -1, minXIdx = -1, minYIdx = -1;

        for (int i = 0; i < points.size(); i++) {
            if ((int) points.get(i).x > maxX) {
                maxX = (int) points.get(i).x;
                maxXIdx = i;
            }
            if ((int) points.get(i).y > maxY) {
                maxY = (int) points.get(i).y;
                maxYIdx = i;
            }
            if ((int) points.get(i).x < minX) {
                minX = (int) points.get(i).x;
                minXIdx = i;
            }
            if ((int) points.get(i).y < minY) {
                minY = (int) points.get(i).y;
                minYIdx = i;
            }
        }

        // slightly enlarging eye ROI
        maxX += 3;
        maxY += 3;
        minX -= 3;
        minY -= 3;
        // to fix resize issue
        float yRatio = IMAGE_HEIGHT / (float) (maxY - minY);
        float xRatio = IMAGE_WIDTH / (float) (maxX - minX);

        if (maxXIdx == -1 || maxYIdx == -1 || minXIdx == -1 || minYIdx == -1) {
            return null;
        }

        // getting all 4 corners of the eye (in relation to the ROI)
        corners[0] = new Point((maxX - points.get(minXIdx).x) * xRatio, (maxY - points.get(minXIdx).y) * yRatio);
        corners[1] = new Point((maxX - points.get(minYIdx).x) * xRatio, (maxY - points.get(minYIdx).y) * yRatio);
        corners[2] = new Point((maxX - points.get(maxXIdx).x) * xRatio, (maxY - points.get(maxXIdx).y) * yRatio);
        corners[3] = new Point((maxX - points.get(maxYIdx).x) * xRatio, (maxY - points.get(maxYIdx).y) * yRatio);

        Log.d("CornerDetection", corners[0].x + " " + corners[1].x + " " + corners[2].x + " " + corners[3].x);
        Log.d("CornerDetection", "Max: " + minX + " " + minY + " " + maxX + " " + maxY);
        Rect boundingBox;
        if (minX >= 0 && minY >= 0 && maxX < mat.cols() && maxY < mat.rows() && minX < maxX && minY < maxY) { // contour is valid
            boundingBox = new Rect(new Point(minX, minY), new Point(maxX, maxY));
            return boundingBox;
        } else {
            return null;
        }
    }

    private Point normalizeIrisCenter(Point irisCenter) { // get the Normalized iris Center with eye corners and the iris center

        double normalizedX = (irisCenter.x - corners[0].x) / (corners[2].x - corners[0].x); // normalized x coordinate
        double normalizedY = (irisCenter.y - corners[1].y) / (corners[3].y - corners[1].y); // normalized y coordinate
        return new Point(normalizedX, normalizedY);
    }

    private Point getIrisCenter(Mat eye) { // getting the normalized iris center (NIC)
        Point normalized = new Point();
        if (eye != null) {
            Point irisCenter = detector.irisDetection(eye);
            Mat irisMat = detector.finalMat;
            for (int i = 0; i < 4; i++) { // testing
                Imgproc.circle(irisMat, corners[i], 2, new Scalar(255,255,255));
            }
            normalized = normalizeIrisCenter(irisCenter);
            detectionOutput.testingMats[2] = detector.opening;
        } else {
            detectionOutput.testingMats[2] = new Mat();
        }
        return normalized;
    }

    // ML Kit only finds roughly upright faces, so try each frame rotation until a face appears and keep the one that works.
    private Mat rotateFrame(Mat frame, int degrees) {
        if (degrees == 0) return frame;
        Mat rotated = new Mat();
        int code = degrees == 90 ? Core.ROTATE_90_CLOCKWISE
                : degrees == 180 ? Core.ROTATE_180
                : Core.ROTATE_90_COUNTERCLOCKWISE;
        Core.rotate(frame, rotated, code);
        return rotated;
    }

    // The pipeline can be switched in Settings while the camera runs; each one has its own templates
    private void syncPipeline() {
        if (userDataManager == null) return;
        VisionPipeline wanted = userDataManager.getVisionPipeline();
        if (wanted == activePipeline) return;
        Log.i("LookCamera", "Vision pipeline " + activePipeline + " -> " + wanted);
        activePipeline = wanted;
        updateCalibrationTemplates();
        dwellSelector.cancel();
        qualityGate.reset();
        rollFilter.reset();
    }

    @Override
    public DetectionOutput classifyLook(Mat cameraMat) {
        long startNs = System.nanoTime();
        syncPipeline();
        int rotation = FRAME_ROTATIONS[rotationIndex];
        Mat upright = rotateFrame(cameraMat, rotation);
        try {
            DetectionOutput output = classifyUprightFrame(upright);
            output.faceDetected = faceFound;
            output.frameRotation = rotation;
            output.pipeline = activePipeline;
            output.processingMs = (System.nanoTime() - startNs) / 1e6f;
            logBenchmark(output);
            if (faceFound) {
                if (!rotationConfirmed) Log.i("LookCamera", "Face found at frame rotation " + rotation);
                rotationConfirmed = true;
                framesWithoutFace = 0;
            } else if (++framesWithoutFace >= (rotationConfirmed ? CONFIRMED_ROTATION_GRACE_FRAMES : ROTATION_SEARCH_AFTER_FRAMES)) {
                framesWithoutFace = 0;
                rotationConfirmed = false;
                rotationIndex = (rotationIndex + 1) % FRAME_ROTATIONS.length;
                Log.i("LookCamera", "No face found, trying frame rotation " + FRAME_ROTATIONS[rotationIndex]);
            }
            return output;
        } finally {
            if (upright != cameraMat) upright.release();
        }
    }

    // One line a second, tagged VisionBench, so a benchmark run can be read straight off logcat
    private void logBenchmark(DetectionOutput out) {
        long now = System.currentTimeMillis();
        if (now - lastBenchLogMs < 1000) return;
        lastBenchLogMs = now;
        LookData a = out.AnalyzedData;
        Log.i("VisionBench", String.format(java.util.Locale.US,
                "pipeline=%s ms=%.1f face=%b yunet=%b look=%d ok=%b err=%.4f gesture=%d issues=%s %s",
                out.pipeline.getSettingValue(), out.processingMs, out.faceDetected, out.yunetFace,
                a != null ? a.LookType : -1, a != null && a.Success, a != null ? a.LookProbability : Float.NaN,
                out.gestureOutput, out.qualityIssues, out.quality != null ? out.quality.summary() : "quality=--"));
    }

    // Face box grown to cover brows and cheeks, clipped to the frame; null when there is no usable face box
    private static Rect grownFaceRect(Rect face, int cols, int rows, double grow) {
        if (face == null) return null;
        int dx = (int) Math.round(face.width * grow), dy = (int) Math.round(face.height * grow);
        int x0 = Math.max(0, face.x - dx), y0 = Math.max(0, face.y - dy);
        int x1 = Math.min(cols, face.x + face.width + dx), y1 = Math.min(rows, face.y + face.height + dy);
        return (x1 - x0 >= 32 && y1 - y0 >= 32) ? new Rect(x0, y0, x1 - x0, y1 - y0) : null;
    }

    private static Rect toCvRect(android.graphics.Rect r) {
        return r == null ? null : new Rect(r.left, r.top, r.width(), r.height());
    }

    private static Rect toCvRect(YuNetFaceDetector.Face f) {
        return f == null ? null : new Rect(Math.round(f.x), Math.round(f.y), Math.round(f.width), Math.round(f.height));
    }

    private static float[] interleaved(List<PointF> points) {
        if (points == null) return null;
        float[] xy = new float[points.size() * 2];
        for (int i = 0; i < points.size(); i++) { xy[2 * i] = points.get(i).x; xy[2 * i + 1] = points.get(i).y; }
        return xy;
    }

    // Head roll from the centres of ML Kit's two eyelid contours (16 points each), else from YuNet's eye
    // landmarks, which are a few degrees less accurate; smoothed over frames
    private double headRoll(YuNetFaceDetector.Face yFace, long nowMs) {
        double roll = Double.NaN;
        double[] a = EyeCropGeometry.centroid(interleaved(faceDetector.leftEyePoints));
        double[] b = EyeCropGeometry.centroid(interleaved(faceDetector.rightEyePoints));
        if (a != null && b != null) {
            roll = EyeCropGeometry.rollFromEyeCenters(a[0], a[1], b[0], b[1]);
        } else if (yFace != null) {
            roll = yFace.rollRad();
        }
        if (Double.isNaN(roll)) { rollFilter.reset(); return 0; }
        return rollFilter.filter(roll, nowMs);
    }

    // One warpAffine cuts, rotates and scales the eye out of the CLAHE-normalised face region
    private Mat alignedEyeCrop(EyeCropGeometry geometry, Mat source, int originX, int originY) {
        if (geometry == null) return null;
        cropTransform.put(0, 0, geometry.forwardMatrix(originX, originY));
        Mat crop = new Mat();
        Imgproc.warpAffine(source, crop, cropTransform, new Size(IMAGE_WIDTH, IMAGE_HEIGHT),
                Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE);
        return crop;
    }

    private DetectionOutput classifyUprightFrame(Mat rgbMat) { @OptIn(markerClass = ExperimentalGetImage.class)

        Mat leftEye = null, rightEye = null;
        Bitmap bmp;

        boolean cv5 = activePipeline.getUsesOpenCv5Stages() && clahe != null; // clahe is null until initialize()
        long nowMs = System.currentTimeMillis();
        // OpenCV 5: YuNet finds the face and ML Kit only reads the eyelids inside it. If YuNet misses, ML Kit
        // scans the whole frame, so this path never finds fewer faces than the classic one.
        YuNetFaceDetector.Face yFace = cv5 ? yunet.detect(rgbMat) : null;

        // google ml kit detection
        Rect roi = cv5 ? grownFaceRect(toCvRect(yFace), rgbMat.cols(), rgbMat.rows(), 0.3) : detectionRoi(rgbMat);
        if (cv5 && roi != null && (roi.width < 96 || roi.height < 96)) roi = null;
        Mat detectSrc = (roi != null) ? rgbMat.submat(roi) : rgbMat;
        Mat rgbaMat = new Mat();
        Imgproc.cvtColor(detectSrc, rgbaMat, Imgproc.COLOR_RGB2RGBA);
        if (detectSrc != rgbMat) detectSrc.release();
        bmp = Bitmap.createBitmap(rgbaMat.cols(), rgbaMat.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(rgbaMat, bmp);
        rgbaMat.release();
        if (roi != null) faceDetector.detect(bmp, roi.x, roi.y); else faceDetector.detect(bmp);

        // initialization
        detectionOutput.initialize(4);
        detectionOutput.gestureOutput = 0;
        detectionOutput.eyeOpenProbability = Math.max(faceDetector.leftEyeOpenProb, faceDetector.rightEyeOpenProb);
        detectionOutput.eyeAspectRatio = moreOpenEyeAspectRatio();
        faceFound = faceDetector.faceDetected || yFace != null;
        detectionOutput.yunetFace = yFace != null;

        // OpenCV 5: grey frame, face quality, and a CLAHE-normalised face region to cut the eyes from
        Rect claheRegion = null;
        if (cv5) {
            Imgproc.cvtColor(rgbMat, grayFrame, Imgproc.COLOR_RGB2GRAY);
            Rect faceRect = yFace != null ? toCvRect(yFace) : toCvRect(faceDetector.faceBound);
            FaceQuality quality = faceRect != null ? qualityMeter.measure(grayFrame, faceRect, yFace) : null;
            qualityGate.update(quality, nowMs);
            detectionOutput.quality = quality;
            detectionOutput.qualityIssues = qualityGate.getActive();
            detectionOutput.qualityPrimary = qualityGate.getPrimary();
            detectionOutput.qualityOkForCalibration = quality != null && qualityGate.getOkForCalibration();

            claheRegion = grownFaceRect(faceRect, grayFrame.cols(), grayFrame.rows(), 0.15);
            if (claheRegion == null) claheRegion = new Rect(0, 0, grayFrame.cols(), grayFrame.rows());
            Mat face = grayFrame.submat(claheRegion);
            clahe.apply(face, claheFace);
            face.release();
        }

        if (!faceDetector.faceDetected && faceDetector.leftEyeContour == null && faceDetector.rightEyeContour == null) { // no face/eye detection
            return detectionOutput;
        }

        boolean bothEyesClosed = (faceDetector.leftEyeOpenProb >= 0 && faceDetector.leftEyeOpenProb <= 0.15f)
                && (faceDetector.rightEyeOpenProb >= 0 && faceDetector.rightEyeOpenProb <= 0.15f);

        if (bothEyesClosed) {
            detectionOutput.setEyeData(0, true, 5, 1, faceDetector.leftEyeOpenProb);
            detectionOutput.setEyeData(1, true, 5, 1, faceDetector.rightEyeOpenProb);
        }

        Rect leftEyeBound = null;
        Mat leftEyeIris = null, rightEyeIris = null;
        EyeCropGeometry leftGeometry = null, rightGeometry = null;
        if (cv5) {
            boolean calibrated = userDataManager != null && userDataManager.checkCalibrationFiles();
            double roll = headRoll(yFace, nowMs);
            if (faceDetector.leftEyePoints != null) {
                leftGeometry = EyeCropGeometry.fromContour(interleaved(faceDetector.leftEyePoints), roll,
                        IMAGE_WIDTH, IMAGE_HEIGHT, rgbMat.cols(), rgbMat.rows());
                leftEye = alignedEyeCrop(leftGeometry, claheFace, claheRegion.x, claheRegion.y);
                if (leftEye != null && !bothEyesClosed && calibrated) {
                    leftTemplateError = detector.runEyeModel(detectionOutput, leftEye, 0);
                }
            }
            if (faceDetector.rightEyePoints != null) {
                rightGeometry = EyeCropGeometry.fromContour(interleaved(faceDetector.rightEyePoints), roll,
                        IMAGE_WIDTH, IMAGE_HEIGHT, rgbMat.cols(), rgbMat.rows());
                rightEye = alignedEyeCrop(rightGeometry, claheFace, claheRegion.x, claheRegion.y);
                if (rightEye != null && !bothEyesClosed && calibrated) {
                    rightTemplateError = detector.runEyeModel(detectionOutput, rightEye, 1);
                }
            }
        } else if (faceDetector.leftEyePoints != null) { // left eye available
            List<PointF> leftEyePoints = faceDetector.leftEyePoints;
            leftEyeBound = getBoundingBox(leftEyePoints, rgbMat);
            if (leftEyeBound != null) {
                leftEye = new Mat(rgbMat, leftEyeBound);
                Imgproc.resize(leftEye, leftEye, new Size(IMAGE_WIDTH, IMAGE_HEIGHT), Imgproc.INTER_LINEAR);
                if (warmLightIrisGray) leftEyeIris = irisGray(leftEye);
                Imgproc.cvtColor(leftEye, leftEye, Imgproc.COLOR_RGB2GRAY);

                if (!bothEyesClosed && userDataManager != null && userDataManager.checkCalibrationFiles()) { // true = calibration complete
                    leftTemplateError = detector.runEyeModel(detectionOutput, leftEye, 0);
                }
            }
        }

        Rect rightEyeBound = null;
        if (!cv5 && faceDetector.rightEyePoints != null) { // right eye available
            List<PointF> rightEyePoints = faceDetector.rightEyePoints;
            rightEyeBound = getBoundingBox(rightEyePoints, rgbMat);
            if (rightEyeBound != null) {
                rightEye = new Mat(rgbMat, rightEyeBound);
                Imgproc.resize(rightEye, rightEye, new Size(IMAGE_WIDTH, IMAGE_HEIGHT), Imgproc.INTER_LINEAR);
                if (warmLightIrisGray) rightEyeIris = irisGray(rightEye);
                Imgproc.cvtColor(rightEye, rightEye, Imgproc.COLOR_RGB2GRAY);

                if (!bothEyesClosed && userDataManager != null && userDataManager.checkCalibrationFiles()) { // if there are calibration images
                    rightTemplateError = detector.runEyeModel(detectionOutput, rightEye, 1);
                }
            }
        }

        // testing data
        detectionOutput.testingMats[0] = leftEye;
        detectionOutput.testingMats[1] = rightEye;
        boolean motionStable = (motionStabilizer == null) || motionStabilizer.isMotionStable();
        boolean phoneShaking = phoneMotion != null && phoneMotion.isShaking();
        boolean qualityBlocks = cv5 && qualityGate.getBlocksInput(); // head turned well away: the templates don't apply
        analyzeLookOutput(nowMs, motionStable && !phoneShaking && !qualityBlocks); // analyze the output before returning to the presenter

        // Transit-robust Normalized Iris Estimation and Ballistic Gesture Detection
        // A flick can only say left/right/up, so once the six calibrated templates exist it must not override them:
        // a look to an upper corner would otherwise be entered as plain left/right before the template is recognised.
        boolean calibrated = userDataManager != null && userDataManager.checkCalibrationFiles();

        boolean eyesClosedByProb = (faceDetector.leftEyeOpenProb >= 0 && faceDetector.leftEyeOpenProb <= 0.15f)
                || (faceDetector.rightEyeOpenProb >= 0 && faceDetector.rightEyeOpenProb <= 0.15f);
        detectionOutput.eyesOpen = !bothEyesClosed; // looking sideways often makes ML Kit score the far eye as closed
        detectionOutput.motionStable = motionStable;
        if (phoneMotion != null) {
            detectionOutput.phoneShaking = phoneMotion.isShaking();
            detectionOutput.phonePitch = phoneMotion.getPitch();
            detectionOutput.phoneYaw = phoneMotion.getYaw();
        }

        // Robust eye contour selection: prefer left eye, fallback to right eye if left eye is missing or out of frame
        List<PointF> primaryPoints = null;
        Rect primaryBound = null;
        EyeCropGeometry primaryGeometry = null;
        Mat primaryEyeMat = null;
        Mat primaryIrisMat = null;
        if (faceDetector.leftEyePoints != null && (leftEyeBound != null || leftGeometry != null) && leftEye != null) {
            primaryPoints = faceDetector.leftEyePoints;
            primaryBound = leftEyeBound;
            primaryGeometry = leftGeometry;
            primaryEyeMat = leftEye;
            primaryIrisMat = leftEyeIris;
        } else if (faceDetector.rightEyePoints != null && (rightEyeBound != null || rightGeometry != null) && rightEye != null) {
            primaryPoints = faceDetector.rightEyePoints;
            primaryBound = rightEyeBound;
            primaryGeometry = rightGeometry;
            primaryEyeMat = rightEye;
            primaryIrisMat = rightEyeIris;
        }

        if (primaryPoints != null && (primaryBound != null || primaryGeometry != null) && primaryEyeMat != null && !primaryPoints.isEmpty()) {
            List<PointF> points = primaryPoints;
            List<NormalizedIrisEstimator.Point2D> pts = new ArrayList<>(points.size());
            for (PointF p : points) {
                pts.add(new NormalizedIrisEstimator.Point2D(p.x, p.y));
            }

            Point irisRoi = detector.irisDetection(primaryIrisMat != null ? primaryIrisMat : primaryEyeMat);
            detectionOutput.testingMats[2] = detector.opening;

            NormalizedIrisEstimator.Point2D irisPt = null;
            if (!Double.isNaN(irisRoi.x) && !Double.isNaN(irisRoi.y) && irisRoi.x > 0 && irisRoi.y > 0) {
                float irisX, irisY;
                if (primaryGeometry != null) { // rotated crop: map back through the inverse transform
                    double[] frame = primaryGeometry.toFrame(irisRoi.x, irisRoi.y);
                    irisX = (float) frame[0];
                    irisY = (float) frame[1];
                } else {
                    irisX = primaryBound.x + (float) (irisRoi.x * primaryBound.width / (double) IMAGE_WIDTH);
                    irisY = primaryBound.y + (float) (irisRoi.y * primaryBound.height / (double) IMAGE_HEIGHT);
                }
                irisPt = new NormalizedIrisEstimator.Point2D(irisX, irisY);
            }
            detectionOutput.irisFound = irisPt != null && detector.lastIrisFound;

            // Rotation-invariant anatomical landmark extraction
            NormalizedIrisEstimator.EyeLandmarks landmarks = irisEstimator.extractLandmarksFromContour(pts, irisPt);
            NormalizedIrisEstimator.LookMetrics metrics = irisEstimator.computeMetrics(landmarks);

            double smoothedX = filterX.filter((double) metrics.getHorizontalRatio(), nowMs);
            double smoothedY = filterY.filter((double) metrics.getVerticalRatio(), nowMs);

            detectionOutput.leftNIC = new Point(smoothedX, smoothedY);
            Log.d("IrisDetection", "Normalized x = " + smoothedX + ", y = " + smoothedY);

            float eyeOpenRatio = metrics.getEyeOpenRatio();
            if (eyesClosedByProb) {
                eyeOpenRatio = Math.min(eyeOpenRatio, 0.05f);
            }

            if (motionStable) {
                BallisticGestureDetector.Gesture gesture = gestureDetector.process(
                        (float) smoothedX,
                        (float) smoothedY,
                        eyeOpenRatio,
                        nowMs
                );

                int gestureCode = 0;
                switch (gesture) {
                    case FLICK_LEFT:
                        gestureCode = 1;
                        break;
                    case FLICK_RIGHT:
                        gestureCode = 2;
                        break;
                    case FLICK_UP:
                        gestureCode = 3;
                        break;
                    case BLINK_CONFIRM:
                        gestureCode = 5;
                        break;
                    case NONE:
                    default:
                        gestureCode = 0;
                        break;
                }
                // Closed eyes are entered by the dwell selector only; a blink reported here as well would be entered twice
                if (calibrated || gestureCode == 5) {
                    gestureCode = 0;
                }

                if (gestureCode != 0) {
                    detectionOutput.gestureOutput = gestureCode;
                    detectionOutput.AnalyzedData.Success = true;
                    detectionOutput.AnalyzedData.LookType = gestureCode;
                    String typeStr = detectionOutput.AnalyzedData.getTypeString(gestureCode);
                    if (prevInputs.size() > 25) {
                        prevInputs.clear();
                    }
                    prevInputs.add(typeStr);
                    detectionOutput.prevInputs = prevInputs;
                } else if (detectionOutput.gestureOutput != 0) {
                    // Calibrated look from analyzeLookOutput() is preserved for text entry
                    gestureDetector.reset();
                }
            } else {
                detectionOutput.gestureOutput = 0;
                gestureDetector.reset();
                Log.d("MotionStabilizer", "Gesture suppressed due to vehicle shock/motion");
            }
        } else {
            if (!motionStable) detectionOutput.gestureOutput = 0;
            gestureDetector.reset(); // no eye contour, so no flick can be measured
        }

        if (leftEyeIris != null) leftEyeIris.release();
        if (rightEyeIris != null) rightEyeIris.release();
        return detectionOutput;
    }
}
