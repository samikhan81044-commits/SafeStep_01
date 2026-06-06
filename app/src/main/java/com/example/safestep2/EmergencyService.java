package com.example.safestep2;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.location.Location;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.telephony.SmsManager;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.util.ArrayList;

@SuppressLint({"InlinedApi", "DeprecatedClass"})
public class EmergencyService extends Service {
    public static final String ACTION_TRIGGER_EMERGENCY = "com.example.safestep2.ACTION_TRIGGER_EMERGENCY";
    public static final String ACTION_STATE_CHANGED = "com.example.safestep2.ACTION_STATE_CHANGED";
    public static final String ACTION_SENSOR_TRIGGERED = "com.example.safestep2.ACTION_SENSOR_TRIGGERED";
    public static final String ACTION_UPDATE_SENSORS = "com.example.safestep2.ACTION_UPDATE_SENSORS";

    public static final String EXTRA_TRIGGER_SOURCE = "com.example.safestep2.EXTRA_TRIGGER_SOURCE";
    public static final String EXTRA_IS_RUNNING = "com.example.safestep2.EXTRA_IS_RUNNING";
    public static final String EXTRA_SENSOR_TYPE = "com.example.safestep2.EXTRA_SENSOR_TYPE";

    private static final String TAG = "SafeStep_Service";
    private static final String CHANNEL_ID = "SafeStepMonitoringChannel";
    private static final int NOTIFICATION_ID = 101;

    // Updated to 20.0 dB for maximum sensitivity as requested
    private static final double SOUND_THRESHOLD_DB = 20.0;

    private SensorManager mSensorManager;
    private ShakeDetector mShakeDetector;
    private FusedLocationProviderClient fusedLocationClient;
    private PowerManager.WakeLock wakeLock;

    private AudioRecord audioRecord;
    private boolean isAudioMonitoring = false;
    private Thread audioThread;

    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private boolean isSpeechChecking = false;
    private boolean isEmergencyActive = false;
    private AudioManager audioManager;

    private boolean shakeEnabled = true;
    private boolean audioEnabled = true;

    private final int[] originalVolumes = new int[7];
    private static final int[] STREAMS_TO_MUTE = {
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_DTMF,
            AudioManager.STREAM_VOICE_CALL
    };
    private boolean isMuted = false;

    @Override
    public void onCreate() {
        super.onCreate();
        broadcastServiceState(true);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SafeStep::ServiceWakeLock");
            wakeLock.acquire(30 * 60 * 1000L);
        }

        initSpeechRecognizer();
        updateSensorMonitoring();
    }

    private void broadcastServiceState(boolean running) {
        Intent intent = new Intent(ACTION_STATE_CHANGED);
        intent.putExtra(EXTRA_IS_RUNNING, running);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void broadcastSensorTrigger(String type) {
        Intent intent = new Intent(ACTION_SENSOR_TRIGGERED);
        intent.putExtra(EXTRA_SENSOR_TYPE, type);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        if (intent != null) {
            if (ACTION_TRIGGER_EMERGENCY.equals(intent.getAction())) {
                handleEmergency(intent.getStringExtra(EXTRA_TRIGGER_SOURCE));
            } else if (ACTION_UPDATE_SENSORS.equals(intent.getAction())) {
                shakeEnabled = intent.getBooleanExtra("shake", true);
                audioEnabled = intent.getBooleanExtra("audio", true);
                updateSensorMonitoring();
            }
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SafeStep Protection Active")
                .setContentText("Monitoring sensors for your safety...")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true).build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION | 
                                      ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                foregroundServiceType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            }
            startForeground(NOTIFICATION_ID, notification, foregroundServiceType);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        
        return START_STICKY;
    }

    private void updateSensorMonitoring() {
        SharedPreferences prefs = getSharedPreferences("SafeStepPrefs", MODE_PRIVATE);
        
        if (shakeEnabled) {
            if (mSensorManager == null) mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            if (mShakeDetector == null) {
                mShakeDetector = new ShakeDetector();
                mShakeDetector.setOnShakeListener(count -> {
                    if (count >= 3) {
                        broadcastSensorTrigger("shake");
                        handleEmergency("Shake Detected 📱");
                    }
                });
            }
            // Apply sensitivity from settings
            String sensitivity = prefs.getString("shake_sensitivity", "Medium");
            mShakeDetector.setSensitivity(sensitivity);
            
            mSensorManager.registerListener(mShakeDetector, mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_GAME);
        } else if (mSensorManager != null) {
            mSensorManager.unregisterListener(mShakeDetector);
        }

        if (audioEnabled) {
            if (!isAudioMonitoring && !isSpeechChecking) startSilentAudioMonitoring();
        } else {
            stopAudioMonitoring();
        }
    }

    private void stopAudioMonitoring() {
        isAudioMonitoring = false;
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop();
            } catch (Exception ignored) {}
            audioRecord.release();
            audioRecord = null;
        }
    }

    private void initSpeechRecognizer() {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (speechRecognizer != null) speechRecognizer.destroy();
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
                speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
                speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                speechRecognizer.setRecognitionListener(new RecognitionListener() {
                    @Override public void onReadyForSpeech(Bundle params) { Log.d(TAG, "Mic Listening..."); }
                    @Override public void onResults(Bundle results) {
                        processVoiceResults(results);
                        finalizeSpeechCheck();
                    }
                    @Override public void onError(int error) { finalizeSpeechCheck(); }
                    @Override public void onBeginningOfSpeech() {}
                    @Override public void onRmsChanged(float rmsdB) {}
                    @Override public void onBufferReceived(byte[] buffer) {}
                    @Override public void onEndOfSpeech() {}
                    @Override public void onPartialResults(Bundle pr) { processVoiceResults(pr); }
                    @Override public void onEvent(int et, Bundle p) {}
                });
            } catch (Exception e) { Log.e(TAG, "Speech Init Error"); }
        });
    }

    private void finalizeSpeechCheck() {
        isSpeechChecking = false;
        restoreVolume();
        if (audioEnabled && !isEmergencyActive) {
            new Handler(Looper.getMainLooper()).postDelayed(this::startSilentAudioMonitoring, 800);
        }
    }

    private void processVoiceResults(Bundle results) {
        SharedPreferences prefs = getSharedPreferences("SafeStepPrefs", MODE_PRIVATE);
        String keyword = prefs.getString("audio_keyword", "help").toLowerCase();
        
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null) {
            for (String phrase : matches) {
                if (phrase.toLowerCase().contains(keyword)) {
                    broadcastSensorTrigger("audio");
                    handleEmergency("Voice Command Detected: " + keyword);
                    return;
                }
            }
        }
    }

    private void startSilentAudioMonitoring() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        if (isSpeechChecking || isEmergencyActive || !audioEnabled) return;
        try {
            int minBufSize = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, 44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minBufSize, 4096));

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) return;

            isAudioMonitoring = true;
            audioRecord.startRecording();

            audioThread = new Thread(() -> {
                short[] buffer = new short[2048];
                long startTime = System.currentTimeMillis();
                while (isAudioMonitoring) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (System.currentTimeMillis() - startTime < 500) continue;

                    if (read > 0) {
                        double sum = 0;
                        for (int i = 0; i < read; i++) sum += (double) buffer[i] * buffer[i];
                        double rms = Math.sqrt(sum / read);
                        double db = (rms > 0) ? 20 * Math.log10(rms) : 0;

                        if (db > SOUND_THRESHOLD_DB) {
                            isAudioMonitoring = false;
                            new Handler(Looper.getMainLooper()).post(this::startKeywordCheck);
                            break;
                        }
                    }
                }
            });
            audioThread.start();
        } catch (Exception e) { Log.e(TAG, "Mic Error"); }
    }

    private void startKeywordCheck() {
        if (isEmergencyActive || isSpeechChecking) return;
        isSpeechChecking = true;
        stopAudioMonitoring();

        vibrateSmall();
        muteVolume();

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (speechRecognizer != null) {
                    speechRecognizer.startListening(speechIntent);
                } else {
                    initSpeechRecognizer();
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (speechRecognizer != null) speechRecognizer.startListening(speechIntent);
                    }, 200);
                }
            } catch (Exception e) { finalizeSpeechCheck(); }
        }, 600);
    }

    private void muteVolume() {
        if (audioManager == null || isMuted) return;
        try {
            for (int i = 0; i < STREAMS_TO_MUTE.length; i++) {
                originalVolumes[i] = audioManager.getStreamVolume(STREAMS_TO_MUTE[i]);
                audioManager.setStreamVolume(STREAMS_TO_MUTE[i], 0, 0);
            }
            isMuted = true;
        } catch (Exception ignored) {}
    }

    private void restoreVolume() {
        if (audioManager == null || !isMuted) return;
        try {
            for (int i = 0; i < STREAMS_TO_MUTE.length; i++) {
                audioManager.setStreamVolume(STREAMS_TO_MUTE[i], originalVolumes[i], 0);
            }
            isMuted = false;
        } catch (Exception ignored) {}
    }

    private void resumeMonitoring() {
        if (audioEnabled && !isAudioMonitoring && !isSpeechChecking && !isEmergencyActive) startSilentAudioMonitoring();
    }

    public synchronized void handleEmergency(String source) {
        if (isEmergencyActive) return;
        isEmergencyActive = true;

        triggerEmergencyVibration();

        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(getApplicationContext(), "🚨 EMERGENCY TRIGGERED!\n" + source, Toast.LENGTH_LONG).show()
        );

        sendSMSWithLocation(source);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            isEmergencyActive = false;
            if (audioEnabled) resumeMonitoring();
        }, 15000);
    }

    private void vibrateSmall() {
        Vibrator v = getVibrator();
        if (v != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(100);
            }
        }
    }

    private void triggerEmergencyVibration() {
        Vibrator v = getVibrator();
        if (v != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                long[] pattern = {0, 800, 200, 800, 200, 800};
                v.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                v.vibrate(2000);
            }
        }
    }

    private Vibrator getVibrator() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return vm.getDefaultVibrator();
        }
        return (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
    }

    @SuppressLint("MissingPermission")
    private void sendSMSWithLocation(String source) {
        // Try last known location first for speed
        fusedLocationClient.getLastLocation().addOnSuccessListener(lastLoc -> {
            if (lastLoc != null) {
                dispatchSMS(lastLoc);
            } else {
                // Fallback to current location request if last location is unavailable
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                        .addOnSuccessListener(loc -> dispatchSMS(loc))
                        .addOnFailureListener(e -> dispatchSMS(null));
            }
        });
    }

    private void dispatchSMS(@Nullable Location loc) {
        SharedPreferences prefs = getSharedPreferences("SafeStepPrefs", MODE_PRIVATE);
        ArrayList<String> nums = new ArrayList<>();
        for (int i=1; i<=3; i++) {
            String n = prefs.getString("contact_" + i, "").trim();
            if (!n.isEmpty()) nums.add(n);
        }
        
        String customMsg = prefs.getString("sos_message", "Emergency! I need help. My location: ");
        String locationUrl = (loc != null) ? "https://maps.google.com/?q=" + loc.getLatitude() + "," + loc.getLongitude() : "[Location Unknown]";
        String msg = customMsg + " " + locationUrl;

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            try {
                SmsManager sms = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ? getSystemService(SmsManager.class) : SmsManager.getDefault();
                for (String num : nums) {
                    ArrayList<String> parts = sms.divideMessage(msg);
                    sms.sendMultipartTextMessage(num, null, parts, null, null);
                }
            } catch (Exception e) { Log.e(TAG, "SMS Fail"); }
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "Monitoring", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(c);
        }
    }

    @Override
    public void onDestroy() {
        broadcastServiceState(false);
        stopAudioMonitoring();
        restoreVolume();
        if (speechRecognizer != null) speechRecognizer.destroy();
        if (mSensorManager != null) mSensorManager.unregisterListener(mShakeDetector);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}