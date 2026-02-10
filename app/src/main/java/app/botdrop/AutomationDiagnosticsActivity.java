package app.botdrop;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.termux.R;
import com.termux.shared.logger.Logger;

import app.botdrop.automation.AutomationControllerService;
import app.botdrop.automation.BotDropAccessibilityService;
import app.botdrop.automation.UiAutomationSocketServer;

/**
 * Simple diagnostics screen for debugging UI automation.
 */
public class AutomationDiagnosticsActivity extends Activity {

    private static final String LOG_TAG = "AutomationDiagnostics";
    private static final String NOTIFICATION_CHANNEL_ID = "botdrop_automation_diag";
    private static final int NOTIFICATION_ID_DUMP = 2101;

    private TextView mStatusText;
    private TextView mHistoryText;
    private TextView mTreeText;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mPendingDump;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_botdrop_automation_diagnostics);

        mStatusText = findViewById(R.id.automation_diag_status);
        mHistoryText = findViewById(R.id.automation_diag_history);
        mTreeText = findViewById(R.id.automation_diag_tree);

        Button openSettings = findViewById(R.id.btn_diag_open_accessibility_settings);
        Button refresh = findViewById(R.id.btn_diag_refresh);
        Button dumpTreeDelayed = findViewById(R.id.btn_diag_dump_tree_delayed);

        openSettings.setOnClickListener(v -> openAccessibilitySettings());
        refresh.setOnClickListener(v -> refreshStatus());
        dumpTreeDelayed.setOnClickListener(v -> dumpTreeDelayed(3000));

        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelPendingDump();
    }

    private void refreshStatus() {
        try {
            boolean enabled = isBotDropAccessibilityEnabled();
            java.io.File sock = new java.io.File(AutomationControllerService.SOCKET_PATH);

            StringBuilder sb = new StringBuilder();
            sb.append("Accessibility: ").append(enabled ? "ON" : "OFF").append("\n");
            sb.append("Socket file: ").append(sock.exists() ? "present" : "missing").append("\n");
            sb.append("Socket path: ").append(AutomationControllerService.SOCKET_PATH).append("\n");
            sb.append("SDK: ").append(Build.VERSION.SDK_INT).append("\n");

            mStatusText.setText(sb.toString());

            String[] history = UiAutomationSocketServer.getRecentHistory();
            StringBuilder h = new StringBuilder();
            for (String e : history) {
                if (e == null || e.trim().isEmpty()) continue;
                h.append("-----\n").append(e).append("\n");
            }
            mHistoryText.setText(h.length() > 0 ? h.toString() : "(no history yet)");
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "refreshStatus failed: " + e.getMessage());
        }
    }

    private void dumpTree() {
        BotDropAccessibilityService svc = BotDropAccessibilityService.getInstance();
        if (svc == null) {
            mTreeText.setText("Accessibility service not connected (enable it in Settings).");
            Toast.makeText(this, "Accessibility is OFF", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            mTreeText.setText(svc.dumpActiveWindowTree(800).toString(2));
            notifyDumpReady("Dump complete");
        } catch (Exception e) {
            mTreeText.setText("Failed to dump tree: " + e.getMessage());
            notifyDumpReady("Dump failed: " + e.getMessage());
        }
    }

    private void dumpTreeDelayed(long delayMs) {
        cancelPendingDump();

        mTreeText.setText("Dump scheduled in " + (delayMs / 1000) + "s. Switch to the target app now...");
        Toast.makeText(this, "Dump scheduled. Switch to target app now.", Toast.LENGTH_SHORT).show();

        mPendingDump = this::dumpTree;
        mHandler.postDelayed(mPendingDump, Math.max(0, delayMs));
    }

    private void cancelPendingDump() {
        if (mPendingDump != null) {
            mHandler.removeCallbacks(mPendingDump);
            mPendingDump = null;
        }
    }

    private void openAccessibilitySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception ignored) {}
    }

    private void notifyDumpReady(String message) {
        try {
            createNotificationChannelIfNeeded();

            Intent intent = new Intent(this, AutomationDiagnosticsActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)
            );

            NotificationCompat.Builder b = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_service_notification)
                .setContentTitle("Automation Dump Ready")
                .setContentText(message != null ? message : "Dump complete")
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID_DUMP, b.build());
        } catch (Exception e) {
            Logger.logDebug(LOG_TAG, "notifyDumpReady failed: " + e.getMessage());
        }
    }

    private void createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Automation Diagnostics",
            NotificationManager.IMPORTANCE_DEFAULT
        );
        nm.createNotificationChannel(channel);
    }

    private boolean isBotDropAccessibilityEnabled() {
        try {
            String enabledServices = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabledServices == null || enabledServices.isEmpty()) return false;
            String target = getPackageName() + "/app.botdrop.automation.BotDropAccessibilityService";
            for (String s : enabledServices.split(":")) {
                if (target.equalsIgnoreCase(s)) return true;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }
}
