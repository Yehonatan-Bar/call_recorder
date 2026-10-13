package il.co.kolrecorder.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.SystemClock;
import il.co.kolrecorder.RecordingService;
import java.io.File;
import java.io.RandomAccessFile;
import java.util.HashSet;
import java.util.Set;

/** Runs only on explicit adb instrumentation invocation; test recordings are deleted. */
public final class DeviceTest extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        Context context = getTargetContext();
        File directory = new File(context.getFilesDir(), "recordings");
        Set<String> before = new HashSet<>();
        File[] initial = directory.listFiles();
        if (initial != null) for (File file : initial) before.add(file.getName());
        boolean ownsCapture = false;
        int status = Activity.RESULT_CANCELED;
        try {
            check(!RecordingService.busy, "A recording is already active; refusing to interfere");
            Intent launch = new Intent().setClassName(context, "il.co.kolrecorder.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity activity = startActivitySync(launch);
            waitForIdleSync();
            context.startForegroundService(new Intent(context, RecordingService.class).setAction(RecordingService.START).putExtra("source", MediaRecorder.AudioSource.MIC));
            ownsCapture = true;
            long until = SystemClock.elapsedRealtime() + 10000;
            while (!RecordingService.recording && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50);
            check(RecordingService.recording, "Microphone start failed: " + RecordingService.message);
            SystemClock.sleep(2200);
            check(RecordingService.duration >= 1000, "No PCM frames were captured");
            context.startService(new Intent(context, RecordingService.class).setAction(RecordingService.STOP));
            until = SystemClock.elapsedRealtime() + 5000;
            while (RecordingService.busy && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50);
            check(!RecordingService.busy, "Recording did not stop");
            File found = null;
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) if (!before.contains(file.getName()) && file.getName().endsWith(".wav")) found = file;
            check(found != null && found.length() > 32044, "Finalized WAV missing");
            try (RandomAccessFile wav = new RandomAccessFile(found, "r")) {
                check(wav.readInt() == 0x52494646, "RIFF header invalid");
                wav.seek(40); long size = Integer.reverseBytes(wav.readInt()) & 0xffffffffL;
                check(size == found.length() - 44, "WAV size incorrect");
            }
            MediaMetadataRetriever metadata = new MediaMetadataRetriever();
            try {
                metadata.setDataSource(found.getAbsolutePath());
                long millis = Long.parseLong(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                check(millis >= 1000, "Android cannot read WAV duration");
                result.putString("duration_ms", String.valueOf(millis));
            } finally { metadata.release(); }
            result.putString("result", "PASS: real-device Activity launch, microphone foreground service, PCM capture, stop/finalize, WAV header, Android media parsing. This is NOT a cellular call audio test.");
            status = Activity.RESULT_OK;
        } catch (Throwable error) {
            result.putString("result", "FAIL: " + error);
        } finally {
            if (ownsCapture && RecordingService.busy) {
                context.startService(new Intent(context, RecordingService.class).setAction(RecordingService.STOP));
                SystemClock.sleep(500);
            }
            File[] files = directory.listFiles();
            if (ownsCapture && files != null) for (File file : files) if (!before.contains(file.getName())) file.delete();
        }
        finish(status, result);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
