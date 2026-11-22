package amirz.aidlbridge;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/** Forwards the private launcher-overlay Binder protocol without changing transactions. */
final class TransactProxy extends Binder {
    private final IBinder target;

    TransactProxy(IBinder target) {
        this.target = target;
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        return target.transact(code, data, reply, flags);
    }
}
