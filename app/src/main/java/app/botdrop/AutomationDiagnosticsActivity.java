package app.botdrop;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;

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

    private TextView mStatusText;
    private TextView mHistoryText;
    private TextView mTreeText;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_botdrop_automation_diagnostics);

        mStatusText = findViewById(R.id.automation_diag_status);
        mHistoryText = findViewById(R.id.automation_diag_history);
        mTreeText = findViewById(R.id.automation_diag_tree);

        Button openSettings = findViewById(R.id.btn_diag_open_accessibility_settings);
        Button refresh = findViewById(R.id.btn_diag_refresh);
        Button dumpTree = findViewById(R.id.btn_diag_dump_tree);

        openSettings.setOnClickListener(v -> openAccessibilitySettings());
        refresh.setOnClickListener(v -> refreshStatus());
        dumpTree.setOnClickListener(v -> dumpTree());

        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
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
            return;
        }
        try {
            mTreeText.setText(svc.dumpActiveWindowTree(800).toString(2));
        } catch (Exception e) {
            mTreeText.setText("Failed to dump tree: " + e.getMessage());
        }
    }

    private void openAccessibilitySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception ignored) {}
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

