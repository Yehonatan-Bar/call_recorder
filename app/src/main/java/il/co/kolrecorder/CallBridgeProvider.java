package il.co.kolrecorder;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

/** Shell can register a capability; ordinary applications can neither replace nor fetch it. */
public final class CallBridgeProvider extends ContentProvider {
    private static IBinder bridge;
    private static IBinder.DeathRecipient death;

    static synchronized IBinder getBridge() {
        return bridge != null && bridge.isBinderAlive() ? bridge : null;
    }

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (Binder.getCallingUid() != 2000) throw new SecurityException("Only ADB shell may register the bridge");
        Bundle reply = new Bundle();
        synchronized (CallBridgeProvider.class) {
            if ("REGISTER".equals(method)) {
                IBinder candidate = extras == null ? null : extras.getBinder("bridge");
                if (candidate == null || !candidate.isBinderAlive()) throw new IllegalArgumentException("Missing live bridge");
                if (bridge != null && bridge.isBinderAlive() && !bridge.equals(candidate)) {
                    throw new IllegalStateException("Another bridge is already registered");
                }
                if (!candidate.equals(bridge)) {
                    IBinder.DeathRecipient listener = () -> clear(candidate);
                    try { candidate.linkToDeath(listener, 0); }
                    catch (RemoteException e) { throw new IllegalStateException("Bridge died during registration", e); }
                    bridge = candidate;
                    death = listener;
                }
                reply.putBoolean("ok", true);
            } else if (!"STATUS".equals(method)) throw new IllegalArgumentException("Unknown bridge method");
            reply.putBoolean("registered", getBridge() != null);
            reply.putBoolean("recording", RecordingService.busy);
            reply.putInt("protocol", 1);
        }
        return reply;
    }

    private static synchronized void clear(IBinder candidate) {
        if (candidate.equals(bridge)) {
            if (death != null) bridge.unlinkToDeath(death, 0);
            bridge = null;
            death = null;
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
