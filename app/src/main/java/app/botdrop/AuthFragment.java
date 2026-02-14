package app.botdrop;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.termux.R;
import com.termux.shared.logger.Logger;

import java.util.Arrays;
import java.util.List;

/**
 * Single-page auth step:
 * 1) Select provider/model
 * 2) Enter API key
 * 3) Verify & continue
 */
public class AuthFragment extends Fragment implements SetupActivity.StepFragment {

    private static final String LOG_TAG = "AuthFragment";

    private EditText mModelText;
    private Button mSelectButton;

    private LinearLayout mKeySection;
    private TextView mKeyLabel;
    private EditText mKeyInput;
    private LinearLayout mBaseUrlSection;
    private TextView mBaseUrlLabel;
    private ImageButton mToggleVisibility;
    private LinearLayout mStatusContainer;
    private TextView mStatusText;
    private Button mVerifyButton;

    private ProviderInfo mSelectedProvider;
    private String mSelectedModel = null; // provider/model
    private boolean mPasswordVisible = false;
    private EditText mBaseUrlInput;
    private List<String> mCurrentCustomModels;
    private String mSelectedProviderBaseUrl;

    private BotDropService mService;
    private boolean mBound = false;

    private Runnable mNavigationRunnable;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BotDropService.LocalBinder binder = (BotDropService.LocalBinder) service;
            mService = binder.getService();
            mBound = true;
            Logger.logDebug(LOG_TAG, "Service connected");
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
        View view = inflater.inflate(R.layout.fragment_botdrop_auth, container, false);

        mModelText = view.findViewById(R.id.auth_model_text);
        mSelectButton = view.findViewById(R.id.auth_select_button);

        mKeySection = view.findViewById(R.id.auth_key_section);
        mKeyLabel = view.findViewById(R.id.auth_key_label);
        mKeyInput = view.findViewById(R.id.auth_key_input);
        mBaseUrlSection = view.findViewById(R.id.auth_base_url_section);
        mBaseUrlLabel = view.findViewById(R.id.auth_base_url_label);
        mBaseUrlInput = view.findViewById(R.id.auth_base_url_input);
        mToggleVisibility = view.findViewById(R.id.auth_key_toggle_visibility);
        mStatusContainer = view.findViewById(R.id.auth_key_status_container);
        mStatusText = view.findViewById(R.id.auth_key_status_text);
        mVerifyButton = view.findViewById(R.id.auth_key_verify_button);

        updateBaseUrlSectionVisibility(false);

        mSelectButton.setOnClickListener(v -> openModelSelector());
        mToggleVisibility.setOnClickListener(v -> togglePasswordVisibility());
        mVerifyButton.setOnClickListener(v -> verifyAndContinue());

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        Intent intent = new Intent(getActivity(), BotDropService.class);
        requireActivity().bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Single-page flow uses its own button, no Setup nav needed here.
        if (getActivity() instanceof SetupActivity) {
            ((SetupActivity) getActivity()).setNavigationVisible(false);
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
        if (mNavigationRunnable != null && mVerifyButton != null) {
            mVerifyButton.removeCallbacks(mNavigationRunnable);
            mNavigationRunnable = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mBound) {
            try {
                requireActivity().unbindService(mConnection);
            } catch (IllegalArgumentException ignored) {
            }
            mBound = false;
            mService = null;
        }
    }

    private void openModelSelector() {
        ModelSelectorDialog dialog = new ModelSelectorDialog(requireContext(), mService, true);
        dialog.show((provider, model, apiKey, baseUrl, availableModels) -> {
            if (provider == null || model == null) {
                Logger.logInfo(LOG_TAG, "Model selection cancelled");
                return;
            }

            String fullModel = provider + "/" + model;
            mSelectedModel = fullModel;
            mSelectedProvider = findProviderById(provider);
            mModelText.setText(fullModel);
            mCurrentCustomModels = availableModels;
            mSelectedProviderBaseUrl = normalizeCustomBaseUrl(!TextUtils.isEmpty(baseUrl) ? baseUrl : BotDropConfig.getBaseUrl(provider));

            boolean isCustomProvider = !TextUtils.isEmpty(mSelectedProviderBaseUrl);
            updateBaseUrlSectionVisibility(isCustomProvider);

            mKeySection.setVisibility(View.VISIBLE);
            mKeyInput.setText("");
            if (!TextUtils.isEmpty(apiKey)) {
                mKeyInput.setText(apiKey);
            }
            mBaseUrlInput.setText("");
            if (isCustomProvider) {
                mBaseUrlInput.setText(!TextUtils.isEmpty(mSelectedProviderBaseUrl) ? mSelectedProviderBaseUrl : "");
            } else {
                mSelectedProviderBaseUrl = null;
                mCurrentCustomModels = null;
            }
            mStatusContainer.setVisibility(View.GONE);
            mKeyLabel.setText("API Key");

            Logger.logInfo(LOG_TAG, "Model selected: " + fullModel);
        });
    }

    @Override
    public boolean handleNext() {
        // This step is fully controlled by the inline Verify button.
        return false;
    }

    private ProviderInfo findProviderById(String providerId) {
        for (ProviderInfo p : ProviderInfo.getPopularProviders()) {
            if (p.getId().equals(providerId)) return p;
        }
        for (ProviderInfo p : ProviderInfo.getMoreProviders()) {
            if (p.getId().equals(providerId)) return p;
        }

        return new ProviderInfo(
            providerId,
            providerId,
            "API Key",
            Arrays.asList(ProviderInfo.AuthMethod.API_KEY),
            false
        );
    }

    private void updateBaseUrlSectionVisibility(boolean visible) {
        if (mBaseUrlSection != null) {
            mBaseUrlSection.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (mBaseUrlLabel != null) {
            mBaseUrlLabel.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (mBaseUrlInput != null) {
            mBaseUrlInput.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (!visible && mBaseUrlInput != null) {
            mBaseUrlInput.setText("");
        }
    }

    private String normalizeCustomBaseUrl(String baseUrl) {
        if (TextUtils.isEmpty(baseUrl)) {
            return "";
        }

        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private boolean isCustomProvider() {
        return !TextUtils.isEmpty(mSelectedProviderBaseUrl);
    }

    private void togglePasswordVisibility() {
        mPasswordVisible = !mPasswordVisible;
        if (mPasswordVisible) {
            mKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        } else {
            mKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        mKeyInput.setSelection(mKeyInput.getText().length());
    }

    private void verifyAndContinue() {
        if (mSelectedModel == null || mSelectedProvider == null) {
            showStatus("Please select a model first.", false);
            return;
        }

        String credential = mKeyInput.getText().toString().trim();
        String baseUrl = null;
        if (isCustomProvider()) {
            baseUrl = mBaseUrlInput == null ? null : normalizeCustomBaseUrl(mBaseUrlInput.getText().toString());
            String selectedBaseUrl = normalizeCustomBaseUrl(mSelectedProviderBaseUrl);
            if (!TextUtils.equals(baseUrl, selectedBaseUrl)) {
                showStatus("Base URL changed; please reselect the provider and model.", false);
                return;
            }

            if (TextUtils.isEmpty(baseUrl)) {
                showStatus("Please enter custom base URL", false);
                return;
            }
        }
        if (TextUtils.isEmpty(credential)) {
            showStatus("Please enter your api key", false);
            return;
        }

        if (credential.length() < 8) {
            showStatus("Invalid format. Please check and try again.", false);
            return;
        }

        mVerifyButton.setEnabled(false);
        mVerifyButton.setText("Verifying...");
        showStatus("Verifying credentials...", true);

        saveCredentials(credential, baseUrl);
    }

    private void saveCredentials(String credential, String baseUrl) {
        String providerId = mSelectedProvider.getId();
        boolean isCustomProvider = isCustomProvider() && !TextUtils.isEmpty(baseUrl);

        String modelToUse;
        if (mSelectedModel != null && !mSelectedModel.isEmpty()) {
            String[] parts = mSelectedModel.split("/", 2);
            modelToUse = parts.length > 1 ? parts[1] : getDefaultModel(providerId);
        } else {
            modelToUse = getDefaultModel(providerId);
        }

        Logger.logInfo(LOG_TAG, "Saving credentials for provider: " + providerId + ", model: " + modelToUse);
        String fullModel = providerId + "/" + modelToUse;
        if (isCustomProvider && (mCurrentCustomModels == null || mCurrentCustomModels.isEmpty())) {
            showStatus("No custom model list found. Please reselect the provider and model.", false);
            return;
        }

        boolean saved = BotDropConfig.setActiveProvider(
            providerId,
            modelToUse,
            credential,
            isCustomProvider ? baseUrl : null,
            isCustomProvider ? mCurrentCustomModels : null
        );
        if (saved) {
            if (getContext() != null) {
                ModelSelectorDialog.cacheProviderApiKey(requireContext(), providerId, credential);
            }

            showStatus("✓ Connected!\nModel: " + fullModel, true);

            ConfigTemplate template = new ConfigTemplate();
            template.provider = providerId;
            template.model = mSelectedModel != null ? mSelectedModel : fullModel;
            template.apiKey = credential;
            if (isCustomProvider && !TextUtils.isEmpty(baseUrl)) {
                template.baseUrl = baseUrl;
            }
            ConfigTemplateCache.saveTemplate(requireContext(), template);

            mNavigationRunnable = () -> {
                if (!isAdded() || !isResumed()) return;
                SetupActivity activity = (SetupActivity) getActivity();
                if (activity != null && !activity.isFinishing()) {
                    activity.goToNextStep();
                }
            };
            mVerifyButton.postDelayed(mNavigationRunnable, 800);
        } else {
            showStatus("Failed to write config. Check app permissions.", false);
            resetVerifyButton();
        }
    }

    private String getDefaultModel(String providerId) {
        switch (providerId) {
            case "anthropic":
                return "claude-sonnet-4-5";
            case "openai":
                return "gpt-4o";
            case "google":
                return "gemini-3-flash-preview";
            case "openrouter":
                return "anthropic/claude-sonnet-4";
            default:
                return "default";
        }
    }

    private void showStatus(String message, boolean success) {
        mStatusText.setText(message);
        mStatusContainer.setVisibility(View.VISIBLE);
    }

    private void resetVerifyButton() {
        mVerifyButton.setEnabled(true);
        mVerifyButton.setText("Verify & Continue");
    }
}
