package app.botdrop.automation;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.termux.shared.logger.Logger;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class OpenAppOperation {

    private static final String LOG_TAG = "OpenAppOperation";
    private static final Map<String, String[]> EXPLICIT_COMPONENT_FALLBACKS = new HashMap<>();
    private static final Map<String, String[]> PACKAGE_ALTERNATE_FALLBACKS = new HashMap<>();

    static {
        EXPLICIT_COMPONENT_FALLBACKS.put("com.tencent.mm", new String[]{
            "com.tencent.mm/.ui.LauncherUI"
        });
        EXPLICIT_COMPONENT_FALLBACKS.put("tv.danmaku.bili", new String[]{
            "tv.danmaku.bili/.MainActivityV2"
        });

        PACKAGE_ALTERNATE_FALLBACKS.put("org.telegram.messenger", new String[]{
            "org.thunderdog.challegram",   // Telegram X
            "org.telegram.plus"            // Plus Messenger
        });
        PACKAGE_ALTERNATE_FALLBACKS.put("com.discord", new String[]{
            "com.discord.beta",
            "com.discord.ptb"
        });
    }

    private OpenAppOperation() {}

    static JSONObject run(BotDropAccessibilityService svc, JSONObject req) {
        String packageName = req.optString("packageName", "").trim();
        if (packageName.isEmpty()) return jsonErr("BAD_REQUEST", "missing packageName");

        String activityName = req.optString("activity", "").trim();
        String componentName = req.optString("component", "").trim();
        int timeoutMs = req.optInt("timeoutMs", 10000);
        int maxNodes = req.optInt("maxNodes", 1500);
        boolean handleConfirmDialog = req.optBoolean("handleConfirmDialog", true);
        String preferredConfirm = req.optString("preferredConfirm", "always");

        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + Math.max(1000L, timeoutMs);

        Set<String> candidatePackages = buildCandidatePackages(packageName);
        String resolvedPackage = null;
        for (String candidate : candidatePackages) {
            if (launchPackage(svc, candidate, activityName, componentName)) {
                resolvedPackage = candidate;
                break;
            }
        }
        if (resolvedPackage == null) {
            JSONObject out = jsonErr("LAUNCH_FAILED", "unable to resolve launcher activity for package: " + packageName);
            putSafe(out, "packageName", packageName);
            putSafe(out, "candidates", new org.json.JSONArray(candidatePackages));
            return out;
        }

        int confirmClicks = 0;
        String lastConfirmLabel = null;

        while (System.currentTimeMillis() < deadline) {
            String activePackage = svc.getActivePackageName();
            String observedPackage = svc.getLastObservedPackageName();
            boolean recentlyObserved = svc.isPackageRecentlyObserved(packageName, 8000L);
            boolean activeMatched = activePackage != null && candidatePackages.contains(activePackage);
            boolean observedMatched = observedPackage != null && candidatePackages.contains(observedPackage);
            boolean recentlyMatched = false;
            for (String p : candidatePackages) {
                if (svc.isPackageRecentlyObserved(p, 8000L)) {
                    recentlyMatched = true;
                    break;
                }
            }
            if (activeMatched || observedMatched || recentlyMatched) {
                JSONObject out = jsonOk();
                putSafe(out, "packageName", packageName);
                putSafe(out, "resolvedPackage", resolvedPackage);
                putSafe(out, "activePackage", activePackage);
                putSafe(out, "observedPackage", observedPackage);
                putSafe(out, "matchedBy",
                    activeMatched ? "activePackage"
                        : (observedMatched ? "observedPackage" : "recentObservation"));
                putSafe(out, "confirmClicks", confirmClicks);
                putSafe(out, "confirmLabel", lastConfirmLabel);
                return out;
            }

            if (handleConfirmDialog) {
                String clicked = tryClickLaunchConfirmDialog(svc, activePackage, maxNodes, preferredConfirm);
                if (clicked != null) {
                    confirmClicks++;
                    lastConfirmLabel = clicked;
                    sleepQuietly(180);
                    continue;
                }
            }

            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;

            long since = System.currentTimeMillis();
            boolean changed = svc.waitForWindowChanged(since, Math.min(400L, remaining));
            if (!changed) {
                svc.waitForContentChanged(since, Math.min(400L, remaining));
            }
        }

        JSONObject out = jsonErr("TIMEOUT", "openApp timeout for package: " + packageName);
        putSafe(out, "packageName", packageName);
        putSafe(out, "resolvedPackage", resolvedPackage);
        putSafe(out, "activePackage", svc.getActivePackageName());
        putSafe(out, "observedPackage", svc.getLastObservedPackageName());
        putSafe(out, "confirmClicks", confirmClicks);
        putSafe(out, "confirmLabel", lastConfirmLabel);
        return out;
    }

    private static Set<String> buildCandidatePackages(String requestedPackage) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (requestedPackage != null && !requestedPackage.isEmpty()) {
            set.add(requestedPackage);
            String[] alts = PACKAGE_ALTERNATE_FALLBACKS.get(requestedPackage);
            if (alts != null) {
                for (String alt : alts) {
                    if (alt != null && !alt.isEmpty()) set.add(alt);
                }
            }
        }
        return set;
    }

    private static boolean launchPackage(BotDropAccessibilityService svc,
                                         String packageName,
                                         String activityName,
                                         String componentName) {
        boolean launched = false;
        try {
            PackageManager pm = svc.getPackageManager();

            if (!componentName.isEmpty() && startByComponentString(svc, packageName, componentName)) return true;
            if (!activityName.isEmpty()) {
                String normalizedActivity = activityName.startsWith(".")
                    ? (packageName + activityName)
                    : activityName;
                if (startByClassName(svc, packageName, normalizedActivity)) return true;
            }

            Intent launch = pm.getLaunchIntentForPackage(packageName);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                svc.startActivity(launch);
                return true;
            }

            try {
                Intent implicitLauncher = new Intent(Intent.ACTION_MAIN);
                implicitLauncher.addCategory(Intent.CATEGORY_LAUNCHER);
                implicitLauncher.setPackage(packageName);
                implicitLauncher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                svc.startActivity(implicitLauncher);
                return true;
            } catch (Exception ignored) {}

            Intent query = new Intent(Intent.ACTION_MAIN);
            query.addCategory(Intent.CATEGORY_LAUNCHER);
            query.setPackage(packageName);
            List<ResolveInfo> candidates = pm.queryIntentActivities(query, 0);
            if (candidates != null && !candidates.isEmpty()) {
                ResolveInfo first = candidates.get(0);
                if (first.activityInfo != null && first.activityInfo.name != null) {
                    Intent explicit = new Intent(Intent.ACTION_MAIN);
                    explicit.addCategory(Intent.CATEGORY_LAUNCHER);
                    explicit.setClassName(packageName, first.activityInfo.name);
                    explicit.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    svc.startActivity(explicit);
                    launched = true;
                }
            }
            if (launched) return true;
        } catch (Exception e) {
            Logger.logDebug(LOG_TAG, "launchPackage standard flow failed: " + e.getMessage());
        }

        try {
            String[] components = EXPLICIT_COMPONENT_FALLBACKS.get(packageName);
            if (components != null) {
                for (String component : components) {
                    if (startByComponentString(svc, packageName, component)) return true;
                }
            }
        } catch (Exception e) {
            Logger.logDebug(LOG_TAG, "launchPackage component fallback failed: " + e.getMessage());
        }
        return false;
    }

    private static boolean startByComponentString(BotDropAccessibilityService svc, String packageName, String component) {
        if (component == null || component.isEmpty()) return false;
        String full = component.trim();
        if (!full.contains("/")) return false;
        String pkg = full.substring(0, full.indexOf('/'));
        String cls = full.substring(full.indexOf('/') + 1);
        if (cls.startsWith(".")) cls = pkg + cls;
        if (packageName != null && !packageName.isEmpty() && !pkg.equals(packageName)) return false;
        return startByClassName(svc, pkg, cls);
    }

    private static boolean startByClassName(BotDropAccessibilityService svc, String packageName, String className) {
        try {
            Intent explicit = new Intent(Intent.ACTION_MAIN);
            explicit.addCategory(Intent.CATEGORY_LAUNCHER);
            explicit.setClassName(packageName, className);
            explicit.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            svc.startActivity(explicit);
            return true;
        } catch (Exception e) {
            Logger.logDebug(LOG_TAG, "startByClassName failed: " + e.getMessage());
            return false;
        }
    }

    private static String tryClickLaunchConfirmDialog(BotDropAccessibilityService svc,
                                                      String activePackage,
                                                      int maxNodes,
                                                      String preferredConfirm) {
        if (!isLikelyConfirmDialogHost(activePackage)) return null;

        String prefer = (preferredConfirm == null) ? "always" : preferredConfirm.trim().toLowerCase();
        String[] primaryText = "once".equals(prefer)
            ? new String[]{"Just once", "just once", "\u4ec5\u6b64\u4e00\u6b21", "\u53ea\u6b64\u4e00\u6b21"}
            : new String[]{"Always", "always", "\u59cb\u7ec8\u6253\u5f00", "\u603b\u662f"};
        String[] secondaryText = "once".equals(prefer)
            ? new String[]{"Always", "always", "\u59cb\u7ec8\u6253\u5f00", "\u603b\u662f"}
            : new String[]{"Just once", "just once", "\u4ec5\u6b64\u4e00\u6b21", "\u53ea\u6b64\u4e00\u6b21"};
        String[] primaryIds = "once".equals(prefer)
            ? new String[]{"android:id/button_once"}
            : new String[]{"android:id/button_always"};
        String[] secondaryIds = "once".equals(prefer)
            ? new String[]{"android:id/button_always"}
            : new String[]{"android:id/button_once"};
        String[] vendorPositiveIds = new String[]{
            "android:id/button1",
            "android:id/button_positive"
        };
        String[] vendorPositiveText = new String[]{
            "Allow", "allow", "Confirm", "confirm", "Continue", "continue",
            "\u5141\u8bb8", "\u786e\u8ba4", "\u7ee7\u7eed", "\u786e\u5b9a", "\u540c\u610f"
        };

        String clicked = tryClickAnyByResourceId(svc, primaryIds, maxNodes);
        if (clicked != null) return clicked;

        clicked = tryClickAnyByTextContains(svc, primaryText, maxNodes);
        if (clicked != null) return clicked;

        clicked = tryClickAnyByResourceId(svc, secondaryIds, maxNodes);
        if (clicked != null) return clicked;

        clicked = tryClickAnyByTextContains(svc, secondaryText, maxNodes);
        if (clicked != null) return clicked;

        clicked = tryClickAnyByResourceId(svc, vendorPositiveIds, maxNodes);
        if (clicked != null) return clicked;

        return tryClickAnyByTextContains(svc, vendorPositiveText, maxNodes);
    }

    private static boolean isLikelyConfirmDialogHost(String activePackage) {
        if (activePackage == null || activePackage.isEmpty()) return false;
        if ("com.android.settings".equals(activePackage)) return false;
        return "com.vivo.appfilter".equals(activePackage)
            || "android".equals(activePackage)
            || "com.android.systemui".equals(activePackage)
            || "com.google.android.permissioncontroller".equals(activePackage)
            || "com.android.permissioncontroller".equals(activePackage)
            || activePackage.contains("permissioncontroller")
            || activePackage.contains("resolver");
    }

    private static String tryClickAnyByResourceId(BotDropAccessibilityService svc, String[] resourceIds, int maxNodes) {
        if (resourceIds == null) return null;
        for (String id : resourceIds) {
            if (id == null || id.isEmpty()) continue;
            JSONObject selector = new JSONObject();
            putSafe(selector, "resourceId", id);
            if (clickFirstMatch(svc, selector, maxNodes)) return id;
        }
        return null;
    }

    private static String tryClickAnyByTextContains(BotDropAccessibilityService svc, String[] labels, int maxNodes) {
        if (labels == null) return null;
        for (String label : labels) {
            if (label == null || label.isEmpty()) continue;
            JSONObject selector = new JSONObject();
            putSafe(selector, "textContains", label);
            if (clickFirstMatch(svc, selector, maxNodes)) return label;
        }
        return null;
    }

    private static boolean clickFirstMatch(BotDropAccessibilityService svc, JSONObject selector, int maxNodes) {
        try {
            JSONObject findRes = svc.find(selector, "first", maxNodes);
            if (!findRes.optBoolean("ok", false)) return false;
            if (findRes.optJSONArray("matches") == null || findRes.optJSONArray("matches").length() <= 0) return false;

            JSONObject m0 = findRes.optJSONArray("matches").optJSONObject(0);
            if (m0 == null) return false;
            String nodeId = m0.optString("actionNodeId", "");
            if (nodeId.isEmpty()) nodeId = m0.optString("nodeId", "");
            if (nodeId.isEmpty()) return false;

            JSONObject res = svc.actionByNodeId(nodeId, "click", null, 1500);
            return res.optBoolean("ok", false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(Math.max(0L, ms));
        } catch (InterruptedException ignored) {}
    }

    private static JSONObject jsonOk() {
        JSONObject o = new JSONObject();
        return putSafe(o, "ok", true);
    }

    private static JSONObject jsonErr(String code, String message) {
        JSONObject o = new JSONObject();
        putSafe(o, "ok", false);
        putSafe(o, "error", code);
        putSafe(o, "message", message != null ? message : "");
        return o;
    }

    private static JSONObject putSafe(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Exception ignored) {}
        return o;
    }
}
