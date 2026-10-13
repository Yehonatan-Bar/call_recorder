package il.co.kolrecorder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** PCM mono, 16-bit little-endian. Checkpoints make interrupted recordings recoverable. */
public final class WavFile implements AutoCloseable {
    public static final int RATE = 16000;
    private final RandomAccessFile file;
    private long bytes;
    private boolean closed;

    public WavFile(File path) throws IOException {
        file = new RandomAccessFile(path, "rw");
        file.setLength(0);
        checkpoint();
    }

    public void write(short[] samples, int count) throws IOException {
        if (closed || count < 0 || count > samples.length) throw new IOException("Invalid WAV write");
        if (bytes + count * 2L > 0xffffffffL - 36) throw new IOException("WAV size limit");
        byte[] buffer = new byte[count * 2];
        for (int i = 0; i < count; i++) {
            buffer[i * 2] = (byte) samples[i];
            buffer[i * 2 + 1] = (byte) (samples[i] >> 8);
        }
        file.write(buffer);
        bytes += buffer.length;
    }

    public long durationMs() { return bytes * 1000 / (RATE * 2); }

    public void checkpoint() throws IOException {
        header(file, bytes);
        file.seek(44 + bytes);
    }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        try { checkpoint(); } finally { file.close(); }
    }

    public static boolean recover(File path) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(path, "rw")) {
            if (file.length() <= 44) return false;
            byte[] magic = new byte[4];
            file.readFully(magic);
            if (!"RIFF".equals(new String(magic, StandardCharsets.US_ASCII))) return false;
            long bytes = (file.length() - 44) & ~1L;
            file.setLength(44 + bytes);
            header(file, bytes);
            return bytes > 0;
        }
    }

    private static void header(RandomAccessFile file, long bytes) throws IOException {
        file.seek(0);
        file.writeBytes("RIFF"); le32(file, 36 + bytes); file.writeBytes("WAVEfmt ");
        le32(file, 16); le16(file, 1); le16(file, 1); le32(file, RATE);
        le32(file, RATE * 2); le16(file, 2); le16(file, 16);
        file.writeBytes("data"); le32(file, bytes);
    }
    private static void le16(RandomAccessFile f, int v) throws IOException { f.write(v); f.write(v >> 8); }
    private static void le32(RandomAccessFile f, long v) throws IOException {
        for (int i = 0; i < 4; i++) f.write((int) (v >> (i * 8)));
    }
}
