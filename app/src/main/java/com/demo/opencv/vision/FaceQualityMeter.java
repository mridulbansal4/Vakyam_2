package com.demo.opencv.vision;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.MatOfPoint3f;
import org.opencv.core.Point;
import org.opencv.core.Point3;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.geometry.Geometry;
import org.opencv.imgproc.Imgproc;

/**
 * Measures how usable a frame's face is, with OpenCV 5: sharpness (variance of the Laplacian), exposure (mean
 * and clipped grey levels), backlight (face against frame brightness) and head pose (solvePnP on the five YuNet
 * landmarks). {@link FaceQualityGate} turns the numbers into decisions.
 */
public class FaceQualityMeter {
    /** The face is resized to this width before measuring sharpness, so the value does not depend on distance. */
    static final int SHARPNESS_WIDTH = 128;

    // A generic face in millimetres, in camera axes (x right, y down, z away from the camera), nose tip at the
    // origin. Same order as YuNetFaceDetector.Face.landmarks.
    private static final MatOfPoint3f FACE_MODEL = new MatOfPoint3f(
            new Point3(-31, -32, 26), new Point3(31, -32, 26), new Point3(0, 0, 0),
            new Point3(-25, 32, 24), new Point3(25, 32, 24));

    private final Mat face = new Mat(), laplacian = new Mat(), clipped = new Mat();
    private final MatOfDouble mean = new MatOfDouble(), stddev = new MatOfDouble();
    private final Mat cameraMatrix = Mat.zeros(3, 3, CvType.CV_64F);
    private final Mat rvec = new Mat(), tvec = new Mat(), rotation = new Mat();
    private final MatOfDouble noDistortion = new MatOfDouble();

    /**
     * @param gray     the upright frame in grey
     * @param faceRect the face box in that frame
     * @param yunet    the YuNet face, or null when the face came from ML Kit (then pose and score stay NaN)
     */
    public FaceQuality measure(Mat gray, Rect faceRect, YuNetFaceDetector.Face yunet) {
        Rect r = clip(faceRect, gray.cols(), gray.rows());
        if (r == null) return null;

        Mat roi = gray.submat(r);
        int height = Math.max(1, Math.round(SHARPNESS_WIDTH * r.height / (float) r.width));
        Imgproc.resize(roi, face, new Size(SHARPNESS_WIDTH, height), 0, 0, Imgproc.INTER_AREA);
        roi.release();

        Imgproc.Laplacian(face, laplacian, CvType.CV_64F, 3);
        Core.meanStdDev(laplacian, mean, stddev);
        double sd = stddev.toArray()[0];
        float sharpness = (float) (sd * sd);

        float faceLuma = (float) Core.mean(face).val[0];
        Imgproc.threshold(face, clipped, 249, 255, Imgproc.THRESH_BINARY);
        float clippedFraction = Core.countNonZero(clipped) / (float) face.total();
        float frameLuma = (float) Core.mean(gray).val[0];

        float yaw = Float.NaN, pitch = Float.NaN, roll = Float.NaN, score = Float.NaN;
        if (yunet != null) {
            score = yunet.score;
            double[] pose = headPose(yunet.landmarks, gray.cols(), gray.rows());
            if (pose != null) { yaw = (float) pose[0]; pitch = (float) pose[1]; roll = (float) pose[2]; }
        }
        return new FaceQuality(r.width, sharpness, faceLuma, frameLuma, clippedFraction, yaw, pitch, roll, score);
    }

    /**
     * Yaw, pitch and roll in degrees, or null. The camera is modelled with a focal length equal to the frame width
     * and no distortion, which is close enough for a phone's front camera to tell "facing the phone" from
     * "turned away"; the angles are not precise measurements.
     */
    double[] headPose(float[] landmarks, int frameWidth, int frameHeight) {
        cameraMatrix.put(0, 0, frameWidth, 0, frameWidth / 2.0, 0, frameWidth, frameHeight / 2.0, 0, 0, 1);
        MatOfPoint2f image = new MatOfPoint2f(
                new Point(landmarks[0], landmarks[1]), new Point(landmarks[2], landmarks[3]),
                new Point(landmarks[4], landmarks[5]), new Point(landmarks[6], landmarks[7]),
                new Point(landmarks[8], landmarks[9]));
        try {
            // SQPnP: globally optimal from as few as three points, and steadier than EPnP on five
            if (!Geometry.solvePnP(FACE_MODEL, image, cameraMatrix, noDistortion, rvec, tvec, false, Geometry.SOLVEPNP_SQPNP)) {
                return null;
            }
            Geometry.Rodrigues(rvec, rotation);
            double[] m = new double[9];
            rotation.get(0, 0, m);
            return HeadPose.eulerDegrees(m);
        } catch (Exception e) {
            return null;
        } finally {
            image.release();
        }
    }

    private static Rect clip(Rect r, int width, int height) {
        if (r == null) return null;
        int x0 = Math.max(0, r.x), y0 = Math.max(0, r.y);
        int x1 = Math.min(width, r.x + r.width), y1 = Math.min(height, r.y + r.height);
        if (x1 - x0 < 16 || y1 - y0 < 16) return null;
        return new Rect(x0, y0, x1 - x0, y1 - y0);
    }
}
