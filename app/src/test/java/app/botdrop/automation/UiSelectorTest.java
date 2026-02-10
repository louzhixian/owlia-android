package app.botdrop.automation;

import android.graphics.Rect;

import org.json.JSONObject;
import org.junit.Test;

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
        assertTrue(UiSelector.compile(sel).matches(n, null));
    }

    @Test
    public void matchTextContains() throws Exception {
        UiNode n = node("0", "com.example", "android.widget.TextView", "Hello world", null,
            null, false, true, true, new Rect(0, 0, 10, 10));

        JSONObject sel = new JSONObject().put("textContains", "world");
        assertTrue(UiSelector.compile(sel).matches(n, null));
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

        assertTrue(UiSelector.compile(sel).matches(n, null));

        JSONObject sel2 = new JSONObject()
            .put("or", new org.json.JSONArray()
                .put(new JSONObject().put("text", "Cancel"))
                .put(new JSONObject().put("resourceId", "com.example:id/submit")));
        assertTrue(UiSelector.compile(sel2).matches(n, null));
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

        assertTrue(UiSelector.compile(sel).matches(child, parent));
        assertFalse(UiSelector.compile(sel).matches(child, null));
    }
}
