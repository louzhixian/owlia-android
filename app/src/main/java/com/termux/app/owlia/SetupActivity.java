package com.termux.app.owlia;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.termux.R;
import com.termux.shared.logger.Logger;

/**
 * Setup wizard with 3 steps:
 * Step 0 (STEP_INSTALL): Welcome + Auto-install OpenClaw
 * Step 1 (STEP_API_KEY): AI Provider + Auth (TODO)
 * Step 2 (STEP_CHANNEL): Connect channel (TODO)
 */
public class SetupActivity extends AppCompatActivity {

    private static final String LOG_TAG = "SetupActivity";

    // Step constants
    public static final int STEP_INSTALL = 0;
    public static final int STEP_API_KEY = 1;
    public static final int STEP_CHANNEL = 2;
    private static final int STEP_COUNT = 3;

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
        setContentView(R.layout.activity_owlia_setup);

        mViewPager = findViewById(R.id.setup_viewpager);
        mNavigationBar = findViewById(R.id.setup_navigation);
        mBackButton = findViewById(R.id.setup_button_back);
        mNextButton = findViewById(R.id.setup_button_next);

        // Set up ViewPager2
        mAdapter = new SetupPagerAdapter(this);
        mViewPager.setAdapter(mAdapter);
        mViewPager.setUserInputEnabled(false); // Disable swipe, only programmatic navigation

        // Start at specified step
        int startStep = getIntent().getIntExtra(EXTRA_START_STEP, STEP_INSTALL);
        mViewPager.setCurrentItem(startStep, false);

        // Set up navigation buttons (hidden by default, fragments can show if needed)
        mBackButton.setOnClickListener(v -> {
            int current = mViewPager.getCurrentItem();
            if (current > 0) {
                mViewPager.setCurrentItem(current - 1);
            }
        });

        mNextButton.setOnClickListener(v -> {
            int current = mViewPager.getCurrentItem();
            if (current < STEP_COUNT - 1) {
                mViewPager.setCurrentItem(current + 1);
            }
        });

        Logger.logDebug(LOG_TAG, "SetupActivity created, starting at step " + startStep);
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
            // Last step complete, finish setup
            Logger.logInfo(LOG_TAG, "Setup complete");
            finish();
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
                case STEP_INSTALL:
                    return new InstallFragment();
                case STEP_API_KEY:
                    return new PlaceholderFragment("API Key Setup",
                        "This step will configure AI provider authentication.\n\n(To be implemented in GUI-M1)");
                case STEP_CHANNEL:
                    return new PlaceholderFragment("Channel Setup",
                        "This step will connect to Telegram/Discord.\n\n(To be implemented in GUI-M2)");
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
