package com.termux.app.owlia;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.termux.R;
import com.termux.shared.logger.Logger;

/**
 * Step 3 of setup: Channel setup
 * Two options:
 * 1. Use @OwliaSetupBot (guided setup with code)
 * 2. Manual setup (direct token + user ID input)
 */
public class ChannelFragment extends Fragment {

    private static final String LOG_TAG = "ChannelFragment";

    // Mode selection
    private View mModeSelectionView;
    private RadioButton mModeSetupBot;
    private RadioButton mModeManual;
    private Button mModeNextButton;

    // SetupBot flow
    private View mSetupBotView;
    private RadioButton mPlatformTelegram;
    private RadioButton mPlatformDiscord;
    private Button mOpenBotButton;
    private EditText mSetupCodeInput;
    private Button mSetupBotConnectButton;

    // Manual flow
    private View mManualView;
    private RadioButton mManualPlatformTelegram;
    private RadioButton mManualPlatformDiscord;
    private EditText mManualTokenInput;
    private EditText mManualOwnerIdInput;
    private Button mManualConnectButton;

    private OwliaService mService;
    private boolean mBound = false;

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            OwliaService.LocalBinder binder = (OwliaService.LocalBinder) service;
            mService = binder.getService();
            mBound = true;
            Logger.logDebug(LOG_TAG, "Service connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBound = false;
            Logger.logDebug(LOG_TAG, "Service disconnected");
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_owlia_channel, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Bind to service
        Intent intent = new Intent(requireContext(), OwliaService.class);
        requireContext().bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

        // Mode selection
        mModeSelectionView = view.findViewById(R.id.channel_mode_selection);
        mModeSetupBot = view.findViewById(R.id.channel_mode_setupbot);
        mModeManual = view.findViewById(R.id.channel_mode_manual);
        mModeNextButton = view.findViewById(R.id.channel_mode_next);

        // SetupBot flow
        mSetupBotView = view.findViewById(R.id.channel_setupbot_view);
        mPlatformTelegram = view.findViewById(R.id.channel_setupbot_telegram);
        mPlatformDiscord = view.findViewById(R.id.channel_setupbot_discord);
        mOpenBotButton = view.findViewById(R.id.channel_setupbot_open);
        mSetupCodeInput = view.findViewById(R.id.channel_setupbot_code);
        mSetupBotConnectButton = view.findViewById(R.id.channel_setupbot_connect);

        // Manual flow
        mManualView = view.findViewById(R.id.channel_manual_view);
        mManualPlatformTelegram = view.findViewById(R.id.channel_manual_telegram);
        mManualPlatformDiscord = view.findViewById(R.id.channel_manual_discord);
        mManualTokenInput = view.findViewById(R.id.channel_manual_token);
        mManualOwnerIdInput = view.findViewById(R.id.channel_manual_owner_id);
        mManualConnectButton = view.findViewById(R.id.channel_manual_connect);

        // Mode selection handlers
        mModeNextButton.setOnClickListener(v -> {
            if (mModeSetupBot.isChecked()) {
                showSetupBotFlow();
            } else if (mModeManual.isChecked()) {
                showManualFlow();
            } else {
                Toast.makeText(requireContext(), "Please select a setup method", Toast.LENGTH_SHORT).show();
            }
        });

        // SetupBot flow handlers
        mOpenBotButton.setOnClickListener(v -> openSetupBot());
        mSetupBotConnectButton.setOnClickListener(v -> connectWithSetupCode());

        // Manual flow handlers
        mManualConnectButton.setOnClickListener(v -> connectManually());

        Logger.logDebug(LOG_TAG, "ChannelFragment view created");
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mBound) {
            requireContext().unbindService(mConnection);
            mBound = false;
        }
    }

    private void showSetupBotFlow() {
        mModeSelectionView.setVisibility(View.GONE);
        mSetupBotView.setVisibility(View.VISIBLE);
        mManualView.setVisibility(View.GONE);
    }

    private void showManualFlow() {
        mModeSelectionView.setVisibility(View.GONE);
        mSetupBotView.setVisibility(View.GONE);
        mManualView.setVisibility(View.VISIBLE);
    }

    private void openSetupBot() {
        String url;
        if (mPlatformTelegram.isChecked()) {
            url = "https://t.me/OwliaSetupBot";
        } else if (mPlatformDiscord.isChecked()) {
            // TODO: Update with actual Discord bot invite link when deployed
            url = "https://discord.com/api/oauth2/authorize?client_id=YOUR_BOT_ID&scope=bot";
            Toast.makeText(requireContext(),
                "Discord setup bot coming soon!\nPlease use manual setup for now.",
                Toast.LENGTH_LONG).show();
            return;
        } else {
            Toast.makeText(requireContext(), "Please select a platform", Toast.LENGTH_SHORT).show();
            return;
        }

        // Open bot URL
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        startActivity(browserIntent);
    }

    private void connectWithSetupCode() {
        String setupCode = mSetupCodeInput.getText().toString().trim();

        if (TextUtils.isEmpty(setupCode)) {
            Toast.makeText(requireContext(), "Please enter the setup code", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!setupCode.startsWith("OWLIA-")) {
            Toast.makeText(requireContext(), "Invalid setup code format", Toast.LENGTH_SHORT).show();
            return;
        }

        // Disable button during processing
        mSetupBotConnectButton.setEnabled(false);
        mSetupBotConnectButton.setText("Connecting...");

        // Decode setup code and configure
        ChannelSetupHelper.SetupCodeData data = ChannelSetupHelper.decodeSetupCode(setupCode);
        if (data == null) {
            Toast.makeText(requireContext(), "Failed to decode setup code", Toast.LENGTH_LONG).show();
            mSetupBotConnectButton.setEnabled(true);
            mSetupBotConnectButton.setText("Connect & Start");
            return;
        }

        // Write channel config
        boolean success = ChannelSetupHelper.writeChannelConfig(data.platform, data.botToken, data.ownerId);
        if (!success) {
            Toast.makeText(requireContext(), "Failed to write configuration", Toast.LENGTH_LONG).show();
            mSetupBotConnectButton.setEnabled(true);
            mSetupBotConnectButton.setText("Connect & Start");
            return;
        }

        // Start gateway
        startGateway();
    }

    private void connectManually() {
        String platform = mManualPlatformTelegram.isChecked() ? "telegram" :
                         mManualPlatformDiscord.isChecked() ? "discord" : null;

        if (platform == null) {
            Toast.makeText(requireContext(), "Please select a platform", Toast.LENGTH_SHORT).show();
            return;
        }

        String token = mManualTokenInput.getText().toString().trim();
        String ownerId = mManualOwnerIdInput.getText().toString().trim();

        if (TextUtils.isEmpty(token)) {
            Toast.makeText(requireContext(), "Please enter bot token", Toast.LENGTH_SHORT).show();
            return;
        }

        if (TextUtils.isEmpty(ownerId)) {
            Toast.makeText(requireContext(), "Please enter your user ID", Toast.LENGTH_SHORT).show();
            return;
        }

        // Disable button during processing
        mManualConnectButton.setEnabled(false);
        mManualConnectButton.setText("Connecting...");

        // Write channel config
        boolean success = ChannelSetupHelper.writeChannelConfig(platform, token, ownerId);
        if (!success) {
            Toast.makeText(requireContext(), "Failed to write configuration", Toast.LENGTH_LONG).show();
            mManualConnectButton.setEnabled(true);
            mManualConnectButton.setText("Connect & Start");
            return;
        }

        // Start gateway
        startGateway();
    }

    private void startGateway() {
        if (!mBound) {
            Toast.makeText(requireContext(), "Service not ready", Toast.LENGTH_SHORT).show();
            return;
        }

        Logger.logInfo(LOG_TAG, "Starting gateway...");

        mService.startGateway(result -> {
            if (result.success) {
                Logger.logInfo(LOG_TAG, "Gateway started successfully");
                Toast.makeText(requireContext(), "Connected! Gateway is starting...", Toast.LENGTH_LONG).show();

                // Setup complete, finish SetupActivity
                if (getActivity() instanceof SetupActivity) {
                    ((SetupActivity) getActivity()).goToNextStep();
                }
            } else {
                Logger.logError(LOG_TAG, "Failed to start gateway: " + result.stderr);
                // Show actual error to user
                String errorMsg = result.stderr;
                if (errorMsg == null || errorMsg.isEmpty()) {
                    errorMsg = result.stdout;
                }
                if (errorMsg == null || errorMsg.isEmpty()) {
                    errorMsg = "Unknown error (exit code: " + result.exitCode + ")";
                }
                Toast.makeText(requireContext(),
                    "Failed to start gateway:\n" + errorMsg,
                    Toast.LENGTH_LONG).show();

                // Re-enable buttons
                mSetupBotConnectButton.setEnabled(true);
                mSetupBotConnectButton.setText("Connect & Start");
                mManualConnectButton.setEnabled(true);
                mManualConnectButton.setText("Connect & Start");
            }
        });
    }
}
