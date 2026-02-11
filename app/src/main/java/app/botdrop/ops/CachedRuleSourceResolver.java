package app.botdrop.ops;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Resolves rule source priority:
 * runtime_schema > official_docs > local_fallback.
 */
public class CachedRuleSourceResolver implements RuleSourceResolver {

    private static final String PREFS = "ops_rule_sources";
    private static final String KEY_OPENCLAW_SCHEMA_VERSION = "openclaw_schema_version";
    private static final String KEY_OPENCLAW_DOCS_VERSION = "openclaw_docs_version";

    private final SharedPreferences prefs;

    public CachedRuleSourceResolver(Context context) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public RuleSource resolveOpenClawSource(String openclawVersion) {
        String schemaVersion = prefs.getString(KEY_OPENCLAW_SCHEMA_VERSION, "");
        if (schemaVersion != null && !schemaVersion.trim().isEmpty()) {
            return new RuleSource(
                RuleSourceType.RUNTIME_SCHEMA,
                attachAgentVersion(schemaVersion.trim(), openclawVersion),
                "openclaw-schema"
            );
        }

        String docsVersion = prefs.getString(KEY_OPENCLAW_DOCS_VERSION, "");
        if (docsVersion != null && !docsVersion.trim().isEmpty()) {
            return new RuleSource(
                RuleSourceType.OFFICIAL_DOCS,
                attachAgentVersion(docsVersion.trim(), openclawVersion),
                "openclaw-docs"
            );
        }

        return new RuleSource(
            RuleSourceType.LOCAL_FALLBACK,
            attachAgentVersion("openclaw-local-v1", openclawVersion),
            "openclaw-agent-rules"
        );
    }

    public void saveOpenClawSchemaVersion(String schemaVersion) {
        prefs.edit().putString(KEY_OPENCLAW_SCHEMA_VERSION, schemaVersion).apply();
    }

    public void saveOpenClawDocsVersion(String docsVersion) {
        prefs.edit().putString(KEY_OPENCLAW_DOCS_VERSION, docsVersion).apply();
    }

    private String attachAgentVersion(String version, String openclawVersion) {
        String agentVersion = (openclawVersion == null || openclawVersion.trim().isEmpty())
            ? "unknown"
            : openclawVersion.trim();
        return version + " | openclaw=" + agentVersion;
    }
}
