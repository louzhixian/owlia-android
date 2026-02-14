package app.botdrop;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.termux.R;
import com.termux.app.TermuxInstaller;
import com.termux.shared.logger.Logger;

/**
 * Step 1 of setup: Choose which agent to install.
 *
 * Currently offers:
 * - OpenClaw (available, triggers install)
 * - OwliaBot (a distinct AI agent product, not a rename leftover - coming soon, disabled)
 */
public class AgentSelectionFragment extends Fragment {

    private static final String LOG_TAG = "AgentSelectionFragment";

    public static final String PREFS_NAME = "botdrop_settings";
    public static final String KEY_OPENCLAW_VERSION = "openclaw_install_version";
    private static final String PINNED_VERSION = "openclaw@2026.2.6";
    private static final int TAP_COUNT_THRESHOLD = 10;
    private static final long TAP_WINDOW_MS = 5000;

    private int mTapCount = 0;
    private long mFirstTapTime = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_botdrop_agent_select, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Button installButton = view.findViewById(R.id.agent_openclaw_install);
        final boolean isOpenclawInstalled = BotDropService.isOpenclawInstalled();
        installButton.setText(isOpenclawInstalled ? "Open" : "Install");
        installButton.setOnClickListener(v -> {
            if (isOpenclawInstalled) {
                Logger.logInfo(LOG_TAG, "OpenClaw already installed, opening dashboard");
                Context ctx = getContext();
                if (ctx != null) {
                    Intent dashboardIntent = new Intent(ctx, DashboardActivity.class);
                    startActivity(dashboardIntent);
                }
                if (getActivity() instanceof SetupActivity && !getActivity().isFinishing()) {
                    getActivity().finish();
                }
            } else {
                Logger.logInfo(LOG_TAG, "OpenClaw selected for installation");
                SetupActivity activity = (SetupActivity) getActivity();
                if (activity != null && !activity.isFinishing()) {
                    activity.goToNextStep();
                }
            }
        });

        // URL click handlers
        view.findViewById(R.id.agent_openclaw_url).setOnClickListener(v -> {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://openclaw.ai")));
        });

        // Easter egg: tap OpenClaw icon 10 times to pin install version
        view.findViewById(R.id.agent_openclaw_icon).setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            if (mTapCount == 0 || now - mFirstTapTime > TAP_WINDOW_MS) {
                mTapCount = 1;
                mFirstTapTime = now;
            } else {
                mTapCount++;
            }

            if (mTapCount >= TAP_COUNT_THRESHOLD) {
                mTapCount = 0;
                showVersionPinDialog();
            }
        });
    }

    private void showVersionPinDialog() {
        Context ctx = getContext();
        if (ctx == null) return;

        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String current = prefs.getString(KEY_OPENCLAW_VERSION, null);
        boolean isPinned = PINNED_VERSION.equals(current);

        if (isPinned) {
            new AlertDialog.Builder(ctx)
                .setTitle("OpenClaw Version")
                .setMessage("Current install version: " + PINNED_VERSION + "\n\nReset to latest?")
                .setPositiveButton("Reset to latest", (d, w) -> {
                    prefs.edit().remove(KEY_OPENCLAW_VERSION).apply();
                    TermuxInstaller.createBotDropScripts("openclaw@latest");
                    Toast.makeText(ctx, "Reset to openclaw@latest", Toast.LENGTH_SHORT).show();
                    Logger.logInfo(LOG_TAG, "OpenClaw version reset to latest");
                })
                .setNegativeButton("Cancel", null)
                .show();
        } else {
            new AlertDialog.Builder(ctx)
                .setTitle("OpenClaw Version")
                .setMessage("Pin install version to " + PINNED_VERSION + "?")
                .setPositiveButton("Pin", (d, w) -> {
                    prefs.edit().putString(KEY_OPENCLAW_VERSION, PINNED_VERSION).apply();
                    TermuxInstaller.createBotDropScripts(PINNED_VERSION);
                    Toast.makeText(ctx, "Set to " + PINNED_VERSION, Toast.LENGTH_SHORT).show();
                    Logger.logInfo(LOG_TAG, "OpenClaw version pinned to " + PINNED_VERSION);
                })
                .setNegativeButton("Cancel", null)
                .show();
        }
    }
}
