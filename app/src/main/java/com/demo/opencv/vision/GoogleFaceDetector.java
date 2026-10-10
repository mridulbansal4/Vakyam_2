package com.demo.opencv.vision;


import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.Rect;
import android.media.Image;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageProxy;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceContour;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.face.FaceLandmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class GoogleFaceDetector {

    FaceDetectorOptions realTimeOpts;
    FaceDetector detector;

    // required public data
    public boolean faceDetected = false;
    public FaceContour leftEyeContour, rightEyeContour;
    public PointF leftEyePos, rightEyePos;
    public float rightEyeOpenProb = 1.0f, leftEyeOpenProb = 1.0f, rotY, rotZ;
    public Rect faceBound; // full-frame coordinates
    public List<PointF> leftEyePoints, rightEyePoints; // full-frame coordinates
    private int offsetX = 0, offsetY = 0; // origin of the crop passed to detect()

    public void initialize() {

        realTimeOpts = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build();

        detector = FaceDetection.getClient(realTimeOpts);
    }

    public void analyze(List<Face> faces) {
        if (faces == null || faces.size() == 0) { // prevent the previous input from continuing to work
            faceDetected = false;
            leftEyeContour = null;
            rightEyeContour = null;
            leftEyePoints = null;
            rightEyePoints = null;
            faceBound = null;
            leftEyeOpenProb = 1.0f;
            rightEyeOpenProb = 1.0f;
            return;
        }
        Face largestFace = null;
        int maxSize = -1;
        for (Face face: faces) { // determine the largest face
            Rect boundingBox = face.getBoundingBox();
            if (boundingBox.height() * boundingBox.width() > maxSize) {
                largestFace = face;
                maxSize = boundingBox.height() * boundingBox.width();
            }
        }

        if (largestFace != null) {
            faceDetected = true;
            faceBound = new Rect(largestFace.getBoundingBox());
            faceBound.offset(offsetX, offsetY);
            leftEyeContour = largestFace.getContour(FaceContour.LEFT_EYE);
            rightEyeContour = largestFace.getContour(FaceContour.RIGHT_EYE);
            leftEyePoints = shifted(leftEyeContour);
            rightEyePoints = shifted(rightEyeContour);

            // If classification was enabled:
            if (largestFace.getLeftEyeOpenProbability() != null) {
                leftEyeOpenProb = largestFace.getLeftEyeOpenProbability();
            }
            if (largestFace.getRightEyeOpenProbability() != null) {
                rightEyeOpenProb = largestFace.getRightEyeOpenProbability();
            }
        }
    }

    private List<PointF> shifted(FaceContour contour) {
        if (contour == null) return null;
        List<PointF> src = contour.getPoints();
        List<PointF> out = new ArrayList<>(src.size());
        for (PointF p : src) out.add(new PointF(p.x + offsetX, p.y + offsetY));
        return out;
    }

    public void detect(Bitmap bm) { detect(bm, 0, 0); }

    @OptIn(markerClass = ExperimentalGetImage.class)
    public void detect(Bitmap bm, int offsetX, int offsetY) {
        this.offsetX = offsetX;
        this.offsetY = offsetY;

        InputImage newImage = InputImage.fromBitmap(bm, 0);
        Log.d("googleFaceDetector", "detect begins");

        try {
            List<Face> faces = Tasks.await(detector.process(newImage), 100, TimeUnit.MILLISECONDS);
            analyze(faces);
        } catch (Exception e) {
            Log.e("googleFaceDetector", "detection failed or timed out", e);
            analyze(null); // safely reset state on failure or timeout
        }
    }
}
