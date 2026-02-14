package app.botdrop;

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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.termux.R;
import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Step 3 of setup: Channel setup (simplified)
 * 
 * Flow:
 * 1. User opens @BotDropSetupBot to create bot and get User ID
 * 2. User enters Bot Token + User ID in app
 * 3. App configures and starts gateway
 */
public class ChannelFragment extends Fragment {

    private static final String LOG_TAG = "ChannelFragment";
    private static final String SETUP_BOT_URL = "https://t.me/BotDropSetupBot";

    private Button mOpenSetupBotButton;
    private EditText mTokenInput;
    private EditText mUserIdInput;
    private Button mConnectButton;
    private Button mSkipButton;
    private TextView mErrorMessage;
    private boolean mHasExistingTelegramConfig;

    private BotDropService mService;
    private boolean mBound = false;

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BotDropService.LocalBinder binder = (BotDropService.LocalBinder) service;
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
        return inflater.inflate(R.layout.fragment_botdrop_channel, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Find views
        mOpenSetupBotButton = view.findViewById(R.id.channel_open_setup_bot);
        mTokenInput = view.findViewById(R.id.channel_token_input);
        mUserIdInput = view.findViewById(R.id.channel_userid_input);
        mConnectButton = view.findViewById(R.id.channel_connect_button);
        mSkipButton = view.findViewById(R.id.channel_skip_button);
        mErrorMessage = view.findViewById(R.id.channel_error_message);

        // Setup click handlers
        mOpenSetupBotButton.setOnClickListener(v -> openSetupBot());
        mConnectButton.setOnClickListener(v -> connect());

        preloadExistingTelegramConfig();
        configureSkipAction();

        Logger.logDebug(LOG_TAG, "ChannelFragment view created");
    }

    @Override
    public void onStart() {
        super.onStart();
        // Bind to service (matching InstallFragment lifecycle pattern)
        Intent intent = new Intent(requireActivity(), BotDropService.class);
        requireActivity().bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mBound) {
            try {
                requireActivity().unbindService(mConnection);
            } catch (IllegalArgumentException e) {
                Logger.logDebug(LOG_TAG, "Service was already unbound");
            }
            mBound = false;
        }
    }

    private void openSetupBot() {
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(SETUP_BOT_URL));
        startActivity(browserIntent);
    }

    private void connect() {
        // Clear previous error
        mErrorMessage.setVisibility(View.GONE);

        String token = mTokenInput.getText().toString().trim();
        String userId = mUserIdInput.getText().toString().trim();

        // Validate inputs
        if (TextUtils.isEmpty(token)) {
            showError("Please enter your bot token");
            return;
        }

        if (TextUtils.isEmpty(userId)) {
            showError("Please enter your User ID");
            return;
        }

        // Basic token format validation (Telegram bot tokens are like "123456789:ABC-DEF...")
        if (!token.matches("^\\d+:[A-Za-z0-9_-]+$")) {
            showError("Invalid bot token format");
            return;
        }

        // Basic User ID validation (should be numeric)
        if (!userId.matches("^\\d+$")) {
            showError("Invalid User ID format (should be numeric)");
            return;
        }

        // Disable button during processing
        mConnectButton.setEnabled(false);
        mConnectButton.setText("Connecting...");

        // Write channel config (Telegram)
        boolean success = ChannelSetupHelper.writeChannelConfig("telegram", token, userId);
        if (!success) {
            showError("Failed to write configuration");
            resetButton();
            return;
        }

        // Update cache with Telegram info
        try {
            ConfigTemplate template = ConfigTemplateCache.loadTemplate(requireContext());
            if (template == null) {
                template = new ConfigTemplate();
            }
            template.tgBotToken = token;
            template.tgUserId = userId;
            ConfigTemplateCache.saveTemplate(requireContext(), template);

            Logger.logInfo(LOG_TAG, "Telegram config saved to cache");
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to save Telegram config to cache: " + e.getMessage());
        }

        // Start gateway
        startGateway();
    }

    private void preloadExistingTelegramConfig() {
        mHasExistingTelegramConfig = false;
        try {
            JSONObject config = BotDropConfig.readConfig();
            JSONObject channels = config != null ? config.optJSONObject("channels") : null;
            if (channels == null) {
                return;
            }

            JSONObject telegram = channels.optJSONObject("telegram");
            if (telegram == null) {
                return;
            }

            String token = telegram.optString("botToken", null);
            String userId = extractUserIdFromAllowFrom(telegram);

            if (!TextUtils.isEmpty(token) || !TextUtils.isEmpty(userId)) {
                mHasExistingTelegramConfig = true;
            }

            if (!TextUtils.isEmpty(token)) {
                mTokenInput.setText(token.trim());
            }
            if (!TextUtils.isEmpty(userId)) {
                mUserIdInput.setText(userId.trim());
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to preload telegram config: " + e.getMessage());
        }
    }

    private void configureSkipAction() {
        if (mSkipButton == null) {
            return;
        }

        if (mHasExistingTelegramConfig) {
            mSkipButton.setText("Cancel");
            mSkipButton.setOnClickListener(v -> finishChannelSetup());
        } else {
            mSkipButton.setOnClickListener(v -> skipSetup());
        }
    }

    private String extractUserIdFromAllowFrom(JSONObject telegram) {
        Object allowFrom = telegram.opt("allowFrom");
        if (allowFrom instanceof String) {
            return (String) allowFrom;
        }

        if (allowFrom instanceof JSONArray) {
            JSONArray ids = (JSONArray) allowFrom;
            if (ids.length() > 0) {
                Object first = ids.opt(0);
                return first != null ? String.valueOf(first) : null;
            }
        }

        return null;
    }

    private void startGateway() {
        if (!mBound || mService == null) {
            showError("Service not ready, please try again");
            resetButton();
            return;
        }

        Logger.logInfo(LOG_TAG, "Starting gateway...");

        mService.startGateway(result -> {
            // Check if fragment is still attached
            if (!isAdded() || getActivity() == null || getActivity().isFinishing()) {
                return;
            }
            
            requireActivity().runOnUiThread(() -> {
                // Double-check in UI thread
                if (!isAdded() || getActivity() == null || getActivity().isFinishing()) {
                    return;
                }
                
                if (result.success) {
                    Logger.logInfo(LOG_TAG, "Gateway started successfully");
                    Toast.makeText(requireContext(), "Connected! Gateway is starting...", Toast.LENGTH_LONG).show();

                    // Setup complete, advance to next step
                    SetupActivity activity = (SetupActivity) getActivity();
                    if (activity != null && !activity.isFinishing()) {
                        activity.goToNextStep();
                    }
                } else {
                    Logger.logError(LOG_TAG, "Failed to start gateway: " + result.stderr);
                    
                    String errorMsg = result.stderr;
                    if (TextUtils.isEmpty(errorMsg)) {
                        errorMsg = result.stdout;
                    }
                    if (TextUtils.isEmpty(errorMsg)) {
                        errorMsg = "Unknown error (exit code: " + result.exitCode + ")";
                    }
                    
                    showError("Failed to start gateway: " + errorMsg);
                    resetButton();
                }
            });
        });
    }

    private void skipSetup() {
        if (!isAdded() || getActivity() == null || getActivity().isFinishing()) {
            return;
        }

        new AlertDialog.Builder(requireContext())
            .setTitle("Skip Telegram setup?")
            .setMessage("If you skip now, Telegram will remain unconfigured. "
                + "You can configure other channels later from the OpenClaw Web UI.")
            .setPositiveButton("Skip", (dialog, which) -> {
                Logger.logInfo(LOG_TAG, "User skipped Telegram setup");

                SetupActivity activity = (SetupActivity) getActivity();
                if (activity == null || activity.isFinishing()) {
                    return;
                }
                activity.goToNextStep();
            })
            .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
            .show();
    }

    private void showError(String message) {
            mErrorMessage.setText(message);
            mErrorMessage.setVisibility(View.VISIBLE);
    }

    private void finishChannelSetup() {
        if (!isAdded() || getActivity() == null || getActivity().isFinishing()) {
            return;
        }
        getActivity().finish();
    }

    private void resetButton() {
        mConnectButton.setEnabled(true);
        mConnectButton.setText("Connect & Start");
    }
}
