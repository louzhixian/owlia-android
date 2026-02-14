package app.botdrop;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;
import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Dialog for selecting a model with search capability.
 * Uses cached OpenClaw model list when possible and fallbacks to static catalog.
 */
public class ModelSelectorDialog extends Dialog {

    private static final String LOG_TAG = "ModelSelectorDialog";
    private static final String PREFS_NAME = "openclaw_model_cache_v1";
    private static final String KEY_CACHE_PREFS_NAME = "openclaw_model_key_cache_v1";
    private static final String STATIC_MODELS_ASSET = "openclaw-models-all.keys";
    private static final String CACHE_KEY_PREFIX = "models_by_version_";
    private static final String KEY_CACHE_PREFIX = "recent_keys_by_provider_";
    private static final String KEY_CACHE_PREFIX_LEGACY = "recent_keys_by_model_";
    private static final int MAX_CACHED_KEYS_PER_MODEL = 8;
    private static final int MODEL_REQUEST_CONNECT_TIMEOUT_MS = 12000;
    private static final int MODEL_REQUEST_READ_TIMEOUT_MS = 15000;
    private static final String MODELS_PATH_SUFFIX = "/models";
    private static final String CUSTOM_PROVIDER_ID = BotDropConfig.CUSTOM_PROVIDER_ID;
    private static final String CUSTOM_PROVIDER_DISPLAY_NAME = "Custom Provider";

    // Cached in-memory for the currently active OpenClaw version.
    private static List<ModelInfo> sCachedAllModels;
    private static String sCachedVersion;

    private final BotDropService mService;
    private final boolean mPromptForApiKey;
    private ModelSelectedCallback mCallback;

    private TextView mTitleText;
    private TextView mStepHint;
    private ImageButton mBackButton;
    private EditText mSearchBox;
    private RecyclerView mModelList;
    private TextView mStatusText;
    private Button mRetryButton;

    private ModelListAdapter mAdapter;
    private List<ModelInfo> mAllModels = new ArrayList<>();
    private List<ModelInfo> mCurrentItems = new ArrayList<>();
    private boolean mSelectingProvider = true;
    private String mCurrentProvider;
    private String mPendingProvider;
    private String mPendingApiKey;
    private String mPendingBaseUrl;
    private List<String> mPendingAvailableModels;

    public interface ModelSelectedCallback {
        void onModelSelected(String provider, String model, String apiKey, String baseUrl, List<String> availableModels);
    }

    public ModelSelectorDialog(@NonNull Context context, BotDropService service) {
        this(context, service, false);
    }

    public ModelSelectorDialog(@NonNull Context context, BotDropService service, boolean promptForApiKey) {
        super(context);
        this.mService = service;
        this.mPromptForApiKey = promptForApiKey;
    }

    public void show(ModelSelectedCallback callback) {
        this.mCallback = callback;
        super.show();
    }

    static void cacheProviderApiKey(@NonNull Context context, String provider, String key) {
        if (TextUtils.isEmpty(provider) || TextUtils.isEmpty(key) || context == null) {
            return;
        }

        try {
            SharedPreferences prefs = context.getSharedPreferences(KEY_CACHE_PREFS_NAME, Context.MODE_PRIVATE);
            String raw = prefs.getString(keyCacheKey(provider), null);
            List<String> existing = new ArrayList<>();

            if (!TextUtils.isEmpty(raw)) {
                JSONArray list = new JSONArray(raw);
                for (int i = 0; i < list.length(); i++) {
                    String item = list.optString(i, "").trim();
                    if (!TextUtils.isEmpty(item) && !existing.contains(item)) {
                        existing.add(item);
                    }
                }
            }

            String normalized = key.trim();
            if (TextUtils.isEmpty(normalized)) return;

            existing.remove(normalized);
            existing.add(0, normalized);
            while (existing.size() > MAX_CACHED_KEYS_PER_MODEL) {
                existing.remove(existing.size() - 1);
            }

            JSONArray merged = new JSONArray();
            for (String item : existing) {
                if (!TextUtils.isEmpty(item)) {
                    merged.put(item);
                }
            }
            prefs.edit().putString(keyCacheKey(provider), merged.toString()).apply();
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to cache API key: " + e.getMessage());
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_model_selector);

        Window window = getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }

        mTitleText = findViewById(R.id.model_title);
        mStepHint = findViewById(R.id.model_step_hint);
        mBackButton = findViewById(R.id.model_back_button);
        mSearchBox = findViewById(R.id.model_search);
        mModelList = findViewById(R.id.model_list);
        mStatusText = findViewById(R.id.model_status);
        mRetryButton = findViewById(R.id.model_retry);
        ImageButton closeButton = findViewById(R.id.model_close_button);

        closeButton.setOnClickListener(v -> {
            if (mCallback != null) {
                mCallback.onModelSelected(null, null, null, null, null);
            }
            clearPendingCredentials();
            dismiss();
        });

        mAdapter = new ModelListAdapter(model -> {
            if (mSelectingProvider) {
                if (model != null && !TextUtils.isEmpty(model.provider) && TextUtils.isEmpty(model.model)) {
                    handleProviderSelection(model.provider);
                }
                return;
            }

            if (model != null && !TextUtils.isEmpty(model.provider) && !TextUtils.isEmpty(model.model)) {
                onModelSelected(model.provider, model.model);
            }
        });

        mModelList.setLayoutManager(new LinearLayoutManager(getContext()));
        mModelList.setAdapter(mAdapter);

        mSearchBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterModels(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        mRetryButton.setOnClickListener(v -> loadModels(true));
        mBackButton.setOnClickListener(v -> showProviderSelection());

        loadModels();
    }

    private void handleProviderSelection(String provider) {
        if (TextUtils.isEmpty(provider)) {
            return;
        }

        if (!mPromptForApiKey) {
            showModelSelection(provider);
            return;
        }

        showProviderCredentialPrompt(provider);
    }

    private void onModelSelected(String provider, String model) {
        if (mCallback == null || TextUtils.isEmpty(provider) || TextUtils.isEmpty(model)) {
            return;
        }

        String normalizedModel = normalizeCustomModelId(provider, model);
        if (TextUtils.isEmpty(normalizedModel)) {
            return;
        }

        if (mPromptForApiKey) {
            if (!TextUtils.isEmpty(mPendingProvider) && TextUtils.equals(mPendingProvider, provider)) {
                mCallback.onModelSelected(
                    provider,
                    normalizedModel,
                    mPendingApiKey,
                    mPendingBaseUrl,
                    getPendingAvailableModels()
                );
                dismiss();
                return;
            }

            showProviderCredentialPrompt(provider);
            return;
        }

        mCallback.onModelSelected(provider, normalizedModel, null, null, null);
        dismiss();
    }

    private void loadModels() {
        loadModels(false);
    }

    private void loadModels(boolean forceRefresh) {
        showLoading();

        String openclawVersion = BotDropService.getOpenclawVersion();
        if (TextUtils.isEmpty(openclawVersion)) {
            openclawVersion = "unknown";
        }
        String normalizedVersion = normalizeCacheKey(openclawVersion);
        final String versionForLog = openclawVersion;

        if (!forceRefresh && TextUtils.equals(normalizedVersion, sCachedVersion) && sCachedAllModels != null && !sCachedAllModels.isEmpty()) {
            showModelsFromCache(sCachedAllModels, true);
            return;
        }

        if (!forceRefresh) {
            List<ModelInfo> cached = loadCachedModels(normalizedVersion);
            if (!cached.isEmpty()) {
                sCachedVersion = normalizedVersion;
                sCachedAllModels = cached;
                showModelsFromCache(cached, true);
                return;
            }
        }

        if (mService == null) {
            List<ModelInfo> models = readModelsFromAsset();
            if (!models.isEmpty()) {
                showModelsFromList("Fallback to bundled catalog", models);
                return;
            }
            showError("Failed to load model catalog.");
            return;
        }

        mService.executeCommand("openclaw models list --all --plain", result -> {
            if (!result.success) {
                Logger.logError(LOG_TAG, "Failed to load models from OpenClaw: exit " + result.exitCode);
                List<ModelInfo> cached = loadCachedModels(normalizedVersion);
                if (!cached.isEmpty()) {
                    sCachedVersion = normalizedVersion;
                    sCachedAllModels = cached;
                    showModelsFromCache(cached, true);
                    return;
                }
                List<ModelInfo> fallback = readModelsFromAsset();
                if (!fallback.isEmpty()) {
                    showModelsFromList("Failed to load from OpenClaw; using bundled catalog", fallback);
                    return;
                }
                showError("Failed to load model catalog.");
                return;
            }

            List<ModelInfo> models = parseModelList(result.stdout);
            if (models.isEmpty()) {
                Logger.logError(LOG_TAG, "Model list command returned empty output");
                List<ModelInfo> fallback = readModelsFromAsset();
                if (!fallback.isEmpty()) {
                    showModelsFromList("Failed to parse command output; using bundled catalog", fallback);
                    return;
                }
                showError("No model list available.");
                return;
            }

            Collections.sort(models,
                (a, b) -> {
                    if (a == null || b == null || a.fullName == null || b.fullName == null) return 0;
                    return b.fullName.compareToIgnoreCase(a.fullName);
                }
            );
            cacheModels(normalizedVersion, models);
            sCachedVersion = normalizedVersion;
            sCachedAllModels = models;
            Logger.logInfo(LOG_TAG, "Loaded " + models.size() + " models for OpenClaw v" + versionForLog);
            showModelsFromList("Loaded " + models.size() + " models from OpenClaw", models);
        });
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
            Logger.logError(LOG_TAG, "Failed to parse model list output: " + e.getMessage());
        }
        return models;
    }

    private void showModelsFromCache(List<ModelInfo> models, boolean fromCache) {
        mAllModels = new ArrayList<>(models);
        if (fromCache) {
            Logger.logInfo(LOG_TAG, "Using cached model list (" + models.size() + ")");
        }
        showProviderSelection();
    }

    private void showModelsFromList(String sourceMessage, List<ModelInfo> models) {
        if (models.isEmpty()) {
            showError(sourceMessage.isEmpty() ? "No model list available." : sourceMessage);
            return;
        }

        if (!TextUtils.isEmpty(sourceMessage)) {
            Logger.logInfo(LOG_TAG, sourceMessage);
        }
        mAllModels = new ArrayList<>(models);
        showProviderSelection();
    }

    private void showProviderCredentialPrompt(String provider) {
        if (TextUtils.isEmpty(provider) || mCallback == null) {
            return;
        }

        String savedBaseUrl = BotDropConfig.getBaseUrl(provider);
        final boolean useCustomEndpoint = isCustomProvider(provider) || !TextUtils.isEmpty(savedBaseUrl);
        String providerForStorage = provider;
        if (useCustomEndpoint && isCustomProvider(provider)) {
            String candidateProvider = sanitizeProviderIdentifier(deriveProviderIdFromUrl(savedBaseUrl));
            if (!TextUtils.isEmpty(candidateProvider)) {
                providerForStorage = candidateProvider;
            }
        }
        boolean hasExistingKey = BotDropConfig.hasApiKey(providerForStorage);
        String currentProviderKey = BotDropConfig.getApiKey(providerForStorage);

        View content = LayoutInflater.from(getContext())
            .inflate(R.layout.dialog_change_model_api_key, null);
        if (content == null) {
            return;
        }

        TextView selectedModelText = content.findViewById(R.id.change_model_selected_text);
        TextView noteText = content.findViewById(R.id.change_model_note);
        android.widget.EditText providerIdInput = content.findViewById(R.id.change_model_provider_id_input);
        View providerIdSection = content.findViewById(R.id.change_model_provider_id_section);
        EditText baseUrlInput = content.findViewById(R.id.change_model_base_url_input);
        View baseUrlSection = content.findViewById(R.id.change_model_base_url_section);
        TextView cachedTitle = content.findViewById(R.id.change_model_cached_title);
        LinearLayout cachedKeysContainer = content.findViewById(R.id.change_model_cached_keys_container);
        EditText apiKeyInput = content.findViewById(R.id.change_model_api_key_input);
        if (selectedModelText == null || noteText == null || baseUrlInput == null || cachedTitle == null
            || cachedKeysContainer == null || apiKeyInput == null || providerIdSection == null || providerIdInput == null) {
            return;
        }
        if (baseUrlSection != null) {
            baseUrlSection.setVisibility(useCustomEndpoint ? View.VISIBLE : View.GONE);
        }
        if (providerIdSection != null) {
            providerIdSection.setVisibility(isCustomProvider(provider) ? View.VISIBLE : View.GONE);
        }

        selectedModelText.setText(isCustomProvider(provider) ? CUSTOM_PROVIDER_DISPLAY_NAME : provider);
        noteText.setText(isCustomProvider(provider)
            ? "Enter provider ID (optional), base URL and API key, then load models."
            : "Enter API key for this provider.");

        String suggestedProviderId = isCustomProvider(provider)
            ? sanitizeProviderIdentifier(deriveProviderIdFromUrl(savedBaseUrl))
            : "";
        if (!TextUtils.isEmpty(suggestedProviderId) && providerIdInput != null) {
            providerIdInput.setText(suggestedProviderId);
        }
        String keyLookupProvider = providerForStorage;
        if (TextUtils.isEmpty(keyLookupProvider)) {
            keyLookupProvider = provider;
        }

        baseUrlInput.setText(savedBaseUrl);
        if (!useCustomEndpoint) {
            baseUrlInput.setText("");
        }
        if (providerIdInput != null && !useCustomEndpoint) {
            providerIdInput.setText("");
        }
        apiKeyInput.setHint(hasExistingKey ? "Leave empty to keep current key" : "Enter API key");
        apiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKeyInput.setTextColor(getContext().getColor(R.color.botdrop_on_background));
        apiKeyInput.setText("");

        if (!TextUtils.isEmpty(currentProviderKey)) {
            cacheApiKey(keyLookupProvider, currentProviderKey);
        }

        renderCachedKeys(keyLookupProvider, apiKeyInput, cachedKeysContainer, cachedTitle);

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(getContext())
            .setView(content)
            .create();

        ImageButton closeButton = content.findViewById(R.id.change_model_close_button);
        Button cancelButton = content.findViewById(R.id.change_model_cancel_button);
        Button confirmButton = content.findViewById(R.id.change_model_confirm_button);
        if (confirmButton != null) {
            confirmButton.setText(useCustomEndpoint ? "Fetch Models" : "Continue");
        }

        final boolean[] confirmed = {false};
        Runnable submitProviderCredentials = () -> {
            String newApiKey = apiKeyInput.getText().toString().trim();
            String newBaseUrl = baseUrlInput.getText().toString().trim();
            String requestedProvider = provider;
            String requestedProviderFromInput = null;
            if (isCustomProvider(provider) && providerIdInput != null) {
                requestedProviderFromInput = providerIdInput.getText().toString().trim();
                requestedProvider = requestedProviderFromInput;
            }
            String resolvedProvider = sanitizeProviderIdentifier(requestedProvider);

            String effectiveApiKey = newApiKey;
            if (TextUtils.isEmpty(effectiveApiKey)) {
                effectiveApiKey = currentProviderKey;
            }

            String effectiveBaseUrl = null;
            if (useCustomEndpoint) {
                effectiveBaseUrl = newBaseUrl;
                if (TextUtils.isEmpty(effectiveBaseUrl)) {
                    effectiveBaseUrl = savedBaseUrl;
                }
                if (TextUtils.isEmpty(effectiveBaseUrl)) {
                    baseUrlInput.setError("Base URL is required for this provider");
                    return;
                }

            if (TextUtils.isEmpty(resolvedProvider)) {
                resolvedProvider = sanitizeProviderIdentifier(deriveProviderIdFromUrl(effectiveBaseUrl));
            }
            if (TextUtils.isEmpty(resolvedProvider) && !TextUtils.isEmpty(requestedProviderFromInput)) {
                resolvedProvider = sanitizeProviderIdentifier(requestedProviderFromInput);
            }

                if (TextUtils.isEmpty(resolvedProvider)) {
                    if (providerIdInput != null) {
                        providerIdInput.setError("Provider ID is required");
                    }
                    return;
                }

                if (isCustomProvider(provider) && isDuplicateProviderId(resolvedProvider)) {
                    if (providerIdInput != null) {
                        providerIdInput.setError("This provider name already exists");
                    }
                    return;
                }

                providerIdInput.setText(resolvedProvider);
                if (isCustomProvider(provider) && !TextUtils.equals(requestedProviderFromInput, resolvedProvider) &&
                    !TextUtils.isEmpty(resolvedProvider)) {
                    requestedProvider = resolvedProvider;
                }
            }

            if (TextUtils.isEmpty(effectiveApiKey)) {
                String cachedKeyForResolvedProvider = BotDropConfig.getApiKey(resolvedProvider);
                if (!TextUtils.isEmpty(cachedKeyForResolvedProvider)) {
                    effectiveApiKey = cachedKeyForResolvedProvider;
                }
            }
            if (TextUtils.isEmpty(effectiveApiKey)) {
                apiKeyInput.setError("API key is required for this provider");
                return;
            }

            if (!TextUtils.isEmpty(newApiKey)) {
                cacheApiKey(resolvedProvider, newApiKey);
            }

            setPendingCredentials(resolvedProvider, effectiveApiKey, effectiveBaseUrl, null);
            confirmed[0] = true;
            dialog.dismiss();

            if (useCustomEndpoint) {
                loadCustomModels(resolvedProvider, effectiveBaseUrl, effectiveApiKey);
            } else {
                showModelSelection(provider);
            }
        };
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> dialog.dismiss());
        }
        if (cancelButton != null) {
            cancelButton.setOnClickListener(v -> dialog.dismiss());
        }
        if (confirmButton != null) {
            confirmButton.setOnClickListener(v -> submitProviderCredentials.run());
        }
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.setOnDismissListener(d -> {
            if (confirmed[0]) {
                return;
            }
            clearPendingCredentials();
        });
        dialog.show();
    }

    private void loadCustomModels(String provider, String baseUrl, String apiKey) {
        if (TextUtils.isEmpty(provider) || TextUtils.isEmpty(baseUrl) || TextUtils.isEmpty(apiKey)) {
            showError("Base URL and API key are required for custom providers.");
            return;
        }

        showLoading();
        if (mStatusText != null) {
            mStatusText.setText("Loading models from custom URL...");
        }

        new Thread(() -> {
            List<ModelInfo> models = fetchModelsFromCustomUrl(provider, baseUrl, apiKey);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!isShowing()) {
                    return;
                }

                if (TextUtils.isEmpty(mPendingProvider) || !TextUtils.equals(mPendingProvider, provider)) {
                    return;
                }

                if (models == null || models.isEmpty()) {
                    showError("No models returned by custom provider URL.");
                    clearPendingCredentials();
                    return;
                }

                Collections.sort(models,
                    (a, b) -> {
                        if (a == null || b == null || a.fullName == null || b.fullName == null) {
                            return 0;
                        }
                        return b.fullName.compareToIgnoreCase(a.fullName);
                    }
                );
                mPendingAvailableModels = extractAvailableModelIds(models);
                showModelSelection(provider, models);
            });
        }).start();
    }

    private List<ModelInfo> fetchModelsFromCustomUrl(String provider, String baseUrl, String apiKey) {
        List<ModelInfo> result = new ArrayList<>();
        String endpoint = buildCustomModelsEndpoint(baseUrl);
        if (TextUtils.isEmpty(endpoint) || TextUtils.isEmpty(apiKey)) {
            return result;
        }

        HttpURLConnection conn = null;
        InputStream stream = null;
        try {
            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(MODEL_REQUEST_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(MODEL_REQUEST_READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);

            int responseCode = conn.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                Logger.logWarn(LOG_TAG, "Custom model request failed: HTTP " + responseCode + " (" + endpoint + ")");
                return result;
            }

            stream = conn.getInputStream();
            String body = readStreamAsUtf8(stream);
            if (TextUtils.isEmpty(body)) {
                return result;
            }

            List<String> modelIds = parseModelIdsFromResponse(body);
            List<String> deduped = new ArrayList<>();
            for (String modelId : modelIds) {
                String normalized = normalizeCustomModelId(provider, modelId);
                if (TextUtils.isEmpty(normalized) || deduped.contains(normalized)) {
                    continue;
                }
                deduped.add(normalized);
            }

            for (String modelId : deduped) {
                result.add(new ModelInfo(provider + "/" + modelId, provider, modelId));
            }
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to fetch models from custom URL: " + e.getMessage());
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                }
            }
            if (conn != null) {
                conn.disconnect();
            }
        }

        return result;
    }

    private String buildCustomModelsEndpoint(String baseUrl) {
        if (TextUtils.isEmpty(baseUrl)) {
            return "";
        }
        String normalized = baseUrl.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith(MODELS_PATH_SUFFIX)) {
            return normalized;
        }
        if (TextUtils.isEmpty(normalized) || (!normalized.startsWith("http://") && !normalized.startsWith("https://"))) {
            return "";
        }
        return normalized + MODELS_PATH_SUFFIX;
    }

    private List<String> parseModelIdsFromResponse(String response) {
        List<String> modelIds = new ArrayList<>();
        if (TextUtils.isEmpty(response)) {
            return modelIds;
        }

        String trimmed = response.trim();
        try {
            if (trimmed.startsWith("{")) {
                JSONObject root = new JSONObject(trimmed);

                JSONArray data = root.optJSONArray("data");
                if (data != null) {
                    extractModelIdsFromArray(data, modelIds);
                }

                JSONArray models = root.optJSONArray("models");
                if (models != null) {
                    extractModelIdsFromArray(models, modelIds);
                }

                if (modelIds.isEmpty()) {
                    String directData = root.optString("data", "");
                    if (!TextUtils.isEmpty(directData)) {
                        modelIds.add(directData);
                    } else {
                        String directModels = root.optString("models", "");
                        if (!TextUtils.isEmpty(directModels)) {
                            modelIds.add(directModels);
                        }
                    }
                }

                if (modelIds.isEmpty()) {
                    String textFallback = root.toString();
                    if (!TextUtils.isEmpty(textFallback)) {
                        modelIds.addAll(parseModelIdsFromText(textFallback));
                    }
                }
            } else if (trimmed.startsWith("[")) {
                extractModelIdsFromArray(new JSONArray(trimmed), modelIds);
                if (modelIds.isEmpty()) {
                    modelIds.addAll(parseModelIdsFromText(trimmed));
                }
            } else {
                modelIds.addAll(parseModelIdsFromText(response));
            }
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to parse custom models response, fallback to plain text: " + e.getMessage());
            modelIds.addAll(parseModelIdsFromText(response));
        }

        return modelIds;
    }

    private void extractModelIdsFromArray(JSONArray array, List<String> modelIds) {
        if (array == null || modelIds == null) {
            return;
        }

        for (int i = 0; i < array.length(); i++) {
            Object entry = array.opt(i);
            if (entry == null) {
                continue;
            }
            if (entry instanceof String) {
                String value = ((String) entry).trim();
                if (!TextUtils.isEmpty(value)) {
                    modelIds.add(value);
                }
                continue;
            }
            if (!(entry instanceof JSONObject)) {
                continue;
            }
            JSONObject obj = (JSONObject) entry;
            String id = obj.optString("id", "").trim();
            if (TextUtils.isEmpty(id)) {
                id = obj.optString("model", "").trim();
            }
            if (TextUtils.isEmpty(id)) {
                id = obj.optString("name", "").trim();
            }
            if (!TextUtils.isEmpty(id)) {
                modelIds.add(id);
            }
        }
    }

    private List<String> parseModelIdsFromText(String response) {
        List<String> modelIds = new ArrayList<>();
        if (TextUtils.isEmpty(response)) {
            return modelIds;
        }

        String[] lines = response.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                continue;
            }
            if (trimmed.startsWith("#") || trimmed.startsWith("Model ")) {
                continue;
            }
            String token = trimmed;
            if (trimmed.contains(" ")) {
                token = trimmed.split("\\s+")[0];
            }
            if (token.startsWith("\"") && token.endsWith("\"") && token.length() > 1) {
                token = token.substring(1, token.length() - 1);
            }
            if (!TextUtils.isEmpty(token)) {
                modelIds.add(token);
            }
        }

        return modelIds;
    }

    private String normalizeCustomModelId(String provider, String modelId) {
        if (TextUtils.isEmpty(provider) || TextUtils.isEmpty(modelId)) {
            return "";
        }
        String normalized = modelId.trim();
        String prefix = provider + "/";
        if (normalized.startsWith(prefix)) {
            normalized = normalized.substring(prefix.length());
        }
        return normalized;
    }

    private List<String> getPendingAvailableModels() {
        if (mPendingAvailableModels == null || mPendingAvailableModels.isEmpty()) {
            return null;
        }
        return new ArrayList<>(mPendingAvailableModels);
    }

    private List<String> extractAvailableModelIds(List<ModelInfo> models) {
        List<String> modelIds = new ArrayList<>();
        if (models == null) {
            return modelIds;
        }
        for (ModelInfo model : models) {
            if (model == null || TextUtils.isEmpty(model.model)) {
                continue;
            }
            if (!modelIds.contains(model.model)) {
                modelIds.add(model.model);
            }
        }
        return modelIds;
    }

    private String readStreamAsUtf8(InputStream stream) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }

    private void setPendingCredentials(String provider, String apiKey, String baseUrl, List<String> availableModels) {
        mPendingProvider = provider;
        mPendingApiKey = apiKey;
        mPendingBaseUrl = baseUrl;
        mPendingAvailableModels = availableModels == null ? null : new ArrayList<>(availableModels);
    }

    private void clearPendingCredentials() {
        mPendingProvider = null;
        mPendingApiKey = null;
        mPendingBaseUrl = null;
        mPendingAvailableModels = null;
    }

    private void renderCachedKeys(String provider, EditText apiKeyInput, LinearLayout container, TextView titleText) {
        if (container == null || TextUtils.isEmpty(provider) || getContext() == null) {
            return;
        }

        List<String> cachedKeys = loadCachedApiKeys(provider);
        container.removeAllViews();
        if (titleText != null) {
            titleText.setVisibility(View.GONE);
        }
        if (cachedKeys.isEmpty()) {
            return;
        }
        if (titleText != null) {
            titleText.setVisibility(View.VISIBLE);
            titleText.setText("Cached keys");
        }

        for (String key : cachedKeys) {
            if (TextUtils.isEmpty(key)) {
                continue;
            }

            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.botdrop_input_bg);
            row.setPadding(
                (int) (12 * getContext().getResources().getDisplayMetrics().density),
                (int) (10 * getContext().getResources().getDisplayMetrics().density),
                (int) (12 * getContext().getResources().getDisplayMetrics().density),
                (int) (10 * getContext().getResources().getDisplayMetrics().density)
            );
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            rowLp.setMargins(0, 0, 0, (int) (10 * getContext().getResources().getDisplayMetrics().density));
            row.setLayoutParams(rowLp);

            TextView keyText = new TextView(getContext());
            keyText.setText(maskApiKey(key));
            keyText.setTextColor(getContext().getColor(R.color.botdrop_on_background));
            keyText.setTextSize(12f);
            keyText.setLayoutParams(new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ));
            keyText.setMaxLines(1);
            keyText.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(keyText);

            TextView useAction = new TextView(getContext());
            useAction.setText("Use");
            useAction.setTextSize(12f);
            useAction.setTextColor(getContext().getColor(R.color.botdrop_accent));
            useAction.setTypeface(useAction.getTypeface(), android.graphics.Typeface.BOLD);
            useAction.setPadding((int) (12 * getContext().getResources().getDisplayMetrics().density),
                (int) (4 * getContext().getResources().getDisplayMetrics().density),
                (int) (12 * getContext().getResources().getDisplayMetrics().density),
                (int) (4 * getContext().getResources().getDisplayMetrics().density));
            useAction.setOnClickListener(v -> {
                apiKeyInput.setText(key);
                apiKeyInput.setSelection(key.length());
                apiKeyInput.setError(null);
            });

            ImageButton deleteAction = new ImageButton(getContext());
            deleteAction.setImageResource(android.R.drawable.ic_menu_delete);
            deleteAction.setContentDescription("Delete cached key");
            deleteAction.setColorFilter(getContext().getColor(R.color.status_disconnected));
            deleteAction.setBackgroundResource(android.R.color.transparent);
            int iconSize = (int) (22 * getContext().getResources().getDisplayMetrics().density);
            LinearLayout.LayoutParams deleteLp = new LinearLayout.LayoutParams(iconSize, iconSize);
            deleteLp.setMarginStart((int) (4 * getContext().getResources().getDisplayMetrics().density));
            deleteAction.setLayoutParams(deleteLp);
            deleteAction.setOnClickListener(v -> {
                removeCachedApiKey(provider, key);
                renderCachedKeys(provider, apiKeyInput, container, titleText);
            });
            row.addView(deleteAction);

            View spacer = new View(getContext());
            spacer.setLayoutParams(new LinearLayout.LayoutParams(
                0,
                0,
                1f
            ));
            row.addView(spacer);
            row.addView(useAction);

            LinearLayout.LayoutParams useLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            useAction.setLayoutParams(useLp);

            container.addView(row);
        }
    }

    private String maskApiKey(String key) {
        if (TextUtils.isEmpty(key)) return "••••";
        String trimmed = key.trim();
        if (trimmed.length() <= 8) {
            return "••••••••";
        }
        return "•••• •••• " + trimmed.substring(trimmed.length() - 4);
    }

    private void cacheApiKey(String provider, String key) {
        if (TextUtils.isEmpty(provider) || TextUtils.isEmpty(key) || getContext() == null) {
            return;
        }

        try {
            List<String> existing = loadCachedApiKeys(provider);
            String normalized = key.trim();
            if (TextUtils.isEmpty(normalized)) return;

            existing.remove(normalized);
            existing.add(0, normalized);
            while (existing.size() > MAX_CACHED_KEYS_PER_MODEL) {
                existing.remove(existing.size() - 1);
            }

            JSONArray list = new JSONArray();
            for (String item : existing) {
                if (!TextUtils.isEmpty(item)) {
                    list.put(item);
                }
            }
            getContext().getSharedPreferences(KEY_CACHE_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(keyCacheKey(provider), list.toString())
                .apply();
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to cache API key: " + e.getMessage());
        }
    }

    private void removeCachedApiKey(String provider, String key) {
        if (TextUtils.isEmpty(provider) || TextUtils.isEmpty(key) || getContext() == null) {
            return;
        }

        try {
            List<String> existing = loadCachedApiKeys(provider);
            if (existing.remove(key)) {
                JSONArray list = new JSONArray();
                for (String item : existing) {
                    if (!TextUtils.isEmpty(item)) {
                        list.put(item);
                    }
                }
                getContext().getSharedPreferences(KEY_CACHE_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(keyCacheKey(provider), list.toString())
                    .apply();
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to remove cached API key: " + e.getMessage());
        }
    }

    private List<String> loadCachedApiKeys(String provider) {
        List<String> keys = new ArrayList<>();
        if (TextUtils.isEmpty(provider) || getContext() == null) {
            return keys;
        }

        try {
            SharedPreferences prefs = getContext().getSharedPreferences(KEY_CACHE_PREFS_NAME, Context.MODE_PRIVATE);
            String raw = prefs.getString(keyCacheKey(provider), null);
            if (!TextUtils.isEmpty(raw)) {
                JSONArray list = new JSONArray(raw);
                for (int i = 0; i < list.length(); i++) {
                    String item = list.optString(i, "").trim();
                    if (!TextUtils.isEmpty(item) && !keys.contains(item)) {
                        keys.add(item);
                    }
                }
            }

            List<String> legacyKeys = loadLegacyCachedApiKeys(prefs, provider);
            for (String item : legacyKeys) {
                if (!TextUtils.isEmpty(item) && !keys.contains(item)) {
                    keys.add(item);
                }
            }

            if (keys.size() > MAX_CACHED_KEYS_PER_MODEL) {
                keys = new ArrayList<>(keys.subList(0, MAX_CACHED_KEYS_PER_MODEL));
            }

            if (!TextUtils.isEmpty(raw) || !legacyKeys.isEmpty()) {
                JSONArray merged = new JSONArray();
                for (String item : keys) {
                    if (!TextUtils.isEmpty(item)) {
                        merged.put(item);
                    }
                }
                SharedPreferences.Editor editor = prefs.edit().putString(keyCacheKey(provider), merged.toString());

                if (!legacyKeys.isEmpty()) {
                    cleanupLegacyCachedApiKeys(editor, provider);
                }
                editor.apply();
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to load cached API keys: " + e.getMessage());
        }

        return keys;
    }

    private List<String> loadLegacyCachedApiKeys(SharedPreferences prefs, String provider) {
        List<String> keys = new ArrayList<>();
        if (prefs == null || TextUtils.isEmpty(provider)) {
            return keys;
        }

        String normalizedProvider = normalizeCacheSegment(provider) + "_";
        String legacyPrefix = KEY_CACHE_PREFIX_LEGACY + normalizedProvider;
        try {
            for (String key : prefs.getAll().keySet()) {
                if (!key.startsWith(legacyPrefix)) {
                    continue;
                }

                Object value = prefs.getAll().get(key);
                if (!(value instanceof String)) {
                    continue;
                }

                JSONArray list = new JSONArray((String) value);
                for (int i = 0; i < list.length(); i++) {
                    String item = list.optString(i, "").trim();
                    if (!TextUtils.isEmpty(item) && !keys.contains(item)) {
                        keys.add(item);
                    }
                }
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to load legacy cached API keys: " + e.getMessage());
        }
        return keys;
    }

    private void cleanupLegacyCachedApiKeys(SharedPreferences.Editor editor, String provider) {
        if (editor == null || TextUtils.isEmpty(provider)) {
            return;
        }

        String normalizedProvider = normalizeCacheSegment(provider) + "_";
        String legacyPrefix = KEY_CACHE_PREFIX_LEGACY + normalizedProvider;
        try {
            for (String key : getContext().getSharedPreferences(KEY_CACHE_PREFS_NAME, Context.MODE_PRIVATE).getAll().keySet()) {
                if (key.startsWith(legacyPrefix)) {
                    editor.remove(key);
                }
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to cleanup legacy cached API keys: " + e.getMessage());
        }
    }

    private static String keyCacheKey(String provider) {
        return KEY_CACHE_PREFIX + normalizeCacheSegment(provider);
    }

    private static String normalizeCacheSegment(String value) {
        if (TextUtils.isEmpty(value)) {
            return "unknown";
        }
        return value.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String deriveProviderIdFromUrl(String baseUrl) {
        if (TextUtils.isEmpty(baseUrl)) {
            return "";
        }

        try {
            URL url = new URL(baseUrl.trim());
            String host = url.getHost();
            if (TextUtils.isEmpty(host)) {
                return "";
            }

            host = host.toLowerCase(Locale.ROOT);
            if (host.startsWith("api.")) {
                host = host.substring("api.".length());
            }
            String[] parts = host.split("\\.");
            if (parts.length == 0) {
                return "";
            }

            if (parts.length >= 2 && TextUtils.equals(parts[0], "api")) {
                return parts[1];
            }
            return parts[0];
        } catch (Exception ignored) {
            return "";
        }
    }

    private String sanitizeProviderIdentifier(String providerId) {
        if (TextUtils.isEmpty(providerId)) {
            return "";
        }
        String normalized = providerId.trim().toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^a-z0-9._-]", "-");
        normalized = normalized.replaceAll("-+", "-");
        normalized = normalized.replaceAll("^-+|-+$", "");
        return normalized;
    }

    private void showProviderSelection() {
        clearPendingCredentials();
        mSelectingProvider = true;
        mCurrentProvider = null;
        mCurrentItems = new ArrayList<>();

        List<String> providers = new ArrayList<>();
        for (ModelInfo model : mAllModels) {
            if (model != null && !TextUtils.isEmpty(model.provider) && !providers.contains(model.provider)) {
                providers.add(model.provider);
            }
        }

        if (mPromptForApiKey) {
            mCurrentItems.add(new ModelInfo(CUSTOM_PROVIDER_DISPLAY_NAME, CUSTOM_PROVIDER_ID, ""));

            List<String> customProviders = BotDropConfig.getConfiguredCustomProviders();
            for (String customProvider : customProviders) {
                if (TextUtils.isEmpty(customProvider)
                    || TextUtils.equals(customProvider, CUSTOM_PROVIDER_ID)
                    || providers.contains(customProvider)) {
                    continue;
                }

                providers.add(customProvider);
            }
        }

        Collections.sort(providers, String::compareToIgnoreCase);

        List<String> keyedProviders = new ArrayList<>();
        List<String> unkeyedProviders = new ArrayList<>();
        for (String provider : providers) {
            if (TextUtils.isEmpty(provider) || TextUtils.equals(provider, CUSTOM_PROVIDER_ID)) {
                continue;
            }

            if (BotDropConfig.hasApiKey(provider)) {
                keyedProviders.add(provider);
            } else {
                unkeyedProviders.add(provider);
            }
        }

        for (String provider : keyedProviders) {
            mCurrentItems.add(new ModelInfo(provider, provider, ""));
        }
        for (String provider : unkeyedProviders) {
            mCurrentItems.add(new ModelInfo(provider, provider, ""));
        }

        mSearchBox.setText("");
        mSearchBox.setHint("Search provider...");
        mBackButton.setVisibility(View.GONE);
        if (mStepHint != null) {
            mStepHint.setText("Step 1 of 2");
        }
        mTitleText.setText("Select Provider");

        mAdapter.updateList(mCurrentItems);
        if (mCurrentItems.isEmpty()) {
            showError("No provider available.");
            return;
        }
        showList();
    }

    private void showModelSelection(String provider) {
        showModelSelection(provider, null);
    }

    private boolean isCustomProvider(String provider) {
        return TextUtils.equals(provider, CUSTOM_PROVIDER_ID);
    }

    private boolean isDuplicateProviderId(String providerId) {
        String normalizedProvider = sanitizeProviderIdentifier(providerId);
        if (TextUtils.isEmpty(normalizedProvider)) {
            return false;
        }

        if (TextUtils.equals(normalizedProvider, CUSTOM_PROVIDER_ID)) {
            return true;
        }

        for (ModelInfo model : mAllModels) {
            if (model == null || TextUtils.isEmpty(model.provider)) {
                continue;
            }
            String normalizedExisting = sanitizeProviderIdentifier(model.provider);
            if (!TextUtils.isEmpty(normalizedExisting) && TextUtils.equals(normalizedExisting, normalizedProvider)) {
                return true;
            }
        }

        for (String configuredProvider : BotDropConfig.getConfiguredCustomProviders()) {
            String normalizedExisting = sanitizeProviderIdentifier(configuredProvider);
            if (!TextUtils.isEmpty(normalizedExisting) && TextUtils.equals(normalizedExisting, normalizedProvider)) {
                return true;
            }
        }

        return false;
    }

    private void showModelSelection(String provider, List<ModelInfo> modelsForProvider) {
        if (TextUtils.isEmpty(provider)) {
            showProviderSelection();
            return;
        }

        mSelectingProvider = false;
        mCurrentProvider = provider;

        List<ModelInfo> models = new ArrayList<>();
        if (modelsForProvider != null) {
            for (ModelInfo model : modelsForProvider) {
                if (model == null || TextUtils.isEmpty(model.model)) {
                    continue;
                }
                if (TextUtils.isEmpty(model.provider)) {
                    models.add(new ModelInfo(provider + "/" + model.model, provider, model.model));
                } else {
                    models.add(new ModelInfo(model.fullName, provider, model.model));
                }
            }
        } else {
            for (ModelInfo model : mAllModels) {
                if (model != null && TextUtils.equals(provider, model.provider)) {
                    models.add(model);
                }
            }
        }

        Collections.sort(models, Comparator.comparing((ModelInfo m) -> m.fullName == null ? "" : m.fullName, String::compareToIgnoreCase).reversed());

        mCurrentItems = new ArrayList<>(models);
        mSearchBox.setText("");
        mSearchBox.setHint("Search model...");
        mBackButton.setVisibility(View.VISIBLE);
        if (mStepHint != null) {
            mStepHint.setText("Step 2 of 2");
        }
        String providerName = isCustomProvider(provider) ? CUSTOM_PROVIDER_DISPLAY_NAME : provider;
        mTitleText.setText(providerName + " models");

        mAdapter.updateList(mCurrentItems);
        if (mCurrentItems.isEmpty()) {
            showError("No models available for " + providerName);
            return;
        }
        showList();
    }

    private List<ModelInfo> readModelsFromAsset() {
        List<ModelInfo> models = new ArrayList<>();

        try (InputStream is = getContext().getAssets().open(STATIC_MODELS_ASSET);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String key = line.trim();
                if (isModelToken(key)) {
                    models.add(new ModelInfo(key));
                }
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to read static model catalog: " + e.getMessage());
        }

        Logger.logInfo(LOG_TAG, "Static catalog loaded: " + models.size() + " models");
        return models;
    }

    private List<ModelInfo> loadCachedModels(String version) {
        List<ModelInfo> models = new ArrayList<>();
        if (TextUtils.isEmpty(version)) {
            return models;
        }

        try {
            SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String raw = prefs.getString(cacheKey(version), null);
            if (TextUtils.isEmpty(raw)) return models;

            JSONObject root = new JSONObject(raw);
            String cachedVersion = root.optString("version", "");
            if (!TextUtils.equals(cachedVersion, version)) return models;

            JSONArray list = root.optJSONArray("models");
            if (list == null || list.length() == 0) return models;

            for (int i = 0; i < list.length(); i++) {
                String modelName = list.optString(i, "");
                if (isModelToken(modelName)) {
                    models.add(new ModelInfo(modelName));
                }
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to read cached model list: " + e.getMessage());
        }

        return models;
    }

    private void cacheModels(String version, List<ModelInfo> models) {
        if (TextUtils.isEmpty(version) || models == null || models.isEmpty()) return;

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
            Logger.logError(LOG_TAG, "Failed to cache model list: " + e.getMessage());
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

    private void filterModels(String query) {
        if (query == null || query.isEmpty()) {
            mAdapter.updateList(mCurrentItems);
            return;
        }

        String lower = query.toLowerCase();
        List<ModelInfo> filtered = mCurrentItems.stream()
            .filter(m -> {
                if (mSelectingProvider) {
                    return !TextUtils.isEmpty(m.provider) && m.provider.toLowerCase().contains(lower);
                }

                return (!TextUtils.isEmpty(m.fullName) && m.fullName.toLowerCase().contains(lower))
                    || (!TextUtils.isEmpty(m.model) && m.model.toLowerCase().contains(lower));
            })
            .collect(Collectors.toList());

        mAdapter.updateList(filtered);
    }

    private void showLoading() {
        mModelList.setVisibility(View.GONE);
        mRetryButton.setVisibility(View.GONE);
        mStatusText.setVisibility(View.VISIBLE);
        mStatusText.setText("Loading models...");
    }

    private void showError(String message) {
        mModelList.setVisibility(View.GONE);
        mRetryButton.setVisibility(View.VISIBLE);
        mStatusText.setVisibility(View.VISIBLE);
        mStatusText.setText(message);
    }

    private void showList() {
        mModelList.setVisibility(View.VISIBLE);
        mRetryButton.setVisibility(View.GONE);
        mStatusText.setVisibility(View.GONE);
    }
}
