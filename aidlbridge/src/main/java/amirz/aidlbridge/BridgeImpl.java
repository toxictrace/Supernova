package amirz.aidlbridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/** Connects to Google App using the bridge package identity. */
final class BridgeImpl extends IBridge.Stub {
    private static final String TAG = "SupernovaDiscoverBridge";

    private final Context context;
    private final Intent googleIntent;
    private final int expectedCallerUid;
    private final Set<ServiceConnection> connections = new HashSet<>();

    BridgeImpl(Context context, Intent launcherIntent, int expectedCallerUid) {
        this.context = context;
        this.expectedCallerUid = expectedCallerUid;

        Uri caller = launcherIntent.getData();
        Uri bridgeUri = caller.buildUpon()
                .encodedAuthority(context.getPackageName() + ":" + Process.myUid())
                .build();

        googleIntent = launcherIntent.cloneFilter();
        googleIntent.setPackage("com.google.android.googlequicksearchbox");
        googleIntent.setData(bridgeUri);
    }

    @Override
    public void bindService(final IBridgeCallback callback, int flags) {
        if (Binder.getCallingUid() != expectedCallerUid) {
            Log.w(TAG, "Rejected bridge request from unexpected UID");
            return;
        }

        final ServiceConnection connection = new ServiceConnection() {
            private boolean connected;

            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (connected) return;
                connected = true;
                Log.i(TAG, "Connected to Google overlay: " + name.flattenToShortString());
                try {
                    callback.onServiceConnected(name, new TransactProxy(service));
                } catch (RemoteException error) {
                    Log.w(TAG, "Unable to deliver Google overlay Binder", error);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (!connected) return;
                connected = false;
                notifyDisconnected(callback, name);
            }

            @Override
            public void onBindingDied(ComponentName name) {
                connected = false;
                notifyDisconnected(callback, name);
            }

            @Override
            public void onNullBinding(ComponentName name) {
                connected = false;
                Log.w(TAG, "Google App returned a null overlay binding");
                notifyDisconnected(callback, name);
            }
        };

        try {
            if (context.bindService(googleIntent, connection, flags)) {
                synchronized (connections) {
                    connections.add(connection);
                }
            } else {
                Log.w(TAG, "Google App rejected bindService");
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to bind Google App overlay", error);
        }
    }

    private static void notifyDisconnected(IBridgeCallback callback, ComponentName name) {
        try {
            callback.onServiceDisconnected(name);
        } catch (RemoteException error) {
            Log.w(TAG, "Unable to report Google overlay disconnection", error);
        }
    }

    void disconnect() {
        synchronized (connections) {
            for (ServiceConnection connection : connections) {
                try {
                    context.unbindService(connection);
                } catch (IllegalArgumentException error) {
                    Log.w(TAG, "Google overlay connection was already released", error);
                }
            }
            connections.clear();
        }
    }
}
