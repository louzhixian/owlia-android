package app.botdrop.ops;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.termux.R;

import java.util.List;

import app.botdrop.BotDropService;

/**
 * Minimal isolated UI for Ops/Doctor workflows.
 */
public class OpsActivity extends Activity {

    private TextView mStatusText;
    private TextView mReportText;
    private Button mDiagnoseButton;
    private Button mApplyFixesButton;
    private Button mRestartButton;

    private BotDropService mService;
    private boolean mBound = false;
    private OpsOrchestrator mOrchestrator;
    private RuntimeProbeCollector mProbeCollector;
    private DoctorReport mLastReport;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BotDropService.LocalBinder binder = (BotDropService.LocalBinder) service;
            mService = binder.getService();
            mBound = true;

            ConfigRepository repository = new OpenClawConfigRepository();
            SafeExecutor safeExecutor = new SafeExecutor(repository, new ConfigBackupStore());
            GatewayController gatewayController = new BotDropGatewayController(mService);
            mOrchestrator = new OpsOrchestrator(
                repository,
                new DoctorEngine(),
                safeExecutor,
                gatewayController
            );
            mProbeCollector = new BotDropRuntimeProbeCollector(mService);
            setBusy(false);
            runDiagnosis();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBound = false;
            mService = null;
            mProbeCollector = null;
            mOrchestrator = null;
            setBusy(true);
            mStatusText.setText("Service disconnected");
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_botdrop_ops);

        mStatusText = findViewById(R.id.ops_status_text);
        mReportText = findViewById(R.id.ops_report_text);
        mDiagnoseButton = findViewById(R.id.ops_btn_diagnose);
        mApplyFixesButton = findViewById(R.id.ops_btn_apply_fixes);
        mRestartButton = findViewById(R.id.ops_btn_restart_gateway);
        Button backButton = findViewById(R.id.ops_btn_back);

        mDiagnoseButton.setOnClickListener(v -> runDiagnosis());
        mApplyFixesButton.setOnClickListener(v -> applySuggestedFixes());
        mRestartButton.setOnClickListener(v -> restartGateway());
        backButton.setOnClickListener(v -> finish());

        mStatusText.setText("Connecting to BotDrop service...");
        mReportText.setText("Doctor report will appear here.");
        setBusy(true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, BotDropService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mBound) {
            try {
                unbindService(mConnection);
            } catch (IllegalArgumentException ignored) {
            }
            mBound = false;
        }
    }

    private void runDiagnosis() {
        if (!mBound || mProbeCollector == null || mOrchestrator == null) return;
        setBusy(true);
        mStatusText.setText("Running diagnostics...");
        mProbeCollector.collect(probe -> {
            mLastReport = mOrchestrator.runDoctor(probe);
            renderReport(mLastReport);
            setBusy(false);
        });
    }

    private void applySuggestedFixes() {
        if (mOrchestrator == null || mLastReport == null) return;
        List<FixAction> fixes = mLastReport.collectSuggestedFixes();
        if (fixes.isEmpty()) {
            Toast.makeText(this, "No suggested fixes", Toast.LENGTH_SHORT).show();
            return;
        }

        setBusy(true);
        mStatusText.setText("Applying fixes...");
        SafeExecutor.ExecutionResult result = mOrchestrator.applyFixes(fixes);
        if (!result.success) {
            setBusy(false);
            mStatusText.setText("Fix failed");
            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show();
            return;
        }

        String msg = "Fix applied (" + result.appliedActions.size() + " actions)";
        if (result.backupPath != null) msg += "\nBackup: " + result.backupPath;
        mStatusText.setText(msg);

        mOrchestrator.restartGateway((success, message) -> {
            if (!success) {
                setBusy(false);
                Toast.makeText(this, "Restart failed", Toast.LENGTH_LONG).show();
                return;
            }
            runDiagnosis();
        });
    }

    private void restartGateway() {
        if (mOrchestrator == null) return;
        setBusy(true);
        mStatusText.setText("Restarting gateway...");
        mOrchestrator.restartGateway((success, message) -> {
            if (!success) {
                setBusy(false);
                mStatusText.setText("Restart failed");
                Toast.makeText(this, "Restart failed", Toast.LENGTH_LONG).show();
                return;
            }
            runDiagnosis();
        });
    }

    private void renderReport(DoctorReport report) {
        if (report == null || report.issues.isEmpty()) {
            mStatusText.setText("No issues found");
            mReportText.setText("Gateway/config baseline looks healthy.");
            mApplyFixesButton.setEnabled(false);
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (DoctorIssue issue : report.issues) {
            sb.append("[").append(issue.severity).append("] ").append(issue.title).append("\n");
            if (issue.detail != null && !issue.detail.trim().isEmpty()) {
                sb.append(issue.detail).append("\n");
            }
            if (issue.suggestedFix != null) {
                sb.append("Suggested fix: ").append(issue.suggestedFix).append("\n");
            }
            sb.append("\n");
        }

        mStatusText.setText("Found " + report.issues.size() + " issue(s)");
        mReportText.setText(sb.toString().trim());
        mApplyFixesButton.setEnabled(!report.collectSuggestedFixes().isEmpty());
    }

    private void setBusy(boolean busy) {
        mDiagnoseButton.setEnabled(!busy);
        mApplyFixesButton.setEnabled(!busy && mLastReport != null && !mLastReport.collectSuggestedFixes().isEmpty());
        mRestartButton.setEnabled(!busy);
        findViewById(R.id.ops_loading).setVisibility(busy ? View.VISIBLE : View.GONE);
    }
}
