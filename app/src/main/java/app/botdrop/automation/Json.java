package app.botdrop.automation;

import org.json.JSONObject;

/** Small helper to avoid noisy JSONException handling. */
public final class Json {
    private Json() {}

    public static JSONObject put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Exception ignored) {}
        return o;
    }
}

