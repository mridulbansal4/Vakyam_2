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
import com.demo.opencv.vision.GazeData;
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
    int gazeNum = 8; // number of types of gaze inputs
    // A gaze must be held this long to count as a selection; closed eyes longer, so a natural blink is never entered
    static final long GAZE_DWELL_MS = 300L, CLOSED_DWELL_MS = 450L;
    final GazeDwellSelector dwellSelector = new GazeDwellSelector(GAZE_DWELL_MS, CLOSED_DWELL_MS);
    int[] gazeCount;
    static int IMAGE_WIDTH = 44, IMAGE_HEIGHT = 18;
    Integer[] tags = new Integer[]{0, 1, 2, 3, 6, 7}; // matches calibration template index to gaze tag
    Point[] corners = new Point[4]; // left, top, right, down
    double[] leftTemplateError, rightTemplateError;

    // Transit-robust gaze stabilization and gesture detection stack
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
        updateCalibrationTemplates();
        prevInputs = new ArrayList<>();
        gazeCount = new int[gazeNum]; // change based on how many detections there are

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

    private boolean gazingLeft(GazeData gaze) {
        return gaze.GazeType == 1 || gaze.GazeType == 6;
    }

    private boolean gazingRight(GazeData gaze) {
        return gaze.GazeType == 2 || gaze.GazeType == 7;
    }

    @Override
    public void analyzeGazeOutput() {
        analyzeGazeOutput(System.currentTimeMillis(), true);
    }

    // steady = false while the phone is being shaken or bumped: the eye crops cannot be trusted then
    void analyzeGazeOutput(long nowMs, boolean steady) { // determine whether input is valid or not based on raw data of both eyes

        if (!detectionOutput.LeftData.Success && !detectionOutput.RightData.Success) { // both eyes not available
            detectionOutput.AnalyzedData = detectionOutput.LeftData;

        } else if (detectionOutput.LeftData.Success && !detectionOutput.RightData.Success) { // only left eye available
            detectionOutput.AnalyzedData = detectionOutput.LeftData;

        } else if (!detectionOutput.LeftData.Success) { // only right eye available
            detectionOutput.AnalyzedData = detectionOutput.RightData;

        } else { // if both eyes are successful, add the two loss and take the lowest

            GazeData leftGazeData = detectionOutput.LeftData;
            GazeData rightGazeData = detectionOutput.RightData;

            if (leftGazeData.GazeType == rightGazeData.GazeType) {
                // If both eyes agree on gaze direction (straight, left, right, up, closed, etc.), take lowest error
                detectionOutput.AnalyzedData = (leftGazeData.GazeProbability <= rightGazeData.GazeProbability) ? leftGazeData : rightGazeData;

            } else if (leftGazeData.GazeType == 5 && rightGazeData.GazeType == 5) { // eyes closed
                detectionOutput.AnalyzedData = rightGazeData;

            } else if (gazingLeft(leftGazeData) && gazingLeft(rightGazeData)) { // eyes looking left, take left eye
                detectionOutput.AnalyzedData = leftGazeData;

            } else if (gazingRight(leftGazeData) && gazingRight(rightGazeData)) { // eyes looking right, take right eye
                detectionOutput.AnalyzedData = rightGazeData;

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
                int selectedGaze = (index >= 0 && index < tags.length) ? tags[index] : 0;
                detectionOutput.setEyeData(2, success, selectedGaze, 1, (float)minError);
            }

        }

        detectionOutput.gestureOutput = 0;
        int observedGaze = GazeDwellSelector.UNRELIABLE;
        if (!steady) {
            dwellSelector.cancel();
        } else if (detectionOutput.AnalyzedData.Success) {
            observedGaze = detectionOutput.AnalyzedData.GazeType;
        }
        int selectedGaze = dwellSelector.update(observedGaze, nowMs);
        if (selectedGaze != 0) { // the gaze was held long enough to be a deliberate input
            if (prevInputs.size() > 25) {
                prevInputs.clear();
            }
            prevInputs.add(detectionOutput.AnalyzedData.getTypeString(selectedGaze));
            detectionOutput.prevInputs = prevInputs;
            detectionOutput.gestureOutput = selectedGaze;
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

    @Override
    public DetectionOutput classifyGaze(Mat cameraMat) {
        int rotation = FRAME_ROTATIONS[rotationIndex];
        Mat upright = rotateFrame(cameraMat, rotation);
        try {
            DetectionOutput output = classifyUprightFrame(upright);
            output.faceDetected = faceDetector.faceDetected;
            output.frameRotation = rotation;
            if (faceDetector.faceDetected) {
                if (!rotationConfirmed) Log.i("GazeCamera", "Face found at frame rotation " + rotation);
                rotationConfirmed = true;
                framesWithoutFace = 0;
            } else if (++framesWithoutFace >= (rotationConfirmed ? CONFIRMED_ROTATION_GRACE_FRAMES : ROTATION_SEARCH_AFTER_FRAMES)) {
                framesWithoutFace = 0;
                rotationConfirmed = false;
                rotationIndex = (rotationIndex + 1) % FRAME_ROTATIONS.length;
                Log.i("GazeCamera", "No face found, trying frame rotation " + FRAME_ROTATIONS[rotationIndex]);
            }
            return output;
        } finally {
            if (upright != cameraMat) upright.release();
        }
    }

    private DetectionOutput classifyUprightFrame(Mat rgbMat) { @OptIn(markerClass = ExperimentalGetImage.class)

        Mat leftEye = null, rightEye = null;
        Bitmap bmp;

        // google ml kit detection
        Rect roi = detectionRoi(rgbMat);
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
        if (faceDetector.leftEyePoints != null) { // left eye available
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
        if (faceDetector.rightEyePoints != null) { // right eye available
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
        long nowMs = System.currentTimeMillis();
        boolean motionStable = (motionStabilizer == null) || motionStabilizer.isMotionStable();
        boolean phoneShaking = phoneMotion != null && phoneMotion.isShaking();
        analyzeGazeOutput(nowMs, motionStable && !phoneShaking); // analyze the output before returning to the presenter

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
        Mat primaryEyeMat = null;
        Mat primaryIrisMat = null;
        if (faceDetector.leftEyePoints != null && leftEyeBound != null && leftEye != null) {
            primaryPoints = faceDetector.leftEyePoints;
            primaryBound = leftEyeBound;
            primaryEyeMat = leftEye;
            primaryIrisMat = leftEyeIris;
        } else if (faceDetector.rightEyePoints != null && rightEyeBound != null && rightEye != null) {
            primaryPoints = faceDetector.rightEyePoints;
            primaryBound = rightEyeBound;
            primaryEyeMat = rightEye;
            primaryIrisMat = rightEyeIris;
        }

        if (primaryPoints != null && primaryBound != null && primaryEyeMat != null && !primaryPoints.isEmpty()) {
            List<PointF> points = primaryPoints;
            List<NormalizedIrisEstimator.Point2D> pts = new ArrayList<>(points.size());
            for (PointF p : points) {
                pts.add(new NormalizedIrisEstimator.Point2D(p.x, p.y));
            }

            Point irisRoi = detector.irisDetection(primaryIrisMat != null ? primaryIrisMat : primaryEyeMat);
            detectionOutput.testingMats[2] = detector.opening;

            NormalizedIrisEstimator.Point2D irisPt = null;
            if (!Double.isNaN(irisRoi.x) && !Double.isNaN(irisRoi.y) && irisRoi.x > 0 && irisRoi.y > 0) {
                float irisX = primaryBound.x + (float) (irisRoi.x * primaryBound.width / (double) IMAGE_WIDTH);
                float irisY = primaryBound.y + (float) (irisRoi.y * primaryBound.height / (double) IMAGE_HEIGHT);
                irisPt = new NormalizedIrisEstimator.Point2D(irisX, irisY);
            }
            detectionOutput.irisFound = irisPt != null && detector.lastIrisFound;

            // Rotation-invariant anatomical landmark extraction
            NormalizedIrisEstimator.EyeLandmarks landmarks = irisEstimator.extractLandmarksFromContour(pts, irisPt);
            NormalizedIrisEstimator.GazeMetrics metrics = irisEstimator.computeMetrics(landmarks);

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
                    detectionOutput.AnalyzedData.GazeType = gestureCode;
                    String typeStr = detectionOutput.AnalyzedData.getTypeString(gestureCode);
                    if (prevInputs.size() > 25) {
                        prevInputs.clear();
                    }
                    prevInputs.add(typeStr);
                    detectionOutput.prevInputs = prevInputs;
                } else if (detectionOutput.gestureOutput != 0) {
                    // Calibrated gaze from analyzeGazeOutput() is preserved for text entry
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
