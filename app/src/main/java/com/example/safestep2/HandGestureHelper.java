package com.example.safestep2;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import java.util.List;

public class HandGestureHelper {
    private static final String TAG = "SafeStep_Gesture";
    private static final String MODEL_PATH = "hand_landmarker.task";

    public interface GestureListener {
        void onPalmDetected(boolean isOpenPalm);
        void onError(String error);
    }

    private HandLandmarker handLandmarker;
    private final GestureListener listener;
    private final Context context;

    public HandGestureHelper(Context context, GestureListener listener) {
        this.context = context;
        this.listener = listener;
        setupHandLandmarker();
    }

    private void setupHandLandmarker() {
        try {
            BaseOptions baseOptions = BaseOptions.builder().setModelAssetPath(MODEL_PATH).build();
            HandLandmarker.HandLandmarkerOptions options = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setMinHandDetectionConfidence(0.4f)
                    .setMinTrackingConfidence(0.4f)
                    .setMinHandPresenceConfidence(0.4f)
                    .setNumHands(1)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setResultListener(this::processResult)
                    .setErrorListener(e -> { if (listener != null) listener.onError(e.getMessage()); })
                    .build();
            handLandmarker = HandLandmarker.createFromOptions(context, options);
        } catch (Exception e) {
            Log.e(TAG, "MediaPipe setup failed. Make sure hand_landmarker.task is in assets.");
        }
    }

    public void detect(Bitmap bitmap, long timestamp) {
        if (handLandmarker != null) {
            try {
                MPImage mpImage = new BitmapImageBuilder(bitmap).build();
                handLandmarker.detectAsync(mpImage, timestamp);
            } catch (Exception e) { Log.e(TAG, "Detection error"); }
        }
    }

    private void processResult(HandLandmarkerResult result, MPImage image) {
        if (result == null || result.landmarks().isEmpty()) return;
        List<NormalizedLandmark> landmarks = result.landmarks().get(0);
        if (listener != null) {
            if (isOpenPalm(landmarks)) listener.onPalmDetected(true);
        }
    }

    private boolean isOpenPalm(List<NormalizedLandmark> landmarks) {
        if (landmarks == null || landmarks.size() < 21) return false;
        // Tips: Index(8), Middle(12), Ring(16), Pinky(20)
        // Base Joints: Index(6), Middle(10), Ring(14), Pinky(18)
        // Tip Y < Joint Y Matlub ungliyaan seedhi khuli hain
        boolean indexUp = landmarks.get(8).y() < landmarks.get(6).y();
        boolean middleUp = landmarks.get(12).y() < landmarks.get(10).y();
        boolean ringUp = landmarks.get(16).y() < landmarks.get(14).y();
        boolean pinkyUp = landmarks.get(20).y() < landmarks.get(18).y();

        return indexUp && middleUp && ringUp && pinkyUp;
    }

    public void close() {
        if (handLandmarker != null) {
            try { handLandmarker.close(); } catch (Exception ignored) {}
            handLandmarker = null;
        }
    }
}