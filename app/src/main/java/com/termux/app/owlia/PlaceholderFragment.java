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
 * Placeholder fragment for unimplemented setup steps.
 * Use newInstance() factory method instead of constructor.
 */
public class PlaceholderFragment extends Fragment {

    private static final String ARG_TITLE = "title";
    private static final String ARG_MESSAGE = "message";

    public PlaceholderFragment() {
        // Required empty constructor for fragment recreation
    }

    /**
     * Factory method to create a new instance with arguments.
     * Preferred over constructor to survive configuration changes.
     */
    public static PlaceholderFragment newInstance(String title, String message) {
        PlaceholderFragment fragment = new PlaceholderFragment();
        Bundle args = new Bundle();
        args.putString(ARG_TITLE, title);
        args.putString(ARG_MESSAGE, message);
        fragment.setArguments(args);
        return fragment;
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
            if (activity != null && !activity.isFinishing()) {
                activity.goToNextStep();
            }
        });

        return view;
    }
}
