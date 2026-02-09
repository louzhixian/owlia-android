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
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.termux.R;
import com.termux.shared.logger.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Fragment for AI provider authentication.
 * Shows provider selection, then switches to auth input within the same fragment.
 */
public class AuthFragment extends Fragment implements SetupActivity.StepFragment {

    private static final String LOG_TAG = "AuthFragment";

    // Provider selection views
    private View mProviderSelectionView;
    private LinearLayout mPopularProvidersContainer;
    private LinearLayout mMoreProvidersContainer;
    private TextView mMoreToggle;
    
    // Auth input views (from fragment_botdrop_auth_input.xml)
    private View mAuthInputView;
    private TextView mBackButton;
    private TextView mTitle;
    private TextView mInstructions;
    private LinearLayout mInputFieldContainer;
    private TextView mInputLabel;
    private EditText mInputField;
    private ImageButton mToggleVisibility;
    private Button mOAuthButton;
    private LinearLayout mStatusContainer;
    private TextView mStatusText;
    private Button mVerifyButton;
    
    private List<ProviderInfo> mPopularProviders;
    private List<ProviderInfo> mMoreProviders;
    
    private ProviderInfo mSelectedProvider;
    private ProviderInfo.AuthMethod mSelectedAuthMethod;
    private String mSelectedModel = null; // Format: "provider/model"
    private boolean mMoreExpanded = false;
    private boolean mPasswordVisible = false;

    // Model selection UI
    private LinearLayout mModelSection;
    private Button mSelectModelButton;
    private TextView mSelectedModelText;

    // Keep track of all provider views for radio button management
    private List<View> mAllProviderViews = new ArrayList<>();

    private BotDropService mService;
    private boolean mBound = false;

    // Track delayed callbacks to prevent memory leaks
    private Runnable mNavigationRunnable;

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
            mService = null;
            mBound = false;
            Logger.logDebug(LOG_TAG, "Service disconnected");
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // Create a container to hold both views
        LinearLayout containerLayout = new LinearLayout(requireContext());
        containerLayout.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        containerLayout.setOrientation(LinearLayout.VERTICAL);

        // Inflate provider selection view
        mProviderSelectionView = inflater.inflate(R.layout.fragment_botdrop_auth, containerLayout, false);
        containerLayout.addView(mProviderSelectionView);
        
        // Inflate auth input view
        mAuthInputView = inflater.inflate(R.layout.fragment_botdrop_auth_input, containerLayout, false);
        mAuthInputView.setVisibility(View.GONE);
        containerLayout.addView(mAuthInputView);

        setupProviderSelectionView();
        setupAuthInputView();

        return containerLayout;
    }

    @Override
    public void onStart() {
        super.onStart();
        // Bind to BotDropService
        Intent intent = new Intent(getActivity(), BotDropService.class);
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

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        // Remove pending delayed callbacks to prevent memory leak
        if (mNavigationRunnable != null && mVerifyButton != null) {
            mVerifyButton.removeCallbacks(mNavigationRunnable);
            mNavigationRunnable = null;
        }

        // Release view references to prevent memory leak
        mAllProviderViews.clear();
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

    private void setupProviderSelectionView() {
        mPopularProvidersContainer = mProviderSelectionView.findViewById(R.id.auth_popular_providers);
        mMoreProvidersContainer = mProviderSelectionView.findViewById(R.id.auth_more_providers);
        mMoreToggle = mProviderSelectionView.findViewById(R.id.auth_more_toggle);

        // Model selection UI
        mModelSection = mProviderSelectionView.findViewById(R.id.auth_model_section);
        mSelectModelButton = mProviderSelectionView.findViewById(R.id.auth_select_model_button);
        mSelectedModelText = mProviderSelectionView.findViewById(R.id.auth_selected_model_text);

        // Set up Select Model button
        mSelectModelButton.setOnClickListener(v -> showModelSelectorFullscreen());

        // Load provider data
        mPopularProviders = ProviderInfo.getPopularProviders();
        mMoreProviders = ProviderInfo.getMoreProviders();

        // Populate popular providers
        for (ProviderInfo provider : mPopularProviders) {
            View providerView = createProviderView(provider);
            mPopularProvidersContainer.addView(providerView);
            mAllProviderViews.add(providerView);
        }

        // Populate more providers
        for (ProviderInfo provider : mMoreProviders) {
            View providerView = createProviderView(provider);
            mMoreProvidersContainer.addView(providerView);
            mAllProviderViews.add(providerView);
        }

        // Set up "More providers" toggle
        mMoreToggle.setOnClickListener(v -> toggleMoreProviders());
    }

    private void setupAuthInputView() {
        mBackButton = mAuthInputView.findViewById(R.id.auth_input_back);
        mTitle = mAuthInputView.findViewById(R.id.auth_input_title);
        mInstructions = mAuthInputView.findViewById(R.id.auth_input_instructions);
        mInputFieldContainer = mAuthInputView.findViewById(R.id.auth_input_field_container);
        mInputLabel = mAuthInputView.findViewById(R.id.auth_input_label);
        mInputField = mAuthInputView.findViewById(R.id.auth_input_field);
        mToggleVisibility = mAuthInputView.findViewById(R.id.auth_input_toggle_visibility);
        mOAuthButton = mAuthInputView.findViewById(R.id.auth_input_oauth_button);
        mStatusContainer = mAuthInputView.findViewById(R.id.auth_input_status_container);
        mStatusText = mAuthInputView.findViewById(R.id.auth_input_status_text);
        mVerifyButton = mAuthInputView.findViewById(R.id.auth_input_verify_button);

        // Set up back button
        mBackButton.setOnClickListener(v -> showProviderSelection());

        // Set up visibility toggle
        mToggleVisibility.setOnClickListener(v -> togglePasswordVisibility());

        // Set up OAuth button
        mOAuthButton.setOnClickListener(v -> handleOAuth());

        // Set up verify button
        mVerifyButton.setOnClickListener(v -> verifyAndContinue());
    }

    private View createProviderView(ProviderInfo provider) {
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        View view = inflater.inflate(R.layout.item_provider, null);

        RadioButton radio = view.findViewById(R.id.provider_radio);
        TextView name = view.findViewById(R.id.provider_name);
        TextView description = view.findViewById(R.id.provider_description);
        TextView recommendedBadge = view.findViewById(R.id.provider_recommended_badge);

        name.setText(provider.getName());
        description.setText(provider.getDescription());
        
        if (provider.isRecommended()) {
            recommendedBadge.setVisibility(View.VISIBLE);
        } else {
            recommendedBadge.setVisibility(View.GONE);
        }

        // Handle click on entire card
        view.setOnClickListener(v -> onProviderSelected(provider, radio));

        return view;
    }

    private void onProviderSelected(ProviderInfo provider, RadioButton selectedRadio) {
        Logger.logInfo(LOG_TAG, "Provider selected: " + provider.getName());

        mSelectedProvider = provider;

        // Update all radio buttons
        for (View providerView : mAllProviderViews) {
            RadioButton radio = providerView.findViewById(R.id.provider_radio);
            radio.setChecked(radio == selectedRadio);
        }

        // Show model selection section
        mModelSection.setVisibility(View.VISIBLE);

        // Reset model selection
        mSelectedModel = null;
        mSelectedModelText.setText("No model selected");
        mSelectedModelText.setTextColor(getResources().getColor(R.color.botdrop_secondary_text, null));

        // Disable Next button until model is selected
        if (getActivity() instanceof SetupActivity) {
            ((SetupActivity) getActivity()).setNextEnabled(false);
        }
    }

    @Override
    public boolean handleNext() {
        // If on provider selection page with model selected, show auth input
        if (mProviderSelectionView.getVisibility() == View.VISIBLE && mSelectedModel != null) {
            showAuthInput(mSelectedProvider);
            return true; // We handled it
        }
        return false; // Let default behavior proceed
    }

    private void showModelSelectorFullscreen() {
        if (!mBound || mService == null) {
            Toast.makeText(requireContext(), "Service not available. Please try again.", Toast.LENGTH_SHORT).show();
            Logger.logError(LOG_TAG, "Cannot show model selector: service not bound");
            return;
        }

        ModelSelectorDialog dialog = new ModelSelectorDialog(requireContext(), mService);
        dialog.show((provider, model) -> {
            if (provider != null && model != null) {
                mSelectedModel = provider + "/" + model;
                Logger.logInfo(LOG_TAG, "Model selected: " + mSelectedModel);

                // Update the display text
                mSelectedModelText.setText(mSelectedModel);
                mSelectedModelText.setTextColor(getResources().getColor(R.color.botdrop_on_background, null));

                // Enable Next button now that model is selected
                if (getActivity() instanceof SetupActivity) {
                    ((SetupActivity) getActivity()).setNextEnabled(true);
                }
            } else {
                // User cancelled - do nothing
                Logger.logInfo(LOG_TAG, "Model selection cancelled");
            }
        });
    }

    private void toggleMoreProviders() {
        mMoreExpanded = !mMoreExpanded;
        
        if (mMoreExpanded) {
            mMoreProvidersContainer.setVisibility(View.VISIBLE);
            mMoreToggle.setText("More providers ▲");
        } else {
            mMoreProvidersContainer.setVisibility(View.GONE);
            mMoreToggle.setText("More providers ▼");
        }
    }

    private void showProviderSelection() {
        mProviderSelectionView.setVisibility(View.VISIBLE);
        mAuthInputView.setVisibility(View.GONE);
    }

    private void showAuthInput(ProviderInfo provider) {
        // Use primary auth method (first in list)
        mSelectedAuthMethod = provider.getAuthMethods().get(0);
        
        // Switch views
        mProviderSelectionView.setVisibility(View.GONE);
        mAuthInputView.setVisibility(View.VISIBLE);
        
        // Configure UI for the selected auth method
        setupUIForAuthMethod();
    }

    private void setupUIForAuthMethod() {
        mTitle.setText(mSelectedProvider.getName());
        mInputField.setText(""); // Clear previous input
        mStatusContainer.setVisibility(View.GONE);
        mPasswordVisible = false;

        switch (mSelectedAuthMethod) {
            case API_KEY:
                setupAPIKeyUI();
                break;
            case SETUP_TOKEN:
                setupSetupTokenUI();
                break;
            case OAUTH:
                setupOAuthUI();
                break;
        }
    }

    private void setupAPIKeyUI() {
        mInputLabel.setText("API Key");
        mInputField.setHint("Paste your API key here");
        mInputField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        
        String instructions = getAPIKeyInstructions(mSelectedProvider.getId());
        mInstructions.setText(instructions);

        mInputFieldContainer.setVisibility(View.VISIBLE);
        mOAuthButton.setVisibility(View.GONE);
    }

    private void setupSetupTokenUI() {
        mInputLabel.setText("Setup Token");
        mInputField.setHint("Paste your setup token here");
        mInputField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        
        String instructions = "1. Open claude.ai/settings → \"Developer\" section\n" +
                             "2. Find or create a \"Setup Token\"\n" +
                             "3. Copy the token\n" +
                             "4. Paste it below";
        mInstructions.setText(instructions);

        mInputFieldContainer.setVisibility(View.VISIBLE);
        mOAuthButton.setVisibility(View.GONE);
    }

    private void setupOAuthUI() {
        String instructions = "Sign in with your " + mSelectedProvider.getName() + " account using OAuth.\n\n" +
                             "This will open your browser to complete the login.";
        mInstructions.setText(instructions);

        mInputFieldContainer.setVisibility(View.GONE);
        mOAuthButton.setVisibility(View.VISIBLE);
        mOAuthButton.setText("Open Browser to Sign In");
    }

    private String getAPIKeyInstructions(String providerId) {
        switch (providerId) {
            case "anthropic":
                return "1. Go to console.anthropic.com\n" +
                       "2. Navigate to API Keys section\n" +
                       "3. Create a new API key\n" +
                       "4. Copy and paste it below";
            case "openai":
                return "1. Go to platform.openai.com\n" +
                       "2. Navigate to API Keys\n" +
                       "3. Create a new secret key\n" +
                       "4. Copy and paste it below";
            case "google":
                return "1. Go to aistudio.google.com\n" +
                       "2. Get an API key\n" +
                       "3. Copy and paste it below";
            case "openrouter":
                return "1. Go to openrouter.ai\n" +
                       "2. Sign in and get your API key\n" +
                       "3. Copy and paste it below";
            default:
                return "1. Get your API key from " + mSelectedProvider.getName() + "\n" +
                       "2. Copy and paste it below";
        }
    }

    private void togglePasswordVisibility() {
        mPasswordVisible = !mPasswordVisible;
        
        if (mPasswordVisible) {
            mInputField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        } else {
            mInputField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        
        // Move cursor to end
        mInputField.setSelection(mInputField.getText().length());
    }

    private void handleOAuth() {
        Logger.logInfo(LOG_TAG, "OAuth requested for: " + mSelectedProvider.getId());
        
        showStatus("OAuth flow not yet implemented.\n\nPlease use API Key method for now.", false);
        
        // TODO: Implement OAuth via openclaw CLI
    }

    private void verifyAndContinue() {
        String credential = mInputField.getText().toString().trim();

        if (TextUtils.isEmpty(credential)) {
            showStatus("Please enter your " + mInputLabel.getText().toString().toLowerCase(), false);
            return;
        }

        // Basic format validation
        if (!validateCredentialFormat(credential)) {
            showStatus("Invalid format. Please check and try again.", false);
            return;
        }

        // Show progress
        mVerifyButton.setEnabled(false);
        mVerifyButton.setText("Verifying...");
        showStatus("Verifying credentials...", true);

        // Save to config and verify
        saveCredentials(credential);
    }

    private boolean validateCredentialFormat(String credential) {
        // Basic length check only — different providers have different formats
        return credential.length() >= 8;
    }

    private void saveCredentials(String credential) {
        String providerId = mSelectedProvider.getId();

        // Use selected model or fall back to default
        String modelToUse;
        if (mSelectedModel != null && !mSelectedModel.isEmpty()) {
            // Extract just the model part (after the /)
            String[] parts = mSelectedModel.split("/", 2);
            modelToUse = parts.length > 1 ? parts[1] : getDefaultModel(providerId);
        } else {
            modelToUse = getDefaultModel(providerId);
        }

        Logger.logInfo(LOG_TAG, "Saving credentials for provider: " + providerId + ", model: " + modelToUse);

        // Write API key and provider config directly (no CLI dependency)
        boolean keyWritten = BotDropConfig.setApiKey(providerId, credential);
        boolean providerWritten = BotDropConfig.setProvider(providerId, modelToUse);

        if (keyWritten && providerWritten) {
            Logger.logInfo(LOG_TAG, "Auth configured successfully");
            showStatus("✓ Connected!\nModel: " + providerId + "/" + modelToUse, true);

            // Save to config template cache
            ConfigTemplate template = new ConfigTemplate();
            template.provider = providerId;
            template.model = mSelectedModel != null ? mSelectedModel : (providerId + "/" + modelToUse);
            template.apiKey = credential;
            // tgBotToken and tgUserId will be added later in ChannelFragment
            ConfigTemplateCache.saveTemplate(requireContext(), template);
            Logger.logInfo(LOG_TAG, "Config template saved to cache");

            // Auto-advance after short delay
            // Track runnable so we can remove it in onDestroyView() if needed
            mNavigationRunnable = () -> {
                if (!isAdded() || !isResumed()) return;
                SetupActivity activity = (SetupActivity) getActivity();
                if (activity != null && !activity.isFinishing()) {
                    activity.goToNextStep();
                }
            };
            mVerifyButton.postDelayed(mNavigationRunnable, 1500);
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
