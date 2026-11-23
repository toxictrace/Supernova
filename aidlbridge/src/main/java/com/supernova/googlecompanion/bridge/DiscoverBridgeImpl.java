package com.supernova.googlecompanion.bridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/** Connects to Google App using the bridge package identity. */
final class DiscoverBridgeImpl extends IDiscoverBridge.Stub {
    private static final String TAG = "SupernovaDiscoverBridge";
    private static final int MAX_RECONNECT_ATTEMPTS = 6;
    private static final long MAX_RECONNECT_DELAY_MS = 30_000L;

    private final Context context;
    private final Intent googleIntent;
    private final int expectedCallerUid;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<GoogleServiceConnection> connections = new HashSet<>();

    DiscoverBridgeImpl(Context context, Intent launcherIntent, int expectedCallerUid) {
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
    public void bindService(final IDiscoverBridgeCallback callback, int flags) {
        if (Binder.getCallingUid() != expectedCallerUid) {
            Log.w(TAG, "Rejected bridge request from unexpected UID");
            return;
        }

        GoogleServiceConnection connection = new GoogleServiceConnection(callback, flags);
        synchronized (connections) {
            connections.add(connection);
        }
        connection.bind();
    }

    private final class GoogleServiceConnection implements ServiceConnection {
        private final IDiscoverBridgeCallback callback;
        private final int flags;
        private boolean bound;
        private boolean connected;
        private int reconnectAttempt;
        private final Runnable reconnect = this::bind;

        GoogleServiceConnection(IDiscoverBridgeCallback callback, int flags) {
            this.callback = callback;
            this.flags = flags;
        }

        void bind() {
            if (bound) return;
            try {
                bound = context.bindService(googleIntent, this, flags);
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to bind Google App overlay", error);
                bound = false;
            }
            if (bound) {
                Log.i(TAG, "Google overlay bindService returned true");
            } else {
                Log.w(TAG, "Google App rejected overlay bindService");
                scheduleReconnect();
            }
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            connected = true;
            reconnectAttempt = 0;
            handler.removeCallbacks(reconnect);
            Log.i(TAG, "Connected to Google overlay: " + name.flattenToShortString());
            try {
                callback.onServiceConnected(name, new BinderTransactionProxy(service));
            } catch (RemoteException error) {
                Log.w(TAG, "Unable to deliver Google overlay Binder", error);
                release();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (!connected) return;
            connected = false;
            notifyDisconnected(callback, name);
            // Android retains this binding and reconnects when the service returns.
        }

        @Override
        public void onBindingDied(ComponentName name) {
            connected = false;
            notifyDisconnected(callback, name);
            releaseBinding();
            scheduleReconnect();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            connected = false;
            Log.w(TAG, "Google App returned a null overlay binding");
            notifyDisconnected(callback, name);
            releaseBinding();
            scheduleReconnect();
        }

        private void scheduleReconnect() {
            if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
                Log.w(TAG, "Google overlay reconnect limit reached");
                return;
            }
            reconnectAttempt++;
            long delay = Math.min(1_000L << (reconnectAttempt - 1), MAX_RECONNECT_DELAY_MS);
            handler.removeCallbacks(reconnect);
            Log.w(TAG, "Retrying Google overlay binding in " + delay + "ms");
            handler.postDelayed(reconnect, delay);
        }

        private void releaseBinding() {
            if (!bound) return;
            try {
                context.unbindService(this);
            } catch (IllegalArgumentException error) {
                Log.w(TAG, "Google overlay binding was already released", error);
            }
            bound = false;
        }

        void release() {
            handler.removeCallbacks(reconnect);
            releaseBinding();
            synchronized (connections) {
                connections.remove(this);
            }
        }
    }

    private static void notifyDisconnected(IDiscoverBridgeCallback callback, ComponentName name) {
        try {
            callback.onServiceDisconnected(name);
        } catch (RemoteException error) {
            Log.w(TAG, "Unable to report Google overlay disconnection", error);
        }
    }

    void disconnect() {
        GoogleServiceConnection[] snapshot;
        synchronized (connections) {
            snapshot = connections.toArray(new GoogleServiceConnection[0]);
            connections.clear();
        }
        for (GoogleServiceConnection connection : snapshot) {
            connection.release();
        }
    }
}
