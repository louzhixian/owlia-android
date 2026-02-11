package app.botdrop.automation;

import android.os.Build;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.annotation.Nullable;

import java.util.List;

final class AccessibilityWindowSelector {

    private AccessibilityWindowSelector() {}

    static @Nullable AccessibilityNodeInfo selectBestRoot(BotDropAccessibilityService service) {
        AccessibilityNodeInfo active = null;
        try {
            active = service.getRootInActiveWindow();
            if (isUsableRoot(active) && !isLikelyOverlayPackage(active)) return active;
        } catch (Exception ignored) {}

        if (active != null) {
            active.recycle();
            active = null;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null;

        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows == null) return null;
            for (AccessibilityWindowInfo w : windows) {
                if (w == null) continue;
                AccessibilityNodeInfo r = null;
                try {
                    r = w.getRoot();
                    if (r == null) continue;
                    if (!isUsableRoot(r)) continue;

                    int score = scoreRoot(r);
                    if (score > bestScore) {
                        if (best != null) best.recycle();
                        best = r;
                        bestScore = score;
                        r = null; // transfer ownership
                    }
                } finally {
                    if (r != null) r.recycle();
                }
            }
        } catch (Exception ignored) {}
        return best;
    }

    static boolean isLikelyOverlayPackageName(@Nullable String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        return pkg.startsWith("com.vivo.upslide")
            || pkg.equals("com.android.systemui")
            || pkg.startsWith("com.miui.systemui")
            || pkg.startsWith("com.oplus.systemui")
            || pkg.startsWith("com.coloros.systemui")
            || pkg.startsWith("com.huawei.android.launcher")
            || pkg.startsWith("com.android.launcher");
    }

    private static boolean isUsableRoot(@Nullable AccessibilityNodeInfo root) {
        if (root == null) return false;
        try {
            if (root.getChildCount() > 0) return true;
            Rect b = new Rect();
            root.getBoundsInScreen(b);
            boolean hasArea = b.width() > 0 && b.height() > 0;
            return hasArea && root.getClassName() != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int scoreRoot(@Nullable AccessibilityNodeInfo root) {
        if (root == null) return Integer.MIN_VALUE;
        try {
            Rect b = new Rect();
            root.getBoundsInScreen(b);
            int area = Math.max(0, b.width()) * Math.max(0, b.height());
            int score = area;
            score += Math.min(1000, Math.max(0, root.getChildCount()) * 100);
            if (isLikelyOverlayPackage(root)) score -= 10_000_000;
            return score;
        } catch (Exception ignored) {
            return Integer.MIN_VALUE;
        }
    }

    private static boolean isLikelyOverlayPackage(@Nullable AccessibilityNodeInfo root) {
        if (root == null || root.getPackageName() == null) return false;
        return isLikelyOverlayPackageName(root.getPackageName().toString());
    }
}
