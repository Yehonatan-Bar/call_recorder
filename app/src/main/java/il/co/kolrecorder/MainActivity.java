package il.co.kolrecorder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(16, 25, 34), CARD = Color.rgb(26, 38, 49), TEXT = Color.rgb(243, 247, 249), MUTED = Color.rgb(159, 179, 194), ACCENT = Color.rgb(88, 218, 184), AMBER = Color.rgb(245, 193, 118), RED = Color.rgb(245, 119, 126);
    private static final int AUDIO_PERMISSION = 5, NOTIFICATION_PERMISSION = 6, EXPORT = 7;
    private RecordingStore store;
    private LinearLayout root, list, playerPanel;
    private TextView timer, status, result, countLabel, playerTitle, playbackTime, bridgeStatus;
    private Button recordButton, sourceButton, playerToggle;
    private MeterView meter;
    private SeekBar seek;
    private MediaPlayer player;
    private File playing;
    private String exportName;
    private boolean foreground, seekDragging, pendingStart;
    private boolean bridgeReady, bridgeChecking;
    private long nextBridgeCheck;
    private long seenRevision = -1;
    private int visibleLimit = 40;
    private int source = MediaRecorder.AudioSource.VOICE_CALL;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!foreground) return;
            updateState();
            if (!bridgeChecking && android.os.SystemClock.elapsedRealtime() >= nextBridgeCheck) checkBridge();
            if (player != null && !seekDragging) {
                try { seek.setProgress(player.getCurrentPosition()); playbackTime.setText(RecordingStore.time(player.getCurrentPosition()) + " / " + RecordingStore.time(player.getDuration())); }
                catch (IllegalStateException ignored) { }
            }
            handler.postDelayed(this, 180);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new RecordingStore(this);
        source = getPreferences(MODE_PRIVATE).getInt("source", MediaRecorder.AudioSource.VOICE_CALL);
        if (state != null) { exportName = state.getString("export"); pendingStart = state.getBoolean("pendingStart", false); }
        if (!RecordingService.busy) store.recover();
        createUi();
    }

    private void createUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout content = column();
        content.setPadding(dp(22), dp(22), dp(22), dp(28));
        scroll.addView(content);

        LinearLayout header = row();
        LinearLayout brand = column();
        TextView logo = text("קול", 36, TEXT); logo.setTypeface(null, Typeface.BOLD); brand.addView(logo);
        brand.addView(text("מקליט אישי · נשמר אצלך", 14, MUTED));
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        Button help = button("מדריך", false); help.setContentDescription("מדריך ותאימות להקלטת שיחות");
        help.setOnClickListener(v -> showHelp()); header.addView(help);
        content.addView(header);
        space(content, 22);

        LinearLayout notice = panel(Color.rgb(49, 42, 33));
        TextView label = text("הקלטת שיחות · מצב ניסיוני", 15, AMBER); label.setTypeface(null, Typeface.BOLD); notice.addView(label);
        TextView note = text("מצב שיחה משתמש בערוץ השיחה דרך רכיב עזר שמופעל ב־ADB, ללא root. יש לבדוק ששני הצדדים נשמעים. לאחר הפעלה מחדש של הטלפון יש להפעיל שוב את רכיב העזר מהמחשב.", 14, TEXT);
        note.setPadding(0, dp(7), 0, 0); notice.addView(note);
        bridgeStatus = text("בודק חיבור להקלטת שיחות…", 13, MUTED); bridgeStatus.setPadding(0, dp(9), 0, 0); notice.addView(bridgeStatus); content.addView(notice);
        space(content, 18);

        LinearLayout recorder = panel(CARD);
        TextView local = text("●  הקלטה ידנית · אחסון מקומי", 13, ACCENT); local.setGravity(Gravity.CENTER); recorder.addView(local);
        timer = text("00:00", 52, TEXT); timer.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL)); timer.setGravity(Gravity.CENTER); timer.setLayoutDirection(View.LAYOUT_DIRECTION_LTR); timer.setPadding(0, dp(18), 0, 0); recorder.addView(timer);
        meter = new MeterView(); recorder.addView(meter, new LinearLayout.LayoutParams(-1, dp(54)));
        status = text("מוכן להקלטה", 14, MUTED); status.setGravity(Gravity.CENTER); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); recorder.addView(status);
        space(recorder, 18);
        recordButton = button("●  התחלת הקלטה", true); recordButton.setTextSize(18); recordButton.setMinHeight(dp(62));
        recordButton.setOnClickListener(v -> {
            if (RecordingService.busy) {
                if (!RecordingService.finishing) startService(new Intent(this, RecordingService.class).setAction(RecordingService.STOP));
            } else requestStart();
        });
        recorder.addView(recordButton, new LinearLayout.LayoutParams(-1, -2));
        sourceButton = button("מקור: " + sourceName(), false);
        sourceButton.setTextColor(MUTED);
        sourceButton.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("בחירת מקור שמע")
                .setSingleChoiceItems(new String[]{"ערוץ שיחה · דורש רכיב עזר", "מיקרופון רגיל · לא ערוץ שיחה", "מיקרופון מותאם לזיהוי דיבור"}, source == MediaRecorder.AudioSource.VOICE_CALL ? 0 : source == MediaRecorder.AudioSource.MIC ? 1 : 2, (dialog, which) -> {
                    source = which == 0 ? MediaRecorder.AudioSource.VOICE_CALL : which == 1 ? MediaRecorder.AudioSource.MIC : MediaRecorder.AudioSource.VOICE_RECOGNITION;
                    getPreferences(MODE_PRIVATE).edit().putInt("source", source).apply(); sourceButton.setText("מקור: " + sourceName()); dialog.dismiss();
                }).setNegativeButton("סגירה", null).show());
        recorder.addView(sourceButton, new LinearLayout.LayoutParams(-1, -2));
        content.addView(recorder);
        result = text("", 14, ACCENT); result.setPadding(dp(4), dp(12), dp(4), 0); result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); content.addView(result);

        playerPanel = panel(CARD); playerPanel.setVisibility(View.GONE);
        playerTitle = text("", 16, TEXT); playerTitle.setTypeface(null, Typeface.BOLD); playerPanel.addView(playerTitle);
        seek = new SeekBar(this); seek.setLayoutDirection(View.LAYOUT_DIRECTION_LTR); seek.setContentDescription("מיקום בניגון"); playerPanel.addView(seek, new LinearLayout.LayoutParams(-1, dp(44)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) { seekDragging = true; }
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { }
            public void onStopTrackingTouch(SeekBar bar) { if (player != null) player.seekTo(bar.getProgress()); seekDragging = false; }
        });
        LinearLayout controls = row();
        playbackTime = text("00:00", 14, MUTED); playbackTime.setLayoutDirection(View.LAYOUT_DIRECTION_LTR); controls.addView(playbackTime, new LinearLayout.LayoutParams(0, -2, 1));
        playerToggle = button("השהיה", false); controls.addView(playerToggle);
        playerToggle.setOnClickListener(v -> { if (player != null) { if (player.isPlaying()) { player.pause(); playerToggle.setText("ניגון"); } else { player.start(); playerToggle.setText("השהיה"); } } });
        Button closePlayer = button("סגירה", false); closePlayer.setOnClickListener(v -> releasePlayer()); controls.addView(closePlayer); playerPanel.addView(controls); content.addView(playerPanel);
        space(content, 22);

        LinearLayout libraryHeader = row();
        TextView library = text("ההקלטות שלי", 22, TEXT); library.setTypeface(null, Typeface.BOLD); libraryHeader.addView(library, new LinearLayout.LayoutParams(0, -2, 1));
        countLabel = text("0", 14, ACCENT); libraryHeader.addView(countLabel); content.addView(libraryHeader);
        space(content, 12);
        list = column(); content.addView(list);
        space(content, 16);
        TextView privacy = text("ללא חיבור לאינטרנט · ללא חשבון\nהסרת האפליקציה מוחקת את ההקלטות. אפשר לייצא קובץ לשמירה.", 12, MUTED); privacy.setGravity(Gravity.CENTER); content.addView(privacy);
    }

    private String sourceName() { return source == MediaRecorder.AudioSource.VOICE_CALL ? "ערוץ שיחה" : source == MediaRecorder.AudioSource.MIC ? "מיקרופון רגיל" : "זיהוי דיבור"; }

    private void checkBridge() {
        bridgeChecking = true; nextBridgeCheck = android.os.SystemClock.elapsedRealtime() + 8000;
        new Thread(() -> {
            boolean ready = CallBridgeClient.available();
            handler.post(() -> {
                bridgeChecking = false; bridgeReady = ready;
                if (!isDestroyed()) { bridgeStatus.setText(ready ? "●  רכיב העזר מחובר" : "○  רכיב העזר לא מחובר · נדרשת הפעלה מהמחשב"); bridgeStatus.setTextColor(ready ? ACCENT : AMBER); }
            });
        }, "Kol-BridgeCheck").start();
    }

    private void requestStart() {
        if (source == MediaRecorder.AudioSource.VOICE_CALL) {
            if (!bridgeReady) {
                if (!bridgeChecking) checkBridge();
                new AlertDialog.Builder(this).setTitle("רכיב הקלטת השיחות לא מחובר")
                        .setMessage("יש להפעיל במחשב את Start-CallBridge.ps1 כשהטלפון מחובר ל־ADB. הרכיב נדרש כדי לגשת לערוץ השיחה, ויש להפעילו שוב לאחר אתחול הטלפון. לאחר שהחיווי הופך לירוק ניתן להתחיל הקלטה.")
                        .setPositiveButton("הבנתי", null).show(); return;
            }
            int mode = getSystemService(AudioManager.class).getMode();
            if (mode != AudioManager.MODE_IN_CALL) {
                new AlertDialog.Builder(this).setTitle("יש להתחיל שיחת טלפון")
                        .setMessage("מצב זה מיועד לשיחה סלולרית פעילה. התחילו או ענו לשיחה, חזרו לכאן ולחצו על התחלת הקלטה. להקלטת קול רגילה אפשר לבחור מקור מיקרופון.")
                        .setPositiveButton("הבנתי", null).show(); return;
            }
        }
        if (!getPreferences(MODE_PRIVATE).getBoolean("explained", false)) {
            new AlertDialog.Builder(this).setTitle("לפני ההקלטה הראשונה")
                    .setMessage("ההקלטה מתחילה רק בלחיצה ומוצגת בהתראה. היא נשמרת במכשיר, עד לעצירה ידנית.\n\nמצב ערוץ שיחה משתמש ברכיב העזר. מצב מיקרופון רגיל עשוי להיחסם במהלך שיחה. בשני המצבים יש להאזין לשיחת ניסיון ולבדוק ששני הצדדים נשמעים לפני שמסתמכים על ההקלטה.")
                    .setPositiveButton("המשך להרשאה", (dialog, which) -> { getPreferences(MODE_PRIVATE).edit().putBoolean("explained", true).apply(); requestStart(); })
                    .setNegativeButton("ביטול", null).show(); return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (getPreferences(MODE_PRIVATE).getBoolean("askedAudio", false) && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                new AlertDialog.Builder(this).setTitle("הרשאת המיקרופון כבויה").setMessage("יש לאפשר מיקרופון בהגדרות האפליקציה, ואז לחזור וללחוץ על התחלת הקלטה.")
                        .setPositiveButton("הגדרות", (d, w) -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))))
                        .setNegativeButton("סגירה", null).show(); return;
            }
            getPreferences(MODE_PRIVATE).edit().putBoolean("askedAudio", true).apply();
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION); return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("askedNotification", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("askedNotification", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION); return;
        }
        pendingStart = true;
        if (foreground) startRecording();
    }

    private void startRecording() {
        if (RecordingService.busy || !foreground) return;
        pendingStart = false;
        releasePlayer();
        try {
            startForegroundService(new Intent(this, RecordingService.class).setAction(RecordingService.START).putExtra("source", source));
            recordButton.setEnabled(false);
            handler.postDelayed(() -> { if (!isDestroyed()) updateState(); }, 400);
        } catch (RuntimeException e) { toast("לא ניתן להתחיל. יש לבדוק את הרשאת המיקרופון."); }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == AUDIO_PERMISSION) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) requestStart();
            else toast("ההקלטה לא התחילה. נדרשת הרשאת מיקרופון.");
        } else if (request == NOTIFICATION_PERMISSION) {
            if (grants.length > 0 && grants[0] != PackageManager.PERMISSION_GRANTED) toast("ההתראות כבויות. ניתן לעצור את ההקלטה מתוך האפליקציה.");
            pendingStart = true;
            if (foreground) startRecording();
        }
    }

    private void updateState() {
        boolean busy = RecordingService.busy;
        timer.setText(RecordingStore.time(busy ? RecordingService.duration : 0));
        String label;
        int color = MUTED;
        if (RecordingService.finishing) label = "שומר את ההקלטה…";
        else if (busy && !RecordingService.recording) label = "פותח מקור שמע…";
        else if (busy && RecordingService.silenced) { label = "Android חוסם את קלט השמע"; color = AMBER; }
        else if (busy && RecordingService.quiet) { label = "לא מזוהה קול · ייתכן שהשמע חסום"; color = AMBER; }
        else if (busy) { label = RecordingService.callMode ? "מקליט מערוץ השיחה" : "מקליט מהמיקרופון"; color = ACCENT; }
        else label = "מוכן להקלטה";
        if (!label.contentEquals(status.getText())) status.setText(label);
        status.setTextColor(color);
        String action = RecordingService.finishing ? "שומר…" : busy ? "■  עצירה ושמירה" : "●  התחלת הקלטה";
        if (!action.contentEquals(recordButton.getText())) { recordButton.setText(action); recordButton.setBackground(shape(busy ? RED : ACCENT, 18)); }
        recordButton.setEnabled(!RecordingService.finishing);
        sourceButton.setEnabled(!busy); sourceButton.setAlpha(busy ? .45f : 1f);
        meter.setAmplitude(busy ? RecordingService.amplitude : 0);
        String message = busy ? "" : RecordingService.message;
        if (!message.contentEquals(result.getText())) result.setText(message);
        if (seenRevision != RecordingService.revision) { seenRevision = RecordingService.revision; renderList(); }
    }

    private void renderList() {
        list.removeAllViews();
        List<File> recordings = store.list();
        countLabel.setText(recordings.size() + " קבצים");
        if (recordings.isEmpty()) {
            LinearLayout empty = panel(CARD);
            TextView heading = text("השיחה הבאה, במקום אחד", 17, TEXT); heading.setGravity(Gravity.CENTER); empty.addView(heading);
            TextView body = text("כאן יופיעו ההקלטות לאחר עצירה ושמירה.\nאפשר להאזין, לשנות שם ולייצא.", 14, MUTED); body.setGravity(Gravity.CENTER); body.setPadding(0, dp(9), 0, dp(4)); empty.addView(body); list.addView(empty); return;
        }
        for (int i = 0; i < Math.min(visibleLimit, recordings.size()); i++) {
            File file = recordings.get(i);
            LinearLayout card = panel(CARD);
            TextView title = text(store.title(file), 17, TEXT); title.setTypeface(null, Typeface.BOLD); card.addView(title);
            String size = String.format(Locale.US, "%.1f MB", file.length() / 1048576.0);
            TextView meta = text(RecordingStore.time(store.durationMs(file)) + "  ·  " + size + "  ·  WAV", 12, MUTED); meta.setTextDirection(View.TEXT_DIRECTION_LTR); meta.setPadding(0, dp(6), 0, 0); card.addView(meta);
            if (store.isQuiet(file)) card.addView(text("ייתכן שחסר שמע · מומלץ להאזין לבדיקה", 12, AMBER));
            if (store.isInterrupted(file)) card.addView(text("שוחזר לאחר עצירה לא צפויה", 12, AMBER));
            LinearLayout actions = row();
            Button play = button("▶  האזנה", false); play.setTextColor(ACCENT); play.setContentDescription("האזנה ל־" + store.title(file)); play.setOnClickListener(v -> play(file));
            actions.addView(play, new LinearLayout.LayoutParams(0, -2, 1));
            Button options = button("אפשרויות", false); options.setContentDescription("אפשרויות עבור " + store.title(file)); options.setOnClickListener(v -> fileOptions(file)); actions.addView(options);
            card.addView(actions); list.addView(card); space(list, 10);
        }
        if (recordings.size() > visibleLimit) {
            Button more = button("הצגת הקלטות נוספות", false); more.setOnClickListener(v -> { visibleLimit += 40; renderList(); }); list.addView(more);
        }
    }

    private void play(File file) {
        if (RecordingService.busy) { toast("יש לעצור את ההקלטה לפני האזנה"); return; }
        releasePlayer();
        try {
            player = new MediaPlayer(); playing = file;
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            player.setDataSource(file.getAbsolutePath());
            player.setOnPreparedListener(p -> {
                if (player != p || !foreground) return;
                playerTitle.setText(store.title(file)); seek.setMax(p.getDuration()); playerPanel.setVisibility(View.VISIBLE); playerToggle.setText("השהיה"); p.start();
            });
            player.setOnCompletionListener(p -> { playerToggle.setText("ניגון מחדש"); seek.setProgress(p.getDuration()); });
            player.setOnErrorListener((p, what, extra) -> { releasePlayer(); toast("לא ניתן לנגן את הקובץ"); return true; });
            player.prepareAsync();
        } catch (Exception e) { releasePlayer(); toast("לא ניתן לפתוח את ההקלטה"); }
    }

    private void releasePlayer() {
        if (player != null) { player.release(); player = null; }
        playing = null; seekDragging = false;
        if (playerPanel != null) playerPanel.setVisibility(View.GONE);
    }

    private void fileOptions(File file) {
        new AlertDialog.Builder(this).setTitle(store.title(file)).setItems(new String[]{"שמירה בתיקייה…", "שיתוף קובץ…", "שינוי שם", "מחיקה"}, (d, which) -> {
            if (which == 0) export(file);
            else if (which == 1) share(file);
            else if (which == 2) rename(file);
            else new AlertDialog.Builder(this).setTitle("למחוק את ההקלטה?").setMessage(store.title(file) + "\nלא ניתן לבטל את המחיקה.")
                        .setNegativeButton("ביטול", null).setPositiveButton("מחיקה", (dialog, button) -> {
                            if (file.equals(playing)) releasePlayer();
                            if (!store.delete(file)) toast("המחיקה לא הושלמה"); renderList();
                        }).show();
        }).show();
    }

    private void rename(File file) {
        EditText input = new EditText(this); input.setSingleLine(true); input.setText(store.title(file)); input.setSelectAllOnFocus(true); input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)}); input.setPadding(dp(24), dp(16), dp(24), dp(16));
        new AlertDialog.Builder(this).setTitle("שם ההקלטה").setView(input).setNegativeButton("ביטול", null).setPositiveButton("שמירה", (d, w) -> {
            String title = input.getText().toString().trim();
            if (!title.isEmpty()) { store.rename(file, title); if (file.equals(playing)) playerTitle.setText(title); renderList(); }
        }).show();
    }

    private void share(File file) {
        Uri uri = RecordingProvider.uri(file);
        Intent send = new Intent(Intent.ACTION_SEND).setType("audio/wav").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri("recording", uri));
        try { startActivity(Intent.createChooser(send, "שיתוף הקלטה")); }
        catch (RuntimeException e) { toast("לא נמצאה אפליקציה לשיתוף"); }
    }

    private void export(File file) {
        exportName = file.getName();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("audio/wav").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, store.title(file).replaceAll("[\\\\/:*?\"<>|]", "_") + ".wav");
        try { startActivityForResult(intent, EXPORT); }
        catch (RuntimeException e) { exportName = null; toast("לא ניתן לפתוח את בוחר הקבצים"); }
    }

    @Override protected void onActivityResult(int request, int resultCode, Intent data) {
        super.onActivityResult(request, resultCode, data);
        if (request != EXPORT) return;
        String name = exportName; exportName = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || name == null) return;
        Uri target = data.getData();
        new Thread(() -> {
            String message;
            try (InputStream in = new FileInputStream(store.resolve(name)); OutputStream out = getContentResolver().openOutputStream(target, "wt")) {
                if (out == null) throw new java.io.IOException("No output stream");
                byte[] buffer = new byte[32768]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                out.flush(); message = "הקובץ יוצא בהצלחה";
            } catch (Exception e) { message = "הייצוא נכשל. ההקלטה המקורית עדיין שמורה באפליקציה."; }
            final String notice = message; handler.post(() -> Toast.makeText(getApplicationContext(), notice, Toast.LENGTH_LONG).show());
        }, "Kol-Export").start();
    }

    private void showHelp() {
        new AlertDialog.Builder(this).setTitle("מדריך קצר")
                .setMessage("1. מפעילים את רכיב העזר מהמחשב ובודקים שהחיווי ירוק.\n2. מתחילים שיחה סלולרית וחוזרים לכאן.\n3. בוחרים מקור ״ערוץ שיחה״ ולוחצים התחלת הקלטה.\n4. עוצרים באפליקציה או בהתראה ומאזינים לשני הצדדים.\n\nרכיב העזר פועל ללא root ובלי המחשב לאחר הפעלתו, עד שהוא נעצר או שהטלפון מאותחל. הוא אינו מקליט כשהאפליקציה לא ביקשה הקלטה.\n\nמצבי המיקרופון אינם גישה ישירה לשיחה ועשויים להיחסם. האפליקציה אינה מחייגת או מקליטה אוטומטית. מד העוצמה אינו הוכחה ששני הצדדים הוקלטו.\n\n״Android חוסם״ מציין השתקה מדווחת; ״לא מזוהה קול״ הוא הערכה לפי עוצמה.\n\nWAV באחסון פרטי, כ־1.9 MB לדקה. אין אינטרנט או גיבוי בענן. יש לייצא קבצים חשובים לפני הסרת האפליקציה.\n\nמכשיר: " + Build.MODEL + " · Android " + Build.VERSION.RELEASE)
                .setPositiveButton("הבנתי", null).show();
    }

    @Override protected void onResume() { super.onResume(); foreground = true; renderList(); handler.removeCallbacks(tick); tick.run(); if (pendingStart) startRecording(); }
    @Override protected void onPause() { foreground = false; handler.removeCallbacks(tick); super.onPause(); }
    @Override protected void onStop() { releasePlayer(); super.onStop(); }
    @Override protected void onDestroy() { handler.removeCallbacks(tick); releasePlayer(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.putString("export", exportName); state.putBoolean("pendingStart", pendingStart); }

    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private LinearLayout panel(int color) { LinearLayout view = column(); view.setPadding(dp(18), dp(18), dp(18), dp(14)); view.setBackground(shape(color, 22)); return view; }
    private TextView text(String value, int size, int color) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setLineSpacing(dp(3), 1); view.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL); return view; }
    private Button button(String value, boolean primary) {
        Button button = new Button(this); button.setText(value); button.setTextSize(14); button.setAllCaps(false); button.setTextColor(primary ? BG : TEXT);
        button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48)); button.setPadding(dp(15), dp(8), dp(15), dp(8));
        button.setBackground(shape(primary ? ACCENT : Color.TRANSPARENT, 16)); return button;
    }
    private GradientDrawable shape(int color, int radius) { GradientDrawable bg = new GradientDrawable(); bg.setColor(color); bg.setCornerRadius(dp(radius)); return bg; }
    private void space(LinearLayout parent, int height) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    private final class MeterView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float level;
        MeterView() { super(MainActivity.this); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); }
        void setAmplitude(int peak) { level = peak <= 0 ? 0 : Math.max(0, (float) ((20 * Math.log10(peak / 32768.0) + 60) / 60)); invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int bars = 30; float gap = dp(4), total = Math.min(getWidth(), dp(270)); float width = (total - gap * (bars - 1)) / bars; float start = (getWidth() - total) / 2;
            for (int i = 0; i < bars; i++) {
                paint.setColor(i < level * bars ? ACCENT : Color.rgb(51, 68, 80));
                float height = dp(7) + dp(19) * (float) Math.abs(Math.sin(i * 1.7));
                float left = start + i * (width + gap); canvas.drawRoundRect(left, (getHeight() - height) / 2, left + width, (getHeight() + height) / 2, dp(2), dp(2), paint);
            }
        }
    }
}
