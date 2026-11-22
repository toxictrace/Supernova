package com.supernova.googlecompanion.bridge;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/** Exported AIDL endpoint used only by the Supernova launcher package. */
public final class DiscoverBridgeService extends Service {
    private static final String TAG = "SupernovaDiscoverBridge";
    private static final String LAUNCHER_PACKAGE = "com.supernova.launcher";
    private final Map<Intent, DiscoverBridgeImpl> bridges = new HashMap<>();

    @Override
    public synchronized IBinder onBind(Intent intent) {
        if (intent == null
                || !"com.android.launcher3.WINDOW_OVERLAY".equals(intent.getAction())) {
            return null;
        }

        Uri data = intent.getData();
        if (data == null || !"app".equals(data.getScheme())) return null;

        String authority = data.getEncodedAuthority();
        if (authority == null) return null;

        String[] parts = authority.split(":", 2);
        if (parts.length != 2 || !LAUNCHER_PACKAGE.equals(parts[0])) {
            Log.w(TAG, "Rejected overlay request with an unexpected package");
            return null;
        }

        final int callerUid;
        try {
            callerUid = Integer.parseInt(parts[1]);
        } catch (NumberFormatException error) {
            return null;
        }

        String[] packages = getPackageManager().getPackagesForUid(callerUid);
        if (packages == null) return null;
        boolean launcherUid = false;
        for (String packageName : packages) {
            if (LAUNCHER_PACKAGE.equals(packageName)) {
                launcherUid = true;
                break;
            }
        }
        if (!launcherUid) {
            Log.w(TAG, "Rejected overlay request with an unverified launcher UID");
            return null;
        }

        DiscoverBridgeImpl bridge = bridges.get(intent);
        if (bridge == null) {
            bridge = new DiscoverBridgeImpl(getApplicationContext(), intent, callerUid);
            bridges.put(new Intent(intent), bridge);
        }
        return bridge;
    }

    @Override
    public synchronized boolean onUnbind(Intent intent) {
        DiscoverBridgeImpl bridge = bridges.remove(intent);
        if (bridge != null) bridge.disconnect();
        return super.onUnbind(intent);
    }

    @Override
    public synchronized void onDestroy() {
        for (DiscoverBridgeImpl bridge : bridges.values()) bridge.disconnect();
        bridges.clear();
        super.onDestroy();
    }
}
