package app.botdrop.ops;

import org.json.JSONObject;

public interface ConfigRepository {
    JSONObject read();
    boolean write(JSONObject config);
}
