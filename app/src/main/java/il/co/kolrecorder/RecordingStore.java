package il.co.kolrecorder;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class RecordingStore {
    private final File directory;
    private final SharedPreferences metadata;
    RecordingStore(Context context) {
        directory = new File(context.getFilesDir(), "recordings");
        directory.mkdirs();
        metadata = context.getSharedPreferences("recordings", Context.MODE_PRIVATE);
    }
    File directory() { return directory; }
    File newPartial() throws IOException { return File.createTempFile("kol_" + System.currentTimeMillis() + "_", ".part", directory); }
    File finish(File part) throws IOException {
        File target = new File(directory, part.getName().replace(".part", ".wav"));
        if (!part.renameTo(target)) throw new IOException("Could not finalize recording");
        return target;
    }
    synchronized void recover() {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".part"));
        if (files == null) return;
        for (File file : files) {
            try {
                if (WavFile.recover(file)) {
                    File done = finish(file);
                    metadata.edit().putBoolean(done.getName() + ".interrupted", true).apply();
                } else file.delete();
            } catch (IOException ignored) { /* Leave partial intact for a later attempt. */ }
        }
    }
    List<File> list() {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".wav"));
        List<File> result = new ArrayList<>();
        if (files != null) for (File f : files) result.add(f);
        result.sort(Comparator.comparingLong(File::lastModified).reversed());
        return result;
    }
    File resolve(String name) throws IOException {
        if (name == null || !name.matches("kol_[0-9]+_[0-9]+\\.wav")) throw new IOException("Invalid recording");
        File file = new File(directory, name).getCanonicalFile();
        if (!directory.getCanonicalFile().equals(file.getParentFile()) || !file.isFile()) throw new IOException("Recording not found");
        return file;
    }
    String title(File f) {
        return metadata.getString(f.getName() + ".title", "הקלטה · " + new SimpleDateFormat("dd.MM.yy HH:mm", Locale.US).format(new Date(f.lastModified())));
    }
    void rename(File f, String title) { metadata.edit().putString(f.getName() + ".title", title).apply(); }
    boolean delete(File f) {
        if (!f.delete()) return false;
        metadata.edit().remove(f.getName() + ".title").remove(f.getName() + ".quiet").remove(f.getName() + ".interrupted").apply();
        return true;
    }
    void setQuiet(File f, boolean quiet) { metadata.edit().putBoolean(f.getName() + ".quiet", quiet).apply(); }
    boolean isQuiet(File f) { return metadata.getBoolean(f.getName() + ".quiet", false); }
    boolean isInterrupted(File f) { return metadata.getBoolean(f.getName() + ".interrupted", false); }
    long durationMs(File f) { return Math.max(0, f.length() - 44) * 1000 / (WavFile.RATE * 2); }
    static String time(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        if (seconds >= 3600) return String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
        return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60);
    }
}
