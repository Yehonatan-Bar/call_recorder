package il.co.kolrecorder;

import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Binder passes an already-connected socket, avoiding SELinux's app-to-shell connect ban. */
final class CallBridgeClient implements AutoCloseable {
    static final String DESCRIPTOR = "il.co.kolrecorder.callbridge";
    private ParcelFileDescriptor socket;
    private DataInputStream input;
    private volatile boolean closed;

    CallBridgeClient(String command) throws IOException {
        try {
            IBinder bridge = findBridge();
            if (bridge == null) throw new IOException("Call bridge is not running");
            Parcel request = Parcel.obtain(), reply = Parcel.obtain();
            try {
                request.writeInterfaceToken(DESCRIPTOR);
                if (!bridge.transact(IBinder.FIRST_CALL_TRANSACTION, request, reply, 0)) throw new IOException("Unsupported bridge protocol");
                reply.readException();
                socket = reply.readFileDescriptor();
                if (socket == null) throw new IOException("Bridge returned no audio connection");
            } catch (RemoteException e) { throw new IOException("Bridge disconnected", e); }
            finally { request.recycle(); reply.recycle(); }
            // This capability was accepted only from UID 2000 by CallBridgeProvider.
            // No hidden peer-credential API or forbidden setsockopt call is needed.
            final FileDescriptor fd = socket.getFileDescriptor();
            input = new DataInputStream(new InputStream() {
                @Override public int read() throws IOException {
                    byte[] one = new byte[1];
                    return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
                }
                @Override public int read(byte[] bytes, int offset, int count) throws IOException {
                    if (count == 0) return 0;
                    poll(fd, OsConstants.POLLIN);
                    try { int n = Os.read(fd, bytes, offset, count); return n == 0 ? -1 : n; }
                    catch (ErrnoException e) { throw new IOException("Bridge read failed", e); }
                }
            });
            DataOutputStream output = new DataOutputStream(new OutputStream() {
                @Override public void write(int value) throws IOException { write(new byte[]{(byte) value}, 0, 1); }
                @Override public void write(byte[] bytes, int offset, int count) throws IOException {
                    while (count > 0) {
                        poll(fd, OsConstants.POLLOUT);
                        try {
                            int n = Os.write(fd, bytes, offset, count);
                            if (n <= 0) throw new IOException("Bridge write stalled");
                            offset += n; count -= n;
                        } catch (ErrnoException e) { throw new IOException("Bridge write failed", e); }
                    }
                }
            });
            output.writeUTF(command); output.flush();
            String status = input.readUTF();
            if ("ERROR".equals(status)) {
                String reason = input.readUTF();
                if ("another recording client is connected".equals(reason)) throw new BusyException();
                throw new IOException("Call audio unavailable: " + reason);
            }
            if (!"OK".equals(status)) throw new IOException("Invalid bridge response");
        } catch (IOException | RuntimeException e) {
            close(); throw e;
        }
    }

    private void poll(FileDescriptor fd, int events) throws IOException {
        if (closed) throw new IOException("Bridge closed");
        StructPollfd wait = new StructPollfd(); wait.fd = fd; wait.events = (short) events;
        try {
            if (Os.poll(new StructPollfd[]{wait}, 5000) == 0) throw new IOException("Bridge timed out");
            if (closed || (wait.revents & OsConstants.POLLNVAL) != 0) throw new IOException("Bridge closed");
        } catch (ErrnoException e) { throw new IOException("Bridge poll failed", e); }
    }

    private static IBinder findBridge() {
        IBinder result = CallBridgeProvider.getBridge();
        if (result != null) return result;
        try (FileOutputStream bell = new FileOutputStream("/data/local/tmp/kol-callbridge.bell")) { bell.write('!'); }
        catch (IOException | RuntimeException e) { return null; }
        long deadline = SystemClock.elapsedRealtime() + 3500;
        while ((result = CallBridgeProvider.getBridge()) == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
        return result;
    }

    static boolean available() {
        try (CallBridgeClient client = new CallBridgeClient("PING")) { return !client.input.readUTF().isEmpty(); }
        catch (IOException | RuntimeException e) { return false; }
    }

    static final class BusyException extends IOException {
        BusyException() { super("Previous call recording is still stopping"); }
    }

    int read(short[] samples) throws IOException {
        int bytes = input.readInt();
        if (bytes == -1) throw new IOException("Call audio stopped: " + input.readUTF());
        if (bytes <= 0 || bytes > 65536 || bytes > samples.length * 2 || bytes % 2 != 0) throw new IOException("Invalid bridge audio frame");
        byte[] buffer = new byte[bytes]; input.readFully(buffer);
        for (int i = 0; i < bytes / 2; i++) samples[i] = (short) ((buffer[2 * i] & 255) | (buffer[2 * i + 1] << 8));
        return bytes / 2;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (socket != null) {
            // shutdown wakes a concurrent poll/read and signals EOF to the helper.
            try { Os.shutdown(socket.getFileDescriptor(), OsConstants.SHUT_RDWR); } catch (ErrnoException ignored) { }
            try { socket.close(); } catch (IOException ignored) { }
        }
    }
}
