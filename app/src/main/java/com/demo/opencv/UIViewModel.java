package com.demo.opencv;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.demo.opencv.sensors.SensorHealthSnapshot;

public class UIViewModel extends ViewModel {
    private final MutableLiveData<AppLiveData> devImages = new MutableLiveData<>();
    private final MutableLiveData<SensorHealthSnapshot> sensorHealth = new MutableLiveData<>();
    public void selectItem(AppLiveData item) {
        devImages.setValue(item);
    }
    public LiveData<AppLiveData> getSelectedItem() {
        return devImages;
    }
    public void postSensorHealth(SensorHealthSnapshot snapshot) { sensorHealth.setValue(snapshot); } // main thread
    public LiveData<SensorHealthSnapshot> getSensorHealth() { return sensorHealth; }
}
