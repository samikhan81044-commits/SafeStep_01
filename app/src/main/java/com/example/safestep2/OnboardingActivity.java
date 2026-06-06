package com.example.safestep2;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.ExperimentalGetImage;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayoutMediator;

import java.util.ArrayList;
import java.util.List;

/**
 * Professional Onboarding flow for SafeStep.
 * Features: Button-driven navigation, real-time permission tracking, and GPS validation.
 */
@ExperimentalGetImage
public class OnboardingActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private OnboardingAdapter adapter;
    private SharedPreferences prefs;

    // UI Elements from the Activity layout
    private Button btnNext;
    private Button btnGrantPermissions;
    private Button btnContinue;
    private View permissionsPageView;

    private final String[] REQUIRED_PERMISSIONS = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH ?
            new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CAMERA,
                    Manifest.permission.BODY_SENSORS
            } :
            new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CAMERA
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        prefs = getSharedPreferences("SafeStepPrefs", MODE_PRIVATE);
        viewPager = findViewById(R.id.viewPager);
        btnNext = findViewById(R.id.btnNext);
        btnGrantPermissions = findViewById(R.id.btnGrantPermissions);
        btnContinue = findViewById(R.id.btnContinue);

        // Requirement: Remove swipe navigation completely. Users must use the buttons.
        viewPager.setUserInputEnabled(false);

        adapter = new OnboardingAdapter();
        viewPager.setAdapter(adapter);

        // Indicator (Dots) - TabLayout is linked to ViewPager2
        new TabLayoutMediator(findViewById(R.id.tabLayout), viewPager, (tab, position) -> {}).attach();

        // Main action button for standard pages (0, 1, 3)
        btnNext.setOnClickListener(v -> {
            int current = viewPager.getCurrentItem();
            if (current < adapter.getItemCount() - 1) {
                viewPager.setCurrentItem(current + 1);
            } else {
                // Final Screen - Start Protection
                if (validateAllRequirements()) {
                    finishOnboarding();
                } else {
                    viewPager.setCurrentItem(2);
                    Toast.makeText(this, "Please ensure all permissions and GPS are enabled.", Toast.LENGTH_LONG).show();
                }
            }
        });

        // Specialized Permissions Step buttons (Page 2)
        btnGrantPermissions.setOnClickListener(v -> requestAppPermissions());

        btnContinue.setOnClickListener(v -> {
            // Requirement: continue button clickable, check all boxes message if not ticked
            if (areAllCheckBoxesChecked()) {
                viewPager.setCurrentItem(3);
            } else {
                Toast.makeText(this, "check all boxes", Toast.LENGTH_SHORT).show();
            }
        });

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateGlobalUI(position);
            }
        });

        updateGlobalUI(0);
    }

    private void updateGlobalUI(int position) {
        if (position == 2) {
            // Permissions Page - Standard 'Next' is hidden
            btnNext.setVisibility(View.GONE);
            updatePermissionsButtonLogic();
        } else {
            // Other pages - Use standard Next button
            btnNext.setVisibility(View.VISIBLE);
            btnGrantPermissions.setVisibility(View.GONE);
            btnContinue.setVisibility(View.GONE);

            switch (position) {
                case 0: btnNext.setText(getString(R.string.btn_get_started)); break;
                case 1: btnNext.setText(getString(R.string.btn_continue)); break;
                case 3: btnNext.setText(getString(R.string.btn_start_protection)); break;
            }
        }
    }

    private void updatePermissionsButtonLogic() {
        boolean allPermissionsGranted = hasAllPermissions();

        // Requirement: Grant permission button first, then continue button after all permissions given
        if (allPermissionsGranted) {
            btnGrantPermissions.setVisibility(View.GONE);
            btnContinue.setVisibility(View.VISIBLE);
        } else {
            btnGrantPermissions.setVisibility(View.VISIBLE);
            btnContinue.setVisibility(View.GONE);
        }
    }

    private boolean hasAllPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private boolean isLocationEnabled() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        return lm != null && (lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
    }

    private boolean validateAllRequirements() {
        return hasAllPermissions() && isLocationEnabled() && areAllCheckBoxesChecked();
    }

    private void requestAppPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 100);
    }

    private void showLocationSettingsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("GPS Required")
                .setMessage("SafeStep requires Location Services (GPS) to send your location during emergencies. Please turn it ON in Settings.")
                .setPositiveButton("Settings", (dialog, which) -> {
                    startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void finishOnboarding() {
        prefs.edit().putBoolean("first_launch", false).apply();
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private boolean areAllCheckBoxesChecked() {
        if (permissionsPageView == null) return false;
        CheckBox cbLoc = permissionsPageView.findViewById(R.id.cbLocation);
        CheckBox cbSms = permissionsPageView.findViewById(R.id.cbSms);
        CheckBox cbMic = permissionsPageView.findViewById(R.id.cbMic);
        CheckBox cbCam = permissionsPageView.findViewById(R.id.cbCamera);
        CheckBox cbSen = permissionsPageView.findViewById(R.id.cbSensors);

        boolean allChecked = (cbLoc != null && cbLoc.isChecked()) &&
                (cbSms != null && cbSms.isChecked()) &&
                (cbMic != null && cbMic.isChecked()) &&
                (cbCam != null && cbCam.isChecked());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH && cbSen != null) {
            allChecked = allChecked && cbSen.isChecked();
        }
        return allChecked;
    }

    private class OnboardingAdapter extends RecyclerView.Adapter<OnboardingAdapter.ViewHolder> {
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int layoutId;
            switch (viewType) {
                case 0: layoutId = R.layout.fragment_onboarding_welcome; break;
                case 1: layoutId = R.layout.fragment_onboarding_features; break;
                case 2: layoutId = R.layout.fragment_onboarding_permissions; break;
                default: layoutId = R.layout.fragment_onboarding_complete; break;
            }
            View view = LayoutInflater.from(parent.getContext()).inflate(layoutId, parent, false);
            return new ViewHolder(view, viewType);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.bind();
        }

        @Override
        public int getItemViewType(int position) { return position; }

        @Override
        public int getItemCount() { return 4; }

        class ViewHolder extends RecyclerView.ViewHolder {
            int type;
            ViewHolder(View itemView, int type) {
                super(itemView);
                this.type = type;
            }

            void bind() {
                if (type == 2) {
                    startPermissionTracking(itemView);
                }
            }
        }
    }

    /**
     * Requirement: Dynamic Checkboxes and button states that update in real-time.
     */
    private void startPermissionTracking(View view) {
        this.permissionsPageView = view;

        CheckBox cbLoc = view.findViewById(R.id.cbLocation);
        CheckBox cbSms = view.findViewById(R.id.cbSms);
        CheckBox cbMic = view.findViewById(R.id.cbMic);
        CheckBox cbCam = view.findViewById(R.id.cbCamera);
        CheckBox cbSen = view.findViewById(R.id.cbSensors);

        if (cbLoc != null) {
            cbLoc.setOnClickListener(v -> {
                // Pehle check karein system level permission mili hui hai ya nahi
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    cbLoc.setChecked(false);
                    Toast.makeText(this, "Grant Location permission first", Toast.LENGTH_SHORT).show();
                } else if (!isLocationEnabled()) {
                    cbLoc.setChecked(false);
                    Toast.makeText(this, "turn on location first", Toast.LENGTH_SHORT).show();
                }
            });
        }

        setupPermissionCheckbox(cbSms, Manifest.permission.SEND_SMS, "SMS");
        setupPermissionCheckbox(cbMic, Manifest.permission.RECORD_AUDIO, "Microphone");
        setupPermissionCheckbox(cbCam, Manifest.permission.CAMERA, "Camera");
        if (cbSen != null) {
            setupPermissionCheckbox(cbSen, Manifest.permission.BODY_SENSORS, "Sensors");
        }

        view.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing()) {
                    if (viewPager.getCurrentItem() == 2) {
                        refreshPermissionsUI(view);
                        updatePermissionsButtonLogic();
                    }
                    view.postDelayed(this, 1000); // Check every second
                }
            }
        }, 100);
    }

    private void setupPermissionCheckbox(CheckBox cb, String permission, String name) {
        if (cb != null) {
            cb.setOnClickListener(v -> {
                // Agar user permission diye bagair manually tick karne ki koshish kare
                if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    cb.setChecked(false);
                    Toast.makeText(this, "Grant " + name + " permission first", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void refreshPermissionsUI(View view) {
        CheckBox cbLoc = view.findViewById(R.id.cbLocation);
        CheckBox cbSms = view.findViewById(R.id.cbSms);
        CheckBox cbMic = view.findViewById(R.id.cbMic);
        CheckBox cbCam = view.findViewById(R.id.cbCamera);
        CheckBox cbSen = view.findViewById(R.id.cbSensors);
        TextView tvWarn = view.findViewById(R.id.tvLocationWarning);

        // Reflect actual Android permission state dynamically
        syncCheckbox(cbLoc, Manifest.permission.ACCESS_FINE_LOCATION, true);
        syncCheckbox(cbSms, Manifest.permission.SEND_SMS, false);
        syncCheckbox(cbMic, Manifest.permission.RECORD_AUDIO, false);
        syncCheckbox(cbCam, Manifest.permission.CAMERA, false);
        if (cbSen != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            syncCheckbox(cbSen, Manifest.permission.BODY_SENSORS, false);
        }

        // Special Location logic: if GPS turns off, uncheck the box if it's not granted
        if (!isLocationEnabled()) {
            if (cbLoc != null && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                cbLoc.setChecked(false);
            }
            if (tvWarn != null) {
                tvWarn.setVisibility(View.VISIBLE);
                tvWarn.setText(R.string.onboarding_location_warning);
            }
        } else if (!hasAllPermissions()) {
            if (tvWarn != null) {
                tvWarn.setVisibility(View.VISIBLE);
                tvWarn.setText("⚠️ Some permissions are still missing.");
            }
        } else {
            if (tvWarn != null) {
                tvWarn.setVisibility(View.GONE);
            }
        }
    }

    private void syncCheckbox(CheckBox cb, String permission, boolean forceUncheck) {
        if (cb == null) return;
        boolean granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;

        // Control checkboxes based on system permission states
        if (granted) {
            cb.setEnabled(true);
        } else {
            if (forceUncheck) {
                cb.setChecked(false);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (viewPager.getCurrentItem() == 2) {
            updatePermissionsButtonLogic();
        }
    }
}