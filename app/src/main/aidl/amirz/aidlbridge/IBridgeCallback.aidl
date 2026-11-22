package amirz.aidlbridge;

import android.content.ComponentName;
import android.os.IBinder;

interface IBridgeCallback {
    void onServiceConnected(in ComponentName name, in IBinder service);
    void onServiceDisconnected(in ComponentName name);
}
