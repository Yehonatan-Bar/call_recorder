import il.co.kolrecorder.WavFile;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

public class WavFileTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        File directory = new File(args[0]); directory.mkdirs();
        File file = new File(directory, "roundtrip.wav");
        short[] samples = new short[]{0, 32767, -32768, -1, 1};
        try (WavFile wav = new WavFile(file)) { wav.write(samples, samples.length); wav.checkpoint(); wav.write(samples, samples.length); }
        byte[] raw = Files.readAllBytes(file.toPath());
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        check(new String(raw, 0, 4, "US-ASCII").equals("RIFF"), "RIFF header");
        check(b.getInt(4) == raw.length - 8, "RIFF length");
        check(b.getInt(24) == 16000 && b.getShort(22) == 1 && b.getShort(34) == 16, "PCM format");
        check(b.getInt(40) == 20, "Data length");
        for (int i = 0; i < 10; i++) check(b.getShort(44 + i * 2) == samples[i % 5], "Sample preservation " + i);
        File partial = new File(directory, "interrupted.part"); Files.copy(file.toPath(), partial.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (RandomAccessFile f = new RandomAccessFile(partial, "rw")) { f.seek(40); f.writeInt(0); f.seek(f.length()); f.write(3); }
        check(WavFile.recover(partial), "Recovery succeeds");
        raw = Files.readAllBytes(partial.toPath()); b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        check(raw.length == 64 && b.getInt(40) == 20, "Recovery repairs header and odd trailing byte");
        File empty = new File(directory, "empty.part"); try (WavFile wav = new WavFile(empty)) { }
        check(!WavFile.recover(empty), "Empty files rejected");
        File duration = new File(directory, "one-second.wav");
        try (WavFile wav = new WavFile(duration)) { wav.write(new short[16000], 16000); check(wav.durationMs() == 1000, "Duration exact"); }
        System.out.println("PASS: PCM samples, headers, checkpoint append, interrupted recovery, empty handling, duration");
    }
}
