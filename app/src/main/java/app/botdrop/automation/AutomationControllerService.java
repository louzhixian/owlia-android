package app.botdrop.automation;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.termux.R;
import com.termux.shared.errors.Error;
import com.termux.shared.logger.Logger;
import com.termux.shared.net.socket.local.LocalSocketManager;
import com.termux.shared.net.socket.local.LocalSocketRunConfig;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;

/**
 * Foreground controller that exposes a local unix socket API for UI automation.
 *
 * This intentionally does not implement auth/token for now (dedicated device assumption).
 */
public class AutomationControllerService extends Service {

    private static final String LOG_TAG = "AutomationControllerService";

    public static final String NOTIFICATION_CHANNEL_ID = "botdrop_automation";
    private static final int NOTIFICATION_ID = 2001;

    public static final String SOCKET_PATH =
        TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/run/botdrop-ui.sock";

    private LocalSocketManager mSocketManager;

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.logInfo(LOG_TAG, "onCreate");
        try {
            startForegroundInternal();
        } catch (Throwable t) {
            // An exception here would crash the whole app process. Keep it defensive.
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start foreground notification", t);
            stopSelf();
            return;
        }

        // Start the native-backed socket server off the main thread to reduce risk of ANR/crash
        // during service startup on OEM ROMs.
        new Thread(() -> {
            try {
                startSocketServer();
            } catch (Throwable t) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Socket server startup failed", t);
                stopSelf();
            }
        }, "BotDropUiSockStart").start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Keep running. The user enables/disables accessibility separately in system settings.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Logger.logInfo(LOG_TAG, "onDestroy");
        stopSocketServer();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startSocketServer() {
        // Ensure parent directory exists.
        try {
            File dir = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/run");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to create socket dir: " + e.getMessage());
        }

        UiAutomationSocketServer serverClient = new UiAutomationSocketServer();
        LocalSocketRunConfig runConfig = new LocalSocketRunConfig(
            "BotDropUiAutomation",
            SOCKET_PATH,
            serverClient
        );

        mSocketManager = new LocalSocketManager(this, runConfig);
        Error error = mSocketManager.start();
        if (error != null) {
            Logger.logErrorExtended(LOG_TAG, "Failed to start socket server:\n" + error.getErrorLogString());
            // No point in keeping the controller alive if API server isn't running.
            stopSelf();
        } else {
            Logger.logInfo(LOG_TAG, "Socket server started at " + SOCKET_PATH);
        }
    }

    private void stopSocketServer() {
        if (mSocketManager == null) return;
        try {
            mSocketManager.stop();
        } catch (Exception ignored) {}
        mSocketManager = null;
    }

    private void startForegroundInternal() {
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("BotDrop Automation")
            .setContentText("UI automation controller running")
            .setSmallIcon(R.drawable.ic_service_notification)
            .setOngoing(true)
            .build();
        startForeground(NOTIFICATION_ID, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "BotDrop Automation",
            NotificationManager.IMPORTANCE_LOW
        );
        nm.createNotificationChannel(channel);
    }
}
