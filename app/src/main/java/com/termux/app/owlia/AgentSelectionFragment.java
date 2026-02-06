package com.termux.app.owlia;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.termux.R;
import com.termux.shared.logger.Logger;

/**
 * Step 2 of setup: Choose which agent to install.
 *
 * Currently offers:
 * - OpenClaw (available, triggers install)
 * - OwliaBot (coming soon, disabled)
 */
public class AgentSelectionFragment extends Fragment {

    private static final String LOG_TAG = "AgentSelectionFragment";

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
        installButton.setOnClickListener(v -> {
            Logger.logInfo(LOG_TAG, "OpenClaw selected for installation");
            SetupActivity activity = (SetupActivity) getActivity();
            if (activity != null && !activity.isFinishing()) {
                activity.goToNextStep();
            }
        });

        // URL click handlers
        view.findViewById(R.id.agent_openclaw_url).setOnClickListener(v -> {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://openclaw.ai")));
        });
    }
}
