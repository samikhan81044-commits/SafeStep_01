package com.example.safestep2;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.safestep2.databinding.ActivitySettingsBinding;

public class SettingsActivity extends AppCompatActivity {

    private ActivitySettingsBinding binding;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefs = getSharedPreferences("SafeStepPrefs", MODE_PRIVATE);

        loadSettings();
        checkPermissions();
        setupClickListeners();
    }

    private void loadSettings() {
        String sosMsg = prefs.getString("sos_message", getString(R.string.default_sos_message));
        binding.etSosMessage.setText(sosMsg);

        String sensitivity = prefs.getString("shake_sensitivity", "Medium");
        if ("Low".equals(sensitivity)) binding.rbLow.setChecked(true);
        else if ("High".equals(sensitivity)) binding.rbHigh.setChecked(true);
        else binding.rbMedium.setChecked(true);

        String keyword = prefs.getString("audio_keyword", getString(R.string.default_audio_keyword));
        binding.etAudioKeyword.setText(keyword);
    }

    private void checkPermissions() {
        updatePermissionIcon(binding.ivPermLocation, Manifest.permission.ACCESS_FINE_LOCATION);
        updatePermissionIcon(binding.ivPermMic, Manifest.permission.RECORD_AUDIO);
        updatePermissionIcon(binding.ivPermCamera, Manifest.permission.CAMERA);
        updatePermissionIcon(binding.ivPermSms, Manifest.permission.SEND_SMS);
    }

    private void updatePermissionIcon(android.widget.ImageView imageView, String permission) {
        boolean granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
        imageView.setColorFilter(ContextCompat.getColor(this, granted ? R.color.neon_green : R.color.status_red_neon));
    }

    private void setupClickListeners() {
        binding.btnOpenSettings.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            Uri uri = Uri.fromParts("package", getPackageName(), null);
            intent.setData(uri);
            startActivity(intent);
        });

        binding.btnPrivacyPolicy.setOnClickListener(v -> {
            startActivity(new Intent(this, PrivacyPolicyActivity.class));
        });

        binding.btnSave.setOnClickListener(v -> {
            saveSettings();
        });
    }

    private void saveSettings() {
        String sosMsg = binding.etSosMessage.getText().toString().trim();
        String keyword = binding.etAudioKeyword.getText().toString().trim();

        if (sosMsg.isEmpty()) sosMsg = getString(R.string.default_sos_message);
        if (keyword.isEmpty()) keyword = getString(R.string.default_audio_keyword);

        String sensitivity = "Medium";
        int checkedId = binding.rgShakeSensitivity.getCheckedRadioButtonId();
        if (checkedId == R.id.rbLow) sensitivity = "Low";
        else if (checkedId == R.id.rbHigh) sensitivity = "High";

        prefs.edit()
                .putString("sos_message", sosMsg)
                .putString("shake_sensitivity", sensitivity)
                .putString("audio_keyword", keyword)
                .apply();

        Toast.makeText(this, "Settings Saved Successfully!", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkPermissions();
    }
}