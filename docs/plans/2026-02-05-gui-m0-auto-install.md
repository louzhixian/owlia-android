# GUI-M0: Auto-Install with No Terminal Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement automatic OpenClaw installation with progress UI, replacing the terminal-first experience with a zero-terminal GUI flow.

**Architecture:** Create a launcher activity that routes based on installation state, a background service for command execution, and a setup wizard with ViewPager2. The installer fragment auto-starts installation on load and shows progress with no user interaction required.

**Tech Stack:** Android SDK, Java, ViewPager2, Services, ProcessBuilder for shell execution

---

## Prerequisites

Before starting, verify:
- Android Studio is set up
- Project builds successfully: `./gradlew assembleDebug`
- Device/emulator available for testing

---

## Task 1: Create OwliaService for Background Command Execution

**Files:**
- Create: `app/src/main/java/com/termux/app/owlia/OwliaService.java`
- Reference: `app/src/main/java/com/termux/app/RunCommandService.java` (for service pattern)
- Reference: `termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java` (for paths)

**Step 1: Create the owlia package directory**

```bash
mkdir -p app/src/main/java/com/termux/app/owlia
```

Expected: Directory created

**Step 2: Write OwliaService with command execution infrastructure**

Create `app/src/main/java/com/termux/app/owlia/OwliaService.java`:

```java
package com.termux.app.owlia;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Background service for executing Owlia-related commands and managing gateway lifecycle.
 * Handles OpenClaw installation, configuration, and gateway control without showing terminal UI.
 */
public class OwliaService extends Service {

    private static final String LOG_TAG = "OwliaService";

    private final IBinder mBinder = new LocalBinder();
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    public class LocalBinder extends Binder {
        public OwliaService getService() {
            return OwliaService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Logger.logDebug(LOG_TAG, "onBind");
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.logDebug(LOG_TAG, "onCreate");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mExecutor.shutdown();
        Logger.logDebug(LOG_TAG, "onDestroy");
    }

    /**
     * Result of a command execution
     */
    public static class CommandResult {
        public final boolean success;
        public final String stdout;
        public final String stderr;
        public final int exitCode;

        public CommandResult(boolean success, String stdout, String stderr, int exitCode) {
            this.success = success;
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
        }
    }

    /**
     * Callback for async command execution
     */
    public interface CommandCallback {
        void onResult(CommandResult result);
    }

    /**
     * Execute a shell command in the Termux environment
     */
    public void executeCommand(String command, CommandCallback callback) {
        mExecutor.execute(() -> {
            CommandResult result = executeCommandSync(command);
            mHandler.post(() -> callback.onResult(result));
        });
    }

    /**
     * Execute a shell command synchronously
     */
    private CommandResult executeCommandSync(String command) {
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        int exitCode = -1;

        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);

            // Set Termux environment variables
            pb.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":" + System.getenv("PATH"));
            pb.environment().put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);

            pb.redirectErrorStream(false);

            Logger.logDebug(LOG_TAG, "Executing: " + command);
            Process process = pb.start();

            // Read stdout
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdout.append(line).append("\n");
                    Logger.logVerbose(LOG_TAG, "stdout: " + line);
                }
            }

            // Read stderr
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderr.append(line).append("\n");
                    Logger.logVerbose(LOG_TAG, "stderr: " + line);
                }
            }

            exitCode = process.waitFor();
            Logger.logDebug(LOG_TAG, "Command exited with code: " + exitCode);

            return new CommandResult(exitCode == 0, stdout.toString(), stderr.toString(), exitCode);

        } catch (IOException | InterruptedException e) {
            Logger.logError(LOG_TAG, "Command execution failed: " + e.getMessage());
            return new CommandResult(false, stdout.toString(),
                stderr.toString() + "\nException: " + e.getMessage(), exitCode);
        }
    }
}
```

**Step 3: Verify the service compiles**

Run: `./gradlew :app:compileDebugJavaWithJavac`

Expected: BUILD SUCCESSFUL

**Step 4: Commit the service foundation**

```bash
git add app/src/main/java/com/termux/app/owlia/OwliaService.java
git commit -m "feat(owlia): add OwliaService for background command execution

- Create service for shell command execution in Termux environment
- Set up proper environment variables (PREFIX, HOME, PATH, TMPDIR)
- Add async execution with callbacks
- Foundation for OpenClaw installation and gateway management

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 2: Add Installation Logic to OwliaService

**Files:**
- Modify: `app/src/main/java/com/termux/app/owlia/OwliaService.java`

**Step 1: Add installation progress callback interface**

Add to OwliaService:

```java
    /**
     * Callback for installation progress
     */
    public interface InstallProgressCallback {
        void onStepStart(int step, String message);
        void onStepComplete(int step);
        void onError(String error);
        void onComplete();
    }
```

**Step 2: Add installation method**

Add to OwliaService:

```java
    /**
     * Install OpenClaw with progress callbacks
     * Steps:
     * 0 - Fix permissions
     * 1 - Verify Node.js and npm
     * 2 - Install OpenClaw via npm
     */
    public void installOpenclaw(InstallProgressCallback callback) {
        mExecutor.execute(() -> {
            // Step 0: Fix permissions
            mHandler.post(() -> callback.onStepStart(0, "Fixing permissions..."));

            CommandResult chmodResult = executeCommandSync(
                "chmod +x $PREFIX/bin/* 2>/dev/null; " +
                "chmod +x $PREFIX/lib/node_modules/.bin/* 2>/dev/null; " +
                "exit 0"
            );

            mHandler.post(() -> callback.onStepComplete(0));

            // Step 1: Verify Node.js and npm
            mHandler.post(() -> callback.onStepStart(1, "Verifying Node.js..."));

            CommandResult verifyResult = executeCommandSync("node --version && npm --version");

            if (!verifyResult.success) {
                mHandler.post(() -> callback.onError(
                    "Node.js not found. Bootstrap installation may be incomplete.\n\n" +
                    verifyResult.stderr
                ));
                return;
            }

            Logger.logInfo(LOG_TAG, "Node.js version: " + verifyResult.stdout.trim());
            mHandler.post(() -> callback.onStepComplete(1));

            // Step 2: Install OpenClaw
            mHandler.post(() -> callback.onStepStart(2, "Installing OpenClaw..."));

            CommandResult installResult = executeCommandSync(
                "npm install -g openclaw@latest --ignore-scripts"
            );

            if (!installResult.success) {
                mHandler.post(() -> callback.onError(
                    "Failed to install OpenClaw:\n\n" +
                    installResult.stderr
                ));
                return;
            }

            Logger.logInfo(LOG_TAG, "OpenClaw installed successfully");
            mHandler.post(() -> {
                callback.onStepComplete(2);
                callback.onComplete();
            });
        });
    }
```

**Step 3: Add status check methods**

Add to OwliaService:

```java
    /**
     * Check if bootstrap (Node.js) is installed
     */
    public static boolean isBootstrapInstalled() {
        return new java.io.File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/node").exists();
    }

    /**
     * Check if OpenClaw is installed
     */
    public static boolean isOpenclawInstalled() {
        return new java.io.File(TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/lib/node_modules/openclaw").exists();
    }

    /**
     * Check if OpenClaw config exists
     */
    public static boolean isOpenclawConfigured() {
        return new java.io.File(TermuxConstants.TERMUX_HOME_DIR_PATH + "/.config/openclaw/openclaw.json").exists();
    }
```

**Step 4: Verify compilation**

Run: `./gradlew :app:compileDebugJavaWithJavac`

Expected: BUILD SUCCESSFUL

**Step 5: Commit installation logic**

```bash
git add app/src/main/java/com/termux/app/owlia/OwliaService.java
git commit -m "feat(owlia): add OpenClaw installation logic to service

- Add installOpenclaw method with progress callbacks
- Implement 3-step installation: permissions, verify node, npm install
- Add status check methods for bootstrap, openclaw, and config
- Proper error handling and logging

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 3: Create OwliaLauncherActivity for Routing

**Files:**
- Create: `app/src/main/java/com/termux/app/owlia/OwliaLauncherActivity.java`
- Create: `app/src/main/res/layout/activity_owlia_launcher.xml`

**Step 1: Create launcher layout**

Create `app/src/main/res/layout/activity_owlia_launcher.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<RelativeLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="?android:attr/colorBackground">

    <LinearLayout
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_centerInParent="true"
        android:orientation="vertical"
        android:gravity="center">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="🦉"
            android:textSize="48sp"
            android:layout_marginBottom="16dp" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Owlia"
            android:textSize="24sp"
            android:textStyle="bold"
            android:layout_marginBottom="8dp" />

        <ProgressBar
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:indeterminate="true" />

        <TextView
            android:id="@+id/launcher_status_text"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="Checking installation..."
            android:textSize="14sp" />

    </LinearLayout>

</RelativeLayout>
```

**Step 2: Create launcher activity**

Create `app/src/main/java/com/termux/app/owlia/OwliaLauncherActivity.java`:

```java
package com.termux.app.owlia;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxInstaller;
import com.termux.shared.logger.Logger;

/**
 * Launcher activity that routes to the appropriate screen based on installation state.
 *
 * Routing logic:
 * 1. If bootstrap not extracted -> Wait for TermuxInstaller (show progress)
 * 2. If OpenClaw not installed -> SetupActivity (auto-install)
 * 3. If OpenClaw not configured -> SetupActivity (auth + channel setup)
 * 4. All ready -> DashboardActivity (TODO: implement later, for now go to TermuxActivity)
 */
public class OwliaLauncherActivity extends Activity {

    private static final String LOG_TAG = "OwliaLauncherActivity";
    private TextView mStatusText;
    private Handler mHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_owlia_launcher);

        mStatusText = findViewById(R.id.launcher_status_text);

        // Check installation state after a short delay to show splash
        mHandler.postDelayed(this::checkAndRoute, 500);
    }

    private void checkAndRoute() {
        // Check 1: Bootstrap installed?
        if (!OwliaService.isBootstrapInstalled()) {
            Logger.logInfo(LOG_TAG, "Bootstrap not ready, waiting for TermuxInstaller");
            mStatusText.setText("Setting up environment...");

            // Wait for bootstrap, then re-check
            TermuxInstaller.setupBootstrapIfNeeded(this, this::checkAndRoute);
            return;
        }

        // Check 2: OpenClaw installed?
        if (!OwliaService.isOpenclawInstalled()) {
            Logger.logInfo(LOG_TAG, "OpenClaw not installed, routing to SetupActivity");
            mStatusText.setText("Preparing installation...");

            Intent intent = new Intent(this, SetupActivity.class);
            intent.putExtra(SetupActivity.EXTRA_START_STEP, SetupActivity.STEP_INSTALL);
            startActivity(intent);
            finish();
            return;
        }

        // Check 3: OpenClaw configured?
        if (!OwliaService.isOpenclawConfigured()) {
            Logger.logInfo(LOG_TAG, "OpenClaw not configured, routing to SetupActivity");
            mStatusText.setText("Setup required...");

            Intent intent = new Intent(this, SetupActivity.class);
            intent.putExtra(SetupActivity.EXTRA_START_STEP, SetupActivity.STEP_API_KEY);
            startActivity(intent);
            finish();
            return;
        }

        // All ready - for now, go to TermuxActivity (DashboardActivity will be implemented later)
        Logger.logInfo(LOG_TAG, "All ready, routing to main activity");
        mStatusText.setText("Starting...");

        // TODO: Replace with DashboardActivity when implemented
        Intent intent = new Intent(this, TermuxActivity.class);
        startActivity(intent);
        finish();
    }
}
```

**Step 3: Verify compilation**

Run: `./gradlew :app:compileDebugJavaWithJavac`

Expected: Compilation errors about SetupActivity not existing (expected)

**Step 4: Commit launcher activity**

```bash
git add app/src/main/java/com/termux/app/owlia/OwliaLauncherActivity.java \
        app/src/main/res/layout/activity_owlia_launcher.xml
git commit -m "feat(owlia): add launcher activity with routing logic

- Create splash/routing screen that checks installation state
- Route to SetupActivity if openclaw not installed/configured
- Route to TermuxActivity when ready (DashboardActivity TODO)
- Wait for bootstrap if needed via TermuxInstaller

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 4: Create SetupActivity with ViewPager2

**Files:**
- Create: `app/src/main/java/com/termux/app/owlia/SetupActivity.java`
- Create: `app/src/main/res/layout/activity_owlia_setup.xml`
- Modify: `app/build.gradle` (add ViewPager2 dependency if needed)

**Step 1: Check ViewPager2 dependency**

Read `app/build.gradle` and verify `androidx.viewpager2:viewpager2` is present.

If not present, add to dependencies:
```gradle
implementation 'androidx.viewpager2:viewpager2:1.0.0'
```

Then run: `./gradlew :app:sync`

**Step 2: Create setup activity layout**

Create `app/src/main/res/layout/activity_owlia_setup.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="?android:attr/colorBackground">

    <!-- Top bar with step indicator (optional, can add later) -->

    <androidx.viewpager2.widget.ViewPager2
        android:id="@+id/setup_viewpager"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />

    <!-- Bottom navigation (Next/Back buttons) -->
    <LinearLayout
        android:id="@+id/setup_navigation"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:padding="16dp"
        android:gravity="end"
        android:visibility="gone">

        <Button
            android:id="@+id/setup_button_back"
            style="?android:attr/buttonBarButtonStyle"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Back"
            android:layout_marginEnd="8dp" />

        <Button
            android:id="@+id/setup_button_next"
            style="?android:attr/buttonBarButtonStyle"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Next" />

    </LinearLayout>

</LinearLayout>
```

**Step 3: Create SetupActivity**

Create `app/src/main/java/com/termux/app/owlia/SetupActivity.java`:

```java
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
```

**Step 4: Create PlaceholderFragment for unimplemented steps**

Create `app/src/main/java/com/termux/app/owlia/PlaceholderFragment.java`:

```java
package com.termux.app.owlia;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.termux.R;

/**
 * Placeholder fragment for unimplemented setup steps
 */
public class PlaceholderFragment extends Fragment {

    private static final String ARG_TITLE = "title";
    private static final String ARG_MESSAGE = "message";

    public PlaceholderFragment() {
        // Required empty constructor
    }

    public PlaceholderFragment(String title, String message) {
        Bundle args = new Bundle();
        args.putString(ARG_TITLE, title);
        args.putString(ARG_MESSAGE, message);
        setArguments(args);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_owlia_placeholder, container, false);

        TextView titleView = view.findViewById(R.id.placeholder_title);
        TextView messageView = view.findViewById(R.id.placeholder_message);
        Button continueButton = view.findViewById(R.id.placeholder_continue);

        Bundle args = getArguments();
        if (args != null) {
            titleView.setText(args.getString(ARG_TITLE, ""));
            messageView.setText(args.getString(ARG_MESSAGE, ""));
        }

        continueButton.setOnClickListener(v -> {
            SetupActivity activity = (SetupActivity) getActivity();
            if (activity != null) {
                activity.goToNextStep();
            }
        });

        return view;
    }
}
```

**Step 5: Create placeholder fragment layout**

Create `app/src/main/res/layout/fragment_owlia_placeholder.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="24dp"
    android:gravity="center">

    <TextView
        android:id="@+id/placeholder_title"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textSize="20sp"
        android:textStyle="bold"
        android:layout_marginBottom="16dp" />

    <TextView
        android:id="@+id/placeholder_message"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textSize="14sp"
        android:gravity="center"
        android:layout_marginBottom="24dp" />

    <Button
        android:id="@+id/placeholder_continue"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Continue →" />

</LinearLayout>
```

**Step 6: Verify compilation**

Run: `./gradlew :app:compileDebugJavaWithJavac`

Expected: Compilation errors about InstallFragment not existing (expected)

**Step 7: Commit setup activity**

```bash
git add app/src/main/java/com/termux/app/owlia/SetupActivity.java \
        app/src/main/java/com/termux/app/owlia/PlaceholderFragment.java \
        app/src/main/res/layout/activity_owlia_setup.xml \
        app/src/main/res/layout/fragment_owlia_placeholder.xml
git commit -m "feat(owlia): add setup wizard with ViewPager2

- Create SetupActivity with 3-step flow
- Add ViewPager2 adapter for fragments
- Add PlaceholderFragment for unimplemented steps (API key, channel)
- Support starting at specific step via EXTRA_START_STEP
- Disable swipe navigation, only programmatic navigation

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 5: Create InstallFragment with Auto-Install

**Files:**
- Create: `app/src/main/java/com/termux/app/owlia/InstallFragment.java`
- Create: `app/src/main/res/layout/fragment_owlia_install.xml`

**Step 1: Create install fragment layout**

Create `app/src/main/res/layout/fragment_owlia_install.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="32dp"
    android:gravity="center">

    <!-- Owl icon -->
    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="🦉"
        android:textSize="64sp"
        android:layout_marginBottom="16dp" />

    <!-- Owlia title -->
    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Owlia"
        android:textSize="32sp"
        android:textStyle="bold"
        android:layout_marginBottom="8dp" />

    <!-- Tagline -->
    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Your AI assistant,\nrunning on your phone."
        android:textSize="16sp"
        android:gravity="center"
        android:layout_marginBottom="32dp" />

    <!-- Divider -->
    <View
        android:layout_width="match_parent"
        android:layout_height="1dp"
        android:background="?android:attr/dividerHorizontal"
        android:layout_marginBottom="32dp" />

    <!-- Installation steps -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:layout_marginBottom="24dp">

        <!-- Step 0: Permissions -->
        <LinearLayout
            android:id="@+id/install_step_0"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:paddingVertical="8dp">

            <TextView
                android:id="@+id/install_step_0_icon"
                android:layout_width="24dp"
                android:layout_height="24dp"
                android:text="○"
                android:textSize="18sp"
                android:gravity="center"
                android:layout_marginEnd="12dp" />

            <TextView
                android:id="@+id/install_step_0_text"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="Environment ready"
                android:textSize="14sp" />
        </LinearLayout>

        <!-- Step 1: Verify Node -->
        <LinearLayout
            android:id="@+id/install_step_1"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:paddingVertical="8dp">

            <TextView
                android:id="@+id/install_step_1_icon"
                android:layout_width="24dp"
                android:layout_height="24dp"
                android:text="○"
                android:textSize="18sp"
                android:gravity="center"
                android:layout_marginEnd="12dp" />

            <TextView
                android:id="@+id/install_step_1_text"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="Verifying Node.js"
                android:textSize="14sp" />
        </LinearLayout>

        <!-- Step 2: Install OpenClaw -->
        <LinearLayout
            android:id="@+id/install_step_2"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:paddingVertical="8dp">

            <TextView
                android:id="@+id/install_step_2_icon"
                android:layout_width="24dp"
                android:layout_height="24dp"
                android:text="○"
                android:textSize="18sp"
                android:gravity="center"
                android:layout_marginEnd="12dp" />

            <TextView
                android:id="@+id/install_step_2_text"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="Installing OpenClaw"
                android:textSize="14sp" />
        </LinearLayout>

    </LinearLayout>

    <!-- Status message -->
    <TextView
        android:id="@+id/install_status_message"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="This takes about a minute"
        android:textSize="13sp"
        android:alpha="0.7"
        android:layout_marginBottom="16dp" />

    <!-- Error container (hidden by default) -->
    <LinearLayout
        android:id="@+id/install_error_container"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:background="?android:attr/colorBackground"
        android:padding="16dp"
        android:layout_marginTop="16dp"
        android:visibility="gone">

        <TextView
            android:id="@+id/install_error_message"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textSize="13sp"
            android:textColor="#D32F2F"
            android:fontFamily="monospace"
            android:layout_marginBottom="12dp" />

        <Button
            android:id="@+id/install_retry_button"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Retry"
            android:layout_gravity="start" />

    </LinearLayout>

</LinearLayout>
```

**Step 2: Create InstallFragment**

Create `app/src/main/java/com/termux/app/owlia/InstallFragment.java`:

```java
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
```

**Step 3: Verify compilation**

Run: `./gradlew :app:compileDebugJavaWithJavac`

Expected: BUILD SUCCESSFUL

**Step 4: Commit install fragment**

```bash
git add app/src/main/java/com/termux/app/owlia/InstallFragment.java \
        app/src/main/res/layout/fragment_owlia_install.xml
git commit -m "feat(owlia): add auto-install fragment with progress UI

- Create InstallFragment that auto-starts installation on load
- Show progress with step indicators (○ → ● → ✓)
- Bind to OwliaService for background installation
- Show error with retry button on failure
- Auto-advance to next step on success
- Welcome screen with Owlia branding

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 6: Update AndroidManifest.xml

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

**Step 1: Add Owlia activities and service to manifest**

In `app/src/main/AndroidManifest.xml`, add inside `<application>` tag (after TermuxActivity):

```xml
        <!-- Owlia GUI Components -->
        <activity
            android:name=".app.owlia.OwliaLauncherActivity"
            android:exported="true"
            android:theme="@style/Theme.TermuxApp.DayNight.NoActionBar">
            <!-- This will be the new MAIN/LAUNCHER after testing -->
        </activity>

        <activity
            android:name=".app.owlia.SetupActivity"
            android:exported="false"
            android:theme="@style/Theme.TermuxApp.DayNight.NoActionBar" />

        <service
            android:name=".app.owlia.OwliaService"
            android:exported="false" />
```

**Step 2: Verify the manifest**

Run: `./gradlew :app:processDebugManifest`

Expected: BUILD SUCCESSFUL

**Step 3: Commit manifest changes**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat(owlia): add activities and service to manifest

- Add OwliaLauncherActivity (not yet main launcher)
- Add SetupActivity
- Add OwliaService
- All use NoActionBar theme for custom UI

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 7: Test the Auto-Install Flow (TermuxActivity Entry)

**Files:**
- Modify: `app/src/main/java/com/termux/app/TermuxActivity.java`

**Step 1: Add navigation to OwliaLauncherActivity from TermuxActivity**

In TermuxActivity's onCreate or onStart, add temporary code to launch Owlia:

Find the onCreate method and add after `super.onCreate(savedInstanceState)`:

```java
// TEMPORARY: Test Owlia launcher
// TODO: Remove this after making OwliaLauncherActivity the main launcher
if (!getIntent().getBooleanExtra("from_owlia", false)) {
    Intent owliaIntent = new Intent(this, com.termux.app.owlia.OwliaLauncherActivity.class);
    owliaIntent.putExtra("from_owlia", true);
    startActivity(owliaIntent);
}
```

**Step 2: Build and install the APK**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

Find APK at: `app/build/outputs/apk/debug/app-debug.apk`

**Step 3: Install on device/emulator**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: Success

**Step 4: Test the flow**

1. Launch the app
2. Should see OwliaLauncherActivity splash
3. Should route to SetupActivity → InstallFragment
4. Should auto-start installation with progress indicators
5. Installation steps should complete
6. Should auto-advance to placeholder "API Key Setup" screen

Expected behavior:
- No terminal shown during installation
- Progress indicators update (○ → ● → ✓)
- Installation completes or shows error with retry

**Step 5: Document test results**

Create test notes in scratchpad:

```bash
cat > /private/tmp/claude-501/-Users-zhixian-Codes-owlia-android/bc449484-2a28-413d-905b-a68742442601/scratchpad/gui-m0-test-results.md << 'EOF'
# GUI-M0 Test Results

## Test Date
[Date]

## Test Environment
- Device: [Device name]
- Android Version: [Version]
- Build: app-debug.apk

## Test Cases

### TC1: First Launch (No OpenClaw)
**Steps:**
1. Fresh install
2. Launch app

**Expected:**
- Splash screen shows
- Routes to SetupActivity
- Auto-starts installation
- Shows progress indicators

**Result:** [PASS/FAIL]
**Notes:**

### TC2: Installation Progress
**Steps:**
1. Observe installation steps

**Expected:**
- Step 0 (permissions): ○ → ● → ✓
- Step 1 (verify node): ○ → ● → ✓
- Step 2 (npm install): ○ → ● → ✓
- Auto-advance to next screen

**Result:** [PASS/FAIL]
**Notes:**

### TC3: Installation Failure (Simulated)
**Steps:**
1. [Simulate failure if possible]

**Expected:**
- Error message shown
- Retry button appears
- Can retry installation

**Result:** [PASS/FAIL]
**Notes:**

### TC4: Re-launch After Installation
**Steps:**
1. Close app
2. Re-launch

**Expected:**
- Should skip installation step
- Route to API key setup or main activity

**Result:** [PASS/FAIL]
**Notes:**

EOF
```

**Step 6: Commit test harness**

```bash
git add app/src/main/java/com/termux/app/TermuxActivity.java
git commit -m "test(owlia): add temporary launcher redirect for testing

- Add code to launch OwliaLauncherActivity from TermuxActivity
- Temporary for GUI-M0 testing
- TODO: Remove when making OwliaLauncherActivity the main launcher

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 8: Switch to OwliaLauncherActivity as Main Launcher

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/termux/app/TermuxActivity.java`

**Step 1: Remove test harness from TermuxActivity**

Remove the temporary code added in Task 7:

```java
// Remove these lines:
// TEMPORARY: Test Owlia launcher
if (!getIntent().getBooleanExtra("from_owlia", false)) {
    Intent owliaIntent = new Intent(this, com.termux.app.owlia.OwliaLauncherActivity.class);
    owliaIntent.putExtra("from_owlia", true);
    startActivity(owliaIntent);
}
```

**Step 2: Update AndroidManifest.xml**

Move the `<intent-filter>` from TermuxActivity to OwliaLauncherActivity:

Remove from TermuxActivity:
```xml
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
```

Add to OwliaLauncherActivity:
```xml
        <activity
            android:name=".app.owlia.OwliaLauncherActivity"
            android:exported="true"
            android:theme="@style/Theme.TermuxApp.DayNight.NoActionBar">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
```

Keep TermuxActivity but make it exported="false" and accessible internally:
```xml
        <activity
            android:name=".app.TermuxActivity"
            android:exported="false"
            ...
```

**Step 3: Verify manifest changes**

Run: `./gradlew :app:processDebugManifest`

Expected: BUILD SUCCESSFUL

**Step 4: Rebuild and test**

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Launch app and verify:
- App launches to OwliaLauncherActivity directly
- Launcher icon shows in app drawer
- Navigation works correctly

**Step 5: Commit the launcher switch**

```bash
git add app/src/main/AndroidManifest.xml \
        app/src/main/java/com/termux/app/TermuxActivity.java
git commit -m "feat(owlia): make OwliaLauncherActivity the main launcher

- Move MAIN/LAUNCHER intent-filter to OwliaLauncherActivity
- Remove test harness from TermuxActivity
- TermuxActivity now internal, launched from launcher/dashboard
- Complete GUI-M0: zero-terminal experience

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 9: Final Testing and Documentation

**Step 1: Full integration test**

Test the complete flow:

1. **Uninstall app completely**
   ```bash
   adb uninstall com.termux
   ```

2. **Install fresh**
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

3. **Launch and verify entire flow:**
   - App opens to splash screen
   - Routes to install screen
   - Installation auto-starts
   - Progress indicators work
   - Installation completes
   - Advances to next screen
   - No terminal visible at any point

4. **Test re-launch:**
   - Kill app
   - Launch again
   - Should skip installation
   - Should go to next step or terminal

**Step 2: Update MEMORY.md with learnings**

Add key findings to `/Users/zhixian/.claude/projects/-Users-zhixian-Codes-owlia-android/memory/MEMORY.md`:

```markdown
## GUI-M0 Implementation

### Key Architecture Decisions
- OwliaService handles all shell execution in background
- Service uses ExecutorService for async operations
- Environment variables (PREFIX, HOME, PATH, TMPDIR) must be set for Termux commands
- ViewPager2 with programmatic navigation (no swipe)
- Fragments auto-advance on completion

### File Paths (Termux Constants)
- Bootstrap: /data/data/com.termux/files/usr
- Home: /data/data/com.termux/files/home
- Bin: /data/data/com.termux/files/usr/bin
- OpenClaw: /data/data/com.termux/files/usr/lib/node_modules/openclaw
- Config: /data/data/com.termux/files/home/.config/openclaw/openclaw.json

### Installation Steps
1. Fix permissions on $PREFIX/bin and node_modules/.bin
2. Verify node and npm are available
3. Run npm install -g openclaw@latest --ignore-scripts

### Common Issues
[Document any issues encountered during implementation]
```

**Step 3: Create GUI-M0 completion checklist**

```markdown
## GUI-M0 Completion Checklist

- [x] OwliaService created with command execution
- [x] Installation logic with progress callbacks
- [x] OwliaLauncherActivity with routing logic
- [x] SetupActivity with ViewPager2
- [x] InstallFragment with auto-install
- [x] PlaceholderFragments for future steps
- [x] All components added to AndroidManifest.xml
- [x] OwliaLauncherActivity set as main launcher
- [x] Tested full installation flow
- [x] No terminal shown during install
- [x] Re-launch skips completed steps
```

**Step 4: Final commit**

```bash
git add /Users/zhixian/.claude/projects/-Users-zhixian-Codes-owlia-android/memory/MEMORY.md
git commit -m "docs: document GUI-M0 implementation learnings

- Architecture decisions and patterns
- File paths and constants
- Installation process
- Common issues to watch for

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Next Steps (Not in GUI-M0)

GUI-M0 is now complete. Future milestones:

- **GUI-M1**: Auth fragment with provider selection and API key input
- **GUI-M2**: Channel connection with @OwliaSetupBot integration
- **GUI-M3**: Dashboard activity with status and controls
- **GUI-M4**: Polish, theming, error handling improvements

---

## Troubleshooting

### Installation Fails: "Node.js not found"
- Check bootstrap is installed: TermuxInstaller.setupBootstrapIfNeeded
- Verify $PREFIX/bin/node exists
- Check file permissions

### NPM Install Fails
- Check network connectivity
- Verify npm is in PATH
- Try with --verbose flag to see detailed error
- Check available disk space

### App Crashes on Launch
- Check LogCat: `adb logcat -s OwliaLauncherActivity OwliaService InstallFragment`
- Verify all activities registered in manifest
- Check service binding in InstallFragment

### Progress Indicators Don't Update
- Verify callbacks are on UI thread (using mHandler.post)
- Check service is bound before starting installation
- Look for exceptions in OwliaService

---

**Plan complete. Ready for execution.**
