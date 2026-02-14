package app.botdrop;

import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.logger.Logger;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

import com.termux.shared.termux.TermuxConstants;

/**
 * Setup wizard with 4 steps:
 * Step 0 (STEP_AGENT_SELECT): Agent Selection
 * Step 1 (STEP_INSTALL): Install openclaw
 * Step 2 (STEP_API_KEY): Choose AI + API Key
 * Step 3 (STEP_CHANNEL): Telegram Config
 */

public class SetupActivity extends AppCompatActivity {

    private static final String LOG_TAG = "SetupActivity";
    private static final String BOTDROP_UPDATE_URL = "https://botdrop.app/";
    private static final int OPENCLAW_STORAGE_PERMISSION_REQUEST_CODE = 3002;
    private static final String OPENCLAW_BACKUP_DIRECTORY = "BotDrop/openclaw";
    private static final String OPENCLAW_BACKUP_FILE_PREFIX = "openclaw-config-backup-";
    private static final String OPENCLAW_BACKUP_FILE_EXTENSION = ".json";
    private static final String OPENCLAW_BACKUP_META_OPENCLAW_CONFIG_KEY = "openclawConfig";
    private static final String OPENCLAW_BACKUP_META_AUTH_PROFILES_KEY = "authProfiles";
    private static final String OPENCLAW_CONFIG_FILE = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw/openclaw.json";
    private static final String OPENCLAW_AUTH_PROFILES_FILE =
        TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw/agents/main/agent/auth-profiles.json";

    /**
     * Interface for fragments to intercept Next button behavior
     */
    public interface StepFragment {
        /**
         * Called when Next is clicked. Return true to handle it internally.
         */
        boolean handleNext();
    }

    // Step constants (Agent selection first, then install)
    public static final int STEP_AGENT_SELECT = 0;  // Step 1: Agent Selection
    public static final int STEP_INSTALL = 1;       // Step 2: Install openclaw
    public static final int STEP_API_KEY = 2;       // Step 3: Choose AI + API Key
    public static final int STEP_CHANNEL = 3;       // Step 4: Telegram config
    private static final int STEP_COUNT = 4;

    // Intent extra for starting at specific step
    public static final String EXTRA_START_STEP = "start_step";

    private ViewPager2 mViewPager;
    private SetupPagerAdapter mAdapter;
    private View mNavigationBar;
    private Button mBackButton;
    private Button mNextButton;
    private Runnable mPendingOpenclawStorageAction;
    private Runnable mPendingOpenclawStorageDeniedAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_botdrop_setup);

        mViewPager = findViewById(R.id.setup_viewpager);
        mNavigationBar = findViewById(R.id.setup_navigation);
        mBackButton = findViewById(R.id.setup_button_back);
        mNextButton = findViewById(R.id.setup_button_next);
        
        // Setup Open Terminal button if it exists in layout
        Button openTerminalBtn = findViewById(R.id.setup_open_terminal);
        if (openTerminalBtn != null) {
            openTerminalBtn.setOnClickListener(v -> openTerminal());
        }

        // Set up ViewPager2
        mAdapter = new SetupPagerAdapter(this);
        mViewPager.setAdapter(mAdapter);
        mViewPager.setUserInputEnabled(false); // Disable swipe, only programmatic navigation

        // Start at specified step
        int startStep = getIntent().getIntExtra(EXTRA_START_STEP, STEP_AGENT_SELECT);
        mViewPager.setCurrentItem(startStep, false);

        // Set up navigation buttons (hidden by default, fragments can show if needed)
        mBackButton.setOnClickListener(v -> {
            int current = mViewPager.getCurrentItem();
            if (current > 0) {
                mViewPager.setCurrentItem(current - 1);
            }
        });

        mNextButton.setOnClickListener(v -> {
            // Try to let current fragment handle Next first
            Fragment fragment = getSupportFragmentManager()
                .findFragmentByTag("f" + mViewPager.getCurrentItem());
            if (fragment instanceof StepFragment && ((StepFragment) fragment).handleNext()) {
                return; // Fragment handled it
            }

            // Default: advance to next step
            int current = mViewPager.getCurrentItem();
            if (current < STEP_COUNT - 1) {
                mViewPager.setCurrentItem(current + 1);
            }
        });

        // Setup manual update check button
        Button checkUpdatesBtn = findViewById(R.id.setup_check_updates);
        checkUpdatesBtn.setOnClickListener(v -> {
            v.setEnabled(false);
            UpdateChecker.forceCheck(this, (version, url, notes) -> {
                v.setEnabled(true);
                if (version != null && !version.isEmpty()) {
                    new AlertDialog.Builder(this)
                        .setTitle("Update available")
                        .setMessage("A new version is available: v" + version + "\n\nGo to update page?")
                        .setPositiveButton("Open", (d, w) -> openBotdropUpdatePage())
                        .setNegativeButton("Cancel", null)
                        .show();
                } else {
                    Toast.makeText(this, "No update available", Toast.LENGTH_SHORT).show();
                }
            });
        });

        Logger.logDebug(LOG_TAG, "SetupActivity created, starting at step " + startStep);

    }

    private void openBotdropUpdatePage() {
        try {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(BOTDROP_UPDATE_URL));
            startActivity(browserIntent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No browser app found", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Open terminal activity
     */
    public void openTerminal() {
        Intent intent = new Intent(this, TermuxActivity.class);
        startActivity(intent);
    }

    /**
     * Allow fragments to control navigation bar visibility
     */
    public void setNavigationVisible(boolean visible) {
        mNavigationBar.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * Allow fragments to enable/disable navigation buttons
     */
    public void setBackEnabled(boolean enabled) {
        mBackButton.setEnabled(enabled);
    }

    public void setNextEnabled(boolean enabled) {
        mNextButton.setEnabled(enabled);
    }

    /**
     * Move to next step (called by fragments when they complete)
     */
    public void goToNextStep() {
        int current = mViewPager.getCurrentItem();
        if (current == STEP_INSTALL) {
            runWithOpenclawStoragePermission(
                () -> {
                    File latestBackup = getLatestOpenclawBackupFile();
                    if (latestBackup == null) {
                        continueToNextStep(current);
                        return;
                    }

                    if (!latestBackup.exists()) {
                        continueToNextStep(current);
                        return;
                    }

                    showOpenclawRestoreDialog(() -> continueToNextStep(current), latestBackup);
                },
                () -> continueToNextStep(current)
            );
            return;
        }
        continueToNextStep(current);
    }

    private void continueToNextStep(int current) {
        if (current < STEP_COUNT - 1) {
            mViewPager.setCurrentItem(current + 1, true);
        } else {
            // Last step complete → go to dashboard
            Logger.logInfo(LOG_TAG, "Setup complete");
            Intent intent = new Intent(this, DashboardActivity.class);
            startActivity(intent);
            finish();
        }
    }

    private void showOpenclawRestoreDialog(@NonNull Runnable continueWithoutRestore, @NonNull File backupFile) {
        new AlertDialog.Builder(this)
            .setTitle("Restore OpenClaw config before setup?")
            .setMessage("A saved OpenClaw backup was found. Restore will replace current openclaw.json and auth-profiles.json, then jump to Dashboard. "
                + "Choose Start from scratch to continue normal setup.")
            .setPositiveButton("Restore config", (dialog, which) -> {
                restoreOpenclawConfigAndContinue(backupFile, continueWithoutRestore);
            })
            .setNegativeButton("Start from scratch", (dialog, which) -> continueWithoutRestore.run())
            .setCancelable(false)
            .show();
    }

    private void restoreOpenclawConfigAndContinue(@NonNull File backupFile, @NonNull Runnable continueWithoutRestore) {
        new Thread(() -> {
            boolean restored = restoreOpenclawBackupFile(backupFile);
            runOnUiThread(() -> {
                if (!restored) {
                    Toast.makeText(this, "Failed to restore OpenClaw backup", Toast.LENGTH_SHORT).show();
                    continueWithoutRestore.run();
                    return;
                }

                Toast.makeText(this, "OpenClaw config restored", Toast.LENGTH_SHORT).show();
                ConfigTemplateCache.clearTemplate(this);
                Intent intent = new Intent(this, DashboardActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            });
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode,
        @NonNull String[] permissions,
        @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == OPENCLAW_STORAGE_PERMISSION_REQUEST_CODE) {
            retryPendingOpenclawStorageActionIfPermitted();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OPENCLAW_STORAGE_PERMISSION_REQUEST_CODE) {
            retryPendingOpenclawStorageActionIfPermitted();
        }
    }

    private void runWithOpenclawStoragePermission(@NonNull Runnable action, @NonNull Runnable deniedAction) {
        if (PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermissionIfPathOnPrimaryExternalStorage(
            this,
            getOpenclawBackupDirectory().getAbsolutePath(),
            OPENCLAW_STORAGE_PERMISSION_REQUEST_CODE,
            true
        )) {
            action.run();
            return;
        }
        mPendingOpenclawStorageAction = action;
        mPendingOpenclawStorageDeniedAction = deniedAction;
    }

    private void retryPendingOpenclawStorageActionIfPermitted() {
        Runnable action = mPendingOpenclawStorageAction;
        Runnable deniedAction = mPendingOpenclawStorageDeniedAction;
        mPendingOpenclawStorageAction = null;
        mPendingOpenclawStorageDeniedAction = null;

        if (action == null) {
            return;
        }

        if (!PermissionUtils.checkStoragePermission(this, PermissionUtils.isLegacyExternalStoragePossible(this))) {
            if (deniedAction != null) {
                deniedAction.run();
            }
            return;
        }

        action.run();
    }

    private boolean restoreOpenclawBackupFile(@NonNull File backupFile) {
        JSONObject backupPayload = readJsonFromFile(backupFile);
        if (backupPayload == null) {
            return false;
        }

        JSONObject openclawConfig = backupPayload.optJSONObject(OPENCLAW_BACKUP_META_OPENCLAW_CONFIG_KEY);
        JSONObject authProfiles = backupPayload.optJSONObject(OPENCLAW_BACKUP_META_AUTH_PROFILES_KEY);
        int restoredCount = 0;

        if (openclawConfig != null && writeJsonToFile(new File(OPENCLAW_CONFIG_FILE), openclawConfig)) {
            restoredCount++;
        }
        if (authProfiles != null && writeJsonToFile(new File(OPENCLAW_AUTH_PROFILES_FILE), authProfiles)) {
            restoredCount++;
        }

        return restoredCount > 0;
    }

    @Nullable
    private File getLatestOpenclawBackupFile() {
        File backupDir = getOpenclawBackupDirectory();
        if (!backupDir.exists() || !backupDir.isDirectory()) {
            return null;
        }

        File[] candidates = backupDir.listFiles((dir, name) ->
            name != null
                && name.startsWith(OPENCLAW_BACKUP_FILE_PREFIX)
                && name.endsWith(OPENCLAW_BACKUP_FILE_EXTENSION)
        );

        if (candidates == null || candidates.length == 0) {
            return null;
        }

        Arrays.sort(candidates, Comparator.comparingLong(File::lastModified));
        return candidates[candidates.length - 1];
    }

    private boolean writeJsonToFile(@NonNull File file, @NonNull JSONObject payload) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Logger.logWarn(LOG_TAG, "Failed to create parent directory: " + parent.getAbsolutePath());
                return false;
            }

            try (FileWriter writer = new FileWriter(file)) {
                writer.write(payload.toString(2));
            }

            file.setReadable(false, false);
            file.setReadable(true, true);
            file.setWritable(false, false);
            file.setWritable(true, true);
            return true;
        } catch (IOException | JSONException e) {
            Logger.logWarn(LOG_TAG, "Failed to write restored OpenClaw file to " + file.getAbsolutePath() + ": " + e.getMessage());
            return false;
        }
    }

    private File getOpenclawBackupDirectory() {
        File documentsDir = Environment.getExternalStorageDirectory();
        return new File(documentsDir, OPENCLAW_BACKUP_DIRECTORY);
    }

    @Nullable
    private JSONObject readJsonFromFile(@NonNull File file) {
        if (!file.exists()) {
            return null;
        }

        try (FileReader reader = new FileReader(file)) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, read);
            }
            return new JSONObject(sb.toString());
        } catch (IOException | org.json.JSONException e) {
            Logger.logWarn(LOG_TAG, "Failed to read JSON backup from " + file.getAbsolutePath() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * ViewPager2 adapter for setup steps
     */
    private static class SetupPagerAdapter extends FragmentStateAdapter {

        public SetupPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
            super(fragmentActivity);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                case STEP_AGENT_SELECT:
                    return new AgentSelectionFragment();
                case STEP_INSTALL:
                    return new InstallFragment();
                case STEP_API_KEY:
                    return new AuthFragment();
                case STEP_CHANNEL:
                    return new ChannelFragment();
                default:
                    throw new IllegalArgumentException("Invalid step: " + position);
            }
        }

        @Override
        public int getItemCount() {
            return STEP_COUNT;
        }
    }
}
