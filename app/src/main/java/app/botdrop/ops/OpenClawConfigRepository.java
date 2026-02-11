package app.botdrop.ops;

import app.botdrop.BotDropConfig;

import org.json.JSONObject;

public class OpenClawConfigRepository implements ConfigRepository {

    @Override
    public JSONObject read() {
        return BotDropConfig.readConfig();
    }

    @Override
    public boolean write(JSONObject config) {
        return BotDropConfig.writeConfig(config);
    }
}
