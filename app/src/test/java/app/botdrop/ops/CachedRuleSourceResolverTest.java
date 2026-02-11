package app.botdrop.ops;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CachedRuleSourceResolverTest {

    @Test
    public void resolveOpenClawSource_fallbackByDefault() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("ops_rule_sources", Context.MODE_PRIVATE).edit().clear().apply();

        CachedRuleSourceResolver resolver = new CachedRuleSourceResolver(context);
        RuleSource source = resolver.resolveOpenClawSource("1.2.3");

        assertEquals(RuleSourceType.LOCAL_FALLBACK, source.sourceType);
        assertTrue(source.sourceVersion.contains("openclaw=1.2.3"));
    }

    @Test
    public void resolveOpenClawSource_prefersSchemaOverDocs() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("ops_rule_sources", Context.MODE_PRIVATE).edit().clear().apply();

        CachedRuleSourceResolver resolver = new CachedRuleSourceResolver(context);
        resolver.saveOpenClawDocsVersion("docs-2026-02-11");
        resolver.saveOpenClawSchemaVersion("schema-2026-02-12");

        RuleSource source = resolver.resolveOpenClawSource("2.0.0");
        assertEquals(RuleSourceType.RUNTIME_SCHEMA, source.sourceType);
        assertTrue(source.sourceVersion.contains("schema-2026-02-12"));
    }
}
