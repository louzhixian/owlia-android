package com.termux.app.owlia;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.termux.R;
import com.termux.shared.logger.Logger;

/**
 * Foreground service that monitors and keeps the OpenClaw gateway alive.
 *
 * Features:
 * - Runs as a foreground service with persistent notification
 * - Starts gateway if not running
 * - Monitors gateway process and restarts if it dies
 * - Handles Android Doze mode with partial wake lock
 * - Shows gateway status in notification
 */
public class GatewayMonitorService extends Service {

    private static final String LOG_TAG = "GatewayMonitorService";
    private static final int NOTIFICATION_ID = 1001;
    private static final int MONITOR_INTERVAL_MS = 30000; // 30 seconds
    private static final int RESTART_DELAY_MS = 5000; // 5 seconds
    private static final int MAX_RESTART_ATTEMPTS = 5;
    private static final long WAKELOCK_TIMEOUT_MS = 15 * 60 * 1000; // 15 minutes
    private static final long WAKELOCK_REACQUIRE_INTERVAL_MS = 10 * 60 * 1000; // 10 minutes

    private Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mMonitorRunnable;
    private PowerManager.WakeLock mWakeLock;
    private long mWakeLockLastAcquired = 0;
    private OwliaService mOwliaService;
    private boolean mOwliaServiceBound = false;
    private boolean mIsMonitoring = false;
    private String mCurrentStatus = "Starting...";
    private int mRestartAttempts = 0;

    /**
     * Service connection for binding to OwliaService
     */
    private ServiceConnection mOwliaServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            OwliaService.LocalBinder binder = (OwliaService.LocalBinder) service;
            mOwliaService = binder.getService();
            mOwliaServiceBound = true;
            Logger.logInfo(LOG_TAG, "Bound to OwliaService");

            // Now that service is bound, start monitoring
            if (!mIsMonitoring) {
                startMonitoring();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mOwliaService = null;
            mOwliaServiceBound = false;
            Logger.logInfo(LOG_TAG, "Disconnected from OwliaService");
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.logInfo(LOG_TAG, "Service created");

        // Bind to OwliaService for command execution
        Intent intent = new Intent(this, OwliaService.class);
        bindService(intent, mOwliaServiceConnection, Context.BIND_AUTO_CREATE);

        // Initialize wake lock to handle Doze mode
        // Uses timeout with periodic re-acquisition to prevent orphaned locks
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            mWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "BotDrop::GatewayMonitor"
            );
            mWakeLock.setReferenceCounted(false); // Ensure single release is enough
            acquireWakeLock();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Logger.logInfo(LOG_TAG, "Service started");

        // Start foreground service with notification
        Notification notification = buildNotification("BotDrop is running");
        startForeground(NOTIFICATION_ID, notification);

        // Monitoring will start automatically when OwliaService is bound
        // (see onServiceConnected callback)

        // START_STICKY ensures the service is restarted if killed
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Logger.logInfo(LOG_TAG, "Service destroyed");

        // Stop monitoring
        stopMonitoring();

        // Remove all pending callbacks to prevent leaks
        mHandler.removeCallbacksAndMessages(null);

        // Unbind from OwliaService
        if (mOwliaServiceBound) {
            try {
                unbindService(mOwliaServiceConnection);
                Logger.logInfo(LOG_TAG, "Unbound from OwliaService");
            } catch (IllegalArgumentException e) {
                // Service was not bound or already unbound
                Logger.logDebug(LOG_TAG, "Service was already unbound");
            }
            mOwliaServiceBound = false;
            mOwliaService = null;
        }

        // Release wake lock
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // We don't provide binding
    }

    /**
     * Start monitoring the gateway
     */
    private void startMonitoring() {
        mIsMonitoring = true;
        Logger.logInfo(LOG_TAG, "Starting gateway monitoring");

        mMonitorRunnable = new Runnable() {
            @Override
            public void run() {
                // Re-acquire WakeLock periodically to prevent timeout
                reacquireWakeLockIfNeeded();

                // Check gateway status
                checkAndRestartGateway();

                if (mIsMonitoring) {
                    mHandler.postDelayed(this, MONITOR_INTERVAL_MS);
                }
            }
        };

        // Start immediately, then repeat at intervals
        mHandler.post(mMonitorRunnable);
    }

    /**
     * Stop monitoring the gateway
     */
    private void stopMonitoring() {
        mIsMonitoring = false;
        if (mMonitorRunnable != null) {
            mHandler.removeCallbacks(mMonitorRunnable);
        }
    }

    /**
     * Check if gateway is running and restart if needed
     */
    private void checkAndRestartGateway() {
        // Only proceed if service is bound
        if (!mOwliaServiceBound || mOwliaService == null) {
            Logger.logDebug(LOG_TAG, "OwliaService not bound yet, skipping check");
            return;
        }

        try {
            mOwliaService.isGatewayRunning(result -> {
                try {
                    boolean isRunning = result.success && result.stdout.trim().equals("running");

                    if (isRunning) {
                        // Gateway is running - reset restart counter and update status
                        mRestartAttempts = 0;
                        updateStatus("Running");
                    } else {
                        // Gateway is not running - restart it
                        Logger.logInfo(LOG_TAG, "Gateway is not running, attempting restart");
                        updateStatus("Restarting...");
                        restartGateway();
                    }
                } catch (Exception e) {
                    Logger.logError(LOG_TAG, "Error in gateway check callback: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Error checking gateway status: " + e.getMessage());
        }
    }

    /**
     * Restart the gateway
     */
    private void restartGateway() {
        // Only proceed if service is bound
        if (!mOwliaServiceBound || mOwliaService == null) {
            Logger.logError(LOG_TAG, "Cannot restart: OwliaService not bound");
            return;
        }

        // Check if we've exceeded max restart attempts
        if (mRestartAttempts >= MAX_RESTART_ATTEMPTS) {
            Logger.logError(LOG_TAG, "Max restart attempts (" + MAX_RESTART_ATTEMPTS + ") reached");
            updateStatus("Failed - manual restart required");
            return;
        }

        mRestartAttempts++;
        Logger.logInfo(LOG_TAG, "Restart attempt " + mRestartAttempts + "/" + MAX_RESTART_ATTEMPTS);

        try {
            mOwliaService.startGateway(result -> {
                try {
                    if (result.success) {
                        Logger.logInfo(LOG_TAG, "Gateway started successfully");
                        mRestartAttempts = 0; // Reset on success
                        mHandler.postDelayed(() -> updateStatus("Running"), RESTART_DELAY_MS);
                    } else {
                        Logger.logError(LOG_TAG, "Failed to start gateway: " + result.stderr);
                        updateStatus("Failed (attempt " + mRestartAttempts + "/" + MAX_RESTART_ATTEMPTS + ")");
                        
                        // Try again after delay if we haven't hit the limit
                        if (mRestartAttempts < MAX_RESTART_ATTEMPTS) {
                            mHandler.postDelayed(this::restartGateway, RESTART_DELAY_MS);
                        }
                    }
                } catch (Exception e) {
                    Logger.logError(LOG_TAG, "Error in gateway restart callback: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Error executing gateway start: " + e.getMessage());
        }
    }

    /**
     * Update the notification with current status
     */
    private void updateStatus(String status) {
        mCurrentStatus = status;
        Notification notification = buildNotification("Gateway: " + status);
        
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
        }
    }

    /**
     * Build notification for foreground service
     */
    private Notification buildNotification(String contentText) {
        // Intent to open DashboardActivity when notification is tapped
        Intent notificationIntent = new Intent(this, DashboardActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, flags
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
            this, DashboardActivity.NOTIFICATION_CHANNEL_ID
        )
            .setContentTitle("💧 BotDrop")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Cannot be dismissed
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false);

        // For Android 14+, specify foreground service type
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        }

        return builder.build();
    }

    /**
     * Acquire WakeLock with timeout to prevent orphaned locks.
     * If the service crashes, the lock will automatically release after timeout.
     */
    private void acquireWakeLock() {
        if (mWakeLock != null && !mWakeLock.isHeld()) {
            mWakeLock.acquire(WAKELOCK_TIMEOUT_MS);
            mWakeLockLastAcquired = System.currentTimeMillis();
            Logger.logDebug(LOG_TAG, "WakeLock acquired with " + (WAKELOCK_TIMEOUT_MS / 60000) + " minute timeout");
        }
    }

    /**
     * Re-acquire WakeLock if it's been held for longer than the reacquire interval.
     * This prevents the timeout from expiring while the service is running normally.
     */
    private void reacquireWakeLockIfNeeded() {
        if (mWakeLock == null) {
            return;
        }

        long timeSinceLastAcquire = System.currentTimeMillis() - mWakeLockLastAcquired;
        if (timeSinceLastAcquire >= WAKELOCK_REACQUIRE_INTERVAL_MS) {
            // Release and re-acquire to reset the timeout
            if (mWakeLock.isHeld()) {
                mWakeLock.release();
                Logger.logDebug(LOG_TAG, "WakeLock released for re-acquisition");
            }
            acquireWakeLock();
            Logger.logDebug(LOG_TAG, "WakeLock re-acquired to prevent timeout");
        }
    }
}
