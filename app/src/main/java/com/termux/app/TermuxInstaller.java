package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.system.Os;
import android.util.Pair;
import android.view.WindowManager;

import com.termux.R;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 * <p/>
 * (1) If $PREFIX already exist, assume that it is correct and be done. Note that this relies on that we do not create a
 * broken $PREFIX directory below.
 * <p/>
 * (2) A progress dialog is shown with "Installing..." message and a spinner.
 * <p/>
 * (3) A staging directory, $STAGING_PREFIX, is cleared if left over from broken installation below.
 * <p/>
 * (4) The zip file is loaded from a shared library.
 * <p/>
 * (5) The zip, containing entries relative to the $PREFIX, is is downloaded and extracted by a zip input stream
 * continuously encountering zip file entries:
 * <p/>
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 * <p/>
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if necessary.
 */
public final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";
    private static final String LEGACY_TERMUX_APP_DATA_DIR_PATH = "/data/data/com.termux";
    private static final String LEGACY_TERMUX_PREFIX_DIR_PATH = "/data/data/com.termux/files/usr";
    private static final String[] LEGACY_PATH_PATCH_CANDIDATES = new String[] {
        "bin/pkg",
        "bin/termux-change-repo",
        "bin/termux-info",
        "bin/termux-reset",
        "bin/login",
        "etc/profile",
        "etc/termux-login.sh",
        "etc/profile.d/init-termux-properties.sh",
        "etc/motd.sh"
    };

    /** Performs bootstrap setup if necessary. */
    public static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        // This will also call Context.getFilesDir(), which should ensure that termux files directory
        // is created if it does not already exist
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        // Termux can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message,
                MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage);
            return;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError);
            //noinspection SdCardPath
            if (PackageUtils.isAppInstalledOnExternalStorage(activity) &&
                !TermuxConstants.TERMUX_FILES_DIR_PATH.equals(activity.getFilesDir().getAbsolutePath().replaceAll("^/data/user/0/", "/data/data/"))) {
                bootstrapErrorMessage += "\n\n" + activity.getString(R.string.bootstrap_error_installed_on_portable_sd,
                    MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            }

            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage, null);
            return;
        }

        // If prefix directory exists, even if its a symlink to a valid directory and symlink is not broken/dangling
        if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true)) {
            if (TermuxFileUtils.isTermuxPrefixDirectoryEmpty()) {
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" exists but is empty or only contains specific unimportant files.");
            } else {
                whenDone.run();
                return;
            }
        } else if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false)) {
            Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" does not exist but another file exists at its destination.");
        }

        final ProgressDialog progress = ProgressDialog.show(activity, null, activity.getString(R.string.bootstrap_installer_body), true, false);
        new Thread() {
            @Override
            public void run() {
                // Acquire a WakeLock to prevent CPU sleep during bootstrap extraction
                PowerManager pm = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
                PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "app.botdrop:bootstrap");
                wakeLock.acquire(10 * 60 * 1000L); // 10 min timeout as safety net
                try {
                    Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");

                    Error error;

                    // Delete prefix staging directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Delete prefix directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix staging directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + TERMUX_STAGING_PREFIX_DIR_PATH + "\".");

                    final byte[] buffer = new byte[8096];
                    final List<Pair<String, String>> symlinks = new ArrayList<>(50);

                    final byte[] zipBytes = loadZipBytes();
                    try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                        ZipEntry zipEntry;
                        while ((zipEntry = zipInput.getNextEntry()) != null) {
                            if (zipEntry.getName().equals("SYMLINKS.txt")) {
                                BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                                String line;
                                while ((line = symlinksReader.readLine()) != null) {
                                    String[] parts = line.split("←");
                                    if (parts.length != 2)
                                        throw new RuntimeException("Malformed symlink line: " + line);
                                    String oldPath = parts[0];
                                    String newPath = TERMUX_STAGING_PREFIX_DIR_PATH + "/" + parts[1];
                                    symlinks.add(Pair.create(oldPath, newPath));

                                    error = ensureDirectoryExists(new File(newPath).getParentFile());
                                    if (error != null) {
                                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                        return;
                                    }
                                }
                            } else {
                                String zipEntryName = zipEntry.getName();
                                File targetFile = new File(TERMUX_STAGING_PREFIX_DIR_PATH, zipEntryName);
                                boolean isDirectory = zipEntry.isDirectory();

                                error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
                                if (error != null) {
                                    showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                    return;
                                }

                                if (!isDirectory) {
                                    try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                                        int readBytes;
                                        while ((readBytes = zipInput.read(buffer)) != -1)
                                            outStream.write(buffer, 0, readBytes);
                                    }
                                    if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") ||
                                        zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods")) {
                                        //noinspection OctalInteger
                                        Os.chmod(targetFile.getAbsolutePath(), 0700);
                                    }
                                }
                            }
                        }
                    }

                    if (symlinks.isEmpty())
                        throw new RuntimeException("No SYMLINKS.txt encountered");
                    for (Pair<String, String> symlink : symlinks) {
                        Os.symlink(symlink.first, symlink.second);
                    }

                    // Some upstream bootstrap assets still contain hardcoded legacy com.termux paths.
                    // Rewrite known script/config files before moving staging -> final prefix.
                    patchLegacyTermuxPaths(TERMUX_STAGING_PREFIX_DIR);

                    Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");

                    if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR)) {
                        throw new RuntimeException("Moving termux prefix staging to prefix directory failed");
                    }

                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");

                    // Recreate env file since termux prefix was wiped earlier
                    TermuxShellEnvironment.writeEnvironmentToFile(activity);

                    // Create BotDrop install script and environment
                    createBotDropScripts();

                    activity.runOnUiThread(whenDone);

                } catch (final Exception e) {
                    showBootstrapErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));

                } finally {
                    if (wakeLock.isHeld()) wakeLock.release();
                    activity.runOnUiThread(() -> {
                        try {
                            progress.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed - ignore.
                        }
                    });
                }
            }
        }.start();
    }

    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);

        // Send a notification with the exception so that the user knows why bootstrap setup failed
        sendBootstrapCrashReportNotification(activity, message);

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        final String title = TermuxConstants.TERMUX_APP_NAME + " Bootstrap Error";

        // Add info of all install Termux plugin apps as well since their target sdk or installation
        // on external/portable sd card can affect Termux app files directory access or exec.
        TermuxCrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            title, null, "## " + title + "\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true);
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                            "## " + title + "\n\n" + Error.getErrorMarkdownString(error),
                            true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");

                    // Get primary storage root "/storage/emulated/0" symlink
                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    Os.symlink(documentsDir.getAbsolutePath(), new File(storageDir, "documents").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    File podcastsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS);
                    Os.symlink(podcastsDir.getAbsolutePath(), new File(storageDir, "podcasts").getAbsolutePath());

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        File audiobooksDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS);
                        Os.symlink(audiobooksDir.getAbsolutePath(), new File(storageDir, "audiobooks").getAbsolutePath());
                    }

                    // Dir 0 should ideally be for primary storage
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/app/ContextImpl.java;l=818
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=219
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=181
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/services/core/java/com/android/server/StorageManagerService.java;l=3796
                    // https://cs.android.com/android/platform/superproject/+/android-7.0.0_r36:frameworks/base/services/core/java/com/android/server/MountService.java;l=3053

                    // Create "Android/data/com.termux" symlinks
                    File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    // Create "Android/media/com.termux" symlinks
                    dirs = context.getExternalMediaDirs();
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "media-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                        "## " + title + "\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
                        true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                }
            }
        }.start();
    }

    private static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }

    public static byte[] loadZipBytes() {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap");
        return getZip();
    }

    public static native byte[] getZip();

    public static void patchLegacyPathsInInstalledPrefix() {
        patchLegacyTermuxPaths(TERMUX_PREFIX_DIR);
    }

    private static void patchLegacyTermuxPaths(File prefixDir) {
        int patched = 0;
        for (String relativePath : LEGACY_PATH_PATCH_CANDIDATES) {
            File file = new File(prefixDir, relativePath);
            if (!file.isFile()) continue;
            if (patchLegacyTermuxPathInFile(file)) patched++;
        }
        Logger.logInfo(LOG_TAG, "Bootstrap legacy-path patch done. patchedFiles=" + patched);
    }

    private static boolean patchLegacyTermuxPathInFile(File file) {
        try {
            String text = readUtf8File(file);
            String patched = text
                .replace(LEGACY_TERMUX_PREFIX_DIR_PATH, TermuxConstants.TERMUX_PREFIX_DIR_PATH)
                .replace(LEGACY_TERMUX_APP_DATA_DIR_PATH, TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH);

            if (patched.equals(text)) return false;

            writeUtf8File(file, patched);
            Logger.logInfo(LOG_TAG, "Patched legacy prefix path in " + file.getAbsolutePath());
            return true;
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to patch legacy path for " + file.getAbsolutePath() + ": " + e.getMessage());
            return false;
        }
    }

    private static String readUtf8File(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void writeUtf8File(File file, String content) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Creates the BotDrop installation script and environment setup.
     *
     * Creates:
     * 1. $PREFIX/share/botdrop/install.sh — standalone installer with structured output
     *    (called by both GUI ProcessBuilder and terminal profile.d)
     * 2. $PREFIX/etc/profile.d/botdrop-env.sh — environment (alias, sshd auto-start)
     *
     * The install.sh outputs structured lines for GUI parsing:
     *   BOTDROP_STEP:N:START:message
     *   BOTDROP_STEP:N:DONE
     *   BOTDROP_COMPLETE
     *   BOTDROP_ERROR:message
     */
    private static void createBotDropScripts() {
        try {
            // --- 1. Create install.sh ---

            File botdropDir = new File(TERMUX_PREFIX_DIR_PATH + "/share/botdrop");
            if (!botdropDir.exists()) {
                botdropDir.mkdirs();
            }

            File installScript = new File(botdropDir, "install.sh");
            String installContent =
                "#!" + com.termux.shared.termux.TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash\n" +
                "# BotDrop install script — single source of truth\n" +
                "# Called by: GUI (ProcessBuilder) and terminal (profile.d)\n" +
                "# Outputs structured lines for GUI progress parsing.\n\n" +
                "LOGFILE=\"$HOME/botdrop-install.log\"\n" +
                "exec > >(tee -a \"$LOGFILE\") 2>&1\n" +
                "echo \"=== BotDrop install started: $(date) ===\"\n\n" +
                "MARKER=\"$HOME/.botdrop_installed\"\n\n" +
                "if [ -f \"$MARKER\" ]; then\n" +
                "    echo \"BOTDROP_ALREADY_INSTALLED\"\n" +
                "    exit 0\n" +
                "fi\n\n" +
                "echo \"BOTDROP_STEP:0:START:Setting up environment\"\n" +
                "chmod +x $PREFIX/bin/* 2>/dev/null\n" +
                "chmod +x $PREFIX/lib/node_modules/.bin/* 2>/dev/null\n" +
                "chmod +x $PREFIX/lib/node_modules/npm/bin/* 2>/dev/null\n" +
                "# Generate SSH host keys if missing (openssh.postinst equivalent)\n" +
                "mkdir -p $PREFIX/var/empty\n" +
                "mkdir -p $HOME/.ssh\n" +
                "touch $HOME/.ssh/authorized_keys\n" +
                "chmod 700 $HOME/.ssh\n" +
                "chmod 600 $HOME/.ssh/authorized_keys\n" +
                "for a in rsa ecdsa ed25519; do\n" +
                "    KEYFILE=\"$PREFIX/etc/ssh/ssh_host_${a}_key\"\n" +
                "    test ! -f \"$KEYFILE\" && ssh-keygen -N '' -t $a -f \"$KEYFILE\" >/dev/null 2>&1\n" +
                "done\n" +
                "# Generate random SSH password\n" +
                "SSH_PASS=$(head -c 12 /dev/urandom | base64 | tr -d '/+=' | head -c 12)\n" +
                "printf '%s\\n%s\\n' \"$SSH_PASS\" \"$SSH_PASS\" | passwd >/dev/null 2>&1\n" +
                "echo \"$SSH_PASS\" > \"$HOME/.ssh_password\"\n" +
                "chmod 600 \"$HOME/.ssh_password\"\n" +
                "# Create required OpenClaw directories\n" +
                "mkdir -p $HOME/.openclaw/agents/main/agent\n" +
                "mkdir -p $HOME/.openclaw/agents/main/sessions\n" +
                "mkdir -p $HOME/.openclaw/credentials\n" +
                "# Start sshd (port 8022)\n" +
                "if ! pgrep -x sshd >/dev/null 2>&1; then\n" +
                "    sshd 2>/dev/null\n" +
                "fi\n" +
                "echo \"BOTDROP_STEP:0:DONE\"\n\n" +
                "echo \"BOTDROP_STEP:1:START:Verifying Node.js\"\n" +
                "NODE_V=$(node --version 2>&1)\n" +
                "NPM_V=$(npm --version 2>&1)\n" +
                "if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then\n" +
                "    echo \"BOTDROP_ERROR:Node.js or npm not found. Bootstrap may be corrupted.\"\n" +
                "    exit 1\n" +
                "fi\n" +
                "echo \"BOTDROP_INFO:Node $NODE_V, npm $NPM_V\"\n" +
                "echo \"BOTDROP_STEP:1:DONE\"\n\n" +
                "echo \"BOTDROP_STEP:2:START:Installing OpenClaw\"\n" +
                "rm -rf $PREFIX/lib/node_modules/openclaw 2>/dev/null\n" +
                "NPM_OUTPUT=$(npm install -g openclaw@latest --ignore-scripts --force 2>&1)\n" +
                "NPM_EXIT=$?\n" +
                "if [ $NPM_EXIT -eq 0 ]; then\n" +
                "    # Create a stable openclaw wrapper (npm-generated shim can be broken on Android/proot)\n" +
                "    cat > $PREFIX/bin/openclaw <<'BOTDROP_OPENCLAW_WRAPPER'\n" +
                "#!" + com.termux.shared.termux.TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash\n" +
                "PREFIX=\"$(cd \"$(dirname \"$0\")/..\" && pwd)\"\n" +
                "ENTRY=\"\"\n" +
                "for CANDIDATE in \\\n" +
                "  \"$PREFIX/lib/node_modules/openclaw/dist/cli.js\" \\\n" +
                "  \"$PREFIX/lib/node_modules/openclaw/bin/openclaw.js\" \\\n" +
                "  \"$PREFIX/lib/node_modules/openclaw/dist/index.js\"; do\n" +
                "  if [ -f \"$CANDIDATE\" ]; then\n" +
                "    ENTRY=\"$CANDIDATE\"\n" +
                "    break\n" +
                "  fi\n" +
                "done\n" +
                "if [ -z \"$ENTRY\" ]; then\n" +
                "  echo \"openclaw entrypoint not found under $PREFIX/lib/node_modules/openclaw\" >&2\n" +
                "  exit 127\n" +
                "fi\n" +
                "export SSL_CERT_FILE=\"$PREFIX/etc/tls/cert.pem\"\n" +
                "export NODE_OPTIONS=\"--dns-result-order=ipv4first\"\n" +
                "exec \"$PREFIX/bin/termux-chroot\" \"$PREFIX/bin/node\" \"$ENTRY\" \"$@\"\n" +
                "BOTDROP_OPENCLAW_WRAPPER\n" +
                "    chmod 755 $PREFIX/bin/openclaw\n" +
                "\n" +
                "    # BotDrop UI automation client (Unix socket)\n" +
                "    mkdir -p $PREFIX/share/botdrop\n" +
                "    cat > $PREFIX/share/botdrop/ui_automation_client.js <<'BOTDROP_UI_CLIENT'\n" +
                "\"use strict\";\n" +
                "const net = require(\"net\");\n" +
                "const prefix = process.env.PREFIX;\n" +
                "if (!prefix) {\n" +
                "  console.error(\"PREFIX is not set (expected in Termux env).\");\n" +
                "  process.exit(1);\n" +
                "}\n" +
                "const sock = `${prefix}/var/run/botdrop-ui.sock`;\n" +
                "const raw = process.argv[2];\n" +
                "if (!raw) {\n" +
                "  console.error(\"Missing request JSON argument.\");\n" +
                "  process.exit(1);\n" +
                "}\n" +
                "let req;\n" +
                "try { req = JSON.parse(raw); } catch (e) {\n" +
                "  console.error(\"Invalid JSON:\", e.message);\n" +
                "  process.exit(1);\n" +
                "}\n" +
                "const s = net.createConnection({ path: sock }, () => s.end(JSON.stringify(req)));\n" +
                "let buf = \"\";\n" +
                "s.on(\"data\", (d) => (buf += d.toString(\"utf8\")));\n" +
                "s.on(\"end\", () => {\n" +
                "  try {\n" +
                "    const obj = JSON.parse(buf);\n" +
                "    process.stdout.write(JSON.stringify(obj, null, 2) + \"\\n\");\n" +
                "  } catch {\n" +
                "    process.stdout.write(buf + \"\\n\");\n" +
                "  }\n" +
                "});\n" +
                "s.on(\"error\", (e) => {\n" +
                "  console.error(\"Socket error:\", e.message);\n" +
                "  process.exit(1);\n" +
                "});\n" +
                "BOTDROP_UI_CLIENT\n" +
                "\n" +
                "    cat > $PREFIX/bin/botdrop-ui <<'BOTDROP_UI_WRAPPER'\n" +
                "#!" + com.termux.shared.termux.TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash\n" +
                "set -e\n" +
                "if [ $# -lt 1 ]; then\n" +
                "  echo \"usage: botdrop-ui '{...json...}'\" >&2\n" +
                "  exit 2\n" +
                "fi\n" +
                "exec \"$PREFIX/bin/node\" \"$PREFIX/share/botdrop/ui_automation_client.js\" \"$1\"\n" +
                "BOTDROP_UI_WRAPPER\n" +
                "    chmod 755 $PREFIX/bin/botdrop-ui\n" +
                "\n" +
                "    # Install an OpenClaw skill README so the agent knows how to drive the UI.\n" +
                "    mkdir -p $HOME/.openclaw/skills/botdrop-ui\n" +
                "    mkdir -p $HOME/.openclaw/agents/main/agent/skills/botdrop-ui\n" +
                "    cat > $HOME/.openclaw/skills/botdrop-ui/apps-aliases.json <<'BOTDROP_UI_ALIASES'\n" +
                "{\n" +
                "  \"version\": 4,\n" +
                "  \"note\": \"Expanded common app alias mapping (global + China-focused), with optional preferredComponent.\"," +
                "\n" +
                "  \"apps\": [\n" +
                "    {\"package\":\"com.twitter.android\",\"aliases\":[\"x\",\"x app\",\"twitter\",\"tweet\",\"\u63a8\u7279\"]},\n" +
                "    {\"package\":\"com.android.chrome\",\"aliases\":[\"chrome\",\"google chrome\",\"\u8c37\u6b4c\u6d4f\u89c8\u5668\"]},\n" +
                "    {\"package\":\"org.mozilla.firefox\",\"aliases\":[\"firefox\",\"\u706b\u72d0\"]},\n" +
                "    {\"package\":\"com.microsoft.emmx\",\"aliases\":[\"edge\",\"microsoft edge\"]},\n" +
                "    {\"package\":\"com.brave.browser\",\"aliases\":[\"brave\",\"brave browser\"]},\n" +
                "    {\"package\":\"com.duckduckgo.mobile.android\",\"aliases\":[\"duckduckgo\",\"ddg\"]},\n" +
                "    {\"package\":\"com.google.android.googlequicksearchbox\",\"aliases\":[\"google\",\"google app\",\"\u8c37\u6b4c\"]},\n" +
                "    {\"package\":\"com.google.android.youtube\",\"aliases\":[\"youtube\",\"yt\",\"\u6cb9\u7ba1\"]},\n" +
                "    {\"package\":\"com.google.android.apps.youtube.music\",\"aliases\":[\"youtube music\",\"yt music\"]},\n" +
                "    {\"package\":\"com.instagram.android\",\"aliases\":[\"instagram\",\"ig\"]},\n" +
                "    {\"package\":\"com.zhiliaoapp.musically\",\"aliases\":[\"tiktok\",\"tik tok\",\"douyin international\"]},\n" +
                "    {\"package\":\"com.ss.android.ugc.aweme\",\"aliases\":[\"douyin\",\"\u6296\u97f3\"]},\n" +
                "    {\"package\":\"com.xingin.xhs\",\"aliases\":[\"xiaohongshu\",\"xhs\",\"rednote\",\"\u5c0f\u7ea2\u4e66\"]},\n" +
                "    {\"package\":\"com.kuaishou.nebula\",\"aliases\":[\"kuaishou\",\"\u5feb\u624b\"]},\n" +
                "    {\"package\":\"tv.danmaku.bili\",\"aliases\":[\"bilibili\",\"bili\",\"\u54d4\u54e9\u54d4\u54e9\"],\"preferredComponent\":\"tv.danmaku.bili/.MainActivityV2\"},\n" +
                "    {\"package\":\"com.sina.weibo\",\"aliases\":[\"weibo\",\"\u5fae\u535a\"]},\n" +
                "    {\"package\":\"com.zhihu.android\",\"aliases\":[\"zhihu\",\"\u77e5\u4e4e\"]},\n" +
                "    {\"package\":\"com.facebook.katana\",\"aliases\":[\"facebook\",\"fb\",\"\u8138\u4e66\"]},\n" +
                "    {\"package\":\"com.facebook.orca\",\"aliases\":[\"messenger\",\"facebook messenger\"]},\n" +
                "    {\"package\":\"com.snapchat.android\",\"aliases\":[\"snapchat\",\"snap\"]},\n" +
                "    {\"package\":\"com.linkedin.android\",\"aliases\":[\"linkedin\"]},\n" +
                "    {\"package\":\"com.reddit.frontpage\",\"aliases\":[\"reddit\"]},\n" +
                "    {\"package\":\"com.discord\",\"aliases\":[\"discord\",\"dc\"]},\n" +
                "    {\"package\":\"org.telegram.messenger\",\"aliases\":[\"telegram\",\"tg\",\"\u98de\u673a\"]},\n" +
                "    {\"package\":\"org.thoughtcrime.securesms\",\"aliases\":[\"signal\"]},\n" +
                "    {\"package\":\"com.whatsapp\",\"aliases\":[\"whatsapp\",\"wa\"]},\n" +
                "    {\"package\":\"com.viber.voip\",\"aliases\":[\"viber\"]},\n" +
                "    {\"package\":\"com.skype.raider\",\"aliases\":[\"skype\"]},\n" +
                "    {\"package\":\"com.microsoft.teams\",\"aliases\":[\"teams\",\"microsoft teams\"]},\n" +
                "    {\"package\":\"us.zoom.videomeetings\",\"aliases\":[\"zoom\"]},\n" +
                "    {\"package\":\"com.slack\",\"aliases\":[\"slack\"]},\n" +
                "    {\"package\":\"com.tencent.mm\",\"aliases\":[\"wechat\",\"weixin\",\"wx\",\"\u5fae\u4fe1\"],\"preferredComponent\":\"com.tencent.mm/.ui.LauncherUI\"},\n" +
                "    {\"package\":\"com.tencent.mobileqq\",\"aliases\":[\"qq\",\"\u817e\u8bafqq\"]},\n" +
                "    {\"package\":\"com.tencent.wework\",\"aliases\":[\"wecom\",\"wechat work\",\"\u4f01\u4e1a\u5fae\u4fe1\"]},\n" +
                "    {\"package\":\"com.alibaba.android.rimet\",\"aliases\":[\"dingtalk\",\"\u9489\u9489\"]},\n" +
                "    {\"package\":\"com.tencent.wemeet.app\",\"aliases\":[\"tencent meeting\",\"\u817e\u8baf\u4f1a\u8bae\"]},\n" +
                "    {\"package\":\"com.spotify.music\",\"aliases\":[\"spotify\"]},\n" +
                "    {\"package\":\"com.apple.android.music\",\"aliases\":[\"apple music\"]},\n" +
                "    {\"package\":\"com.netease.cloudmusic\",\"aliases\":[\"netease music\",\"\u7f51\u6613\u4e91\u97f3\u4e50\"]},\n" +
                "    {\"package\":\"com.tencent.qqmusic\",\"aliases\":[\"qq music\",\"\u817e\u8baf\u97f3\u4e50\"]},\n" +
                "    {\"package\":\"com.google.android.apps.maps\",\"aliases\":[\"google maps\",\"maps\"]},\n" +
                "    {\"package\":\"com.waze\",\"aliases\":[\"waze\"]},\n" +
                "    {\"package\":\"com.autonavi.minimap\",\"aliases\":[\"amap\",\"gaode\",\"\u9ad8\u5fb7\u5730\u56fe\"]},\n" +
                "    {\"package\":\"com.baidu.BaiduMap\",\"aliases\":[\"baidu map\",\"\u767e\u5ea6\u5730\u56fe\"]},\n" +
                "    {\"package\":\"com.ubercab\",\"aliases\":[\"uber\"]},\n" +
                "    {\"package\":\"com.ubercab.eats\",\"aliases\":[\"uber eats\"]},\n" +
                "    {\"package\":\"com.lyft.android\",\"aliases\":[\"lyft\"]},\n" +
                "    {\"package\":\"com.didapinche.booking\",\"aliases\":[\"didi\",\"\u6ef4\u6ef4\"]},\n" +
                "    {\"package\":\"com.airbnb.android\",\"aliases\":[\"airbnb\"]},\n" +
                "    {\"package\":\"com.booking\",\"aliases\":[\"booking\",\"booking.com\"]},\n" +
                "    {\"package\":\"com.expedia.bookings\",\"aliases\":[\"expedia\"]},\n" +
                "    {\"package\":\"ctrip.android.view\",\"aliases\":[\"ctrip\",\"\u643a\u7a0b\"]},\n" +
                "    {\"package\":\"com.netflix.mediaclient\",\"aliases\":[\"netflix\",\"\u5948\u98de\"]},\n" +
                "    {\"package\":\"com.amazon.avod.thirdpartyclient\",\"aliases\":[\"prime video\",\"amazon prime video\"]},\n" +
                "    {\"package\":\"com.disney.disneyplus\",\"aliases\":[\"disney+\",\"disney plus\"]},\n" +
                "    {\"package\":\"tv.twitch.android.app\",\"aliases\":[\"twitch\"]},\n" +
                "    {\"package\":\"com.qiyi.video\",\"aliases\":[\"iqiyi\",\"\u7231\u5947\u827a\"]},\n" +
                "    {\"package\":\"com.youku.phone\",\"aliases\":[\"youku\",\"\u4f18\u9177\"]},\n" +
                "    {\"package\":\"com.tencent.qqlive\",\"aliases\":[\"tencent video\",\"\u817e\u8baf\u89c6\u9891\"]},\n" +
                "    {\"package\":\"com.google.android.gm\",\"aliases\":[\"gmail\"]},\n" +
                "    {\"package\":\"com.microsoft.office.outlook\",\"aliases\":[\"outlook\"]},\n" +
                "    {\"package\":\"com.google.android.calendar\",\"aliases\":[\"google calendar\",\"calendar\"]},\n" +
                "    {\"package\":\"com.google.android.apps.docs\",\"aliases\":[\"google drive\",\"drive\"]},\n" +
                "    {\"package\":\"com.google.android.apps.photos\",\"aliases\":[\"google photos\",\"photos\"]},\n" +
                "    {\"package\":\"com.google.android.keep\",\"aliases\":[\"google keep\",\"keep\"]},\n" +
                "    {\"package\":\"com.amazon.mShop.android.shopping\",\"aliases\":[\"amazon\",\"amazon shopping\"]},\n" +
                "    {\"package\":\"com.ebay.mobile\",\"aliases\":[\"ebay\"]},\n" +
                "    {\"package\":\"com.walmart.android\",\"aliases\":[\"walmart\"]},\n" +
                "    {\"package\":\"com.target.ui\",\"aliases\":[\"target\"]},\n" +
                "    {\"package\":\"com.dd.doordash\",\"aliases\":[\"doordash\"]},\n" +
                "    {\"package\":\"com.grubhub.android\",\"aliases\":[\"grubhub\"]},\n" +
                "    {\"package\":\"com.instacart.client\",\"aliases\":[\"instacart\"]},\n" +
                "    {\"package\":\"com.taobao.taobao\",\"aliases\":[\"taobao\",\"\u6dd8\u5b9d\"]},\n" +
                "    {\"package\":\"com.tmall.wireless\",\"aliases\":[\"tmall\",\"\u5929\u732b\"]},\n" +
                "    {\"package\":\"com.jingdong.app.mall\",\"aliases\":[\"jd\",\"jingdong\",\"\u4eac\u4e1c\"]},\n" +
                "    {\"package\":\"com.xunmeng.pinduoduo\",\"aliases\":[\"pinduoduo\",\"pdd\",\"\u62fc\u591a\u591a\"]},\n" +
                "    {\"package\":\"com.suning.mobile.ebuy\",\"aliases\":[\"suning\",\"\u82cf\u5b81\"]},\n" +
                "    {\"package\":\"com.sankuai.meituan\",\"aliases\":[\"meituan\",\"\u7f8e\u56e2\"]},\n" +
                "    {\"package\":\"me.ele\",\"aliases\":[\"eleme\",\"\u997f\u4e86\u4e48\"]},\n" +
                "    {\"package\":\"com.eg.android.AlipayGphone\",\"aliases\":[\"alipay\",\"\u652f\u4ed8\u5b9d\"]},\n" +
                "    {\"package\":\"com.tencent.mtt\",\"aliases\":[\"qq browser\",\"\u624b\u673aqq\u6d4f\u89c8\u5668\"]},\n" +
                "    {\"package\":\"com.quark.browser\",\"aliases\":[\"quark\",\"\u5938\u514b\"]},\n" +
                "    {\"package\":\"com.UCMobile\",\"aliases\":[\"uc browser\",\"uc\"]}\n" +
                "  ]\n" +
                "}\n" +
                "BOTDROP_UI_ALIASES\n" +
                "    cat > $HOME/.openclaw/skills/botdrop-ui/SKILL.md <<'BOTDROP_UI_SKILL'\n" +
                "# BotDrop UI Automation (Android)\n" +
                "\n" +
                "This skill lets the agent control Android UI using BotDrop's AccessibilityService and a local Unix socket API.\n" +
                "\n" +
                "Prerequisites\n" +
                "- BotDrop app opened at least once (Automation Controller notification is visible).\n" +
                "- Accessibility: enable \"BotDrop Accessibility\" in Android Settings.\n" +
                "\n" +
                "How To Call\n" +
                "- Use the `botdrop-ui` CLI from the Termux environment.\n" +
                "- It takes exactly one argument: a JSON string.\n" +
                "- Do NOT use `adb pair` / `adb connect` for this skill. It is local Accessibility automation.\n" +
                "\n" +
                "Open App Strategy (Important)\n" +
                "- First read alias map: `~/.openclaw/skills/botdrop-ui/apps-aliases.json`.\n" +
                "- Resolve user app name (English/Chinese aliases) to package.\n" +
                "- Launch with shell command: `am start -n <package>/<launcherActivity>` when launcher activity is known.\n" +
                "- If launcher activity is unknown, use monkey fallback: `monkey -p <package> -c android.intent.category.LAUNCHER 1`.\n" +
                "- After launch, verify using:\n" +
                "  - `botdrop-ui '{\"op\":\"wait\",\"event\":\"windowChanged\",\"sinceMs\":0,\"timeoutMs\":5000}'`\n" +
                "  - `botdrop-ui '{\"op\":\"tree\",\"maxNodes\":200}'` and check current package.\n" +
                "- If alias is missing, fallback to launcher icon flow:\n" +
                "  - go to launcher screen\n" +
                "  - find by text/content-desc\n" +
                "  - click icon and verify package changed\n" +
                "- If Android shows intent confirmation dialog (e.g. \"BotDrop wants to open X\"):\n" +
                "  - click `\u59cb\u7ec8\u6253\u5f00` / `Always` first\n" +
                "  - fallback to `\u4ec5\u6b64\u4e00\u6b21` / `Just once`\n" +
                "  - OEM prompts like `\u5141\u8bb8` / `\u786e\u8ba4` are auto-handled by `openApp`\n" +
                "  - then `wait windowChanged` and verify package changed\n" +
                "\n" +
                "Examples\n" +
                "```bash\n" +
                "botdrop-ui '{\"op\":\"ping\"}'\n" +
                "botdrop-ui '{\"op\":\"openApp\",\"packageName\":\"com.twitter.android\",\"timeoutMs\":12000}'\n" +
                "botdrop-ui '{\"op\":\"tree\",\"maxNodes\":400}'\n" +
                "botdrop-ui '{\"op\":\"global\",\"action\":\"home\"}'\n" +
                "botdrop-ui '{\"op\":\"find\",\"selector\":{\"resourceId\":\"com.example:id/ok\"},\"mode\":\"first\",\"timeoutMs\":3000}'\n" +
                "botdrop-ui '{\"op\":\"action\",\"target\":{\"selector\":{\"textContains\":\"OK\"}},\"action\":\"click\",\"timeoutMs\":3000}'\n" +
                "botdrop-ui '{\"op\":\"action\",\"target\":{\"selector\":{\"resourceId\":\"com.example:id/input\"}},\"action\":\"setText\",\"args\":{\"text\":\"hello\"},\"timeoutMs\":3000}'\n" +
                "botdrop-ui '{\"op\":\"wait\",\"event\":\"windowChanged\",\"sinceMs\":0,\"timeoutMs\":5000}'\n" +
                "```\n" +
                "\n" +
                "Selectors (MVP)\n" +
                "- Stable fields: `resourceId`, `contentDescContains`, `packageName`, `className`\n" +
                "- Text fields: `text`, `textContains`\n" +
                "- Composition: `and`, `or`, `not`, `parent`\n" +
                "\n" +
                "Notes\n" +
                "- Prefer `resourceId` over text when possible.\n" +
                "- Use `timeoutMs` on `find`/`action` for synchronization.\n" +
                "- Do not treat `pm list packages` as source of truth for installation status.\n" +
                "  Android package visibility can hide apps from package queries.\n" +
                "BOTDROP_UI_SKILL\n" +
                "    cp -f $HOME/.openclaw/skills/botdrop-ui/SKILL.md $HOME/.openclaw/agents/main/agent/skills/botdrop-ui/SKILL.md 2>/dev/null || true\n" +
                "    cp -f $HOME/.openclaw/skills/botdrop-ui/apps-aliases.json $HOME/.openclaw/agents/main/agent/skills/botdrop-ui/apps-aliases.json 2>/dev/null || true\n" +
                "\n" +
                "    # Also install into OpenClaw's system skills dir for out-of-box loading.\n" +
                "    SYS_SKILLS_DIR=\"$PREFIX/lib/node_modules/openclaw/skills\"\n" +
                "    if [ -d \"$PREFIX/lib/node_modules/openclaw\" ]; then\n" +
                "        mkdir -p \"$SYS_SKILLS_DIR/botdrop-ui\" 2>/dev/null || true\n" +
                "        cp -f \"$HOME/.openclaw/skills/botdrop-ui/SKILL.md\" \"$SYS_SKILLS_DIR/botdrop-ui/SKILL.md\" 2>/dev/null || true\n" +
                "        cp -f \"$HOME/.openclaw/skills/botdrop-ui/apps-aliases.json\" \"$SYS_SKILLS_DIR/botdrop-ui/apps-aliases.json\" 2>/dev/null || true\n" +
                "    fi\n" +
                "    echo \"BOTDROP_STEP:2:DONE\"\n" +
                "    touch \"$MARKER\"\n" +
                "    echo \"BOTDROP_COMPLETE\"\n" +
            "else\n" +
                "    echo \"BOTDROP_ERROR:npm install failed (exit $NPM_EXIT): $NPM_OUTPUT\"\n" +
                "    exit 1\n" +
                "fi\n";

            try (FileOutputStream fos = new FileOutputStream(installScript)) {
                fos.write(installContent.getBytes());
            }
            //noinspection OctalInteger
            Os.chmod(installScript.getAbsolutePath(), 0755);

            // --- 2. Create profile.d env script ---

            File profileDir = new File(TERMUX_PREFIX_DIR_PATH + "/etc/profile.d");
            if (!profileDir.exists()) {
                profileDir.mkdirs();
            }

            File envScript = new File(profileDir, "botdrop-env.sh");
            String envContent =
                "# BotDrop environment setup\n" +
                "export TMPDIR=$PREFIX/tmp\n" +
                "mkdir -p $TMPDIR 2>/dev/null\n\n" +
                "# `openclaw` is installed as a wrapper that already runs under `termux-chroot`.\n" +
                "# Avoid nesting proot/termux-chroot which can make commands extremely slow.\n\n" +
                "# Auto-start sshd if not running\n" +
                "if ! pgrep -x sshd >/dev/null 2>&1; then\n" +
                "    sshd 2>/dev/null\n" +
                "fi\n\n" +
                "# Run install if not done yet\n" +
                "if [ ! -f \"$HOME/.botdrop_installed\" ]; then\n" +
                "    echo \"\\U0001F4A7 Setting up BotDrop...\"\n" +
                "    bash $PREFIX/share/botdrop/install.sh\n" +
                "fi\n";

            try (FileOutputStream fos = new FileOutputStream(envScript)) {
                fos.write(envContent.getBytes());
            }
            //noinspection OctalInteger
            Os.chmod(envScript.getAbsolutePath(), 0755);

            Logger.logInfo(LOG_TAG, "Created BotDrop scripts in " + botdropDir.getAbsolutePath());

        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to create BotDrop scripts", e);
        }
    }

}
