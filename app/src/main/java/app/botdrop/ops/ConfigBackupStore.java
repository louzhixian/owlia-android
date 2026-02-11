package app.botdrop.ops;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONObject;

import java.io.FileReader;
import java.io.File;
import java.io.FileWriter;

public class ConfigBackupStore {

    private static final String BACKUP_DIR_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw/backups";
    private static final String BACKUP_PREFIX = "openclaw-";
    private static final String BACKUP_SUFFIX = ".json";

    public String createBackup(JSONObject config) {
        try {
            File backupDir = new File(BACKUP_DIR_PATH);
            if (!backupDir.exists() && !backupDir.mkdirs()) return null;

            String id = String.valueOf(System.currentTimeMillis());
            File backup = new File(backupDir, BACKUP_PREFIX + id + BACKUP_SUFFIX);
            try (FileWriter writer = new FileWriter(backup)) {
                writer.write(config.toString(2));
            }
            return backup.getAbsolutePath();
        } catch (Exception ignored) {
            return null;
        }
    }

    public JSONObject readBackup(String backupPath) {
        if (backupPath == null || backupPath.trim().isEmpty()) return null;
        try {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[1024];
            int read;
            try (FileReader reader = new FileReader(new File(backupPath))) {
                while ((read = reader.read(buffer)) != -1) {
                    sb.append(buffer, 0, read);
                }
            }
            return new JSONObject(sb.toString());
        } catch (Exception ignored) {
            return null;
        }
    }
}
