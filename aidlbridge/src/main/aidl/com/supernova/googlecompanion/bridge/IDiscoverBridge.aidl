package com.supernova.googlecompanion.bridge;

import com.supernova.googlecompanion.bridge.IDiscoverBridgeCallback;

interface IDiscoverBridge {
    oneway void bindService(in IDiscoverBridgeCallback callback, in int flags);
}
