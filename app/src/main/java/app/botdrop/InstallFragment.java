package app.botdrop;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.termux.R;
import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Step 2 of setup: Welcome + Auto-install OpenClaw
 *
 * This fragment automatically starts installation when loaded.
 * Shows progress with checkmarks for each step.
 * On success, automatically advances to next step.
 * On failure, shows error and retry button.
 */
public class InstallFragment extends Fragment {

    private static final String LOG_TAG = "InstallFragment";
    private static final String MODEL_LIST_COMMAND = "openclaw models list --all --plain";
    private static final String PREFS_NAME = "openclaw_model_cache_v1";
    private static final String CACHE_KEY_PREFIX = "models_by_version_";

    // Step indicators
    private TextView mStep0Icon, mStep0Text;
    private TextView mStep1Icon, mStep1Text;
    private TextView mStep2Icon, mStep2Text;

    private TextView mStatusMessage;
    private View mErrorContainer;
    private TextView mErrorMessage;
    private Button mRetryButton;

    private BotDropService mService;
    private boolean mBound = false;
    private final AtomicBoolean mInstallationStarted = new AtomicBoolean(false);

    // Track delayed callbacks to prevent memory leaks
    private Runnable mNavigationRunnable;

    private interface ModelListPrefetchCallback {
        void onFinished();
    }

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BotDropService.LocalBinder binder = (BotDropService.LocalBinder) service;
            mService = binder.getService();
            mBound = true;
            Logger.logDebug(LOG_TAG, "Service connected");

            // Only auto-start if this fragment is actually visible (resumed).
            // ViewPager2 pre-creates adjacent fragments in STARTED state;
            // only the visible fragment reaches RESUMED.
            if (isResumed()) {
                tryStartInstallation();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
            mBound = false;
            Logger.logDebug(LOG_TAG, "Service disconnected");
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_botdrop_install, container, false);

        // Find all step views
        mStep0Icon = view.findViewById(R.id.install_step_0_icon);
        mStep0Text = view.findViewById(R.id.install_step_0_text);
        mStep1Icon = view.findViewById(R.id.install_step_1_icon);
        mStep1Text = view.findViewById(R.id.install_step_1_text);
        mStep2Icon = view.findViewById(R.id.install_step_2_icon);
        mStep2Text = view.findViewById(R.id.install_step_2_text);

        mStatusMessage = view.findViewById(R.id.install_status_message);
        mErrorContainer = view.findViewById(R.id.install_error_container);
        mErrorMessage = view.findViewById(R.id.install_error_message);
        mRetryButton = view.findViewById(R.id.install_retry_button);

        mRetryButton.setOnClickListener(v -> {
            mErrorContainer.setVisibility(View.GONE);
            resetSteps();
            startInstallation();
        });

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        // Bind to BotDropService
        Intent intent = new Intent(getActivity(), BotDropService.class);
        requireActivity().bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Fragment is now visible — start installation if service is already bound
        if (mBound && mService != null) {
            tryStartInstallation();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mBound) {
            requireActivity().unbindService(mConnection);
            mBound = false;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        // Remove pending delayed callbacks to prevent memory leak
        if (mNavigationRunnable != null && mStatusMessage != null) {
            mStatusMessage.removeCallbacks(mNavigationRunnable);
            mNavigationRunnable = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Ensure service is unbound even if onStop() wasn't called
        // (e.g., if fragment was destroyed while in background)
        if (mBound) {
            try {
                requireActivity().unbindService(mConnection);
                Logger.logDebug(LOG_TAG, "Service unbound in onDestroy()");
            } catch (IllegalArgumentException e) {
                // Service was not bound or already unbound
                Logger.logDebug(LOG_TAG, "Service was already unbound");
            }
            mBound = false;
            mService = null;
        }
    }

    private void tryStartInstallation() {
        if (mInstallationStarted.compareAndSet(false, true)) {
            startInstallation();
        }
    }

    private void startInstallation() {
        if (!mBound || mService == null) {
            Logger.logError(LOG_TAG, "Cannot start installation: service not bound");
            mInstallationStarted.set(false); // Reset so it can retry
            return;
        }

        Logger.logInfo(LOG_TAG, "Starting OpenClaw installation");

        mService.installOpenclaw(new BotDropService.InstallProgressCallback() {
            @Override
            public void onStepStart(int step, String message) {
                updateStep(step, "●", message, false);
            }

            @Override
            public void onStepComplete(int step) {
                updateStep(step, "✓", null, true);
            }

            @Override
            public void onError(String error) {
                Logger.logError(LOG_TAG, "Installation failed: " + error);
                showError(error);
            }

            @Override
            public void onComplete() {
                Logger.logInfo(LOG_TAG, "Installation complete");

                // Get and display version
                String version = BotDropService.getOpenclawVersion();
                if (version != null) {
                    mStatusMessage.setText("Installation complete! (v" + version + ")");
                } else {
                    mStatusMessage.setText("Installation complete!");
                }

                prefetchModelList(version, () -> {
                    if (!isAdded() || !isResumed() || mStatusMessage == null) {
                        return;
                    }
                    mStatusMessage.setText("Preparing next step...");

                    // Auto-advance to next step after 1.5 seconds
                    // Track runnable so we can remove it in onDestroyView() if needed
                    mNavigationRunnable = () -> {
                        if (!isAdded() || !isResumed()) return;
                        SetupActivity activity = (SetupActivity) getActivity();
                        if (activity != null && !activity.isFinishing()) {
                            activity.goToNextStep();
                        }
                    };
                    mStatusMessage.postDelayed(mNavigationRunnable, 1500);
                });
            }
        });
    }

    private void prefetchModelList(String openclawVersion, ModelListPrefetchCallback callback) {
        if (mService == null || getContext() == null) {
            if (callback != null) {
                callback.onFinished();
            }
            return;
        }

        final String normalizedVersion = normalizeCacheKey(openclawVersion);
        if (!TextUtils.isEmpty(normalizedVersion) && hasCachedModelList(normalizedVersion)) {
            Logger.logInfo(LOG_TAG, "Using cached model list for OpenClaw v" + openclawVersion);
            if (callback != null) {
                callback.onFinished();
            }
            return;
        }

        mService.executeCommand(MODEL_LIST_COMMAND, result -> {
            if (!result.success) {
                Logger.logWarn(LOG_TAG, "Model list prefetch failed for v" + openclawVersion + ": exit " + result.exitCode);
                if (callback != null) {
                    callback.onFinished();
                }
                return;
            }

            List<ModelInfo> models = parseModelList(result.stdout);
            if (models.isEmpty()) {
                Logger.logWarn(LOG_TAG, "Model list prefetch returned empty output for v" + openclawVersion);
                if (callback != null) {
                    callback.onFinished();
                }
                return;
            }

            Collections.sort(models, (a, b) -> {
                if (a == null || b == null || a.fullName == null || b.fullName == null) return 0;
                return b.fullName.compareToIgnoreCase(a.fullName);
            });

            cacheModels(normalizedVersion, models);
            Logger.logInfo(LOG_TAG, "Prefetched " + models.size() + " models for OpenClaw v" + openclawVersion);
            if (callback != null) {
                callback.onFinished();
            }
        });
    }

    private boolean hasCachedModelList(String version) {
        if (TextUtils.isEmpty(version)) {
            return false;
        }

        try {
            SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String raw = prefs.getString(cacheKey(version), null);
            if (TextUtils.isEmpty(raw)) return false;

            JSONObject root = new JSONObject(raw);
            String cachedVersion = root.optString("version", "");
            if (!TextUtils.equals(cachedVersion, version)) return false;

            JSONArray list = root.optJSONArray("models");
            return list != null && list.length() > 0;
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to check cached model list: " + e.getMessage());
        }
        return false;
    }

    private List<ModelInfo> parseModelList(String output) {
        List<ModelInfo> models = new ArrayList<>();
        if (TextUtils.isEmpty(output)) {
            return models;
        }

        try {
            String[] lines = output.split("\\r?\\n");
            for (String line : lines) {
                String trimmed = line == null ? "" : line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.startsWith("#") || trimmed.startsWith("Model ")) {
                    continue;
                }

                String token = trimmed;
                if (trimmed.contains(" ")) {
                    token = trimmed.split("\\s+")[0];
                }

                if (isModelToken(token)) {
                    models.add(new ModelInfo(token));
                }
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to parse prefetched model list: " + e.getMessage());
        }

        return models;
    }

    private void cacheModels(String version, List<ModelInfo> models) {
        if (TextUtils.isEmpty(version) || models == null || models.isEmpty() || getContext() == null) {
            return;
        }

        try {
            JSONArray list = new JSONArray();
            for (ModelInfo model : models) {
                if (model != null && !TextUtils.isEmpty(model.fullName)) {
                    list.put(model.fullName);
                }
            }

            JSONObject root = new JSONObject();
            root.put("version", version);
            root.put("updated_at", System.currentTimeMillis());
            root.put("models", list);

            SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(cacheKey(version), root.toString()).apply();
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to cache prefetched model list: " + e.getMessage());
        }
    }

    private String cacheKey(String version) {
        return CACHE_KEY_PREFIX + normalizeCacheKey(version);
    }

    private String normalizeCacheKey(String version) {
        if (TextUtils.isEmpty(version)) {
            return "unknown";
        }
        return version.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private boolean isModelToken(String token) {
        if (token == null || token.isEmpty()) return false;
        if (!token.contains("/")) return false;
        return token.matches("[A-Za-z0-9._-]+/[A-Za-z0-9._:/-]+");
    }

    private void updateStep(int step, String icon, String text, boolean complete) {
        TextView iconView = null;
        TextView textView = null;

        switch (step) {
            case 0:
                iconView = mStep0Icon;
                textView = mStep0Text;
                break;
            case 1:
                iconView = mStep1Icon;
                textView = mStep1Text;
                break;
            case 2:
                iconView = mStep2Icon;
                textView = mStep2Text;
                break;
        }

        if (iconView != null) {
            iconView.setText(icon);
        }

        if (textView != null && text != null) {
            textView.setText(text);
        }
    }

    private void showError(String error) {
        mErrorMessage.setText(error);
        mErrorContainer.setVisibility(View.VISIBLE);
        mStatusMessage.setText("Installation failed");
    }

    private void resetSteps() {
        mStep0Icon.setText("○");
        mStep1Icon.setText("○");
        mStep2Icon.setText("○");
        mStatusMessage.setText("This takes about a minute");
        mInstallationStarted.set(false);
    }
}
