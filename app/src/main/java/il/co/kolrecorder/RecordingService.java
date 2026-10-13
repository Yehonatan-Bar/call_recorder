package il.co.kolrecorder;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RecordingService extends Service {
    public static final String START = "il.co.kolrecorder.START";
    public static final String STOP = "il.co.kolrecorder.STOP";
    public static volatile boolean busy;
    public static volatile boolean recording;
    public static volatile boolean finishing;
    public static volatile boolean silenced;
    public static volatile boolean quiet;
    public static volatile int amplitude;
    public static volatile long duration;
    public static volatile long revision;
    public static volatile String message = "";
    private final AtomicBoolean running = new AtomicBoolean();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private boolean destroyed;
    private volatile CallBridgeClient activeBridge;
    public static volatile boolean callMode;
    private static final String CHANNEL = "active_recording";

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "הקלטה פעילה", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("חיווי גלוי וכפתור עצירה בזמן ההקלטה");
        channel.setSound(null, null);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (STOP.equals(intent.getAction())) {
            if (busy) { finishing = true; running.set(false); CallBridgeClient bridge = activeBridge; if (bridge != null) bridge.close(); }
            else stopSelf();
            return START_NOT_STICKY;
        }
        if (!START.equals(intent.getAction()) || busy) return START_NOT_STICKY;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            message = "נדרשת הרשאת מיקרופון כדי להקליט"; stopSelf(); return START_NOT_STICKY;
        }
        busy = true; recording = false; finishing = false; silenced = false; quiet = false;
        duration = 0; amplitude = 0; message = "";
        try {
            Notification notification = notification("מתכונן להקלטה…");
            if (Build.VERSION.SDK_INT >= 29) startForeground(11, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(11, notification);
            wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KolRecorder:Capture");
            wakeLock.acquire(6 * 60 * 60 * 1000L + 60_000L);
        } catch (RuntimeException e) {
            busy = false;
            message = "לא ניתן להתחיל הקלטה. יש לפתוח את האפליקציה ולאשר הרשאת מיקרופון.";
            releaseWakeLock(); stopSelf(); return START_NOT_STICKY;
        }
        int requestedSource = intent.getIntExtra("source", MediaRecorder.AudioSource.MIC);
        int source = requestedSource == MediaRecorder.AudioSource.VOICE_CALL || requestedSource == MediaRecorder.AudioSource.VOICE_RECOGNITION ? requestedSource : MediaRecorder.AudioSource.MIC;
        callMode = source == MediaRecorder.AudioSource.VOICE_CALL;
        running.set(true);
        new Thread(() -> capture(source), "Kol-Audio").start();
        return START_NOT_STICKY;
    }

    private void capture(int source) {
        AudioRecord audio = null;
        WavFile wav = null;
        File partial = null;
        RecordingStore store = new RecordingStore(this);
        String result = "";
        long voicedSamples = 0;
        boolean wasBlocked = false;
        try {
            store.recover();
            if (store.directory().getUsableSpace() < 16 * 1024 * 1024L) throw new IOException("storage");
            if (source == MediaRecorder.AudioSource.VOICE_CALL) {
                try {
                    long deadline = SystemClock.elapsedRealtime() + 1500;
                    while (running.get()) {
                        try { activeBridge = new CallBridgeClient("RECORD"); break; }
                        catch (CallBridgeClient.BusyException busyBridge) {
                            if (SystemClock.elapsedRealtime() >= deadline) throw busyBridge;
                            SystemClock.sleep(100);
                        }
                    }
                    if (!running.get()) return;
                }
                catch (IOException | RuntimeException e) { throw new IllegalStateException("Call bridge unavailable", e); }
            } else {
            int minimum = AudioRecord.getMinBufferSize(WavFile.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("Unsupported audio format");
            audio = new AudioRecord.Builder().setAudioSource(source)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(WavFile.RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(Math.max(minimum * 2, 8192)).build();
            if (audio.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Audio unavailable");
            }
            partial = store.newPartial();
            wav = new WavFile(partial);
            if (running.get() && audio != null) audio.startRecording();
            recording = running.get();
            if (recording && audio != null && audio.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("Audio unavailable");
            long started = SystemClock.elapsedRealtime();
            long lastSignal = started;
            long lastCheck = started;
            long lastCheckpoint = started;
            short[] buffer = new short[32768];
            int emptyReads = 0;
            while (running.get()) {
                int count;
                if (activeBridge != null) {
                    try { count = activeBridge.read(buffer); }
                    catch (IOException e) { if (!running.get()) break; throw new IllegalStateException("Call bridge disconnected", e); }
                } else count = audio.read(buffer, 0, 1600, AudioRecord.READ_BLOCKING);
                if (count < 0) throw new IllegalStateException("Audio read failed: " + count);
                if (count == 0) {
                    if (++emptyReads > 50) throw new IllegalStateException("Audio stalled");
                    SystemClock.sleep(20); continue;
                }
                emptyReads = 0;
                wav.write(buffer, count);
                int peak = 0;
                for (int i = 0; i < count; i++) {
                    int value = Math.abs((int) buffer[i]);
                    peak = Math.max(peak, value);
                    if (value > 64) voicedSamples++;
                }
                amplitude = peak;
                duration = wav.durationMs();
                long now = SystemClock.elapsedRealtime();
                if (peak > 64) lastSignal = now;
                quiet = now - lastSignal >= 8000;
                if (now - lastCheck >= 500) {
                    lastCheck = now;
                    if (audio != null && Build.VERSION.SDK_INT >= 29) {
                        AudioRecordingConfiguration config = audio.getActiveRecordingConfiguration();
                        silenced = config != null && config.isClientSilenced();
                        wasBlocked |= silenced;
                    }
                }
                if (now - lastCheckpoint >= 5000) {
                    lastCheckpoint = now;
                    wav.checkpoint();
                    if (store.directory().getUsableSpace() < 8 * 1024 * 1024L) { result = "ההקלטה נעצרה: אין מספיק מקום פנוי"; break; }
                    handler.post(() -> {
                        if (!destroyed && busy && !finishing) getSystemService(NotificationManager.class)
                                .notify(11, notification(silenced ? "Android חוסם את קלט השמע" : quiet ? "לא מזוהה אות שמע — יש לבדוק את ההקלטה" : (callMode ? "מקליט מערוץ השיחה · " : "מקליט מהמיקרופון · ") + RecordingStore.time(duration)));
                    });
                }
                if (now - started >= 6 * 60 * 60 * 1000L) { result = "ההקלטה נעצרה לאחר שש שעות"; break; }
            }
        } catch (IOException e) {
            result = "ההקלטה נעצרה עקב שגיאה בשמירה. יש לבדוק מקום פנוי.";
        } catch (RuntimeException e) {
            result = callMode ? "ערוץ השיחה לא זמין או נותק. יש להתחיל שיחה ולוודא שרכיב העזר פעיל." : "המיקרופון לא זמין. ייתכן ש־Android, שיחה או יישום אחר חוסמים אותו.";
            android.util.Log.w("KolRecorder", "Audio capture failed", e);
        } finally {
            running.set(false); recording = false; finishing = true;
            if (activeBridge != null) { activeBridge.close(); activeBridge = null; }
            if (audio != null) {
                try { audio.stop(); } catch (RuntimeException ignored) { }
                audio.release();
            }
            boolean closed = false;
            if (wav != null) {
                try { wav.close(); closed = true; }
                catch (IOException e) { result = "השמירה לא הושלמה. האפליקציה תנסה לשחזר בפתיחה הבאה."; }
            }
            if (partial != null && closed) {
                if (partial.length() > 44) {
                    try {
                        File saved = store.finish(partial);
                        boolean suspect = voicedSamples < WavFile.RATE / 10 || wasBlocked;
                        store.setQuiet(saved, suspect);
                        if (result.isEmpty()) result = suspect ? "נשמרה הקלטה, אך ייתכן שחסר בה שמע. יש להאזין ולבדוק." : "ההקלטה נשמרה במכשיר";
                    } catch (IOException e) { result = "הקלטה חלקית נשמרה ותשוחזר בפתיחה הבאה"; }
                } else partial.delete();
            }
            final String finalResult = result;
            handler.post(() -> {
                message = finalResult; amplitude = 0; busy = false; finishing = false; silenced = false; quiet = false; revision++;
                releaseWakeLock();
                if (!destroyed) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
            });
        }
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, RecordingService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_record).setContentTitle("קול · הקלטה פעילה")
                .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "עצירה ושמירה", stop).build()).build();
    }

    private void releaseWakeLock() { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }
    @Override public void onDestroy() {
        destroyed = true; running.set(false); releaseWakeLock();
        if (activeBridge != null) activeBridge.close();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
