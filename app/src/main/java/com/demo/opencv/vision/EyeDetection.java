package com.demo.opencv.vision;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;
import java.util.ArrayList;

import com.demo.opencv.UserDataManager;

public class EyeDetection {
    UserDataManager userDataManager;
    public Mat finalMat, opening;
    Integer[] tags = new Integer[]{0, 1, 2, 3, 6, 7}; // matches the calibration order to the gaze data order
    Mat[] leftTemplates, rightTemplates;
    public float thresholdValue = 18f;
    public float sensitivity = 0.015f;
    static int IMAGE_WIDTH = 40, IMAGE_HEIGHT = 14;

    public void updateCalibrationTemplates(Context applicationContext, boolean left) {
        if (applicationContext == null) return;
        userDataManager = (UserDataManager) applicationContext;
        if (userDataManager.getSensitivity() > 0) {
            sensitivity = userDataManager.getSensitivity() / 1000.0f;
        }
        Mat[] templates = new Mat[userDataManager.calibrationTemplateNum];
        Bitmap[] bm;
        if (left) {
            bm = userDataManager.getLeftCalibrationData();
        } else {
            bm = userDataManager.getRightCalibrationData();
        }
        if (bm == null) { // calibration incomplete
            Log.d("CalibrationInterface", "Calibration Missing");
            return;
        }
        for (int i = 0; i < userDataManager.calibrationTemplateNum; i++) {
            if (bm[i] == null) {
                Log.d("CalibrationInterface", "Skipped calibration number = " + i);
                continue; // one frame incomplete, skip
            }
            Log.d("CalibrationInterface", "Recorded = " + i);
            templates[i] = new Mat(bm[i].getHeight(), bm[i].getWidth(), CvType.CV_8UC4);
            Utils.bitmapToMat(bm[i], templates[i]);
            Imgproc.cvtColor(templates[i], templates[i], Imgproc.COLOR_BGR2GRAY);
            Imgproc.resize(templates[i], templates[i], new Size(IMAGE_WIDTH,IMAGE_HEIGHT), Imgproc.INTER_AREA);
        }
        if (left) {
            leftTemplates = templates;
        } else {
            rightTemplates = templates;
        }
    }

    private double mse(Mat a, Mat b, int h, int w) { // minimum squared error calculations
        if (a == null || b == null || a.empty() || b.empty()) {
            return 1000000.0;
        }
        double sum;
        Mat difMat = new Mat(h, w, CvType.CV_8UC4);
        Imgproc.cvtColor(difMat, difMat, Imgproc.COLOR_BGR2GRAY);
        Mat norA = new Mat();
        Mat norB = new Mat();
        a.convertTo(norA, CvType.CV_32F, 1.0/255, 0);
        b.convertTo(norB, CvType.CV_32F, 1.0/255, 0);
        Core.subtract(norA, norB, difMat);
        Mat destSquared = difMat.mul(difMat);
        Scalar s = Core.sumElems(destSquared);
        double d = (float) h * w;
        sum = s.val[0] / d;

        difMat.release();
        norA.release();
        norB.release();
        destSquared.release();
        return sum;
    }
    public double[] runEyeModel(DetectionOutput detectionOutput, Mat eyeROI, int type) {
        Log.d("GestureDetection", "Here!");
        Mat[] compareTemplates;
        double[] templateError = new double[userDataManager != null ? userDataManager.calibrationTemplateNum : 6];
        if (eyeROI == null || eyeROI.empty() || detectionOutput == null) {
            return templateError;
        }

        // resize the eyeROI to the correct image size/format
        eyeROI.convertTo(eyeROI, CvType.CV_8UC4);
        Mat tensorMat = new Mat(IMAGE_HEIGHT, IMAGE_WIDTH, CvType.CV_8UC4);
        Imgproc.resize(eyeROI, tensorMat, new Size(IMAGE_WIDTH, IMAGE_HEIGHT), Imgproc.INTER_AREA);

        if (type == 0) {
            compareTemplates = leftTemplates;
            detectionOutput.testingMats[0] = tensorMat;
        } else {
            compareTemplates = rightTemplates;
            detectionOutput.testingMats[1] = tensorMat;
        }
        // MSE
        double minError = 10000000;
        int index = 0;
        int numTemplates = (userDataManager != null) ? userDataManager.calibrationTemplateNum : 6;
        for (int i = 0; i < numTemplates; i++) {
            double sum = 1000000.0;
            if (compareTemplates != null && i < compareTemplates.length && compareTemplates[i] != null && !compareTemplates[i].empty()) {
                sum = mse(compareTemplates[i], tensorMat, IMAGE_HEIGHT, IMAGE_WIDTH);
            }
            templateError[i] = sum;
            if (sum < minError) {
                minError = sum;
                index = i;
            }
        }
        float effectiveSensitivity = (userDataManager != null && userDataManager.getSensitivity() > 0)
                ? (userDataManager.getSensitivity() / 1000.0f)
                : sensitivity;
        Log.d("GestureDetection", "Sensitivity = " + effectiveSensitivity);
        Boolean success = minError <= effectiveSensitivity; // get threshold for the specific type
        Log.d("GestureDetection", "The gaze for ");
        int gazeTag = (index >= 0 && index < tags.length) ? tags[index] : 0;
        detectionOutput.setEyeData(type, success, gazeTag, 1, (float)minError);

        return templateError;

    }

    public Point irisDetection(Mat ROI) {
        Mat eroded = new Mat();
        Mat threshold = new Mat();
        opening = new Mat();
        Mat hierarchy = new Mat();
        Point irisCenter = new Point();

        Mat erode_kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 5));
        //Mat opening_kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 5));
        ArrayList<MatOfPoint> contours = new ArrayList<>();

        // image processing
     //   Imgproc.erode(ROI, eroded, erode_kernel);
        Imgproc.blur(ROI, eroded, new Size(3,3));
       // Imgproc.adaptiveThreshold(eroded, threshold, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 5, 3);
        Imgproc.threshold(eroded, threshold, thresholdValue, 255, Imgproc.THRESH_BINARY);
        //Imgproc.morphologyEx(threshold, opening, Imgproc.MORPH_OPEN, opening_kernel);
        Imgproc.blur(threshold, opening, new Size(3,3));

        // contour detection
        Core.bitwise_not(opening, opening);
        Imgproc.findContours(opening, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE);
        double maxVal = -1;
        int maxValIdx = -1;
        for (int i = 0; i < contours.size(); i++) {
            double contourArea = Imgproc.contourArea(contours.get(i));
            if (maxVal < contourArea) {
                maxVal = contourArea;
                maxValIdx = i;
            }
        }
        finalMat = new Mat();
        Mat draw;
        if (maxValIdx != -1) {
            MatOfPoint maxContour = contours.get(maxValIdx);
            Moments moments = Imgproc.moments(maxContour);
            double m00 = moments.get_m00();
            if (Math.abs(m00) > 1e-5) {
                irisCenter.x = moments.get_m10() / m00;
                irisCenter.y = moments.get_m01() / m00;
            } else {
                irisCenter.x = ROI.cols() / 2.0;
                irisCenter.y = ROI.rows() / 2.0;
            }
            //Imgproc.drawContours(draw, contours, maxValIdx, new Scalar(255,255,255), 2);
            draw = new Mat(ROI.rows(), ROI.cols(), Imgproc.COLOR_BGR2GRAY);
            draw.setTo(new Scalar(0,0,0));
            Imgproc.circle(draw, new Point(irisCenter.x,irisCenter.y), 2, new Scalar(255,255,255));
            draw.convertTo(finalMat, CvType.CV_8UC3);
        }
        return irisCenter;
    }
}