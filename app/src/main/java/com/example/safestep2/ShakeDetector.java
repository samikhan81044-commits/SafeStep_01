package com.example.safestep2;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;

public class ShakeDetector implements SensorEventListener {
    // Restored baseline
    private float shakeThresholdGravity = 1.5f; 
    private static final int SHAKE_SLOP_TIME_MS = 500;
    private static final int SHAKE_COUNT_RESET_TIME_MS = 3000;

    private OnShakeListener mListener;
    private long mShakeTimestamp;
    private int mShakeCount;

    private final Handler resetHandler = new Handler(Looper.getMainLooper());
    private final Runnable resetRunnable = () -> mShakeCount = 0;

    public void setOnShakeListener(OnShakeListener listener) {
        this.mListener = listener;
    }

    public void setSensitivity(String level) {
        switch (level) {
            case "High":
                shakeThresholdGravity = 1.1f; // Even more sensitive
                break;
            case "Low":
                shakeThresholdGravity = 3.5f; // Much less sensitive (harder to trigger)
                break;
            case "Medium":
            default:
                shakeThresholdGravity = 1.5f; // Increased sensitivity
                break;
        }
    }

    public interface OnShakeListener {
        void onShake(int count);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (mListener != null) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];

            float gX = x / SensorManager.GRAVITY_EARTH;
            float gY = y / SensorManager.GRAVITY_EARTH;
            float gZ = z / SensorManager.GRAVITY_EARTH;

            float gForce = (float) Math.sqrt(gX * gX + gY * gY + gZ * gZ);

            if (gForce > shakeThresholdGravity) {
                final long now = System.currentTimeMillis();
                if (mShakeTimestamp + SHAKE_SLOP_TIME_MS > now) return;

                if (mShakeTimestamp + SHAKE_COUNT_RESET_TIME_MS < now) {
                    mShakeCount = 0;
                }

                mShakeTimestamp = now;
                mShakeCount++;
                mListener.onShake(mShakeCount);

                resetHandler.removeCallbacks(resetRunnable);
                resetHandler.postDelayed(resetRunnable, SHAKE_COUNT_RESET_TIME_MS);
            }
        }
    }
}