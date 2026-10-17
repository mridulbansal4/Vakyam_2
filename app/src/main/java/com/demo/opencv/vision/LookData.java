package com.demo.opencv.vision;


public class LookData {
    public boolean Success;
    public int LookType;
    int LookLength;
    public float LookProbability;
    private final String[] reference = {"Straight", "Left", "Right", "Up", "Down", "Closed", "LeftUp", "RightUp", "ClosedLong"};

    public void setLookData(Boolean success, int lookType, int lookLength, float lookProbability) {
        this.Success = success;
        this.LookType = lookType;
        this.LookLength = lookLength;
        this.LookProbability = lookProbability;
    }

    public String getTypeString(int index) {
        return reference[index];
    }

}
