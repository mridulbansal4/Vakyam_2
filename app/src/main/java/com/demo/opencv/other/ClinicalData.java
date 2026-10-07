package com.demo.opencv.other;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ClinicalData {
    public static final int MAX_DATA_POINTS = 1000;

    public final List<Float> leftNICX = Collections.synchronizedList(new ArrayList<>());
    public final List<Float> leftNICY = Collections.synchronizedList(new ArrayList<>());
    public final List<String> gazeLog = Collections.synchronizedList(new ArrayList<>());

    public synchronized void addNICPoint(float x, float y) {
        if (leftNICX.size() >= MAX_DATA_POINTS) {
            leftNICX.remove(0);
        }
        if (leftNICY.size() >= MAX_DATA_POINTS) {
            leftNICY.remove(0);
        }
        leftNICX.add(x);
        leftNICY.add(y);
    }

    public synchronized void addGazeLog(String entry) {
        if (entry == null) return;
        if (gazeLog.size() >= MAX_DATA_POINTS) {
            gazeLog.remove(0);
        }
        gazeLog.add(entry);
    }

    public synchronized void clear() {
        leftNICX.clear();
        leftNICY.clear();
        gazeLog.clear();
    }
}
