package app.botdrop.automation;

import android.graphics.Rect;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Compiles a JSON selector into a matcher.
 *
 * Supported fields (MVP):
 * - packageName, className, resourceId
 * - text, textContains
 * - contentDesc, contentDescContains
 * - clickable, enabled, visible
 * - boundsContains: [x,y]
 * - boundsIntersects: [l,t,r,b]
 * - and: [selector...], or: [selector...], not: selector
 * - parent: selector (immediate parent)
 *
 * If a selector is empty, it matches everything.
 */
public final class UiSelector {

    private UiSelector() {}

    public interface Matcher {
        boolean matches(UiNode node, @Nullable UiNode parent);
    }

    public static Matcher compile(@Nullable JSONObject selector) {
        if (selector == null) return (n, p) -> true;

        // Composite operators take precedence.
        if (selector.has("and")) {
            JSONArray arr = selector.optJSONArray("and");
            List<Matcher> ms = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject s = arr.optJSONObject(i);
                    if (s != null) ms.add(compile(s));
                }
            }
            return (n, p) -> {
                for (Matcher m : ms) if (!m.matches(n, p)) return false;
                return true;
            };
        }

        if (selector.has("or")) {
            JSONArray arr = selector.optJSONArray("or");
            List<Matcher> ms = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject s = arr.optJSONObject(i);
                    if (s != null) ms.add(compile(s));
                }
            }
            return (n, p) -> {
                if (ms.isEmpty()) return true;
                for (Matcher m : ms) if (m.matches(n, p)) return true;
                return false;
            };
        }

        if (selector.has("not")) {
            JSONObject s = selector.optJSONObject("not");
            Matcher m = compile(s);
            return (n, p) -> !m.matches(n, p);
        }

        // Leaf matcher with optional parent constraint.
        final String packageName = optString(selector, "packageName");
        final String className = optString(selector, "className");
        final String resourceId = optString(selector, "resourceId");
        final String text = optString(selector, "text");
        final String textContains = optString(selector, "textContains");
        final String contentDesc = optString(selector, "contentDesc");
        final String contentDescContains = optString(selector, "contentDescContains");
        final Boolean clickable = optBool(selector, "clickable");
        final Boolean scrollable = optBool(selector, "scrollable");
        final Boolean enabled = optBool(selector, "enabled");
        final Boolean visible = optBool(selector, "visible");

        final int[] boundsContains = optPoint(selector, "boundsContains");
        final Rect boundsIntersects = optRect(selector, "boundsIntersects");

        final JSONObject parentSel = selector.optJSONObject("parent");
        final Matcher parentMatcher = parentSel != null ? compile(parentSel) : null;

        return (n, p) -> {
            if (packageName != null && !packageName.equals(n.packageName)) return false;
            if (className != null && !className.equals(n.className)) return false;
            if (resourceId != null && !resourceId.equals(n.resourceId)) return false;

            if (text != null && !text.equals(n.text)) return false;
            if (textContains != null && (n.text == null || !n.text.contains(textContains))) return false;

            if (contentDesc != null && !contentDesc.equals(n.contentDesc)) return false;
            if (contentDescContains != null && (n.contentDesc == null || !n.contentDesc.contains(contentDescContains))) return false;

            if (clickable != null && clickable.booleanValue() != n.clickable) return false;
            if (scrollable != null && scrollable.booleanValue() != n.scrollable) return false;
            if (enabled != null && enabled.booleanValue() != n.enabled) return false;
            if (visible != null && visible.booleanValue() != n.visible) return false;

            if (boundsContains != null) {
                if (!n.bounds.contains(boundsContains[0], boundsContains[1])) return false;
            }
            if (boundsIntersects != null) {
                if (!Rect.intersects(n.bounds, boundsIntersects)) return false;
            }

            if (parentMatcher != null) {
                if (p == null) return false;
                if (!parentMatcher.matches(p, null)) return false;
            }

            return true;
        };
    }

    private static @Nullable String optString(JSONObject o, String k) {
        String s = o.optString(k, null);
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static @Nullable Boolean optBool(JSONObject o, String k) {
        if (!o.has(k)) return null;
        return o.optBoolean(k);
    }

    private static @Nullable int[] optPoint(JSONObject o, String k) {
        JSONArray a = o.optJSONArray(k);
        if (a == null || a.length() < 2) return null;
        return new int[] { a.optInt(0), a.optInt(1) };
    }

    private static @Nullable Rect optRect(JSONObject o, String k) {
        JSONArray a = o.optJSONArray(k);
        if (a == null || a.length() < 4) return null;
        return new Rect(a.optInt(0), a.optInt(1), a.optInt(2), a.optInt(3));
    }
}
