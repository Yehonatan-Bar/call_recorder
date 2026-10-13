package il.co.kolrecorder;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.FileObserver;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.system.StructTimeval;
import android.util.Log;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.SocketTimeoutException;
import java.nio.channels.FileLock;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shell-side call-audio helper. Runs as uid 2000 under app_process (no root) and records the
 * VOICE_CALL source (both directions of a cellular call) on behalf of il.co.kolrecorder.
 *
 * Why Binder and not a LocalSocket: on a stock Pixel (Android 17, SELinux enforcing) an app cannot
 * connect() to a socket owned by the shell domain, and the shell cannot connect to the app either.
 * Binder calls and file-descriptor passing between the two domains are allowed, so:
 *
 *   1. this process hosts a hand-written Binder (descriptor DESCRIPTOR, transaction TX_CONNECT);
 *   2. it hands the Binder to the app by calling the app's exported provider PROVIDER_AUTHORITY
 *      (method REGISTER, extras key "bridge"), the same pattern Shizuku uses. The provider is guarded by
 *      android.permission.DUMP, which only shell and system hold, and the app also checks the caller uid;
 *   3. CONNECT returns one end of a fresh socketpair, authorized by Binder.getCallingUid() (kernel
 *      supplied, unforgeable). The stream protocol (PING / RECORD, length-prefixed PCM) runs on that fd.
 *
 * Wire details: work/bridge-contract.txt. Modes: --serve (default), --probe, --probe-bell, --selftest.
 *
 * Start it independent of the ADB session, for example:
 *   setsid nohup app_process -Djava.class.path="$(pm path il.co.kolrecorder | head -n1 | cut -d: -f2)" \
 *       /system/bin --nice-name=kol_callbridge il.co.kolrecorder.CallBridgeMain --serve \
 *       >/data/local/tmp/kol-callbridge.log 2>&1 < /dev/null &
 *
 * Attribution: the shell identity (FakeContext), the ActivityThread bootstrap and the
 * getContentProviderExternal / IContentProvider.call reflection patterns are adapted from scrcpy
 * (server/src/main/java/com/genymobile/scrcpy: FakeContext, Workarounds, wrappers/ActivityManager,
 * wrappers/ContentProvider, audio/AudioDirectCapture), https://github.com/Genymobile/scrcpy
 *
 *   Copyright (C) 2018 Genymobile
 *   Copyright (C) 2018-2026 Romain Vimont
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
public final class CallBridgeMain {
    static final String VERSION = "1";
    static final String PROTOCOL = "callbridge/1";
    static final String TARGET_PACKAGE = "il.co.kolrecorder";
    static final String PROVIDER_AUTHORITY = "il.co.kolrecorder.bridge";
    static final String REGISTER_METHOD = "REGISTER";
    static final String BRIDGE_EXTRA = "bridge";
    static final String DESCRIPTOR = "il.co.kolrecorder.callbridge";
    static final int TX_CONNECT = IBinder.FIRST_CALL_TRANSACTION;
    static final String SHELL_PACKAGE = "com.android.shell";
    static final int SHELL_UID = 2000; // android.os.Process.SHELL_UID is not in the public stubs
    static final int RATE = WavFile.RATE; // inlined by javac; 16 kHz mono 16-bit little-endian
    static final int CALL_SOURCE = MediaRecorder.AudioSource.VOICE_CALL; // never replaced by a microphone source
    static final String WORK_DIR = "/data/local/tmp";
    static final String BELL_PATH = WORK_DIR + "/kol-callbridge.bell";
    static final String LOCK_PATH = WORK_DIR + "/kol-callbridge.lock";
    static final String LOG_TAG = "KolCallBridge";

    static final int MAX_BLOCK_BYTES = 3200; // 100 ms; equals RecordingService's short[1600] read buffer
    static final int POLL_INTERVAL_MS = 10; // non-blocking read cadence: bounds the stop latency
    static final int MAX_SESSIONS = 3; // one RECORD plus a couple of short PINGs
    static final int SEND_TIMEOUT_MS = 15000; // a client that stops reading for this long is dropped
    static final int STARTUP_PUSH_ATTEMPTS = 12;
    static final long STARTUP_PUSH_RETRY_MS = 5000;
    static final long MIN_PUSH_GAP_MS = 1500;
    static final long DOORBELL_CHECK_MS = 30000;
    static final long SLOT_GRACE_MS = 150; // a new RECORD tolerates a closing session this long before judging it "active"
    static final long SLOT_TAKEOVER_MS = 2500; // longest wait for a session that is releasing AudioRecord

    /** Test hooks: only --selftest changes them; serve mode always uses the defaults. */
    static volatile int trustedUidOverride = -1;
    static volatile int handshakeTimeoutMs = 5000;
    static volatile PcmSourceFactory sourceFactory = new AudioRecordFactory();

    private static final long START_BOOT_MS = SystemClock.elapsedRealtime();
    private static BridgeBinder bridgeBinder; // strong reference: the app only holds a proxy
    private static Rendezvous rendezvous;
    private static FileObserver doorbellObserver; // FileObserver keeps only weak references to itself
    private static long bellInode;
    private static FileLock instanceLock;
    private static volatile Session activeRecording;
    private static volatile String lastRecordingSummary = "none yet";

    private CallBridgeMain() { }

    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = run(args);
        } catch (Throwable fatal) {
            log("fatal: " + describe(fatal));
            exitCode = 70;
        }
        System.out.flush();
        System.err.flush();
        System.exit(exitCode);
    }

    private static int run(String[] args) throws Exception {
        String mode = args.length == 0 ? "--serve" : args[0];
        switch (mode) {
            case "--serve": return serve();
            case "--probe": return Probe.run();
            case "--probe-bell": return Probe.runDoorbell();
            case "--selftest": return SelfTest.run();
            case "--version": System.out.println(PROTOCOL + " version " + VERSION); return 0;
            default:
                System.err.println("usage: CallBridgeMain [--serve | --probe | --probe-bell | --selftest | --version]");
                return 1;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Serve mode
    // ------------------------------------------------------------------------------------------

    private static int serve() throws Exception {
        if (Process.myUid() != SHELL_UID) log("warning: not running as shell (uid " + Process.myUid() + ")");
        detachFromTerminal();
        if (!acquireInstanceLock()) {
            log("another call bridge instance is already running; exiting");
            return 0;
        }
        Environment.init();
        bridgeBinder = new BridgeBinder();
        rendezvous = new Rendezvous(bridgeBinder);
        armDoorbell();
        Runtime.getRuntime().addShutdownHook(new Thread(CallBridgeMain::shutdown, "kolbridge-shutdown"));
        Thread pusher = new Thread(rendezvous, "kolbridge-push");
        pusher.setDaemon(true);
        pusher.start();
        log(PROTOCOL + " serving: version " + VERSION + " pid " + Process.myPid() + " uid " + Process.myUid()
                + " sdk " + Build.VERSION.SDK_INT + " target " + TARGET_PACKAGE);
        // app_process keeps the VM alive only while a non-daemon thread exists; Binder threads are native.
        Object forever = new Object();
        synchronized (forever) {
            while (true) forever.wait();
        }
    }

    /** Leave the ADB shell's session so a hang-up on the terminal side cannot kill the daemon. */
    private static void detachFromTerminal() {
        try {
            Os.setsid();
        } catch (ErrnoException alreadyGroupLeader) {
            // EPERM when this process already leads a process group; "setsid nohup" in the start script covers it.
        }
        try {
            System.in.close();
        } catch (IOException ignored) { }
    }

    private static boolean acquireInstanceLock() throws IOException {
        RandomAccessFile lockFile = new RandomAccessFile(LOCK_PATH, "rw");
        instanceLock = lockFile.getChannel().tryLock(); // released by the kernel when the process dies
        return instanceLock != null;
    }

    /**
     * (Re)creates the world-writable doorbell file and (re)starts watching it. Called at startup and again
     * whenever the file was deleted or replaced, because an inotify watch dies with the inode it watches.
     */
    private static synchronized void armDoorbell() throws IOException, ErrnoException {
        if (doorbellObserver != null) {
            doorbellObserver.stopWatching();
            doorbellObserver = null;
        }
        File bell = new File(BELL_PATH);
        if (!bell.exists() && !bell.createNewFile()) throw new IOException("cannot create " + BELL_PATH);
        Os.chmod(BELL_PATH, 0666); // the app (uid 10xxx) must be able to open it for writing
        bellInode = Os.stat(BELL_PATH).st_ino;
        doorbellObserver = new FileObserver(bell, FileObserver.CLOSE_WRITE | FileObserver.MODIFY
                | FileObserver.DELETE_SELF | FileObserver.MOVE_SELF) {
            @Override public void onEvent(int event, String path) {
                int type = event & FileObserver.ALL_EVENTS;
                if ((type & (FileObserver.DELETE_SELF | FileObserver.MOVE_SELF)) != 0) rendezvous.requestRearm();
                else rendezvous.request();
            }
        };
        doorbellObserver.startWatching();
    }

    /** True while the file at BELL_PATH is still the inode the watch was installed on. */
    private static boolean doorbellIntact() {
        try {
            return Os.stat(BELL_PATH).st_ino == bellInode;
        } catch (ErrnoException missing) {
            return false;
        }
    }

    private static void shutdown() {
        log("shutting down");
        Session recording = activeRecording;
        if (recording != null) recording.requestStop();
        try {
            if (doorbellObserver != null) doorbellObserver.stopWatching();
        } catch (RuntimeException ignored) { }
    }

    // ------------------------------------------------------------------------------------------
    // Binder service: authorization + socketpair hand-out
    // ------------------------------------------------------------------------------------------

    private static final class BridgeBinder extends Binder {
        BridgeBinder() {
            attachInterface(null, DESCRIPTOR);
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code != TX_CONNECT) return super.onTransact(code, data, reply, flags);
            int callerUid = Binder.getCallingUid();
            ParcelFileDescriptor clientEnd = null;
            try {
                data.enforceInterface(DESCRIPTOR);
                authorize(callerUid);
                clientEnd = Session.open();
                reply.writeNoException();
                reply.writeFileDescriptor(clientEnd.getFileDescriptor()); // the parcel dups the descriptor
            } catch (SecurityException | IllegalStateException e) {
                log("CONNECT refused for uid " + callerUid + ": " + e.getMessage());
                reply.writeException(e);
            } catch (Exception e) {
                log("CONNECT failed for uid " + callerUid + ": " + describe(e));
                reply.writeException(new IllegalStateException(describe(e)));
            } finally {
                closeQuietly(clientEnd);
            }
            return true;
        }
    }

    /** Only the installed il.co.kolrecorder package may connect; resolved on every call (reinstalls change nothing). */
    private static void authorize(int callerUid) {
        int override = trustedUidOverride;
        if (override >= 0) {
            if (callerUid != override) throw new SecurityException("uid " + callerUid + " is not the test uid");
            return;
        }
        int trustedUid;
        try {
            trustedUid = Environment.context.getPackageManager().getApplicationInfo(TARGET_PACKAGE, 0).uid;
        } catch (PackageManager.NameNotFoundException notInstalled) {
            throw new SecurityException(TARGET_PACKAGE + " is not installed");
        }
        if (callerUid != trustedUid) throw new SecurityException("uid " + callerUid + " is not " + TARGET_PACKAGE);
    }

    // ------------------------------------------------------------------------------------------
    // Session: one socket, one command (PING or RECORD)
    // ------------------------------------------------------------------------------------------

    private static final AtomicInteger openSessions = new AtomicInteger();
    private static final AtomicInteger sessionCounter = new AtomicInteger();
    private static final AtomicBoolean recordingBusy = new AtomicBoolean();
    private static final byte[] ZERO_BLOCK = new byte[MAX_BLOCK_BYTES];

    private static final class Session implements Runnable {
        private final int id = sessionCounter.incrementAndGet();
        private final ParcelFileDescriptor socket;
        private final FileDescriptor fd;
        private volatile boolean stopRequested;
        private volatile GapFiller filler;
        private volatile PcmSource liveSource;
        private volatile long recordingStartedMs;

        private Session(ParcelFileDescriptor socket) {
            this.socket = socket;
            this.fd = socket.getFileDescriptor();
        }

        /** Creates the socketpair, starts the handler thread on the helper end and returns the app end. */
        static ParcelFileDescriptor open() throws IOException {
            if (openSessions.incrementAndGet() > MAX_SESSIONS) {
                openSessions.decrementAndGet();
                throw new IllegalStateException("too many open sessions");
            }
            ParcelFileDescriptor[] pair = null;
            try {
                pair = ParcelFileDescriptor.createSocketPair();
                Session session = new Session(pair[0]);
                Thread thread = new Thread(session, "kolbridge-session-" + session.id);
                thread.setDaemon(true);
                thread.start();
                return pair[1];
            } catch (IOException | RuntimeException | Error e) {
                openSessions.decrementAndGet();
                if (pair != null) { closeQuietly(pair[0]); closeQuietly(pair[1]); }
                throw e;
            }
        }

        void requestStop() {
            stopRequested = true;
            shutdownQuietly(fd);
        }

        @Override public void run() {
            // The fd stays owned by `socket`; the streams are only views on it.
            DeadlineInputStream handshakeInput = new DeadlineInputStream(fd, handshakeTimeoutMs);
            DataInputStream in = new DataInputStream(handshakeInput);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(fd), MAX_BLOCK_BYTES + 16));
            try {
                String command = in.readUTF(); // the only time-out: waiting for the command (poll based, see DeadlineInputStream)
                handshakeInput.clearDeadline();
                setSendTimeout(SEND_TIMEOUT_MS);
                if ("PING".equals(command)) ping(out);
                else if ("RECORD".equals(command)) record(in, out);
                else refuse(out, "unknown command");
            } catch (IOException e) {
                log("session " + id + " ended: " + describe(e));
            } catch (RuntimeException e) {
                log("session " + id + " failed: " + describe(e));
            } finally {
                shutdownQuietly(fd); // wake anything still blocked on this fd before the descriptor goes away
                closeQuietly(socket);
                openSessions.decrementAndGet();
            }
        }

        /** A client that stops reading for this long makes writes fail with EAGAIN, which ends the session. */
        private void setSendTimeout(int millis) throws IOException {
            try {
                Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_SNDTIMEO, StructTimeval.fromMillis(millis));
            } catch (ErrnoException e) {
                throw new IOException("setsockopt: " + e.getMessage());
            }
        }

        private void refuse(DataOutputStream out, String reason) throws IOException {
            out.writeUTF("ERROR");
            out.writeUTF(reason);
            out.flush();
        }

        private void ping(DataOutputStream out) throws IOException {
            out.writeUTF("OK");
            out.writeUTF(diagnostics());
            out.flush();
        }

        /**
         * Takes the single recording slot. A client that has just closed its connection may still be inside
         * AudioRecord.stop()/release(), which takes several hundred ms on the Pixel, so a new RECORD waits for a
         * session that is winding down. It never waits for a session that is really recording (refused after
         * SLOT_GRACE_MS, long enough to cover the moment before the closing session notices the EOF).
         */
        private boolean acquireRecordingSlot() {
            long startMs = SystemClock.elapsedRealtime();
            while (!recordingBusy.compareAndSet(false, true)) {
                Session holder = activeRecording;
                long waitedMs = SystemClock.elapsedRealtime() - startMs;
                boolean windingDown = holder == null || holder.stopRequested;
                if (waitedMs > SLOT_TAKEOVER_MS || (waitedMs > SLOT_GRACE_MS && !windingDown)) return false;
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }

        private void record(DataInputStream in, DataOutputStream out) throws IOException {
            if (!acquireRecordingSlot()) {
                refuse(out, "another recording client is connected");
                return;
            }
            PcmSource source = null;
            Thread eofWatcher = null;
            String summary = "";
            try {
                try {
                    source = sourceFactory.open();
                } catch (Throwable failure) {
                    log("session " + id + " RECORD refused: " + describe(failure));
                    refuse(out, describe(failure));
                    return;
                }
                liveSource = source;
                GapFiller timeline = new GapFiller(RATE, SystemClock.elapsedRealtimeNanos());
                filler = timeline;
                recordingStartedMs = SystemClock.elapsedRealtime();
                activeRecording = this;
                out.writeUTF("OK"); // AudioRecord exists, is initialized and recording
                out.flush();
                log("session " + id + " RECORD started");
                eofWatcher = startEofWatcher(in);
                String endReason = stream(source, out, timeline);
                summary = endReason + "; " + timeline.summary();
            } catch (IOException e) {
                summary = "client connection ended (" + describe(e) + ")";
            } finally {
                stopRequested = true;
                shutdownQuietly(fd); // unblocks the watcher read and any blocked write
                long releaseStartMs = SystemClock.elapsedRealtime();
                if (source != null) source.close(); // AudioRecord.stop() + release() before anything else can wait
                long releaseMs = SystemClock.elapsedRealtime() - releaseStartMs;
                activeRecording = null;
                recordingBusy.set(false);
                if (source != null) summary += (summary.isEmpty() ? "" : "; ") + "source released in " + releaseMs + " ms";
                if (eofWatcher != null) {
                    try { eofWatcher.join(500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                if (!summary.isEmpty()) {
                    lastRecordingSummary = summary;
                    log("session " + id + " RECORD stopped: " + summary);
                }
            }
        }

        /** Reads (and ignores) anything the client sends; EOF or an error means the client closed: stop recording. */
        private Thread startEofWatcher(DataInputStream in) {
            Thread watcher = new Thread(() -> {
                byte[] sink = new byte[64];
                try {
                    while (in.read(sink) >= 0) { /* unsolicited bytes are ignored */ }
                } catch (IOException closedOrReset) {
                    // same outcome as EOF
                }
                stopRequested = true;
                shutdownQuietly(fd);
            }, "kolbridge-eof-" + id);
            watcher.setDaemon(true);
            watcher.start();
            return watcher;
        }

        /** Main capture loop; returns why it ended. Never blocks on audio: reads are non-blocking + a short sleep. */
        private String stream(PcmSource source, DataOutputStream out, GapFiller timeline) throws IOException {
            byte[] buffer = new byte[MAX_BLOCK_BYTES];
            while (!stopRequested) {
                int count = source.read(buffer);
                long nowNs = SystemClock.elapsedRealtimeNanos();
                if (count < 0) {
                    String reason = "audio read failed (" + count + ")";
                    terminate(out, reason);
                    return reason;
                }
                count &= ~1; // whole 16-bit samples only
                if (count > 0) {
                    writePadding(out, timeline.padBeforeData(nowNs, count / 2));
                    writeBlock(out, buffer, count);
                    if (count == buffer.length) continue; // buffer was full: more is probably waiting
                } else {
                    writePadding(out, timeline.padWhileIdle(nowNs));
                }
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return "interrupted";
                }
            }
            return "client closed the connection";
        }

        private void writePadding(DataOutputStream out, long frames) throws IOException {
            long bytes = frames * 2;
            while (bytes > 0) {
                int block = (int) Math.min(bytes, MAX_BLOCK_BYTES);
                writeBlock(out, ZERO_BLOCK, block);
                bytes -= block;
            }
        }

        private void writeBlock(DataOutputStream out, byte[] data, int length) throws IOException {
            out.writeInt(length); // big-endian length, then little-endian PCM
            out.write(data, 0, length);
            out.flush();
        }

        /** In-band end of stream: length -1 followed by the reason. Best effort. */
        private void terminate(DataOutputStream out, String reason) {
            try {
                out.writeInt(-1);
                out.writeUTF(reason);
                out.flush();
            } catch (IOException clientGone) { /* nothing left to tell */ }
        }

        String status() {
            GapFiller timeline = filler;
            if (timeline == null) return "starting";
            return "recording " + (SystemClock.elapsedRealtime() - recordingStartedMs) / 1000 + " s; " + timeline.summary()
                    + "; source: " + liveSource.health();
        }
    }

    /**
     * Socket input with an optional deadline implemented by poll(). SO_RCVTIMEO cannot be used: on Android a
     * receive time-out makes FileInputStream.read() return 0 (measured on the Pixel) instead of throwing, which
     * DataInputStream would then consume as a zero byte. After clearDeadline() reads simply block.
     */
    private static final class DeadlineInputStream extends InputStream {
        private final FileDescriptor fd;
        private final FileInputStream stream;
        private volatile long deadlineMs; // SystemClock.elapsedRealtime() based; 0 means no deadline

        DeadlineInputStream(FileDescriptor fd, long timeoutMs) {
            this.fd = fd;
            this.stream = new FileInputStream(fd);
            this.deadlineMs = SystemClock.elapsedRealtime() + timeoutMs;
        }

        void clearDeadline() { deadlineMs = 0; }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            while (true) {
                int count = read(one, 0, 1);
                if (count < 0) return -1;
                if (count > 0) return one[0] & 0xff;
            }
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            long deadline = deadlineMs;
            if (deadline != 0) awaitReadable(deadline);
            return stream.read(buffer, offset, length);
        }

        private void awaitReadable(long deadline) throws IOException {
            StructPollfd request = new StructPollfd();
            request.fd = fd;
            request.events = (short) OsConstants.POLLIN;
            while (true) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) throw new SocketTimeoutException("no command within the handshake time-out");
                try {
                    if (Os.poll(new StructPollfd[]{request}, (int) Math.min(remaining, Integer.MAX_VALUE)) > 0) return; // data, hang-up or error: read() tells which
                } catch (ErrnoException e) {
                    if (e.errno != OsConstants.EINTR) throw new IOException("poll: " + e.getMessage());
                }
            }
        }
    }

    private static String diagnostics() {
        Session recording = activeRecording;
        return PROTOCOL + " version " + VERSION + "\n"
                + "pid " + Process.myPid() + " uid " + Process.myUid() + " uptime " + (SystemClock.elapsedRealtime() - START_BOOT_MS) / 1000 + " s\n"
                + "device " + Build.MODEL + " sdk " + Build.VERSION.SDK_INT + " build " + Build.ID + "\n"
                + "source VOICE_CALL(" + CALL_SOURCE + ") " + RATE + " Hz mono pcm16le, max block " + MAX_BLOCK_BYTES + " bytes\n"
                + "state " + (recording == null ? "idle" : recording.status()) + "\n"
                + "last recording: " + lastRecordingSummary;
    }

    // ------------------------------------------------------------------------------------------
    // Wall-clock timeline: zero padding for the intervals in which the virtual source is silent
    // ------------------------------------------------------------------------------------------

    /**
     * VOICE_CALL (like the other "virtual" sources) delivers no packets while the line is silent, so
     * concatenating what AudioRecord returns would produce a recording that is shorter than the call.
     *
     * The timeline is anchored on a monotonic clock that keeps counting in suspend (CLOCK_BOOTTIME) at
     * the moment recording starts. "Frames due" at time T is (T - anchor) * rate. Two rules keep the
     * number of frames sent equal to the frames due, without any HAL timestamps:
     *
     *  - data: a chunk of N frames read at time T ends at about T, so it belongs at frame due(T) - N.
     *    If that is at least `toleranceFrames` beyond what was already sent, the gap is filled with
     *    zeros first. Smaller differences are scheduling jitter and are left alone.
     *  - idle: when nothing is available, zeros are sent continuously but only up to due(T) - `lagFrames`.
     *    The lag leaves room for a late chunk to still land in its own slot, and keeps the recording
     *    growing in real time (live duration, crash recovery) during long silences.
     *
     * Padding is only ever added: if a large burst makes the stream run ahead of the wall clock, no
     * negative padding is produced, and the next real gap is shortened by exactly that surplus, so the
     * stream converges back to the wall clock. Single writer thread; counters are volatile for PING.
     */
    public static final class GapFiller {
        static final int TOLERANCE_MS = 100;
        static final int IDLE_LAG_MS = 200;
        static final int IDLE_MIN_CHUNK_MS = 20;

        private final long rate;
        private final long anchorNs;
        private final long toleranceFrames;
        private final long lagFrames;
        private final long idleMinChunkFrames;
        private long emittedFrames;
        private long openGapFrames;
        private volatile long dataFrames;
        private volatile long paddedFrames;
        private volatile long longestGapFrames;
        private volatile int gapCount;

        public GapFiller(int rate, long anchorNs) {
            this.rate = rate;
            this.anchorNs = anchorNs;
            this.toleranceFrames = rate * TOLERANCE_MS / 1000L;
            this.lagFrames = rate * IDLE_LAG_MS / 1000L;
            this.idleMinChunkFrames = rate * IDLE_MIN_CHUNK_MS / 1000L;
        }

        /** Frames that should have been delivered by `nowNs`. Microsecond resolution avoids long overflow. */
        public long framesDue(long nowNs) {
            return Math.max(0, (nowNs - anchorNs) / 1000L * rate / 1000000L);
        }

        /** Zero frames to send before `dataFrameCount` real frames that were just read at `nowNs`; accounts for both. */
        public long padBeforeData(long nowNs, int dataFrameCount) {
            long shortfall = framesDue(nowNs) - dataFrameCount - emittedFrames;
            long pad = shortfall >= toleranceFrames ? shortfall : 0;
            openGapFrames += pad;
            if (openGapFrames >= toleranceFrames) {
                gapCount++;
                longestGapFrames = Math.max(longestGapFrames, openGapFrames);
            }
            openGapFrames = 0; // real data ends the gap
            paddedFrames += pad;
            dataFrames += dataFrameCount;
            emittedFrames += pad + dataFrameCount;
            return pad;
        }

        /** Zero frames to send now because nothing was available at `nowNs`; accounts for them. */
        public long padWhileIdle(long nowNs) {
            long deficit = framesDue(nowNs) - lagFrames - emittedFrames;
            if (deficit < idleMinChunkFrames) return 0;
            openGapFrames += deficit;
            paddedFrames += deficit;
            emittedFrames += deficit;
            return deficit;
        }

        public long emittedFrames() { return emittedFrames; }
        public long dataFrames() { return dataFrames; }
        public long paddedFrames() { return paddedFrames; }
        public int gapCount() { return gapCount; }

        String summary() {
            return String.format(Locale.US, "audio %.1f s, padded %.1f s, gaps %d, longest gap %.1f s",
                    dataFrames / (double) rate, paddedFrames / (double) rate, gapCount, longestGapFrames / (double) rate);
        }
    }

    // ------------------------------------------------------------------------------------------
    // PCM sources
    // ------------------------------------------------------------------------------------------

    /** Little-endian 16-bit mono PCM; read() never blocks. */
    interface PcmSource {
        /** Bytes read (even), 0 when nothing is available right now, negative on a read error. */
        int read(byte[] buffer);

        /** One-line state for PING. */
        String health();

        /** Stops and releases the underlying audio resource; must be quick and idempotent. */
        void close();
    }

    interface PcmSourceFactory {
        /** Creates, initializes and starts a source or throws with a human-safe reason. */
        PcmSource open() throws Exception;
    }

    private static final class AudioRecordFactory implements PcmSourceFactory {
        @Override public PcmSource open() throws Exception {
            Environment.init();
            AudioRecord record = createAudioRecord(CALL_SOURCE, RATE, AudioFormat.CHANNEL_IN_MONO);
            try {
                if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new IllegalStateException("VOICE_CALL AudioRecord is not initialized (state " + record.getState() + ")");
                }
                record.startRecording();
                if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    throw new IllegalStateException("VOICE_CALL AudioRecord did not start recording");
                }
                return new AudioRecordSource(record);
            } catch (Throwable failure) {
                releaseQuietly(record);
                throw failure;
            }
        }
    }

    private static final class AudioRecordSource implements PcmSource {
        private final AudioRecord record;
        private final AtomicBoolean closed = new AtomicBoolean();
        private long lastHealthCheckMs;
        private String healthText = "ok";

        AudioRecordSource(AudioRecord record) {
            this.record = record;
        }

        @Override public int read(byte[] buffer) {
            if (closed.get()) return -1;
            return record.read(buffer, 0, buffer.length, AudioRecord.READ_NON_BLOCKING);
        }

        @Override public String health() {
            long now = SystemClock.elapsedRealtime();
            if (!closed.get() && now - lastHealthCheckMs > 2000) {
                lastHealthCheckMs = now;
                try {
                    AudioRecordingConfiguration configuration = record.getActiveRecordingConfiguration();
                    healthText = configuration == null ? "no active configuration"
                            : configuration.isClientSilenced() ? "SILENCED by the audio system" : "ok";
                } catch (RuntimeException ignored) { }
            }
            return healthText;
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            releaseQuietly(record);
        }
    }

    /** Builds an AudioRecord attributed to com.android.shell (uid 2000), see FakeContext. */
    static AudioRecord createAudioRecord(int source, int sampleRate, int channelMask) {
        int minimumBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord.Builder builder = new AudioRecord.Builder()
                .setContext(Environment.context)
                .setAudioSource(source)
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(channelMask).build());
        if (minimumBuffer > 0) builder.setBufferSizeInBytes(Math.max(minimumBuffer * 2, sampleRate)); // about half a second of mono
        return builder.build();
    }

    private static void releaseQuietly(AudioRecord record) {
        try {
            if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) record.stop();
        } catch (RuntimeException ignored) { }
        try {
            record.release();
        } catch (RuntimeException ignored) { }
    }

    // ------------------------------------------------------------------------------------------
    // Shell identity (adapted from scrcpy)
    // ------------------------------------------------------------------------------------------

    private static final class Environment {
        static volatile FakeContext context;

        /** Must run on the main thread: ActivityThread's handler needs a looper on the creating thread. */
        static synchronized void init() throws ReflectiveOperationException {
            if (context != null) return;
            if (Looper.myLooper() == null) {
                try {
                    Looper.prepareMainLooper();
                } catch (IllegalStateException mainLooperExists) {
                    Looper.prepare();
                }
            }
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Constructor<?> constructor = activityThreadClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object activityThread = constructor.newInstance();
            Field current = activityThreadClass.getDeclaredField("sCurrentActivityThread");
            current.setAccessible(true);
            current.set(null, activityThread);
            Field systemThread = activityThreadClass.getDeclaredField("mSystemThread");
            systemThread.setAccessible(true);
            systemThread.setBoolean(activityThread, true);
            Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            context = new FakeContext((Context) getSystemContext.invoke(activityThread));
        }
    }

    /** A context that claims to be the shell package, so permission and app-op checks use uid 2000 / com.android.shell. */
    private static final class FakeContext extends ContextWrapper {
        FakeContext(Context systemContext) {
            super(systemContext);
        }

        @Override public String getPackageName() { return SHELL_PACKAGE; }

        // Hidden in the public stubs, so no @Override; the framework still calls it virtually.
        public String getOpPackageName() { return SHELL_PACKAGE; }

        @Override public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build();
        }

        @Override public int getDeviceId() { return 0; }

        @Override public Context getApplicationContext() { return this; }

        @Override public Context createPackageContext(String packageName, int flags) { return this; }
    }

    // ------------------------------------------------------------------------------------------
    // Handing the Binder to the app (adapted from scrcpy's ActivityManager / ContentProvider wrappers)
    // ------------------------------------------------------------------------------------------

    private static final class ProviderCaller {
        private final Object activityManager;

        ProviderCaller() throws ReflectiveOperationException {
            Object manager;
            try {
                manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
            } catch (ReflectiveOperationException newApiMissing) {
                manager = Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
            }
            activityManager = manager;
        }

        /** Acquires the provider as shell, calls it once, releases it. May cold-start the provider's process. */
        Bundle call(String authority, String method, String argument, Bundle extras) throws Exception {
            IBinder token = new Binder();
            Object provider = acquire(authority, token);
            if (provider == null) throw new IllegalStateException("provider not found: " + authority);
            try {
                return invoke(provider, authority, method, argument, extras);
            } finally {
                release(authority, token);
            }
        }

        private int userId() { return Process.myUid() / 100000; }

        private Object acquire(String authority, IBinder token) throws Exception {
            Class<?> managerInterface = Class.forName("android.app.IActivityManager");
            Object holder;
            try {
                Method withTag = managerInterface.getMethod("getContentProviderExternal", String.class, int.class, IBinder.class, String.class);
                holder = withTag.invoke(activityManager, authority, userId(), token, "callbridge");
            } catch (NoSuchMethodException olderSignature) {
                Method plain = managerInterface.getMethod("getContentProviderExternal", String.class, int.class, IBinder.class);
                holder = plain.invoke(activityManager, authority, userId(), token);
            }
            if (holder == null) return null;
            Field providerField = holder.getClass().getDeclaredField("provider");
            providerField.setAccessible(true);
            return providerField.get(holder);
        }

        private void release(String authority, IBinder token) {
            try {
                Class<?> managerInterface = Class.forName("android.app.IActivityManager");
                try {
                    managerInterface.getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class)
                            .invoke(activityManager, authority, token, userId());
                } catch (NoSuchMethodException olderSignature) {
                    managerInterface.getMethod("removeContentProviderExternal", String.class, IBinder.class)
                            .invoke(activityManager, authority, token);
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                log("could not release provider " + authority + ": " + describe(e));
            }
        }

        /** IContentProvider.call() changed shape over the releases; pick the variant this device has. */
        private Bundle invoke(Object provider, String authority, String method, String argument, Bundle extras) throws Exception {
            Class<?> providerInterface = Class.forName("android.content.IContentProvider");
            AttributionSource attribution = Environment.context.getAttributionSource();
            try { // Android 12+: call(AttributionSource, authority, method, arg, extras)
                Method call = providerInterface.getMethod("call", AttributionSource.class, String.class, String.class, String.class, Bundle.class);
                return (Bundle) call.invoke(provider, attribution, authority, method, argument, extras);
            } catch (NoSuchMethodException ignored) { }
            try { // Android 11: call(callingPkg, attributionTag, authority, method, arg, extras)
                Method call = providerInterface.getMethod("call", String.class, String.class, String.class, String.class, String.class, Bundle.class);
                return (Bundle) call.invoke(provider, SHELL_PACKAGE, null, authority, method, argument, extras);
            } catch (NoSuchMethodException ignored) { }
            try { // Android 10: call(callingPkg, authority, method, arg, extras)
                Method call = providerInterface.getMethod("call", String.class, String.class, String.class, String.class, Bundle.class);
                return (Bundle) call.invoke(provider, SHELL_PACKAGE, authority, method, argument, extras);
            } catch (NoSuchMethodException ignored) { }
            Method call = providerInterface.getMethod("call", String.class, String.class, String.class, Bundle.class);
            return (Bundle) call.invoke(provider, SHELL_PACKAGE, method, argument, extras);
        }
    }

    /** Pushes the Binder to the app: once at startup (with retries) and whenever the doorbell rings. */
    private static final class Rendezvous implements Runnable {
        private final BridgeBinder binder;
        private final Object lock = new Object();
        private boolean requested;
        private boolean rearmRequested;
        private long lastPushMs;

        Rendezvous(BridgeBinder binder) {
            this.binder = binder;
        }

        /** The doorbell rang (or the startup push is due): hand the Binder to the app. */
        void request() {
            synchronized (lock) {
                requested = true;
                lock.notifyAll();
            }
        }

        /** The doorbell file was deleted or moved: its inotify watch is gone and must be rebuilt. */
        void requestRearm() {
            synchronized (lock) {
                rearmRequested = true;
                lock.notifyAll();
            }
        }

        @Override public void run() {
            int startupAttemptsLeft = STARTUP_PUSH_ATTEMPTS;
            request();
            while (true) {
                try {
                    boolean pushNeeded;
                    boolean rearmNeeded;
                    synchronized (lock) {
                        // The timed wait doubles as a slow safety net for watch failures that raise no event.
                        if (!requested && !rearmRequested) lock.wait(DOORBELL_CHECK_MS);
                        pushNeeded = requested;
                        rearmNeeded = rearmRequested;
                        requested = false;
                        rearmRequested = false;
                    }
                    if (rearmNeeded || !doorbellIntact()) {
                        try {
                            armDoorbell();
                            log("doorbell re-armed");
                        } catch (IOException | ErrnoException | RuntimeException e) {
                            log("doorbell re-arm failed: " + describe(e));
                        }
                    }
                    if (!pushNeeded) continue;
                    long sinceLastPush = SystemClock.elapsedRealtime() - lastPushMs;
                    if (lastPushMs != 0 && sinceLastPush < MIN_PUSH_GAP_MS) Thread.sleep(MIN_PUSH_GAP_MS - sinceLastPush);
                    boolean delivered = push();
                    lastPushMs = SystemClock.elapsedRealtime();
                    if (delivered) {
                        startupAttemptsLeft = 0;
                    } else if (startupAttemptsLeft-- > 0) {
                        Thread.sleep(STARTUP_PUSH_RETRY_MS); // app or provider not there yet; the doorbell covers later starts
                        request();
                    }
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }

        private boolean push() {
            try {
                Bundle extras = new Bundle();
                extras.putBinder(BRIDGE_EXTRA, binder);
                extras.putInt("protocol", 1);
                extras.putString("version", VERSION);
                extras.putInt("pid", Process.myPid());
                Bundle result = new ProviderCaller().call(PROVIDER_AUTHORITY, REGISTER_METHOD, null, extras);
                boolean accepted = result != null && result.getBoolean("ok", false);
                log("REGISTER " + (accepted ? "accepted by the app" : "returned no ok"));
                return accepted;
            } catch (Throwable failure) {
                log("REGISTER failed: " + describe(unwrap(failure)));
                return false;
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // --probe: read-only initialization test. Creates and releases AudioRecord objects, never starts one.
    // ------------------------------------------------------------------------------------------

    private static final class Probe {
        private static void line(String text) { System.out.println("PROBE " + text); }

        static int run() {
            line(PROTOCOL + " version " + VERSION + " (no audio is started or captured)");
            line("environment: uid " + Process.myUid() + " pid " + Process.myPid() + " sdk " + Build.VERSION.SDK_INT
                    + " release " + Build.VERSION.RELEASE + " model " + Build.MODEL);
            line("fingerprint: " + Build.FINGERPRINT);
            if (Process.myUid() != SHELL_UID) line("WARNING: uid is not 2000, results do not describe the shell helper");
            try {
                Environment.init();
                line("step 1 shell context: ok, package " + Environment.context.getPackageName()
                        + ", attribution " + Environment.context.getAttributionSource().getUid()
                        + "/" + Environment.context.getAttributionSource().getPackageName());
            } catch (Throwable failure) {
                line("step 1 shell context: FAILED " + describe(unwrap(failure)));
                line("RESULT FAIL: cannot build the shell context (hidden API bootstrap)");
                return 3;
            }
            try {
                int appUid = Environment.context.getPackageManager().getApplicationInfo(TARGET_PACKAGE, 0).uid;
                line("step 2 trusted package: " + TARGET_PACKAGE + " installed, uid " + appUid);
            } catch (Throwable failure) {
                line("step 2 trusted package: " + TARGET_PACKAGE + " NOT resolvable (" + describe(failure) + "); CONNECT would be refused");
            }
            try {
                Bundle result = new ProviderCaller().call("settings", "GET_global", "adb_enabled", null);
                line("step 3 provider call path (getContentProviderExternal + IContentProvider.call): ok, settings adb_enabled="
                        + (result == null ? "null" : result.getString("value")));
            } catch (Throwable failure) {
                line("step 3 provider call path: FAILED " + describe(unwrap(failure)) + " (REGISTER push would fail)");
            }
            try {
                ParcelFileDescriptor[] pair = ParcelFileDescriptor.createSocketPair();
                closeQuietly(pair[0]);
                closeQuietly(pair[1]);
                line("step 4 socketpair for the data channel: ok");
            } catch (Throwable failure) {
                line("step 4 socketpair for the data channel: FAILED " + describe(failure));
            }
            line("step 5 AudioRecord initialization (create, read state, release; startRecording is never called)");
            boolean production = tryCreate("VOICE_CALL 16000 Hz mono   [production configuration]", CALL_SOURCE, RATE, AudioFormat.CHANNEL_IN_MONO);
            tryCreate("VOICE_UPLINK 16000 Hz mono   [diagnostic]", MediaRecorder.AudioSource.VOICE_UPLINK, RATE, AudioFormat.CHANNEL_IN_MONO);
            tryCreate("VOICE_DOWNLINK 16000 Hz mono [diagnostic]", MediaRecorder.AudioSource.VOICE_DOWNLINK, RATE, AudioFormat.CHANNEL_IN_MONO);
            tryCreate("VOICE_CALL 8000 Hz mono      [diagnostic]", CALL_SOURCE, 8000, AudioFormat.CHANNEL_IN_MONO);
            tryCreate("VOICE_CALL 48000 Hz mono     [diagnostic]", CALL_SOURCE, 48000, AudioFormat.CHANNEL_IN_MONO);
            tryCreate("VOICE_CALL 48000 Hz stereo   [diagnostic]", CALL_SOURCE, 48000, AudioFormat.CHANNEL_IN_STEREO);
            tryCreate("MIC 16000 Hz mono            [control only: separates attribution problems from call-audio problems]",
                    MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO);
            if (production) {
                line("RESULT PASS: a VOICE_CALL AudioRecord can be created from the shell context; nothing was started or captured");
                return 0;
            }
            line("RESULT FAIL: the production VOICE_CALL 16 kHz mono AudioRecord could not be initialized (see the line above)");
            return 2;
        }

        private static boolean tryCreate(String label, int source, int rate, int channelMask) {
            AudioRecord record = null;
            try {
                int minimumBuffer = AudioRecord.getMinBufferSize(rate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
                record = createAudioRecord(source, rate, channelMask);
                boolean initialized = record.getState() == AudioRecord.STATE_INITIALIZED;
                line("  " + label + ": " + (initialized ? "INITIALIZED" : "NOT INITIALIZED") + ", minBuffer " + minimumBuffer
                        + ", rate " + record.getSampleRate() + ", channels " + record.getChannelCount() + ", session " + record.getAudioSessionId());
                return initialized;
            } catch (Throwable failure) {
                line("  " + label + ": FAILED " + describe(failure));
                return false;
            } finally {
                if (record != null) releaseQuietly(record);
            }
        }

        /** Verifies that an inotify watch on a world-writable file in /data/local/tmp fires when another writer touches it. */
        static int runDoorbell() throws Exception {
            File bell = new File(WORK_DIR + "/kol-callbridge-probe.bell");
            if (!bell.exists() && !bell.createNewFile()) {
                line("doorbell: cannot create " + bell);
                return 2;
            }
            try {
                Os.chmod(bell.getPath(), 0666);
                final AtomicBoolean fired = new AtomicBoolean();
                FileObserver observer = new FileObserver(bell, FileObserver.CLOSE_WRITE | FileObserver.MODIFY) {
                    @Override public void onEvent(int event, String path) { fired.set(true); }
                };
                observer.startWatching();
                try (FileOutputStream ring = new FileOutputStream(bell)) {
                    ring.write('!');
                }
                long deadline = SystemClock.elapsedRealtime() + 1500;
                while (!fired.get() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10);
                observer.stopWatching();
                line("doorbell: inotify event " + (fired.get() ? "RECEIVED" : "NOT received") + " after writing " + bell.getName());
                return fired.get() ? 0 : 2;
            } finally {
                if (!bell.delete()) line("doorbell: could not delete " + bell);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // --selftest: runs the whole Binder + socket + stream path inside this process with a fake PCM
    // source. No AudioRecord is created, nothing is recorded.
    // ------------------------------------------------------------------------------------------

    private static final class SelfTest {
        private static int failures;

        private static void check(String name, boolean ok, String detail) {
            System.out.println("SELFTEST " + (ok ? "PASS " : "FAIL ") + name + (detail.isEmpty() ? "" : " - " + detail));
            if (!ok) failures++;
        }

        /** A 440 Hz tone in scripted windows (ms after open); outside the windows it delivers nothing, like VOICE_CALL in silence. */
        private static final class ScriptedSource implements PcmSource {
            private final long openedMs = SystemClock.elapsedRealtime();
            private final long[][] windows;
            private long producedUpToMs;
            private long phase;
            private final long releaseDelayMs;
            volatile long closedAtMs;

            ScriptedSource(long[][] windows) { this(windows, 0); }

            /** `releaseDelayMs` imitates the slow AudioRecord.stop()/release() seen on the Pixel. */
            ScriptedSource(long[][] windows, long releaseDelayMs) {
                this.windows = windows;
                this.releaseDelayMs = releaseDelayMs;
            }

            @Override public int read(byte[] buffer) {
                long nowMs = SystemClock.elapsedRealtime() - openedMs;
                for (long[] window : windows) {
                    long from = Math.max(producedUpToMs, window[0]);
                    long to = Math.min(nowMs, window[1]);
                    if (to <= from) continue;
                    int frames = (int) Math.min((to - from) * RATE / 1000, buffer.length / 2);
                    if (frames <= 0) continue;
                    for (int i = 0; i < frames; i++, phase++) {
                        short sample = (short) (8000 * Math.sin(2 * Math.PI * 440 * phase / RATE));
                        if (sample == 0) sample = 1; // keep tone samples non-zero so zero runs are unambiguous padding
                        buffer[2 * i] = (byte) sample;
                        buffer[2 * i + 1] = (byte) (sample >> 8);
                    }
                    producedUpToMs = from + frames * 1000L / RATE;
                    return frames * 2;
                }
                return 0;
            }

            @Override public String health() { return "scripted"; }
            @Override public void close() {
                if (closedAtMs != 0) return;
                closedAtMs = SystemClock.elapsedRealtime();
                if (releaseDelayMs > 0) SystemClock.sleep(releaseDelayMs);
            }
        }

        private static final class Client {
            final ParcelFileDescriptor socket;
            final DataInputStream in;
            final DataOutputStream out;

            Client(IBinder bridge) throws Exception {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    bridge.transact(TX_CONNECT, data, reply, 0);
                    reply.readException();
                    socket = reply.readFileDescriptor();
                } finally {
                    data.recycle();
                    reply.recycle();
                }
                in = new DataInputStream(new FileInputStream(socket.getFileDescriptor()));
                out = new DataOutputStream(new FileOutputStream(socket.getFileDescriptor()));
            }

            /** Same order the app uses: shutdown first (wakes blocked readers), then close. */
            void close() {
                shutdownQuietly(socket.getFileDescriptor());
                closeQuietly(socket);
            }
        }

        /** A hung self-test must explain itself: after the limit, print every thread's stack and exit with 3. */
        private static Thread startWatchdog(final long limitMs) {
            Thread watchdog = new Thread(() -> {
                try {
                    Thread.sleep(limitMs);
                } catch (InterruptedException finished) {
                    return;
                }
                System.out.println("SELFTEST FAIL watchdog: not finished after " + limitMs / 1000 + " s, thread stacks follow");
                for (java.util.Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                    System.out.println("  thread " + entry.getKey().getName() + " " + entry.getKey().getState());
                    for (StackTraceElement frame : entry.getValue()) System.out.println("      at " + frame);
                }
                System.out.flush();
                Runtime.getRuntime().halt(3);
            }, "kolbridge-selftest-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            return watchdog;
        }

        static int run() throws Exception {
            Environment.init();
            Thread watchdog = startWatchdog(40000);
            BridgeBinder bridge = new BridgeBinder();
            PcmSourceFactory realFactory = sourceFactory;
            try {
                // 1. authorization: a caller that is not the installed app must be refused
                try {
                    new Client(bridge).close();
                    check("authorization refuses an untrusted uid", false, "CONNECT unexpectedly succeeded");
                } catch (SecurityException refused) {
                    check("authorization refuses an untrusted uid", true, refused.getMessage());
                }

                trustedUidOverride = Process.myUid(); // from here on this process plays the trusted app

                // 2. PING
                Client ping = new Client(bridge);
                ping.out.writeUTF("PING");
                ping.out.flush();
                String pingStatus = ping.in.readUTF();
                String pingText = ping.in.readUTF();
                check("PING answers OK + diagnostics", "OK".equals(pingStatus) && pingText.startsWith(PROTOCOL), pingText.split("\n")[0]);
                check("PING connection is closed by the helper", ping.in.read() == -1, "");
                ping.close();

                // 3. unknown command
                Client unknown = new Client(bridge);
                unknown.out.writeUTF("HELLO");
                unknown.out.flush();
                check("unknown command is refused", "ERROR".equals(unknown.in.readUTF()), unknown.in.readUTF());
                unknown.close();

                // 4. RECORD with a scripted source: 0-400 ms tone, silence to 1600 ms, tone 1600-1900 ms
                final ScriptedSource scripted = new ScriptedSource(new long[][]{{0, 400}, {1600, 1900}});
                sourceFactory = () -> scripted;
                Client record = new Client(bridge);
                record.out.writeUTF("RECORD");
                record.out.flush();
                long startedMs = SystemClock.elapsedRealtime();
                check("RECORD acknowledges with OK", "OK".equals(record.in.readUTF()), "");

                // 4a. PING and a second RECORD while the first is active
                Client pingWhileActive = new Client(bridge);
                pingWhileActive.out.writeUTF("PING");
                pingWhileActive.out.flush();
                pingWhileActive.in.readUTF();
                String activeText = pingWhileActive.in.readUTF();
                check("PING works while recording", activeText.contains("state recording"), "");
                pingWhileActive.close();
                Client second = new Client(bridge);
                second.out.writeUTF("RECORD");
                second.out.flush();
                check("second RECORD is refused", "ERROR".equals(second.in.readUTF()), second.in.readUTF());
                second.close();

                // 4b. read frames for 2.2 s of wall time
                long totalBytes = 0;
                long zeroRun = 0;
                long longestZeroRun = 0;
                boolean framesValid = true;
                while (SystemClock.elapsedRealtime() - startedMs < 2200) {
                    int length = record.in.readInt();
                    if (length <= 0 || length > MAX_BLOCK_BYTES || (length & 1) != 0) { framesValid = false; break; }
                    byte[] block = new byte[length];
                    record.in.readFully(block);
                    totalBytes += length;
                    for (int i = 0; i < length; i += 2) {
                        if (block[i] == 0 && block[i + 1] == 0) { zeroRun++; longestZeroRun = Math.max(longestZeroRun, zeroRun); }
                        else zeroRun = 0;
                    }
                }
                long elapsedMs = SystemClock.elapsedRealtime() - startedMs;
                long receivedMs = totalBytes * 1000 / (RATE * 2L);
                check("frames are even, 1.." + MAX_BLOCK_BYTES + " bytes", framesValid, "");
                check("received audio duration tracks wall clock", Math.abs(receivedMs - elapsedMs) <= 400,
                        "received " + receivedMs + " ms vs wall " + elapsedMs + " ms");
                long longestZeroMs = longestZeroRun * 1000 / RATE;
                check("the 1.2 s silence became about 1.2 s of zeros", longestZeroMs >= 900 && longestZeroMs <= 1500, "longest zero run " + longestZeroMs + " ms");

                // 4c. remote close must stop and release the source promptly
                long closeStartMs = SystemClock.elapsedRealtime();
                record.close();
                while (scripted.closedAtMs == 0 && SystemClock.elapsedRealtime() - closeStartMs < 3000) Thread.sleep(5);
                long stopLatency = scripted.closedAtMs == 0 ? -1 : scripted.closedAtMs - closeStartMs;
                check("closing the connection releases the source within 1 s", stopLatency >= 0 && stopLatency <= 1000, "latency " + stopLatency + " ms");
                Thread.sleep(100);
                check("recording slot is free again", !recordingBusy.get(), "");

                // 4d. close during pure silence (no data ever arrives) must also stop promptly
                final ScriptedSource silent = new ScriptedSource(new long[0][]);
                sourceFactory = () -> silent;
                Client silentRecord = new Client(bridge);
                silentRecord.out.writeUTF("RECORD");
                silentRecord.out.flush();
                silentRecord.in.readUTF();
                Thread.sleep(600);
                long silentCloseMs = SystemClock.elapsedRealtime();
                silentRecord.close();
                while (silent.closedAtMs == 0 && SystemClock.elapsedRealtime() - silentCloseMs < 3000) Thread.sleep(5);
                long silentLatency = silent.closedAtMs == 0 ? -1 : silent.closedAtMs - silentCloseMs;
                check("close during silence releases the source within 1 s", silentLatency >= 0 && silentLatency <= 1000, "latency " + silentLatency + " ms");

                // 4d2. reopening right after a close while the old source is still releasing slowly (700 ms) must succeed
                final ScriptedSource slowRelease = new ScriptedSource(new long[0][], 700);
                final ScriptedSource reopened = new ScriptedSource(new long[0][]);
                final AtomicInteger openCalls = new AtomicInteger();
                sourceFactory = () -> openCalls.getAndIncrement() == 0 ? slowRelease : reopened;
                Client first = new Client(bridge);
                first.out.writeUTF("RECORD");
                first.out.flush();
                first.in.readUTF();
                Thread.sleep(200);
                first.close();
                long reopenStartMs = SystemClock.elapsedRealtime();
                Client again = new Client(bridge);
                again.out.writeUTF("RECORD");
                again.out.flush();
                String againStatus = again.in.readUTF();
                long reopenWaitMs = SystemClock.elapsedRealtime() - reopenStartMs;
                check("RECORD right after a close waits for the slow release and succeeds",
                        "OK".equals(againStatus) && reopenWaitMs >= 400 && reopenWaitMs < 2500, againStatus + " after " + reopenWaitMs + " ms");
                again.close();
                Thread.sleep(200);

                // 4e. a failing factory must be reported as ERROR with a reason, and must not leave the slot busy
                sourceFactory = () -> { throw new UnsupportedOperationException("Cannot create AudioRecord"); };
                Client failing = new Client(bridge);
                failing.out.writeUTF("RECORD");
                failing.out.flush();
                String failStatus = failing.in.readUTF();
                String failReason = failing.in.readUTF();
                check("source failure is reported, no fallback", "ERROR".equals(failStatus) && failReason.contains("Cannot create AudioRecord"), failReason);
                failing.close();
                Thread.sleep(100);
                check("slot is free after a failed RECORD", !recordingBusy.get(), "");

                // 5. handshake time-out: a client that never sends a command is dropped
                handshakeTimeoutMs = 400;
                Client silentClient = new Client(bridge);
                long waitStartMs = SystemClock.elapsedRealtime();
                int eof = silentClient.in.read();
                long waited = SystemClock.elapsedRealtime() - waitStartMs;
                check("idle handshake is closed by the helper", eof == -1 && waited < 2000, "after " + waited + " ms");
                silentClient.close();
                Thread.sleep(100);
                check("no sessions leaked", openSessions.get() == 0, "open " + openSessions.get());
            } finally {
                trustedUidOverride = -1;
                handshakeTimeoutMs = 5000;
                sourceFactory = realFactory;
            }
            watchdog.interrupt();
            System.out.println("SELFTEST RESULT " + (failures == 0 ? "PASS" : "FAIL (" + failures + ")"));
            return failures == 0 ? 0 : 2;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private static void shutdownQuietly(FileDescriptor fd) {
        try {
            Os.shutdown(fd, OsConstants.SHUT_RDWR);
        } catch (ErrnoException | RuntimeException ignored) { }
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        try {
            descriptor.close();
        } catch (IOException ignored) { }
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof InvocationTargetException && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }

    /** One line, at most 300 characters, exception names + messages only: safe to hand to the app as a reason. */
    static String describe(Throwable failure) {
        StringBuilder text = new StringBuilder();
        Throwable cursor = unwrap(failure);
        for (int depth = 0; cursor != null && depth < 3; depth++, cursor = cursor.getCause()) {
            if (depth > 0) text.append(" <- ");
            text.append(cursor.getClass().getSimpleName());
            if (cursor.getMessage() != null) text.append(": ").append(cursor.getMessage());
        }
        String line = text.toString().replace('\n', ' ').replace('\r', ' ');
        return line.length() > 300 ? line.substring(0, 300) : line;
    }

    private static void log(String message) {
        Log.i(LOG_TAG, message);
        System.err.println(String.format(Locale.US, "%tT.%<tL ", System.currentTimeMillis()) + LOG_TAG + " " + message);
    }
}
