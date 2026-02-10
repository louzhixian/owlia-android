package app.botdrop.automation;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * No-root UI automation bridge based on AccessibilityService.
 *
 * Current scope (MVP):
 * - Dump active window node tree (compact JSON)
 * - Perform a subset of global actions (back/home/recents/notifications)
 *
 * NOTE: AccessibilityNodeInfo objects must not be cached long-term. Build snapshots on demand.
 */
public class BotDropAccessibilityService extends AccessibilityService {

    private static final String LOG_TAG = "BotDropAccessibilityService";
    private static volatile BotDropAccessibilityService sInstance;

    private final Object mEventLock = new Object();
    private long mLastWindowChangedAtMs = 0L;
    private long mLastContentChangedAtMs = 0L;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    public static @Nullable BotDropAccessibilityService getInstance() {
        return sInstance;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;

        // Ensure we can retrieve window content + view IDs for stable selectors.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            }
            setServiceInfo(info);
        }

        Logger.logInfo(LOG_TAG, "Accessibility service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int type = event != null ? event.getEventType() : 0;
        long now = System.currentTimeMillis();
        boolean notify = false;

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            mLastWindowChangedAtMs = now;
            notify = true;
        } else if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            mLastContentChangedAtMs = now;
            notify = true;
        }

        if (notify) {
            synchronized (mEventLock) {
                mEventLock.notifyAll();
            }
        }
    }

    @Override
    public void onInterrupt() {
        // Nothing to do.
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (sInstance == this) sInstance = null;
        Logger.logInfo(LOG_TAG, "Accessibility service destroyed");
    }

    /**
     * Dump the active window node tree.
     *
     * @param maxNodes Hard cap to avoid producing huge payloads.
     */
    public JSONObject dumpActiveWindowTree(int maxNodes) {
        JSONObject out = new JSONObject();
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root == null) {
                out.put("ok", false);
                out.put("error", "NO_ACTIVE_WINDOW");
                return out;
            }

            NodeBudget budget = new NodeBudget(maxNodes);
            JSONObject tree = nodeToJson(root, budget, "");

            out.put("ok", true);
            out.put("tree", tree);
            out.put("truncated", budget.truncated);
            out.put("nodeCount", budget.visited);
            return out;
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("error", "EXCEPTION");
                out.put("message", e.getMessage() != null ? e.getMessage() : "unknown");
            } catch (Exception ignored) {}
            return out;
        } finally {
            if (root != null) root.recycle();
        }
    }

    public boolean performBotDropGlobalAction(String action) {
        if (action == null) return false;
        switch (action) {
            case "back":
                return performGlobalAction(GLOBAL_ACTION_BACK);
            case "home":
                return performGlobalAction(GLOBAL_ACTION_HOME);
            case "recents":
                return performGlobalAction(GLOBAL_ACTION_RECENTS);
            case "notifications":
                return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
            default:
                return false;
        }
    }

    public JSONObject find(JSONObject selector, String mode, int maxNodes) {
        JSONObject out = new JSONObject();
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root == null) {
                Json.put(out, "ok", false);
                Json.put(out, "error", "NO_ACTIVE_WINDOW");
                return out;
            }

            UiSelector.Matcher matcher = UiSelector.compile(selector);
            NodeBudget budget = new NodeBudget(maxNodes);

            JSONArray matches = new JSONArray();
            findMatches(root, "", null, matcher, mode, matches, budget);

            Json.put(out, "ok", true);
            Json.put(out, "mode", mode);
            Json.put(out, "matches", matches);
            Json.put(out, "truncated", budget.truncated);
            Json.put(out, "nodeCount", budget.visited);
            return out;
        } catch (Exception e) {
            Json.put(out, "ok", false);
            Json.put(out, "error", "EXCEPTION");
            Json.put(out, "message", e.getMessage() != null ? e.getMessage() : "unknown");
            return out;
        } finally {
            if (root != null) root.recycle();
        }
    }

    /**
     * Perform an action on the node referenced by nodeId path, like "0/3/1".
     */
    public JSONObject actionByNodeId(String nodeId, String action, JSONObject args, int timeoutMs) {
        JSONObject out = new JSONObject();
        AccessibilityNodeInfo root = null;
        AccessibilityNodeInfo target = null;
        try {
            root = getRootInActiveWindow();
            if (root == null) {
                Json.put(out, "ok", false);
                Json.put(out, "error", "NO_ACTIVE_WINDOW");
                return out;
            }

            if (nodeId == null || nodeId.trim().isEmpty()) {
                Json.put(out, "ok", false);
                Json.put(out, "error", "BAD_NODE_ID");
                return out;
            }

            target = findNodeByPath(root, nodeId);
            if (target == null) {
                Json.put(out, "ok", false);
                Json.put(out, "error", "NOT_FOUND");
                return out;
            }

            boolean ok = performNodeAction(target, action, args);
            if (!ok) {
                Json.put(out, "ok", false);
                Json.put(out, "error", "ACTION_FAILED");
                return out;
            }
            Json.put(out, "ok", true);
            return out;
        } catch (Exception e) {
            Json.put(out, "ok", false);
            Json.put(out, "error", "EXCEPTION");
            Json.put(out, "message", e.getMessage() != null ? e.getMessage() : "unknown");
            return out;
        } finally {
            if (target != null) target.recycle();
            if (root != null) root.recycle();
        }
    }

    public boolean waitForWindowChanged(long sinceMs, long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        synchronized (mEventLock) {
            while (mLastWindowChangedAtMs <= sinceMs) {
                long now = System.currentTimeMillis();
                long remaining = deadline - now;
                if (remaining <= 0) return false;
                try {
                    mEventLock.wait(Math.min(remaining, 250L));
                } catch (InterruptedException ignored) {
                    return false;
                }
            }
            return true;
        }
    }

    public boolean waitForContentChanged(long sinceMs, long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        synchronized (mEventLock) {
            while (mLastContentChangedAtMs <= sinceMs) {
                long now = System.currentTimeMillis();
                long remaining = deadline - now;
                if (remaining <= 0) return false;
                try {
                    mEventLock.wait(Math.min(remaining, 250L));
                } catch (InterruptedException ignored) {
                    return false;
                }
            }
            return true;
        }
    }

    public boolean waitForExists(JSONObject selector, long timeoutMs, int maxNodes) {
        UiSelector.Matcher matcher = UiSelector.compile(selector);
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);

        // Initial check
        if (existsNow(matcher, maxNodes)) return true;

        synchronized (mEventLock) {
            while (true) {
                long now = System.currentTimeMillis();
                long remaining = deadline - now;
                if (remaining <= 0) return false;
                try {
                    mEventLock.wait(Math.min(remaining, 250L));
                } catch (InterruptedException ignored) {
                    return false;
                }
                if (existsNow(matcher, maxNodes)) return true;
            }
        }
    }

    private boolean existsNow(UiSelector.Matcher matcher, int maxNodes) {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root == null) return false;
            NodeBudget budget = new NodeBudget(maxNodes);
            return findFirstMatch(root, "", null, matcher, budget) != null;
        } finally {
            if (root != null) root.recycle();
        }
    }

    private static class NodeBudget {
        final int max;
        int visited = 0;
        boolean truncated = false;
        NodeBudget(int max) { this.max = Math.max(1, max); }
        boolean canVisit() { return visited < max; }
        void onVisit() { visited++; if (visited >= max) truncated = true; }
    }

    private JSONObject nodeToJson(AccessibilityNodeInfo node, NodeBudget budget, String path) throws Exception {
        JSONObject obj = new JSONObject();
        if (!budget.canVisit()) {
            // Shouldn't normally happen because parent checks, but keep it safe.
            obj.put("truncated", true);
            return obj;
        }
        budget.onVisit();

        Rect r = new Rect();
        node.getBoundsInScreen(r);

        obj.put("nodeId", path);
        obj.put("package", node.getPackageName() != null ? node.getPackageName().toString() : JSONObject.NULL);
        obj.put("class", node.getClassName() != null ? node.getClassName().toString() : JSONObject.NULL);
        obj.put("text", node.getText() != null ? node.getText().toString() : JSONObject.NULL);
        obj.put("contentDesc", node.getContentDescription() != null ? node.getContentDescription().toString() : JSONObject.NULL);
        obj.put("resourceId", node.getViewIdResourceName() != null ? node.getViewIdResourceName() : JSONObject.NULL);
        obj.put("clickable", node.isClickable());
        obj.put("enabled", node.isEnabled());
        obj.put("visible", node.isVisibleToUser());

        JSONArray bounds = new JSONArray();
        bounds.put(r.left);
        bounds.put(r.top);
        bounds.put(r.right);
        bounds.put(r.bottom);
        obj.put("bounds", bounds);

        int childCount = node.getChildCount();
        JSONArray children = new JSONArray();
        for (int i = 0; i < childCount; i++) {
            if (!budget.canVisit()) break;
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
                if (child == null) continue;
                String childPath = path.isEmpty() ? String.valueOf(i) : (path + "/" + i);
                children.put(nodeToJson(child, budget, childPath));
            } finally {
                if (child != null) child.recycle();
            }
        }
        obj.put("children", children);
        return obj;
    }

    private void findMatches(AccessibilityNodeInfo node,
                             String path,
                             @Nullable UiNode parentSnapshot,
                             UiSelector.Matcher matcher,
                             String mode,
                             JSONArray out,
                             NodeBudget budget) throws Exception {
        if (!budget.canVisit()) return;
        budget.onVisit();

        UiNode snap = snapshotNode(node, path);
        if (matcher.matches(snap, parentSnapshot)) {
            out.put(snap.toJson(false));
            if ("first".equals(mode)) return;
        }

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            if (!budget.canVisit()) break;
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
                if (child == null) continue;
                String childPath = path.isEmpty() ? String.valueOf(i) : (path + "/" + i);
                findMatches(child, childPath, snap, matcher, mode, out, budget);
                if ("first".equals(mode) && out.length() > 0) return;
            } finally {
                if (child != null) child.recycle();
            }
        }
    }

    private @Nullable String findFirstMatch(AccessibilityNodeInfo node,
                                           String path,
                                           @Nullable UiNode parentSnapshot,
                                           UiSelector.Matcher matcher,
                                           NodeBudget budget) {
        if (!budget.canVisit()) return null;
        budget.onVisit();

        UiNode snap = snapshotNode(node, path);
        if (matcher.matches(snap, parentSnapshot)) {
            return path;
        }

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            if (!budget.canVisit()) break;
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
                if (child == null) continue;
                String childPath = path.isEmpty() ? String.valueOf(i) : (path + "/" + i);
                String match = findFirstMatch(child, childPath, snap, matcher, budget);
                if (match != null) return match;
            } finally {
                if (child != null) child.recycle();
            }
        }
        return null;
    }

    private UiNode snapshotNode(AccessibilityNodeInfo node, String path) {
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        return new UiNode(
            path,
            node.getPackageName() != null ? node.getPackageName().toString() : null,
            node.getClassName() != null ? node.getClassName().toString() : null,
            node.getText() != null ? node.getText().toString() : null,
            node.getContentDescription() != null ? node.getContentDescription().toString() : null,
            node.getViewIdResourceName(),
            node.isClickable(),
            node.isEnabled(),
            node.isVisibleToUser(),
            r
        );
    }

    private @Nullable AccessibilityNodeInfo findNodeByPath(AccessibilityNodeInfo root, @Nullable String path) {
        if (path == null || path.trim().isEmpty()) return null;

        String[] parts = path.split("/");
        AccessibilityNodeInfo current = root;
        boolean recycleCurrent = false;
        try {
            for (String part : parts) {
                int idx;
                try {
                    idx = Integer.parseInt(part);
                } catch (NumberFormatException e) {
                    if (recycleCurrent) current.recycle();
                    return null;
                }

                AccessibilityNodeInfo next = current.getChild(idx);
                if (recycleCurrent) current.recycle();
                recycleCurrent = true;
                current = next;
                if (current == null) return null;
            }
            return current; // caller recycles
        } catch (Exception e) {
            if (recycleCurrent && current != null) current.recycle();
            return null;
        }
    }

    private boolean performNodeAction(AccessibilityNodeInfo node, String action, JSONObject args) {
        if (action == null) return false;
        switch (action) {
            case "click":
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                return gestureClick(node, 120);
            case "longClick":
                if (node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true;
                return gestureClick(node, 650);
            case "focus":
                return node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            case "scrollForward":
                if (node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
                return gestureScroll(node, true, 380);
            case "scrollBackward":
                if (node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true;
                return gestureScroll(node, false, 380);
            case "setText":
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false;
                if (args == null) return false;
                String text = args.optString("text", null);
                if (text == null) return false;
                android.os.Bundle b = new android.os.Bundle();
                b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
            default:
                return false;
        }
    }

    private boolean gestureClick(AccessibilityNodeInfo node, long durationMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;

        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.width() <= 1 || r.height() <= 1) return false;

        int x = r.centerX();
        int y = r.centerY();
        return dispatchTap(x, y, durationMs, 1200);
    }

    /**
     * Scroll by swiping inside the node's bounds. forward=true means swipe up (content moves down).
     */
    private boolean gestureScroll(AccessibilityNodeInfo node, boolean forward, long durationMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;

        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.width() <= 1 || r.height() <= 1) return false;

        // Avoid edges where gesture can be intercepted by system bars.
        int x = r.centerX();
        int startY = (int) (r.top + r.height() * 0.75f);
        int endY = (int) (r.top + r.height() * 0.25f);
        if (!forward) {
            int tmp = startY;
            startY = endY;
            endY = tmp;
        }

        return dispatchSwipe(x, startY, x, endY, durationMs, 2000);
    }

    private boolean dispatchTap(int x, int y, long durationMs, long timeoutMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        Path p = new Path();
        p.moveTo(x, y);
        return dispatchGestureBlocking(p, durationMs, timeoutMs);
    }

    private boolean dispatchSwipe(int x1, int y1, int x2, int y2, long durationMs, long timeoutMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return dispatchGestureBlocking(p, durationMs, timeoutMs);
    }

    private boolean dispatchGestureBlocking(Path path, long durationMs, long timeoutMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;

        CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = new boolean[] { false };

        GestureDescription.StrokeDescription stroke =
            new GestureDescription.StrokeDescription(path, 0, Math.max(1, durationMs));
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();

        mMainHandler.post(() -> {
            try {
                dispatchGesture(gesture, new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        ok[0] = true;
                        latch.countDown();
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        ok[0] = false;
                        latch.countDown();
                    }
                }, null);
            } catch (Throwable t) {
                ok[0] = false;
                latch.countDown();
            }
        });

        try {
            if (!latch.await(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS)) return false;
        } catch (InterruptedException ignored) {
            return false;
        }
        return ok[0];
    }
}
