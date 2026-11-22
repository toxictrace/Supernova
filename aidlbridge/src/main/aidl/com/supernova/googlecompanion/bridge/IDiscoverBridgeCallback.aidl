package com.supernova.googlecompanion.bridge;

import android.content.ComponentName;
import android.os.IBinder;

interface IDiscoverBridgeCallback {
    void onServiceConnected(in ComponentName name, in IBinder service);
    void onServiceDisconnected(in ComponentName name);
}
