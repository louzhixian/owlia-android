package app.botdrop;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;
import com.termux.shared.logger.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Dialog for selecting a model with search capability.
 * Fetches model list from openclaw models list command.
 */
public class ModelSelectorDialog extends Dialog {

    private static final String LOG_TAG = "ModelSelectorDialog";

    private BotDropService mService;
    private ModelSelectedCallback mCallback;

    private EditText mSearchBox;
    private RecyclerView mModelList;
    private TextView mStatusText;
    private Button mRetryButton;

    private ModelListAdapter mAdapter;
    private List<ModelInfo> mAllModels = new ArrayList<>();

    public interface ModelSelectedCallback {
        void onModelSelected(String provider, String model);
    }

    public ModelSelectorDialog(@NonNull Context context, BotDropService service) {
        super(context);
        this.mService = service;
    }

    public void show(ModelSelectedCallback callback) {
        this.mCallback = callback;
        super.show();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_model_selector);

        // Set dialog to fullscreen
        Window window = getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }

        // Initialize views
        mSearchBox = findViewById(R.id.model_search);
        mModelList = findViewById(R.id.model_list);
        mStatusText = findViewById(R.id.model_status);
        mRetryButton = findViewById(R.id.model_retry);
        ImageButton closeButton = findViewById(R.id.model_close_button);

        // Close button
        closeButton.setOnClickListener(v -> {
            if (mCallback != null) {
                mCallback.onModelSelected(null, null); // User cancelled
            }
            dismiss();
        });

        // Setup RecyclerView
        mAdapter = new ModelListAdapter(model -> {
            if (mCallback != null) {
                mCallback.onModelSelected(model.provider, model.model);
            }
            dismiss();
        });
        mModelList.setLayoutManager(new LinearLayoutManager(getContext()));
        mModelList.setAdapter(mAdapter);

        // Setup search
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

        // Setup retry button
        mRetryButton.setOnClickListener(v -> loadModels());

        // Load models
        loadModels();
    }

    private void loadModels() {
        showLoading();

        if (mService == null) {
            showError("Service not available");
            return;
        }

        // Execute openclaw models list command
        mService.executeCommand("termux-chroot openclaw models list", result -> {
            if (!result.success) {
                showError("Failed to load models. Please try again.");
                return;
            }

            // Parse models
            List<ModelInfo> models = parseModelList(result.stdout);

            if (models.isEmpty()) {
                showError("No models available.");
                return;
            }

            // Update UI
            mAllModels = models;
            mAdapter.updateList(models);
            showList();
        });
    }

    /**
     * Parse model list output from openclaw models list.
     * Format: "Model                                      Input      Ctx      Local Auth  Tags"
     *         "google/gemini-3-flash-preview              text+image 1024k    no    yes   default"
     */
    private List<ModelInfo> parseModelList(String output) {
        List<ModelInfo> models = new ArrayList<>();

        try {
            String[] lines = output.split("\n");

            for (String line : lines) {
                // Skip header and empty lines
                if (line.trim().isEmpty() || line.startsWith("Model ")) {
                    continue;
                }

                // Extract model name (first column before whitespace)
                String[] parts = line.trim().split("\\s+");
                if (parts.length > 0) {
                    String modelName = parts[0];
                    if (modelName.contains("/")) {
                        ModelInfo info = new ModelInfo(modelName);

                        // Parse additional fields if available
                        if (parts.length > 1) info.input = parts[1];
                        if (parts.length > 2) info.context = parts[2];

                        // Check for default tag
                        info.isDefault = line.contains("default");

                        models.add(info);
                    }
                }
            }

            Logger.logInfo(LOG_TAG, "Parsed " + models.size() + " models");

        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to parse model list: " + e.getMessage());
        }

        return models;
    }

    /**
     * Filter models based on search query.
     */
    private void filterModels(String query) {
        if (query == null || query.isEmpty()) {
            mAdapter.updateList(mAllModels);
            return;
        }

        String lowerQuery = query.toLowerCase();
        List<ModelInfo> filtered = mAllModels.stream()
            .filter(m -> m.fullName.toLowerCase().contains(lowerQuery))
            .collect(Collectors.toList());

        mAdapter.updateList(filtered);
    }

    /**
     * Show loading state.
     */
    private void showLoading() {
        mModelList.setVisibility(View.GONE);
        mRetryButton.setVisibility(View.GONE);
        mStatusText.setVisibility(View.VISIBLE);
        mStatusText.setText("Loading models...");
    }

    /**
     * Show error state.
     */
    private void showError(String message) {
        mModelList.setVisibility(View.GONE);
        mRetryButton.setVisibility(View.VISIBLE);
        mStatusText.setVisibility(View.VISIBLE);
        mStatusText.setText(message);
    }

    /**
     * Show list state.
     */
    private void showList() {
        mModelList.setVisibility(View.VISIBLE);
        mRetryButton.setVisibility(View.GONE);
        mStatusText.setVisibility(View.GONE);
    }
}
