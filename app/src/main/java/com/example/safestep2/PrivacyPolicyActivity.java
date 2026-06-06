package com.example.safestep2;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.example.safestep2.databinding.ActivityPrivacyPolicyBinding;

public class PrivacyPolicyActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityPrivacyPolicyBinding binding = ActivityPrivacyPolicyBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.btnClosePrivacy.setOnClickListener(v -> finish());
    }
}