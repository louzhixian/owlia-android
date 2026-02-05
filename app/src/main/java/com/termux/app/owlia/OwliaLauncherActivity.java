package com.termux.app.owlia;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxInstaller;
import com.termux.shared.logger.Logger;

/**
 * Launcher activity that routes to the appropriate screen based on installation state.
 *
 * Routing logic:
 * 1. If bootstrap not extracted -> Wait for TermuxInstaller (show progress)
 * 2. If OpenClaw not installed -> SetupActivity (auto-install)
 * 3. If OpenClaw not configured -> SetupActivity (auth + channel setup)
 * 4. All ready -> DashboardActivity (TODO: implement later, for now go to TermuxActivity)
 */
public class OwliaLauncherActivity extends Activity {

    private static final String LOG_TAG = "OwliaLauncherActivity";
    private TextView mStatusText;
    private Handler mHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_owlia_launcher);

        mStatusText = findViewById(R.id.launcher_status_text);

        // Check installation state after a short delay to show splash
        mHandler.postDelayed(this::checkAndRoute, 500);
    }

    private void checkAndRoute() {
        // Check 1: Bootstrap installed?
        if (!OwliaService.isBootstrapInstalled()) {
            Logger.logInfo(LOG_TAG, "Bootstrap not ready, waiting for TermuxInstaller");
            mStatusText.setText("Setting up environment...");

            // Wait for bootstrap, then re-check
            TermuxInstaller.setupBootstrapIfNeeded(this, this::checkAndRoute);
            return;
        }

        // Check 2: OpenClaw installed?
        if (!OwliaService.isOpenclawInstalled()) {
            Logger.logInfo(LOG_TAG, "OpenClaw not installed, routing to SetupActivity");
            mStatusText.setText("Preparing installation...");

            Intent intent = new Intent(this, SetupActivity.class);
            intent.putExtra(SetupActivity.EXTRA_START_STEP, SetupActivity.STEP_INSTALL);
            startActivity(intent);
            finish();
            return;
        }

        // Check 3: OpenClaw configured?
        if (!OwliaService.isOpenclawConfigured()) {
            Logger.logInfo(LOG_TAG, "OpenClaw not configured, routing to SetupActivity");
            mStatusText.setText("Setup required...");

            Intent intent = new Intent(this, SetupActivity.class);
            intent.putExtra(SetupActivity.EXTRA_START_STEP, SetupActivity.STEP_API_KEY);
            startActivity(intent);
            finish();
            return;
        }

        // All ready - for now, go to TermuxActivity (DashboardActivity will be implemented later)
        Logger.logInfo(LOG_TAG, "All ready, routing to main activity");
        mStatusText.setText("Starting...");

        // TODO: Replace with DashboardActivity when implemented
        Intent intent = new Intent(this, TermuxActivity.class);
        startActivity(intent);
        finish();
    }
}
