package app.botdrop;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.logger.Logger;

/**
 * Setup wizard with 4 steps:
 * Step 0 (STEP_API_KEY): AI Provider + Auth
 * Step 1 (STEP_AGENT_SELECT): Choose which agent to install
 * Step 2 (STEP_INSTALL): Install selected agent
 * Step 3 (STEP_CHANNEL): Connect channel
 */

public class SetupActivity extends AppCompatActivity {

    private static final String LOG_TAG = "SetupActivity";

    /**
     * Interface for setup step fragments to handle Next button clicks.
     * Fragments can intercept the Next button to show dialogs or perform validation.
     */
    public interface SetupStepFragment {
        /**
         * Called when Next button is clicked.
         * @return true if the fragment handled the click, false to proceed with default behavior
         */
        boolean onNextClicked();
    }

    // Step constants
    public static final int STEP_API_KEY = 0;
    public static final int STEP_AGENT_SELECT = 1;
    public static final int STEP_INSTALL = 2;
    public static final int STEP_CHANNEL = 3;
    private static final int STEP_COUNT = 4;

    // Intent extra for starting at specific step
    public static final String EXTRA_START_STEP = "start_step";

    private ViewPager2 mViewPager;
    private SetupPagerAdapter mAdapter;
    private View mNavigationBar;
    private Button mBackButton;
    private Button mNextButton;

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
        int startStep = getIntent().getIntExtra(EXTRA_START_STEP, STEP_API_KEY);
        mViewPager.setCurrentItem(startStep, false);

        // Set up navigation buttons (hidden by default, fragments can show if needed)
        mBackButton.setOnClickListener(v -> {
            int current = mViewPager.getCurrentItem();
            if (current > 0) {
                mViewPager.setCurrentItem(current - 1);
            }
        });

        mNextButton.setOnClickListener(v -> {
            // Get current fragment and check if it wants to handle Next button
            Fragment currentFragment = getSupportFragmentManager()
                .findFragmentByTag("f" + mViewPager.getCurrentItem());

            if (currentFragment instanceof SetupStepFragment) {
                // Let fragment handle Next button (e.g., show model selector dialog)
                boolean handled = ((SetupStepFragment) currentFragment).onNextClicked();
                if (handled) {
                    return; // Fragment handled it, don't advance
                }
            }

            // Default behavior: advance to next step
            int current = mViewPager.getCurrentItem();
            if (current < STEP_COUNT - 1) {
                mViewPager.setCurrentItem(current + 1);
            }
        });

        // Setup manual update check button
        ImageButton checkUpdatesBtn = findViewById(R.id.setup_check_updates);
        checkUpdatesBtn.setOnClickListener(v -> {
            v.setEnabled(false);
            UpdateChecker.forceCheck(this, (version, url, notes) -> {
                v.setEnabled(true);
                if (version != null && !version.isEmpty()) {
                    // Show update available message
                    Toast.makeText(this, "Update available: v" + version, Toast.LENGTH_SHORT).show();
                    // Could show a dialog or banner here
                } else {
                    Toast.makeText(this, "No updates available", Toast.LENGTH_SHORT).show();
                }
            });
        });

        Logger.logDebug(LOG_TAG, "SetupActivity created, starting at step " + startStep);

        // Check for cached config and offer restore
        showRestoreConfigDialog();
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

    /**
     * Check for cached config and offer to restore it.
     * Called in onCreate() before showing the first setup fragment.
     */
    private void showRestoreConfigDialog() {
        if (!ConfigTemplateCache.hasTemplate(this)) {
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("Restore Configuration?")
            .setMessage("Use your previous configuration to set up quickly.")
            .setPositiveButton("Use Previous", (dialog, which) -> {
                applyTemplateAndContinue();
            })
            .setNegativeButton("Start Fresh", (dialog, which) -> {
                dialog.dismiss();
            })
            .setCancelable(false)
            .show();
    }

    /**
     * Apply cached config template and navigate to appropriate step.
     * If Telegram is configured, go to dashboard. Otherwise, skip to channel setup.
     */
    private void applyTemplateAndContinue() {
        ConfigTemplate template = ConfigTemplateCache.loadTemplate(this);
        if (template == null || !template.isValid()) {
            Toast.makeText(this, "Failed to load previous configuration", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean success = ConfigTemplateCache.applyTemplate(this, template);
        if (!success) {
            Toast.makeText(this, "Failed to apply configuration", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "Configuration restored", Toast.LENGTH_SHORT).show();

        // Check if Telegram is configured
        if (template.tgBotToken != null && !template.tgBotToken.isEmpty()) {
            // Full config restored, go to dashboard
            Intent intent = new Intent(this, DashboardActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        } else {
            // Partial config, jump to channel setup step
            showSetupStep(STEP_CHANNEL);
        }
    }

    /**
     * Show a specific setup step.
     * @param step The step index to show
     */
    private void showSetupStep(int step) {
        if (step >= 0 && step < STEP_COUNT) {
            mViewPager.setCurrentItem(step, true);
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
                case STEP_API_KEY:
                    return new AuthFragment();
                case STEP_AGENT_SELECT:
                    return new AgentSelectionFragment();
                case STEP_INSTALL:
                    return new InstallFragment();
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
