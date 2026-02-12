package app.botdrop.ops;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.termux.R;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import app.botdrop.BotDropService;
import com.termux.shared.logger.Logger;

public class OpsChatActivity extends Activity {
    private static final String LOG_TAG = "OpsChatActivity";

    private static final String PREFS = "ops_chat";
    private static final String KEY_TRANSCRIPT = "transcript";
    private static final int MAX_TRANSCRIPT_CHARS = 24000;

    private TextView mChatText;
    private EditText mInput;
    private Button mSendButton;
    private Button mRunDoctorButton;

    private boolean mBound = false;
    private BotDropService mService;
    private OpsOrchestrator mOrchestrator;
    private RuntimeProbeCollector mProbeCollector;
    private OpenClawRuleSourceSyncManager mRuleSyncManager;
    private OpsPiAgentEngine mAssistantEngine;
    private DoctorReport mLastReport;
    private PiAgentInstaller mPiAgentInstaller;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BotDropService.LocalBinder binder = (BotDropService.LocalBinder) service;
            mService = binder.getService();
            mBound = true;
            mRuleSyncManager = new OpenClawRuleSourceSyncManager(getApplicationContext());
            buildOrchestrator();
            mProbeCollector = new BotDropRuntimeProbeCollector(mService);
            mPiAgentInstaller = new PiAgentInstaller(mService);
            setBusy(true);
            append("system", "Connected. Preparing assistant runtime...");
            mPiAgentInstaller.ensureInstalled((success, message) -> runOnUiThread(() -> {
                if (!mBound) return;
                if (!success) {
                    append("system", "Failed to prepare assistant runtime: " + message);
                    append("system", "Tap Send to retry after network stabilizes.");
                    setBusy(false);
                    return;
                }
                mAssistantEngine = new OpsPiAgentEngine(new PiAgentBridge(mService, new OpsCredentialResolver()));
                append("system", "Assistant runtime ready.");
                setBusy(false);
                append("system", "Tap Diagnose to run health checks.");
            }));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBound = false;
            mService = null;
            mOrchestrator = null;
            mProbeCollector = null;
            mAssistantEngine = null;
            mPiAgentInstaller = null;
            append("system", "Service disconnected.");
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_botdrop_ops_chat);

        mChatText = findViewById(R.id.ops_chat_text);
        mInput = findViewById(R.id.ops_chat_input);
        mSendButton = findViewById(R.id.ops_chat_btn_send);
        mRunDoctorButton = findViewById(R.id.ops_chat_btn_diagnose);
        Button backButton = findViewById(R.id.ops_chat_btn_back);

        mSendButton.setOnClickListener(v -> sendMessage());
        mRunDoctorButton.setOnClickListener(v -> runDoctorNow());
        backButton.setOnClickListener(v -> finish());

        loadTranscript();
        append("system", "Connecting...");
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, BotDropService.class), mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        persistTranscript();
        if (mBound) {
            try {
                unbindService(mConnection);
            } catch (IllegalArgumentException ignored) {
            }
            mBound = false;
        }
    }

    private void sendMessage() {
        String msg = mInput.getText().toString().trim();
        if (msg.isEmpty() || !mBound || mOrchestrator == null) return;
        mInput.setText("");
        append("you", msg);
        setBusy(true);

        new Thread(() -> {
            try {
                ensureAssistantEngine();
                if (mAssistantEngine == null) {
                    runOnUiThread(() -> {
                        append("system", "Assistant runtime is unavailable. Please try again.");
                        setBusy(false);
                    });
                    return;
                }
                if (mLastReport == null) {
                    mLastReport = mOrchestrator.runDoctor(null);
                }

                OpsPiAgentEngine.AssistantReply reply = mAssistantEngine.reply(msg, mLastReport);
                String toolResult = executeTool(reply.tool);

                runOnUiThread(() -> {
                    append("assistant", reply.text);
                    if (toolResult != null && !toolResult.trim().isEmpty()) {
                        append("tool", toolResult);
                    }
                    setBusy(false);
                });
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "sendMessage failed: " + e.getMessage());
                runOnUiThread(() -> {
                    append("system", "Assistant failed: " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : (": " + e.getMessage())));
                    setBusy(false);
                });
            }
        }).start();
    }

    private String executeTool(String tool) {
        if (tool == null || "none".equals(tool)) return "";
        switch (tool) {
            case "doctor.run":
                DoctorReport r = runDoctorBlocking();
                return r == null ? "Doctor failed." : "Doctor found " + r.issues.size() + " issue(s).";
            case "fix.preview":
                if (mLastReport == null) mLastReport = mOrchestrator.runDoctor(null);
                SafeExecutor.PreviewResult preview = mOrchestrator.previewFixes(mLastReport.collectSuggestedFixes());
                return preview.message;
            case "fix.apply":
                if (mLastReport == null) mLastReport = mOrchestrator.runDoctor(null);
                SafeExecutor.ExecutionResult apply = mOrchestrator.applyFixes(mLastReport.collectSuggestedFixes());
                if (!apply.success) return "Fix apply failed: " + apply.message;
                String restartMsg = restartGatewayBlocking();
                mLastReport = runDoctorBlocking();
                return "Fix applied. " + restartMsg;
            case "gateway.restart":
                return restartGatewayBlocking();
            default:
                return "Tool not allowed: " + tool;
        }
    }

    private void runDoctorNow() {
        if (!mBound || mOrchestrator == null) return;
        setBusy(true);
        new Thread(() -> {
            DoctorReport r = runDoctorBlocking();
            runOnUiThread(() -> {
                if (r == null) {
                    append("system", "Diagnose failed.");
                } else {
                    append("doctor", "Diagnose complete: " + r.issues.size() + " issue(s).");
                }
                setBusy(false);
            });
        }).start();
    }

    private DoctorReport runDoctorBlocking() {
        if (mProbeCollector == null) return mOrchestrator.runDoctor(null);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<RuntimeProbe> ref = new AtomicReference<>();
        mProbeCollector.collect(probe -> {
            ref.set(probe);
            latch.countDown();
        });
        try {
            latch.await(4, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        RuntimeProbe probe = ref.get();
        mLastReport = mOrchestrator.runDoctor(probe);
        return mLastReport;
    }

    private String restartGatewayBlocking() {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> msg = new AtomicReference<>("Restart timeout");
        mOrchestrator.restartGateway((success, message) -> {
            msg.set(success ? "Gateway restarted." : "Gateway restart failed.");
            latch.countDown();
        });
        try {
            latch.await(6, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return msg.get();
    }

    private void append(String role, String text) {
        String old = mChatText.getText() == null ? "" : mChatText.getText().toString();
        String line = "[" + role + "] " + text + "\n\n";
        String merged = old + line;
        if (merged.length() > MAX_TRANSCRIPT_CHARS) {
            merged = merged.substring(merged.length() - MAX_TRANSCRIPT_CHARS);
        }
        mChatText.setText(merged);
        persistTranscript();
    }

    private void setBusy(boolean busy) {
        mSendButton.setEnabled(!busy);
        mRunDoctorButton.setEnabled(!busy);
    }

    private void ensureAssistantEngine() {
        if (mAssistantEngine != null) return;
        if (mPiAgentInstaller == null) return;
        CountDownLatch latch = new CountDownLatch(1);
        mPiAgentInstaller.ensureInstalled((success, message) -> {
            if (success && mService != null) {
                mAssistantEngine = new OpsPiAgentEngine(new PiAgentBridge(mService, new OpsCredentialResolver()));
            }
            latch.countDown();
        });
        try {
            latch.await(75, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    private void buildOrchestrator() {
        ConfigRepository repository = new OpenClawConfigRepository();
        SafeExecutor safeExecutor = new SafeExecutor(repository, new ConfigBackupStore());
        GatewayController gatewayController = new BotDropGatewayController(mService);
        String openclawVersion = BotDropService.getOpenclawVersion();
        RuleSource source = mRuleSyncManager.resolveSource(openclawVersion);

        mOrchestrator = new OpsOrchestrator(
            repository,
            new DoctorEngine(Arrays.asList(
                new OpenClawAgentRuleProvider(source, openclawVersion),
                new BotDropInvariantRuleProvider(openclawVersion)
            )),
            safeExecutor,
            gatewayController
        );
    }

    private void loadTranscript() {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String transcript = prefs.getString(KEY_TRANSCRIPT, "");
        if (transcript != null && !transcript.isEmpty()) {
            mChatText.setText(transcript);
        }
    }

    private void persistTranscript() {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String text = mChatText.getText() == null ? "" : mChatText.getText().toString();
        prefs.edit().putString(KEY_TRANSCRIPT, text).apply();
    }
}
