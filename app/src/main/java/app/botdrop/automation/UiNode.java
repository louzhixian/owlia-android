package app.botdrop.automation;

import android.graphics.Rect;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable-ish snapshot node used for matching selectors without holding AccessibilityNodeInfo refs.
 *
 * nodeId is a stable path in the active window tree using child indices, like "0/3/1".
 */
public class UiNode {

    public final String nodeId;
    public final @Nullable String packageName;
    public final @Nullable String className;
    public final @Nullable String text;
    public final @Nullable String contentDesc;
    public final @Nullable String resourceId;
    public final boolean clickable;
    public final boolean scrollable;
    public final boolean enabled;
    public final boolean visible;
    public final Rect bounds;
    public final List<UiNode> children = new ArrayList<>();

    public UiNode(String nodeId,
                  @Nullable String packageName,
                  @Nullable String className,
                  @Nullable String text,
                  @Nullable String contentDesc,
                  @Nullable String resourceId,
                  boolean clickable,
                  boolean scrollable,
                  boolean enabled,
                  boolean visible,
                  Rect bounds) {
        this.nodeId = nodeId;
        this.packageName = packageName;
        this.className = className;
        this.text = text;
        this.contentDesc = contentDesc;
        this.resourceId = resourceId;
        this.clickable = clickable;
        this.scrollable = scrollable;
        this.enabled = enabled;
        this.visible = visible;
        this.bounds = bounds;
    }

    public JSONObject toJson(boolean includeChildren) {
        JSONObject obj = new JSONObject();
        Json.put(obj, "nodeId", nodeId);
        Json.put(obj, "package", packageName);
        Json.put(obj, "class", className);
        Json.put(obj, "text", text);
        Json.put(obj, "contentDesc", contentDesc);
        Json.put(obj, "resourceId", resourceId);
        Json.put(obj, "clickable", clickable);
        Json.put(obj, "scrollable", scrollable);
        Json.put(obj, "enabled", enabled);
        Json.put(obj, "visible", visible);

        JSONArray b = new JSONArray();
        b.put(bounds.left);
        b.put(bounds.top);
        b.put(bounds.right);
        b.put(bounds.bottom);
        Json.put(obj, "bounds", b);

        if (includeChildren) {
            JSONArray arr = new JSONArray();
            for (UiNode c : children) {
                arr.put(c.toJson(true));
            }
            Json.put(obj, "children", arr);
        }
        return obj;
    }
}
