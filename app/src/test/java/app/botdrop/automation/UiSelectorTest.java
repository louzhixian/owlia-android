package app.botdrop.automation;

import android.graphics.Rect;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;

import static org.junit.Assert.*;

public class UiSelectorTest {

    private static UiNode node(String id, String pkg, String cls, String text, String desc, String resId,
                               boolean clickable, boolean enabled, boolean visible, Rect b) {
        return new UiNode(id, pkg, cls, text, desc, resId, clickable, false, enabled, visible, b);
    }

    @Test
    public void matchByResourceId() throws Exception {
        UiNode n = node("0", "com.example", "android.widget.Button", "OK", null,
            "com.example:id/ok", true, true, true, new Rect(0, 0, 10, 10));

        JSONObject sel = new JSONObject().put("resourceId", "com.example:id/ok");
        assertTrue(UiSelector.compileStack(sel).matches(Collections.singletonList(n)));
    }

    @Test
    public void matchTextContains() throws Exception {
        UiNode n = node("0", "com.example", "android.widget.TextView", "Hello world", null,
            null, false, true, true, new Rect(0, 0, 10, 10));

        JSONObject sel = new JSONObject().put("textContains", "world");
        assertTrue(UiSelector.compileStack(sel).matches(Collections.singletonList(n)));
    }

    @Test
    public void matchAndOrNot() throws Exception {
        UiNode n = node("0", "com.example", "android.widget.Button", "Submit", null,
            "com.example:id/submit", true, true, true, new Rect(0, 0, 10, 10));

        JSONObject sel = new JSONObject()
            .put("and", new org.json.JSONArray()
                .put(new JSONObject().put("packageName", "com.example"))
                .put(new JSONObject().put("clickable", true))
                .put(new JSONObject().put("not", new JSONObject().put("text", "Cancel"))));

        assertTrue(UiSelector.compileStack(sel).matches(Collections.singletonList(n)));

        JSONObject sel2 = new JSONObject()
            .put("or", new org.json.JSONArray()
                .put(new JSONObject().put("text", "Cancel"))
                .put(new JSONObject().put("resourceId", "com.example:id/submit")));
        assertTrue(UiSelector.compileStack(sel2).matches(Collections.singletonList(n)));
    }

    @Test
    public void matchParentConstraint() throws Exception {
        UiNode parent = node("0", "com.example", "android.widget.LinearLayout", null, null,
            "com.example:id/container", false, true, true, new Rect(0, 0, 100, 100));
        UiNode child = node("0/0", "com.example", "android.widget.Button", "OK", null,
            "com.example:id/ok", true, true, true, new Rect(0, 0, 10, 10));

        JSONObject sel = new JSONObject()
            .put("resourceId", "com.example:id/ok")
            .put("parent", new JSONObject().put("resourceId", "com.example:id/container"));

        ArrayList<UiNode> stack = new ArrayList<>();
        stack.add(parent);
        stack.add(child);
        assertTrue(UiSelector.compileStack(sel).matches(stack));
        assertFalse(UiSelector.compileStack(sel).matches(Collections.singletonList(child)));
    }

    @Test
    public void matchAncestorConstraint() throws Exception {
        UiNode root = node("0", "com.example", "android.widget.FrameLayout", null, null,
            "com.example:id/root", false, true, true, new Rect(0, 0, 100, 100));
        UiNode container = node("0/0", "com.example", "android.widget.LinearLayout", null, null,
            "com.example:id/container", false, true, true, new Rect(0, 0, 100, 100));
        UiNode child = node("0/0/0", "com.example", "android.widget.TextView", "OK", null,
            "com.example:id/label", false, true, true, new Rect(0, 0, 10, 10));

        JSONObject sel = new JSONObject()
            .put("text", "OK")
            .put("ancestor", new JSONObject().put("resourceId", "com.example:id/container"));

        ArrayList<UiNode> stack = new ArrayList<>();
        stack.add(root);
        stack.add(container);
        stack.add(child);
        assertTrue(UiSelector.compileStack(sel).matches(stack));

        JSONObject sel2 = new JSONObject()
            .put("text", "OK")
            .put("ancestor", new JSONObject().put("resourceId", "com.example:id/missing"));
        assertFalse(UiSelector.compileStack(sel2).matches(stack));
    }

    @Test
    public void compilePlanExtractsSubtreeSelectors() throws Exception {
        UiNode n = node("0", "com.example", "android.widget.LinearLayout", null, null,
            "com.example:id/container", false, true, true, new Rect(0, 0, 100, 100));
        UiNode child = node("0/0", "com.example", "android.widget.TextView", "OK", null,
            "com.example:id/label", false, true, true, new Rect(0, 0, 10, 10));

        JSONObject sel = new JSONObject()
            .put("resourceId", "com.example:id/container")
            .put("hasChild", new JSONObject().put("text", "OK"));

        UiSelector.Plan plan = UiSelector.compilePlan(sel);
        assertNotNull(plan.baseMatcher);
        assertNotNull(plan.hasChildMatcher);
        assertNull(plan.hasDescendantMatcher);

        ArrayList<UiNode> stack = new ArrayList<>();
        stack.add(n);
        assertTrue(plan.baseMatcher.matches(stack));

        stack.add(child);
        assertTrue(plan.hasChildMatcher.matches(stack));
    }
}
