package il.co.kolrecorder;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import java.io.File;
import java.io.RandomAccessFile;
import java.util.HashSet;
import java.util.Set;

/** Explicit adb tests. No dialing. Refuses to run during a user's real call. */
public final class BridgeDeviceTest extends Instrumentation {
    private String mode;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        mode = args == null ? "live" : args.getString("mode", "live");
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        Context context = getTargetContext();
        RecordingStore store = new RecordingStore(context);
        Set<String> before = new HashSet<>();
        File[] initial = store.directory().listFiles();
        if (initial != null) for (File file : initial) before.add(file.getName());
        boolean ownsCapture = false;
        int status = Activity.RESULT_CANCELED;
        try {
            check(!RecordingService.busy, "User recording active");
            check(context.getSystemService(AudioManager.class).getMode() == AudioManager.MODE_NORMAL,
                    "Refusing automated test during a real call");
            startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            if ("missing".equals(mode)) {
                check(!CallBridgeClient.available(), "Expected bridge to be stopped");
                context.startForegroundService(new Intent(context, RecordingService.class)
                        .setAction(RecordingService.START).putExtra("source", MediaRecorder.AudioSource.VOICE_CALL));
                ownsCapture = true;
                SystemClock.sleep(500);
                awaitStopped();
                check(!RecordingService.message.isEmpty(), "Missing bridge error not shown");
                check(RecordingService.duration == 0, "Missing bridge unexpectedly produced audio");
                check(store.list().size() == countWavs(before), "Missing bridge created a recording");
                result.putString("result", "PASS: absent bridge produces explicit error and no microphone fallback/file");
            } else {
                long until = SystemClock.elapsedRealtime() + 15000;
                while (!CallBridgeClient.available() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(250);
                check(CallBridgeClient.available(), "Bridge not registered");
                long began = SystemClock.elapsedRealtime();
                long samples = 0;
                try (CallBridgeClient bridge = new CallBridgeClient("RECORD")) {
                    check(CallBridgeClient.available(), "PING failed while recording");
                    short[] pcm = new short[32768];
                    while (SystemClock.elapsedRealtime() - began < 2200) samples += bridge.read(pcm);
                    long elapsed = SystemClock.elapsedRealtime() - began;
                    long pcmMs = samples * 1000 / WavFile.RATE;
                    check(Math.abs(pcmMs - elapsed) < 700, "Silence changes duration: PCM=" + pcmMs + " wall=" + elapsed);
                    result.putString("bridge_pcm_ms", String.valueOf(pcmMs));
                    result.putString("bridge_wall_ms", String.valueOf(elapsed));
                }
                SystemClock.sleep(1200);
                // Reopening proves closing a silent stream releases the previous capture.
                try (CallBridgeClient bridge = new CallBridgeClient("RECORD")) { bridge.read(new short[32768]); }
                SystemClock.sleep(1200);
                context.startForegroundService(new Intent(context, RecordingService.class)
                        .setAction(RecordingService.START).putExtra("source", MediaRecorder.AudioSource.VOICE_CALL));
                ownsCapture = true;
                until = SystemClock.elapsedRealtime() + 6000;
                while (!RecordingService.recording && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50);
                check(RecordingService.recording, "Bridge service start failed: " + RecordingService.message);
                SystemClock.sleep(2300);
                check(RecordingService.callMode && RecordingService.duration >= 1500, "Service did not capture call-source frames");
                StatusBarNotification active = null;
                for (StatusBarNotification item : context.getSystemService(NotificationManager.class).getActiveNotifications()) {
                    if (item.getId() == 11) active = item;
                }
                check(active != null, "Recording notification missing");
                long stoppedAt = SystemClock.elapsedRealtime();
                if ("disconnect".equals(mode)) {
                    Bundle ready = new Bundle(); ready.putString("state", "READY_FOR_HELPER_DISCONNECT"); sendStatus(1, ready);
                    until = SystemClock.elapsedRealtime() + 20000;
                    while (RecordingService.busy && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50);
                    check(!RecordingService.busy && !RecordingService.message.isEmpty(), "Lost helper was not reported");
                } else active.getNotification().actions[0].actionIntent.send();
                awaitStopped();
                long stopMs = SystemClock.elapsedRealtime() - stoppedAt;
                if (!"disconnect".equals(mode)) check(stopMs < 2000, "Stop during silence was slow: " + stopMs);
                result.putString("stop_ms", String.valueOf(stopMs));
                File saved = null;
                for (File file : store.list()) if (!before.contains(file.getName())) saved = file;
                check(saved != null && saved.length() > 32044, "Final WAV missing");
                try (RandomAccessFile wav = new RandomAccessFile(saved, "r")) {
                    check(wav.readInt() == 0x52494646, "Invalid RIFF");
                    wav.seek(40);
                    check((Integer.reverseBytes(wav.readInt()) & 0xffffffffL) == saved.length() - 44, "Invalid WAV length");
                }
                if ("disconnect".equals(mode)) {
                    check(!CallBridgeClient.available(), "Expected stopped bridge");
                    result.putString("result", "PASS: helper termination ends capture, reports error, finalizes existing audio, no microphone fallback");
                } else {
                    check(CallBridgeClient.available(), "Bridge lost after service stop");
                    result.putString("result", "PASS: authenticated bridge, concurrent PING, real-time silent PCM, close/reopen, call-source service, visible notification stop, finalized WAV. NOT proof of two-sided cellular audio.");
                }
            }
            status = Activity.RESULT_OK;
        } catch (Throwable error) {
            result.putString("result", "FAIL: " + error);
            android.util.Log.e("KolBridgeTest", "Device test failed", error);
        } finally {
            if (ownsCapture && RecordingService.busy) {
                context.startService(new Intent(context, RecordingService.class).setAction(RecordingService.STOP));
                SystemClock.sleep(500);
            }
            if (ownsCapture && !RecordingService.busy) {
                for (File file : store.list()) if (!before.contains(file.getName())) store.delete(file);
            }
        }
        finish(status, result);
    }
    private static int countWavs(Set<String> names) {
        int count = 0;
        for (String name : names) if (name.endsWith(".wav")) count++;
        return count;
    }
    private static void awaitStopped() {
        long until = SystemClock.elapsedRealtime() + 6000;
        while (RecordingService.busy && SystemClock.elapsedRealtime() < until) SystemClock.sleep(25);
        check(!RecordingService.busy, "Service did not stop");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
