package app.botdrop.ops;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.shared.logger.Logger;

public class OpsChatActivity extends Activity {

    private static final String LOG_TAG = "OpsChatActivity";
    private static final String PREFS = "ops_chat";
    private static final String KEY_TRANSCRIPT = "transcript";
    private static final int MAX_TRANSCRIPT_CHARS = 24000;

    private TextView mChatText;
    private EditText mInput;
    private Button mSendButton;

    private OpsPiAgentEngine mAssistantEngine;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_botdrop_ops_chat);

        mChatText = findViewById(R.id.ops_chat_text);
        mInput = findViewById(R.id.ops_chat_input);
        mSendButton = findViewById(R.id.ops_chat_btn_send);
        Button diagnoseButton = findViewById(R.id.ops_chat_btn_diagnose);
        Button backButton = findViewById(R.id.ops_chat_btn_back);

        diagnoseButton.setEnabled(false);
        diagnoseButton.setText("Diagnose (disabled)");

        mSendButton.setOnClickListener(v -> sendMessage());
        backButton.setOnClickListener(v -> finish());

        mAssistantEngine = new OpsPiAgentEngine(new PiAgentBridge(new OpsCredentialResolver()));

        loadTranscript();
        append("system", "Pi chat ready.");
    }

    @Override
    protected void onStop() {
        super.onStop();
        persistTranscript();
    }

    private void sendMessage() {
        String msg = mInput.getText().toString().trim();
        if (msg.isEmpty()) return;

        mInput.setText("");
        append("you", msg);
        setBusy(true);

        new Thread(() -> {
            try {
                OpsPiAgentEngine.AssistantReply reply = mAssistantEngine.reply(msg, null);
                runOnUiThread(() -> {
                    append("assistant", reply.text);
                    setBusy(false);
                });
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "sendMessage failed: " + e.getMessage());
                runOnUiThread(() -> {
                    append("system", "Assistant failed: " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : (": " + e.getMessage())));
                    setBusy(false);
                });
            }
        }).start();
    }

    private void append(String role, String text) {
        String old = mChatText.getText() == null ? "" : mChatText.getText().toString();
        String line = "[" + role + "] " + text + "\n\n";
        String merged = old + line;
        if (merged.length() > MAX_TRANSCRIPT_CHARS) {
            merged = merged.substring(merged.length() - MAX_TRANSCRIPT_CHARS);
        }
        mChatText.setText(merged);
        persistTranscript();
    }

    private void setBusy(boolean busy) {
        mSendButton.setEnabled(!busy);
    }

    private void loadTranscript() {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String transcript = prefs.getString(KEY_TRANSCRIPT, "");
        if (transcript != null && !transcript.isEmpty()) {
            mChatText.setText(transcript);
        }
    }

    private void persistTranscript() {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String text = mChatText.getText() == null ? "" : mChatText.getText().toString();
        prefs.edit().putString(KEY_TRANSCRIPT, text).apply();
    }
}
