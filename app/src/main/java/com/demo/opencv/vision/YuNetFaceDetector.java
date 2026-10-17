package com.demo.opencv.vision;

import android.content.Context;
import android.util.Log;

import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.FaceDetectorYN;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Face detection with OpenCV 5's YuNet ({@code cv::FaceDetectorYN}), the face stage of the OpenCV 5 pipeline.
 *
 * YuNet returns a face box and five landmarks (two eye centres, nose tip, two mouth corners). The eye centres
 * give the head's roll and all five give its pose; the box tells ML Kit where to look for the eyelid contours.
 * The frame is shrunk so its long side is {@link #DETECT_LONG_SIDE} before detection, which keeps YuNet at a few
 * milliseconds a frame.
 */
public class YuNetFaceDetector {
    static final String MODEL_ASSET = "face_detection_yunet_2023mar.onnx";
    static final int DETECT_LONG_SIDE = 320;
    static final float SCORE_THRESHOLD = 0.6f, NMS_THRESHOLD = 0.3f;
    static final int TOP_K = 5;

    /** One detected face, in full-frame coordinates. */
    public static final class Face {
        public final float x, y, width, height, score;
        /** Image-left eye, image-right eye, nose tip, image-left mouth corner, image-right mouth corner; x then y. */
        public final float[] landmarks;

        Face(float x, float y, float width, float height, float score, float[] landmarks) {
            this.x = x; this.y = y; this.width = width; this.height = height; this.score = score;
            this.landmarks = landmarks;
        }

        /** Roll of the eye line, radians. */
        public double rollRad() {
            return EyeCropGeometry.rollFromEyeCenters(landmarks[0], landmarks[1], landmarks[2], landmarks[3]);
        }
    }

    private FaceDetectorYN detector;
    private Size inputSize;
    private Mat small, faces; // made in initialize()
    private boolean available = false;

    /** Loads the model from the app's assets. Returns false (and the pipeline falls back to ML Kit) if it cannot. */
    public boolean initialize(Context context) {
        try {
            small = new Mat();
            faces = new Mat();
            File model = copyAssetToFiles(context, MODEL_ASSET);
            detector = FaceDetectorYN.create(model.getAbsolutePath(), "", new Size(DETECT_LONG_SIDE, DETECT_LONG_SIDE),
                    SCORE_THRESHOLD, NMS_THRESHOLD, TOP_K);
            available = detector != null;
        } catch (Exception | UnsatisfiedLinkError e) {
            Log.e("YuNet", "Could not load " + MODEL_ASSET + "; the OpenCV 5 pipeline will use ML Kit for faces", e);
            available = false;
        }
        Log.i("YuNet", "available=" + available);
        return available;
    }

    public boolean isAvailable() { return available; }

    /** The largest face in an RGB frame, or null. */
    public Face detect(Mat rgbFrame) {
        if (!available || rgbFrame == null || rgbFrame.empty()) return null;
        double scale = Math.min(1.0, DETECT_LONG_SIDE / (double) Math.max(rgbFrame.cols(), rgbFrame.rows()));
        Imgproc.resize(rgbFrame, small, new Size(Math.round(rgbFrame.cols() * scale), Math.round(rgbFrame.rows() * scale)),
                0, 0, Imgproc.INTER_AREA);
        Imgproc.cvtColor(small, small, Imgproc.COLOR_RGB2BGR); // YuNet was trained on BGR images
        Size size = small.size();
        if (inputSize == null || inputSize.width != size.width || inputSize.height != size.height) {
            detector.setInputSize(size);
            inputSize = size;
        }
        try {
            detector.detect(small, faces);
        } catch (Exception e) {
            Log.e("YuNet", "detect failed", e);
            return null;
        }
        if (faces.empty() || faces.cols() < 15) return null;

        int best = -1;
        float bestArea = -1;
        float[] row = new float[15];
        for (int i = 0; i < faces.rows(); i++) {
            faces.get(i, 0, row);
            float area = row[2] * row[3];
            if (area > bestArea) { bestArea = area; best = i; }
        }
        faces.get(best, 0, row);
        float inv = (float) (1.0 / scale);
        float[] landmarks = new float[10];
        for (int i = 0; i < 10; i++) landmarks[i] = row[4 + i] * inv;
        // Name the eyes and mouth corners by side in the image, whatever order the model gives them in
        if (landmarks[0] > landmarks[2]) { swap(landmarks, 0, 2); swap(landmarks, 1, 3); }
        if (landmarks[6] > landmarks[8]) { swap(landmarks, 6, 8); swap(landmarks, 7, 9); }
        return new Face(row[0] * inv, row[1] * inv, row[2] * inv, row[3] * inv, row[14], landmarks);
    }

    private static void swap(float[] a, int i, int j) { float t = a[i]; a[i] = a[j]; a[j] = t; }

    private static File copyAssetToFiles(Context context, String name) throws IOException {
        File dir = new File(context.getFilesDir(), "models");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File out = new File(dir, name);
        long assetLength;
        try (android.content.res.AssetFileDescriptor fd = context.getAssets().openFd(name)) {
            assetLength = fd.getLength();
        } catch (IOException compressed) {
            assetLength = -1; // compressed assets have no descriptor; copy every time
        }
        if (out.exists() && out.length() == assetLength) return out;
        try (InputStream in = context.getAssets().open(name); OutputStream os = new FileOutputStream(out)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) os.write(buffer, 0, n);
        }
        return out;
    }
}
