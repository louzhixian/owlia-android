package com.termux.app.owlia;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.logger.Logger;

import org.json.JSONObject;

/**
 * Dashboard activity - main screen after setup is complete.
 * Shows gateway status, connected channels, and control buttons.
 * Auto-refreshes status every 5 seconds.
 */
public class DashboardActivity extends Activity {

    private static final String LOG_TAG = "DashboardActivity";
    public static final String NOTIFICATION_CHANNEL_ID = "botdrop_gateway";
    private static final int STATUS_REFRESH_INTERVAL_MS = 5000; // 5 seconds

    private TextView mStatusText;
    private TextView mUptimeText;
    private View mStatusIndicator;
    private TextView mTelegramStatus;
    private TextView mDiscordStatus;
    private Button mStartButton;
    private Button mStopButton;
    private Button mRestartButton;

    private OwliaService mOwliaService;
    private boolean mBound = false;
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mStatusRefreshRunnable;

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            OwliaService.LocalBinder binder = (OwliaService.LocalBinder) service;
            mOwliaService = binder.getService();
            mBound = true;
            Logger.logDebug(LOG_TAG, "Service connected");
            
            // Start status refresh
            startStatusRefresh();
            
            // Start gateway monitor service
            startGatewayMonitorService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBound = false;
            mOwliaService = null;
            Logger.logDebug(LOG_TAG, "Service disconnected");
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_botdrop_dashboard);

        // Create notification channel
        createNotificationChannel();

        // Initialize views
        mStatusText = findViewById(R.id.status_text);
        mUptimeText = findViewById(R.id.uptime_text);
        mStatusIndicator = findViewById(R.id.status_indicator);
        mTelegramStatus = findViewById(R.id.telegram_status);
        mDiscordStatus = findViewById(R.id.discord_status);
        mStartButton = findViewById(R.id.btn_start);
        mStopButton = findViewById(R.id.btn_stop);
        mRestartButton = findViewById(R.id.btn_restart);
        Button openTerminalButton = findViewById(R.id.btn_open_terminal);

        // Setup button listeners
        mStartButton.setOnClickListener(v -> startGateway());
        mStopButton.setOnClickListener(v -> stopGateway());
        mRestartButton.setOnClickListener(v -> restartGateway());
        openTerminalButton.setOnClickListener(v -> openTerminal());

        // Load channel info
        loadChannelInfo();

        // Bind to service
        Intent intent = new Intent(this, OwliaService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // Cancel all pending callbacks to prevent memory leak
        mHandler.removeCallbacksAndMessages(null);
        mStatusRefreshRunnable = null;
        
        // Unbind from service
        if (mBound) {
            unbindService(mConnection);
            mBound = false;
        }
    }

    /**
     * Create notification channel for gateway monitor service
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "BotDrop Gateway",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows when BotDrop is running");
            channel.setShowBadge(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Start the gateway monitor service
     */
    private void startGatewayMonitorService() {
        Intent serviceIntent = new Intent(this, GatewayMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    /**
     * Start periodic status refresh
     */
    private void startStatusRefresh() {
        mStatusRefreshRunnable = new Runnable() {
            @Override
            public void run() {
                refreshStatus();
                mHandler.postDelayed(this, STATUS_REFRESH_INTERVAL_MS);
            }
        };
        mHandler.post(mStatusRefreshRunnable);
    }

    /**
     * Refresh gateway status and uptime
     */
    private void refreshStatus() {
        if (!mBound || mOwliaService == null) {
            return;
        }

        // Check if gateway is running
        mOwliaService.isGatewayRunning(result -> {
            boolean isRunning = result.success && result.stdout.trim().equals("running");
            updateStatusUI(isRunning);

            // Get uptime if running
            if (isRunning) {
                mOwliaService.getGatewayUptime(uptimeResult -> {
                    if (uptimeResult.success) {
                        String uptime = uptimeResult.stdout.trim();
                        if (!uptime.equals("—")) {
                            mUptimeText.setText("Uptime: " + uptime);
                        } else {
                            mUptimeText.setText("—");
                        }
                    }
                });
            }
        });
    }

    /**
     * Update the status UI based on gateway state
     */
    private void updateStatusUI(boolean isRunning) {
        if (isRunning) {
            mStatusText.setText("Gateway Running");
            mStatusIndicator.setBackgroundResource(R.drawable.status_indicator_running);
            mStartButton.setEnabled(false);
            mStopButton.setEnabled(true);
            mRestartButton.setEnabled(true);
        } else {
            mStatusText.setText("Gateway Stopped");
            mStatusIndicator.setBackgroundResource(R.drawable.status_indicator_stopped);
            mUptimeText.setText("—");
            mStartButton.setEnabled(true);
            mStopButton.setEnabled(false);
            mRestartButton.setEnabled(false);
        }
    }

    /**
     * Load channel configuration and update UI
     */
    private void loadChannelInfo() {
        try {
            JSONObject config = OwliaConfig.readConfig();
            if (config.has("channels")) {
                JSONObject channels = config.getJSONObject("channels");

                // Check Telegram
                if (channels.has("telegram")) {
                    mTelegramStatus.setText("● Connected");
                    mTelegramStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected));
                } else {
                    mTelegramStatus.setText("○ —");
                    mTelegramStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected));
                }

                // Check Discord
                if (channels.has("discord")) {
                    mDiscordStatus.setText("● Connected");
                    mDiscordStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected));
                } else {
                    mDiscordStatus.setText("○ —");
                    mDiscordStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected));
                }
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to load channel info: " + e.getMessage());
        }
    }

    /**
     * Start the gateway
     */
    private void startGateway() {
        if (!mBound || mOwliaService == null) {
            return;
        }

        Toast.makeText(this, "Starting gateway...", Toast.LENGTH_SHORT).show();
        mStartButton.setEnabled(false);

        mOwliaService.startGateway(result -> {
            if (result.success) {
                Toast.makeText(this, "Gateway started", Toast.LENGTH_SHORT).show();
                refreshStatus();
            } else {
                Toast.makeText(this, "Failed to start gateway", Toast.LENGTH_SHORT).show();
                mStartButton.setEnabled(true);
                Logger.logError(LOG_TAG, "Start failed: " + result.stderr);
            }
        });
    }

    /**
     * Stop the gateway
     */
    private void stopGateway() {
        if (!mBound || mOwliaService == null) {
            return;
        }

        Toast.makeText(this, "Stopping gateway...", Toast.LENGTH_SHORT).show();
        mStopButton.setEnabled(false);

        mOwliaService.stopGateway(result -> {
            if (result.success) {
                Toast.makeText(this, "Gateway stopped", Toast.LENGTH_SHORT).show();
                refreshStatus();
            } else {
                Toast.makeText(this, "Failed to stop gateway", Toast.LENGTH_SHORT).show();
                mStopButton.setEnabled(true);
                Logger.logError(LOG_TAG, "Stop failed: " + result.stderr);
            }
        });
    }

    /**
     * Restart the gateway
     */
    private void restartGateway() {
        if (!mBound || mOwliaService == null) {
            return;
        }

        Toast.makeText(this, "Restarting gateway...", Toast.LENGTH_SHORT).show();
        mRestartButton.setEnabled(false);

        mOwliaService.restartGateway(result -> {
            if (result.success) {
                Toast.makeText(this, "Gateway restarted", Toast.LENGTH_SHORT).show();
                refreshStatus();
            } else {
                Toast.makeText(this, "Failed to restart gateway", Toast.LENGTH_SHORT).show();
                mRestartButton.setEnabled(true);
                Logger.logError(LOG_TAG, "Restart failed: " + result.stderr);
            }
        });
    }

    /**
     * Open terminal activity
     */
    private void openTerminal() {
        Intent intent = new Intent(this, TermuxActivity.class);
        startActivity(intent);
    }
}
