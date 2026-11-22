package com.supernova.googlecompanion.bridge;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/** Forwards the private launcher-overlay Binder protocol without changing transactions. */
final class BinderTransactionProxy extends Binder {
    private final IBinder target;

    BinderTransactionProxy(IBinder target) {
        this.target = target;
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        return target.transact(code, data, reply, flags);
    }
}
