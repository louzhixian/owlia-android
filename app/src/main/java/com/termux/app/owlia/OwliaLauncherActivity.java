package com.termux.app.owlia;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;

import com.termux.R;
import com.termux.app.TermuxInstaller;
import com.termux.shared.logger.Logger;

import org.json.JSONObject;

/**
 * Launcher activity with two phases:
 *
 * Phase 1 (Welcome): Guided permission requests — user taps buttons to grant
 * notification permission and battery optimization exemption, with clear explanations.
 *
 * Phase 2 (Loading): Routes to the appropriate screen based on installation state:
 * 1. If bootstrap not extracted -> Wait for TermuxInstaller
 * 2. If OpenClaw not installed -> SetupActivity (auto-install)
 * 3. If OpenClaw not configured -> SetupActivity (auth + channel setup)
 * 4. All ready -> DashboardActivity
 */
public class OwliaLauncherActivity extends Activity {

    private static final String LOG_TAG = "BotDropLauncherActivity";
    private static final int REQUEST_CODE_NOTIFICATION_SETTINGS = 1001;
    private static final int REQUEST_CODE_BATTERY_OPTIMIZATION = 1002;

    // Views
    private View mWelcomeContainer;
    private View mLoadingContainer;
    private TextView mStatusText;
    private Button mNotificationButton;
    private Button mBatteryButton;
    private Button mContinueButton;
    private TextView mNotificationStatus;
    private TextView mBatteryStatus;

    private Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mPermissionsPhaseComplete = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_botdrop_launcher);

        mWelcomeContainer = findViewById(R.id.welcome_container);
        mLoadingContainer = findViewById(R.id.loading_container);
        mStatusText = findViewById(R.id.launcher_status_text);
        mNotificationButton = findViewById(R.id.btn_notification_permission);
        mBatteryButton = findViewById(R.id.btn_battery_permission);
        mContinueButton = findViewById(R.id.btn_continue);
        mNotificationStatus = findViewById(R.id.notification_status);
        mBatteryStatus = findViewById(R.id.battery_status);

        mNotificationButton.setOnClickListener(v -> openNotificationSettings());
        mBatteryButton.setOnClickListener(v -> requestBatteryOptimization());
        mContinueButton.setOnClickListener(v -> {
            mPermissionsPhaseComplete = true;
            showLoadingPhase();
            mHandler.postDelayed(this::checkAndRoute, 300);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mPermissionsPhaseComplete) {
            // Already past the welcome screen
            showLoadingPhase();
            mHandler.postDelayed(this::checkAndRoute, 300);
            return;
        }

        // Check if all permissions are already granted (returning user)
        if (areNotificationsEnabled() && isBatteryOptimizationExempt()) {
            Logger.logInfo(LOG_TAG, "All permissions already granted, skipping welcome");
            mPermissionsPhaseComplete = true;
            showLoadingPhase();
            mHandler.postDelayed(this::checkAndRoute, 300);
            return;
        }

        // Show welcome screen and update permission status
        showWelcomePhase();
        updatePermissionStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
    }

    // --- Phase management ---

    private void showWelcomePhase() {
        mWelcomeContainer.setVisibility(View.VISIBLE);
        mLoadingContainer.setVisibility(View.GONE);
    }

    private void showLoadingPhase() {
        mWelcomeContainer.setVisibility(View.GONE);
        mLoadingContainer.setVisibility(View.VISIBLE);
    }

    // --- Permission checks ---

    private boolean areNotificationsEnabled() {
        return NotificationManagerCompat.from(this).areNotificationsEnabled();
    }

    private boolean isBatteryOptimizationExempt() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        return true; // Pre-Android M: no battery optimization
    }

    // --- Permission requests ---

    /**
     * Open app notification settings page.
     * targetSdk=28 means requestPermissions(POST_NOTIFICATIONS) is a no-op on Android 13+.
     * Opening the settings page works reliably across all Android versions.
     */
    private void openNotificationSettings() {
        Logger.logInfo(LOG_TAG, "Opening notification settings");
        try {
            Intent intent = new Intent();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            } else {
                intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS");
                intent.putExtra("app_package", getPackageName());
                intent.putExtra("app_uid", getApplicationInfo().uid);
            }
            startActivityForResult(intent, REQUEST_CODE_NOTIFICATION_SETTINGS);
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to open notification settings: " + e.getMessage());
        }
    }

    private void requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Logger.logInfo(LOG_TAG, "Requesting battery optimization exemption");
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQUEST_CODE_BATTERY_OPTIMIZATION);
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Failed to request battery optimization: " + e.getMessage());
            }
        }
    }

    // --- Permission results ---

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_NOTIFICATION_SETTINGS) {
            if (areNotificationsEnabled()) {
                Logger.logInfo(LOG_TAG, "Notifications enabled");
            } else {
                Logger.logWarn(LOG_TAG, "Notifications still disabled");
            }
            updatePermissionStatus();
        } else if (requestCode == REQUEST_CODE_BATTERY_OPTIMIZATION) {
            if (isBatteryOptimizationExempt()) {
                Logger.logInfo(LOG_TAG, "Battery optimization exemption granted");
            } else {
                Logger.logWarn(LOG_TAG, "Battery optimization exemption denied");
            }
            updatePermissionStatus();
        }
    }

    // --- UI updates ---

    private void updatePermissionStatus() {
        boolean notifGranted = areNotificationsEnabled();
        boolean batteryExempt = isBatteryOptimizationExempt();

        // Notification status
        if (notifGranted) {
            mNotificationStatus.setText("✓");
            mNotificationStatus.setVisibility(View.VISIBLE);
            mNotificationButton.setEnabled(false);
            mNotificationButton.setText("Enabled");
        } else {
            mNotificationStatus.setVisibility(View.GONE);
            mNotificationButton.setEnabled(true);
            mNotificationButton.setText("Allow");
        }

        // Battery status
        if (batteryExempt) {
            mBatteryStatus.setText("✓");
            mBatteryStatus.setVisibility(View.VISIBLE);
            mBatteryButton.setEnabled(false);
            mBatteryButton.setText("Granted");
        } else {
            mBatteryStatus.setVisibility(View.GONE);
            mBatteryButton.setEnabled(true);
            mBatteryButton.setText("Allow");
        }

        // Enable continue when both handled
        mContinueButton.setEnabled(notifGranted && batteryExempt);
    }

    // --- Routing ---

    private void checkAndRoute() {
        // Check 1: Bootstrap installed?
        if (!OwliaService.isBootstrapInstalled()) {
            Logger.logInfo(LOG_TAG, "Bootstrap not ready, waiting for TermuxInstaller");
            mStatusText.setText("Setting up environment...");

            TermuxInstaller.setupBootstrapIfNeeded(this, this::checkAndRoute);
            return;
        }

        // Check 2: OpenClaw configured (API key)?
        if (!OwliaService.isOpenclawConfigured()) {
            Logger.logInfo(LOG_TAG, "OpenClaw not configured, routing to auth setup");
            mStatusText.setText("Setup required...");

            Intent intent = new Intent(this, SetupActivity.class);
            intent.putExtra(SetupActivity.EXTRA_START_STEP, SetupActivity.STEP_API_KEY);
            startActivity(intent);
            finish();
            return;
        }

        // Check 3: OpenClaw installed?
        if (!OwliaService.isOpenclawInstalled()) {
            Logger.logInfo(LOG_TAG, "OpenClaw not installed, routing to agent selection");
            mStatusText.setText("Preparing installation...");

            Intent intent = new Intent(this, SetupActivity.class);
            intent.putExtra(SetupActivity.EXTRA_START_STEP, SetupActivity.STEP_AGENT_SELECT);
            startActivity(intent);
            finish();
            return;
        }

        // Check 4: Channel configured?
        if (!hasChannelConfigured()) {
            Logger.logInfo(LOG_TAG, "No channel configured, routing to channel setup");
            mStatusText.setText("Channel setup required...");

            Intent intent = new Intent(this, SetupActivity.class);
            intent.putExtra(SetupActivity.EXTRA_START_STEP, SetupActivity.STEP_CHANNEL);
            startActivity(intent);
            finish();
            return;
        }

        // All ready - go to DashboardActivity
        Logger.logInfo(LOG_TAG, "All ready, routing to dashboard");
        mStatusText.setText("Starting...");

        Intent intent = new Intent(this, DashboardActivity.class);
        startActivity(intent);
        finish();
    }

    private boolean hasChannelConfigured() {
        try {
            JSONObject config = OwliaConfig.readConfig();
            if (config.has("channels")) {
                JSONObject channels = config.getJSONObject("channels");
                return channels.has("telegram") || channels.has("discord");
            }
            return false;
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to check channel config: " + e.getMessage());
            return false;
        }
    }
}
