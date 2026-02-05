package com.termux.app.owlia;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.termux.R;
import com.termux.shared.logger.Logger;

/**
 * Step 1 of setup: Welcome + Auto-install OpenClaw
 *
 * This fragment automatically starts installation when loaded.
 * Shows progress with checkmarks for each step.
 * On success, automatically advances to next step.
 * On failure, shows error and retry button.
 */
public class InstallFragment extends Fragment {

    private static final String LOG_TAG = "InstallFragment";

    // Step indicators
    private TextView mStep0Icon, mStep0Text;
    private TextView mStep1Icon, mStep1Text;
    private TextView mStep2Icon, mStep2Text;

    private TextView mStatusMessage;
    private View mErrorContainer;
    private TextView mErrorMessage;
    private Button mRetryButton;

    private OwliaService mService;
    private boolean mBound = false;
    private boolean mInstallationStarted = false;

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            OwliaService.LocalBinder binder = (OwliaService.LocalBinder) service;
            mService = binder.getService();
            mBound = true;
            Logger.logDebug(LOG_TAG, "Service connected");

            // Auto-start installation
            if (!mInstallationStarted) {
                startInstallation();
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
        View view = inflater.inflate(R.layout.fragment_owlia_install, container, false);

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
        // Bind to OwliaService
        Intent intent = new Intent(getActivity(), OwliaService.class);
        requireActivity().bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mBound) {
            requireActivity().unbindService(mConnection);
            mBound = false;
        }
    }

    private void startInstallation() {
        if (!mBound || mService == null) {
            Logger.logError(LOG_TAG, "Cannot start installation: service not bound");
            return;
        }

        mInstallationStarted = true;
        Logger.logInfo(LOG_TAG, "Starting OpenClaw installation");

        mService.installOpenclaw(new OwliaService.InstallProgressCallback() {
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
                mStatusMessage.setText("Installation complete!");

                // Auto-advance to next step after 1 second
                mStatusMessage.postDelayed(() -> {
                    SetupActivity activity = (SetupActivity) getActivity();
                    if (activity != null) {
                        activity.goToNextStep();
                    }
                }, 1000);
            }
        });
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
        mInstallationStarted = false;
    }
}
