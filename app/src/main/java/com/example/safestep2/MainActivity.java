package com.example.safestep2;

import android.Manifest;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.example.safestep2.databinding.ActivityMainBinding;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ExperimentalGetImage
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "SafeStep_Main";
    private ActivityMainBinding binding;
    private ExecutorService cameraExecutor;
    private HandGestureHelper gestureHelper;

    private boolean isShakeActive = false;
    private boolean isAudioActive = false;
    private boolean isGestureActive = false;
    private boolean isEmergencyTriggered = false;

    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (EmergencyService.ACTION_STATE_CHANGED.equals(action)) {
                updateUIState(isServiceRunning());
            } else if (EmergencyService.ACTION_SENSOR_TRIGGERED.equals(action)) {
                String type = intent.getStringExtra(EmergencyService.EXTRA_SENSOR_TYPE);
                highlightTriggeredSensor(type);
            }
        }
    };

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (hasRequiredPermissions()) startProtection();
                else Toast.makeText(this, "Permissions required for safety features.", Toast.LENGTH_LONG).show();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // First Launch Check
        SharedPreferences prefs = getSharedPreferences("SafeStepPrefs", MODE_PRIVATE);
        if (prefs.getBoolean("first_launch", true)) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        cameraExecutor = Executors.newSingleThreadExecutor();
        setupGestureHelper();
        loadSavedContacts();
        setupClickListeners();

        updateUIState(isServiceRunning());
    }

    private void setupGestureHelper() {
        try {
            gestureHelper = new HandGestureHelper(this, new HandGestureHelper.GestureListener() {
                @Override
                public void onPalmDetected(boolean isOpenPalm) {
                    if (isOpenPalm && isGestureActive && !isEmergencyTriggered) {
                        runOnUiThread(() -> {
                            if (!isEmergencyTriggered) {
                                // One solid vibration on detection
                                vibrateDevice(600); 
                                Toast.makeText(MainActivity.this, "✋ Hand Detected! Emergency Triggered.", Toast.LENGTH_SHORT).show();
                                triggerEmergency("Hand Gesture Alert ✋");
                            }
                        });
                    }
                }
                @Override
                public void onError(String error) {
                    Log.e(TAG, "Gesture Error: " + error);
                }
            });
        } catch (Exception e) { Log.e(TAG, "Gesture Init Failed", e); }
    }

    private void setupClickListeners() {
        binding.btnSettings.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
        });

        binding.btnStartService.setOnClickListener(v -> {
            if (saveContacts()) {
                if (hasRequiredPermissions()) startProtection();
                else requestPermissions();
            } else {
                Toast.makeText(this, "Please enter at least one contact number.", Toast.LENGTH_SHORT).show();
            }
        });

        binding.btnStopService.setOnClickListener(v -> stopProtection());
        binding.ivShield.setOnClickListener(v -> triggerEmergency("Manual SOS Trigger 🚨"));

        binding.btnShakeInfo.setOnClickListener(v -> {
            if (!isServiceRunning()) {
                Toast.makeText(this, "Enable protection first!", Toast.LENGTH_SHORT).show();
                return;
            }
            isShakeActive = !isShakeActive;
            if (isShakeActive) {
                isAudioActive = isGestureActive = false;
                stopCamera();
                Toast.makeText(this, "Shake your mobile 3 times quickly to trigger emergency", Toast.LENGTH_LONG).show();
            }
            updateSensorCommands();
        });

        binding.btnAudioInfo.setOnClickListener(v -> {
            if (!isServiceRunning()) {
                Toast.makeText(this, "Enable protection first!", Toast.LENGTH_SHORT).show();
                return;
            }
            isAudioActive = !isAudioActive;
            if (isAudioActive) {
                isShakeActive = isGestureActive = false;
                stopCamera();
                String keyword = getSharedPreferences("SafeStepPrefs", MODE_PRIVATE)
                        .getString("audio_keyword", getString(R.string.default_audio_keyword));
                Toast.makeText(this, "say \"" + keyword + "\" to trigger Emergency", Toast.LENGTH_LONG).show();
            }
            updateSensorCommands();
        });

        binding.btnGestureInfo.setOnClickListener(v -> {
            if (!isServiceRunning()) {
                Toast.makeText(this, "Enable protection first!", Toast.LENGTH_SHORT).show();
                return;
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(new String[]{Manifest.permission.CAMERA});
                return;
            }
            isGestureActive = !isGestureActive;
            if (isGestureActive) {
                isShakeActive = isAudioActive = false;
                startCamera();
                Toast.makeText(this, "Gesture Mode: Show Open Palm ✋ for 1 second.", Toast.LENGTH_SHORT).show();
            } else {
                stopCamera();
            }
            updateSensorCommands();
        });
    }

    private void updateSensorCommands() {
        if (isServiceRunning()) {
            Intent intent = new Intent(this, EmergencyService.class);
            intent.setAction(EmergencyService.ACTION_UPDATE_SENSORS);
            boolean anySelected = isShakeActive || isAudioActive || isGestureActive;
            intent.putExtra("shake", isShakeActive || !anySelected);
            intent.putExtra("audio", isAudioActive || !anySelected);
            startService(intent);
        }
        updateUIState(isServiceRunning());
    }

    private void highlightTriggeredSensor(String type) {
        int neon = ContextCompat.getColor(this, R.color.neon_green);
        if ("shake".equals(type)) binding.ivShakeIcon.setColorFilter(neon);
        else if ("audio".equals(type)) binding.ivAudioIcon.setColorFilter(neon);

        new Handler(Looper.getMainLooper()).postDelayed(() -> updateUIState(isServiceRunning()), 3000);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider provider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.viewFinder.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build();
                analysis.setAnalyzer(cameraExecutor, image -> {
                    if (isGestureActive && gestureHelper != null) {
                        gestureHelper.detect(image.toBitmap(), System.currentTimeMillis());
                    }
                    image.close();
                });
                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis);
                binding.viewFinder.setVisibility(View.VISIBLE);
                binding.gestureGlow.setVisibility(View.VISIBLE);
            } catch (Exception e) { Log.e(TAG, "Camera Error", e); }
        }, ContextCompat.getMainExecutor(this));
    }

    private void stopCamera() {
        try {
            ProcessCameraProvider provider = ProcessCameraProvider.getInstance(this).get();
            provider.unbindAll();
            binding.viewFinder.setVisibility(View.INVISIBLE);
            binding.gestureGlow.setVisibility(View.GONE);
        } catch (Exception ignored) {}
    }

    private void triggerEmergency(String source) {
        if (isEmergencyTriggered) return;
        isEmergencyTriggered = true;

        Intent intent = new Intent(this, EmergencyService.class);
        intent.setAction(EmergencyService.ACTION_TRIGGER_EMERGENCY);
        intent.putExtra(EmergencyService.EXTRA_TRIGGER_SOURCE, source);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);

        vibrateDevice(1000); // Confirmation vibration
        Toast.makeText(this, "🚨 EMERGENCY ALERT SENT: " + source, Toast.LENGTH_LONG).show();

        new Handler(Looper.getMainLooper()).postDelayed(() -> isEmergencyTriggered = false, 15000);
    }

    private void vibrateDevice(int duration) {
        Vibrator v;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            v = vm.getDefaultVibrator();
        } else {
            v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }
        if (v != null && v.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(duration);
            }
        }
    }

    private void startProtection() {
        Intent intent = new Intent(this, EmergencyService.class);
        // Explicitly set sensors to true on start to match original behavior
        intent.putExtra("shake", true);
        intent.putExtra("audio", true);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
        
        // Reset toggle states to default (true/visible)
        isShakeActive = isAudioActive = isGestureActive = false;
        updateUIState(true);
    }

    private void stopProtection() {
        stopService(new Intent(this, EmergencyService.class));
        isShakeActive = isAudioActive = isGestureActive = false;
        stopCamera();
        updateUIState(false);
    }

    private void updateUIState(boolean isRunning) {
        binding.statusIndicator.setText(isRunning ? "PROTECTION ACTIVE" : "PROTECTION INACTIVE");
        int mainColor = ContextCompat.getColor(this, isRunning ? R.color.neon_green : R.color.status_red_neon);
        binding.statusIndicator.setTextColor(mainColor);
        binding.ivShield.setColorFilter(mainColor);

        int neon = ContextCompat.getColor(this, R.color.neon_green);
        int grey = ContextCompat.getColor(this, R.color.text_gray_dim);

        binding.ivShakeIcon.setColorFilter(isShakeActive ? neon : grey);
        binding.ivAudioIcon.setColorFilter(isAudioActive ? neon : grey);
        binding.gestureGlow.setVisibility(isGestureActive ? View.VISIBLE : View.GONE);

        binding.btnStartService.setEnabled(!isRunning);
        binding.btnStopService.setEnabled(isRunning);
    }

    private boolean isServiceRunning() {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return false;
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (EmergencyService.class.getName().equals(service.service.getClassName())) return true;
        }
        return false;
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(EmergencyService.ACTION_STATE_CHANGED);
        filter.addAction(EmergencyService.ACTION_SENSOR_TRIGGERED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(updateReceiver, filter);
        }
        updateUIState(isServiceRunning());
    }

    @Override
    protected void onStop() {
        super.onStop();
        try { unregisterReceiver(updateReceiver); } catch (Exception ignored) {}
    }

    private boolean saveContacts() {
        String n1 = binding.etPhoneNumber1.getText().toString().trim();
        String n2 = binding.etPhoneNumber2.getText().toString().trim();
        String n3 = binding.etPhoneNumber3.getText().toString().trim();
        if (n1.isEmpty() && n2.isEmpty() && n3.isEmpty()) return false;
        getSharedPreferences("SafeStepPrefs", MODE_PRIVATE).edit()
                .putString("contact_1", n1).putString("contact_2", n2).putString("contact_3", n3).apply();
        return true;
    }

    private void loadSavedContacts() {
        SharedPreferences p = getSharedPreferences("SafeStepPrefs", MODE_PRIVATE);
        binding.etPhoneNumber1.setText(p.getString("contact_1", ""));
        binding.etPhoneNumber2.setText(p.getString("contact_2", ""));
        binding.etPhoneNumber3.setText(p.getString("contact_3", ""));
    }

    private boolean hasRequiredPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        permissionLauncher.launch(new String[]{
                Manifest.permission.SEND_SMS, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (gestureHelper != null) gestureHelper.close();
    }
}