package app.botdrop.automation;

import android.graphics.Rect;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Compiles JSON selectors into matchers.
 *
 * Leaf fields:
 * - packageName, className, resourceId
 * - text, textContains
 * - contentDesc, contentDescContains
 * - clickable, scrollable, enabled, visible
 * - boundsContains: [x,y]
 * - boundsIntersects: [l,t,r,b]
 *
 * Composition:
 * - and: [selector...], or: [selector...], not: selector
 *
 * Structural:
 * - parent: selector (immediate parent)
 * - ancestor: selector (any ancestor in the path, excluding self)
 */
public final class UiSelector {

    private UiSelector() {}

    /** Legacy matcher (node + parent only). Prefer {@link #compileStack(JSONObject)} for new code. */
    public interface Matcher {
        boolean matches(UiNode node, @Nullable UiNode parent);
    }

    /** Matcher that evaluates against the full path from root->current (stack). */
    public interface StackMatcher {
        boolean matches(List<UiNode> stack);
    }

    public static final class Plan {
        public final StackMatcher baseMatcher;
        public final @Nullable StackMatcher hasChildMatcher;
        public final @Nullable StackMatcher hasDescendantMatcher;

        private Plan(StackMatcher baseMatcher,
                     @Nullable StackMatcher hasChildMatcher,
                     @Nullable StackMatcher hasDescendantMatcher) {
            this.baseMatcher = baseMatcher;
            this.hasChildMatcher = hasChildMatcher;
            this.hasDescendantMatcher = hasDescendantMatcher;
        }
    }

    public static Matcher compile(@Nullable JSONObject selector) {
        // Backwards-compatible: evaluate as a stack matcher using [node] or [parent,node].
        StackMatcher sm = compileStack(selector);
        return (n, p) -> {
            if (p == null) return sm.matches(java.util.Collections.singletonList(n));
            List<UiNode> s = new ArrayList<>(2);
            s.add(p);
            s.add(n);
            return sm.matches(s);
        };
    }

    /**
     * Compile a selector plan including subtree constraints:
     * - hasChild: selector that must match at least one immediate child
     * - hasDescendant: selector that must match at least one descendant
     */
    public static Plan compilePlan(@Nullable JSONObject selector) {
        if (selector == null) {
            return new Plan(stack -> true, null, null);
        }

        JSONObject hasChildSel = selector.optJSONObject("hasChild");
        JSONObject hasDescSel = selector.optJSONObject("hasDescendant");

        JSONObject baseSel = copyWithoutKeys(selector, "hasChild", "hasDescendant");
        StackMatcher base = compileStack(baseSel);
        StackMatcher child = hasChildSel != null ? compileStack(hasChildSel) : null;
        StackMatcher desc = hasDescSel != null ? compileStack(hasDescSel) : null;

        return new Plan(base, child, desc);
    }

    public static StackMatcher compileStack(@Nullable JSONObject selector) {
        if (selector == null) return stack -> true;

        // Composite operators take precedence.
        if (selector.has("and")) {
            JSONArray arr = selector.optJSONArray("and");
            List<StackMatcher> ms = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject s = arr.optJSONObject(i);
                    if (s != null) ms.add(compileStack(s));
                }
            }
            return stack -> {
                for (StackMatcher m : ms) if (!m.matches(stack)) return false;
                return true;
            };
        }

        if (selector.has("or")) {
            JSONArray arr = selector.optJSONArray("or");
            List<StackMatcher> ms = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject s = arr.optJSONObject(i);
                    if (s != null) ms.add(compileStack(s));
                }
            }
            return stack -> {
                if (ms.isEmpty()) return true;
                for (StackMatcher m : ms) if (m.matches(stack)) return true;
                return false;
            };
        }

        if (selector.has("not")) {
            JSONObject s = selector.optJSONObject("not");
            StackMatcher m = compileStack(s);
            return stack -> !m.matches(stack);
        }

        // Leaf matcher for the current node.
        final LeafMatcher leaf = compileLeaf(selector);

        // Structural constraints.
        final JSONObject parentSel = selector.optJSONObject("parent");
        final StackMatcher parentMatcher = parentSel != null ? compileStack(parentSel) : null;

        final JSONObject ancestorSel = selector.optJSONObject("ancestor");
        final StackMatcher ancestorMatcher = ancestorSel != null ? compileStack(ancestorSel) : null;

        return stack -> {
            if (stack == null || stack.isEmpty()) return false;
            UiNode cur = stack.get(stack.size() - 1);
            UiNode parent = stack.size() >= 2 ? stack.get(stack.size() - 2) : null;

            if (!leaf.matches(cur, parent)) return false;

            if (parentMatcher != null) {
                if (stack.size() < 2) return false;
                if (!parentMatcher.matches(stack.subList(0, stack.size() - 1))) return false;
            }

            if (ancestorMatcher != null) {
                boolean ok = false;
                for (int i = stack.size() - 1; i >= 1; i--) { // evaluate each ancestor as "current"
                    if (ancestorMatcher.matches(stack.subList(0, i))) {
                        ok = true;
                        break;
                    }
                }
                if (!ok) return false;
            }

            return true;
        };
    }

    private interface LeafMatcher {
        boolean matches(UiNode node, @Nullable UiNode parent);
    }

    private static LeafMatcher compileLeaf(JSONObject selector) {
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

            return true;
        };
    }

    private static JSONObject copyWithoutKeys(JSONObject src, String... keysToRemove) {
        JSONObject o = new JSONObject();
        try {
            java.util.HashSet<String> remove = new java.util.HashSet<>();
            if (keysToRemove != null) {
                java.util.Collections.addAll(remove, keysToRemove);
            }
            java.util.Iterator<String> it = src.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (remove.contains(k)) continue;
                Object v = src.opt(k);
                o.put(k, v);
            }
        } catch (Exception ignored) {}
        return o;
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
