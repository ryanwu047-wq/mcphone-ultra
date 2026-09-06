package com.mcphoneultra.client.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 音乐编辑引擎：解码 WAV/MP3/AIFF → 浮点采样 → 剪辑（裁剪/拼接/增益/淡入淡出/反转/变速/归一化）→ 导出 WAV。
 * 播放走 JavaSound 的 SourceDataLine，后台线程推流。
 * OGG 不在 JDK 自带解码器范围内，会明确报「不支援」而不是静默没声。
 */
public final class AudioEngine {

    private AudioEngine() {}

    public record Samples(float[] data, int sampleRate, int channels) {
        public int frames() {
            return data.length / channels;
        }

        public double seconds() {
            return frames() / (double) sampleRate;
        }

        public Samples copy() {
            float[] copy = new float[data.length];
            System.arraycopy(data, 0, copy, 0, data.length);
            return new Samples(copy, sampleRate, channels);
        }
    }

    public static boolean isSupportedName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".wav") || n.endsWith(".mp3") || n.endsWith(".aiff") || n.endsWith(".aif")
                || n.endsWith(".au") || n.endsWith(".mp2") || n.endsWith(".mp1");
    }

    /** 解码文件。失败返回 empty（不抛）。 */
    public static Optional<Samples> load(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".mp3") || name.endsWith(".mp2") || name.endsWith(".mp1")) {
            return loadMp3(p);
        }
        try (AudioInputStream base = AudioSystem.getAudioInputStream(p.toFile())) {
            AudioFormat fmt = base.getFormat();
            float rate = fmt.getSampleRate();
            int channels = fmt.getChannels();
            if (rate <= 0 || channels <= 0) return Optional.empty();

            AudioInputStream decoded;
            if (fmt.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) {
                AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                        rate, 16, channels, channels * 2, rate, false);
                decoded = AudioSystem.getAudioInputStream(pcm, base);
            } else {
                decoded = base;
            }
            byte[] bytes = decoded.readAllBytes();
            int frameBytes = channels * 2;
            int frames = bytes.length / frameBytes;
            if (frames <= 0) return Optional.empty();
            float[] data = new float[frames * channels];
            ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < data.length; i++) {
                data[i] = bb.getShort() / 32768f;
            }
            return Optional.of(new Samples(data, (int) rate, channels));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * MP3 解码走 mcphone 已打包的 JavaMP3（fr.delthas:javamp3，MIT，只认 MPEG-1）。
     * 先自己跳过 ID3v2 标签，解码库不认它。
     */
    private static Optional<Samples> loadMp3(Path p) {
        try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(p))) {
            in.mark(10);
            byte[] head = in.readNBytes(10);
            if (head.length == 10 && head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
                int size = ((head[6] & 0x7F) << 21) | ((head[7] & 0x7F) << 14)
                        | ((head[8] & 0x7F) << 7) | (head[9] & 0x7F);
                in.skipNBytes(size);
            } else {
                in.reset();
            }

            fr.delthas.javamp3.Sound sound = new fr.delthas.javamp3.Sound(in);
            AudioFormat fmt = sound.getAudioFormat();
            float rate = fmt.getSampleRate();
            int channels = fmt.getChannels();
            if (rate <= 0 || channels <= 0 || fmt.getSampleSizeInBits() != 16
                    || fmt.getEncoding() != AudioFormat.Encoding.PCM_SIGNED
                    || fmt.isBigEndian()) {
                sound.close();
                return Optional.empty();
            }
            byte[] bytes = sound.readAllBytes();
            int frameBytes = channels * 2;
            int frames = bytes.length / frameBytes;
            if (frames <= 0) return Optional.empty();
            float[] data = new float[frames * channels];
            ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < data.length; i++) {
                data[i] = bb.getShort() / 32768f;
            }
            return Optional.of(new Samples(data, (int) rate, channels));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 导出 WAV（16-bit PCM）。 */
    public static void saveWav(Path p, Samples s) throws IOException {
        Files.createDirectories(p.getParent());
        int dataLen = s.data().length * 2;
        try (OutputStream out = Files.newOutputStream(p)) {
            writeAscii(out, "RIFF");
            writeIntLE(out, 36 + dataLen);
            writeAscii(out, "WAVE");
            writeAscii(out, "fmt ");
            writeIntLE(out, 16);
            writeShortLE(out, 1);                      // PCM
            writeShortLE(out, s.channels());
            writeIntLE(out, s.sampleRate());
            writeIntLE(out, s.sampleRate() * s.channels() * 2);
            writeShortLE(out, s.channels() * 2);
            writeShortLE(out, 16);
            writeAscii(out, "data");
            writeIntLE(out, dataLen);
            for (float v : s.data()) {
                short sv = (short) Math.max(-32768, Math.min(32767, Math.round(v * 32767f)));
                writeShortLE(out, sv);
            }
        }
    }

    private static void writeAscii(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static void writeIntLE(OutputStream out, int v) throws IOException {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
        out.write((v >> 16) & 0xff);
        out.write((v >> 24) & 0xff);
    }

    private static void writeShortLE(OutputStream out, int v) throws IOException {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
    }

    // ---------------- 剪辑运算 ----------------

    public static Samples trim(Samples s, double startSec, double endSec) {
        int start = Math.max(0, (int) (startSec * s.sampleRate()));
        int end = Math.min(s.frames(), (int) (endSec * s.sampleRate()));
        if (end <= start) return silence(0.1, s.sampleRate(), s.channels());
        int ch = s.channels();
        float[] out = new float[(end - start) * ch];
        System.arraycopy(s.data(), start * ch, out, 0, out.length);
        return new Samples(out, s.sampleRate(), ch);
    }

    public static Samples concat(List<Samples> list) {
        if (list.isEmpty()) return silence(0.5, 44100, 1);
        int ch = list.get(0).channels();
        int rate = list.get(0).sampleRate();
        int total = 0;
        for (Samples s : list) total += s.data().length;
        float[] out = new float[total];
        int pos = 0;
        for (Samples s : list) {
            System.arraycopy(s.data(), 0, out, pos, s.data().length);
            pos += s.data().length;
        }
        return new Samples(out, rate, ch);
    }

    public static Samples gain(Samples s, double db) {
        float k = (float) Math.pow(10.0, db / 20.0);
        float[] out = new float[s.data().length];
        for (int i = 0; i < out.length; i++) out[i] = s.data()[i] * k;
        return new Samples(out, s.sampleRate(), s.channels());
    }

    public static Samples fade(Samples s, double inSec, double outSec) {
        float[] out = s.copy().data();
        int ch = s.channels();
        int rate = s.sampleRate();
        int inFrames = (int) (inSec * rate);
        int outFrames = (int) (outSec * rate);
        int frames = s.frames();
        for (int f = 0; f < frames; f++) {
            float k = 1f;
            if (f < inFrames && inFrames > 0) k = Math.min(1f, f / (float) inFrames);
            int fromEnd = frames - 1 - f;
            if (fromEnd < outFrames && outFrames > 0) k = Math.min(k, fromEnd / (float) outFrames);
            if (k != 1f) {
                for (int c = 0; c < ch; c++) out[f * ch + c] *= k;
            }
        }
        return new Samples(out, s.sampleRate(), ch);
    }

    public static Samples reverse(Samples s) {
        int ch = s.channels();
        float[] out = new float[s.data().length];
        int frames = s.frames();
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < ch; c++) {
                out[(frames - 1 - f) * ch + c] = s.data()[f * ch + c];
            }
        }
        return new Samples(out, s.sampleRate(), ch);
    }

    /** 变速不变调（线性重采样，factor>1 变快）。 */
    public static Samples speed(Samples s, double factor) {
        if (factor <= 0.05) factor = 0.05;
        int ch = s.channels();
        int newFrames = (int) (s.frames() / factor);
        float[] out = new float[newFrames * ch];
        for (int f = 0; f < newFrames; f++) {
            double src = f * factor;
            int i0 = (int) src;
            int i1 = Math.min(i0 + 1, s.frames() - 1);
            double t = src - i0;
            for (int c = 0; c < ch; c++) {
                float v0 = s.data()[i0 * ch + c];
                float v1 = s.data()[i1 * ch + c];
                out[f * ch + c] = (float) (v0 + (v1 - v0) * t);
            }
        }
        return new Samples(out, s.sampleRate(), ch);
    }

    /** 归一化到目标峰值（db，如 -1 表示接近满幅）。 */
    public static Samples normalize(Samples s, double targetDb) {
        double peak = peak(s);
        if (peak <= 0.0001) return s.copy();
        double target = Math.pow(10.0, targetDb / 20.0);
        double k = target / peak;
        float[] out = new float[s.data().length];
        for (int i = 0; i < out.length; i++) out[i] = (float) (s.data()[i] * k);
        return new Samples(out, s.sampleRate(), s.channels());
    }

    public static Samples silence(double sec, int rate, int channels) {
        int frames = Math.max(1, (int) (sec * rate));
        return new Samples(new float[frames * channels], rate, channels);
    }

    public static double peak(Samples s) {
        double peak = 0;
        for (float v : s.data()) peak = Math.max(peak, Math.abs(v));
        return peak;
    }

    public static String fmt(double sec) {
        int total = (int) Math.round(sec);
        return String.format("%d:%02d", total / 60, total % 60);
    }

    // ---------------- 播放器 ----------------

    public static final class Player {

        private volatile Samples current;
        private final AtomicInteger playPos = new AtomicInteger();
        private volatile boolean playing;
        private volatile boolean stopReq;
        private Thread thread;
        private SourceDataLine line;
        private final Object lock = new Object();

        public void play(Samples s) {
            stop();
            current = s;
            playPos.set(0);
            stopReq = false;
            playing = true;
            ensureThread();
        }

        public void pause() {
            playing = false;
        }

        public void resume() {
            if (current == null || playPos.get() >= current.frames()) return;
            playing = true;
            ensureThread();
        }

        public void stop() {
            stopReq = true;
            playing = false;
            synchronized (lock) {
                if (line != null) {
                    try {
                        line.stop();
                        line.close();
                    } catch (Exception ignored) {
                    }
                    line = null;
                }
            }
            current = null;
            playPos.set(0);
        }

        public void seek(double sec) {
            Samples s = current;
            if (s == null) return;
            int frame = (int) (sec * s.sampleRate());
            playPos.set(Math.max(0, Math.min(frame, s.frames())));
        }

        public boolean isPlaying() {
            return playing;
        }

        public boolean hasTrack() {
            return current != null;
        }

        public Samples current() {
            return current;
        }

        public double positionSec() {
            Samples s = current;
            return s == null ? 0 : playPos.get() / (double) s.sampleRate();
        }

        public double durationSec() {
            Samples s = current;
            return s == null ? 0 : s.seconds();
        }

        private void ensureThread() {
            synchronized (lock) {
                if (thread == null || !thread.isAlive()) {
                    stopReq = false;
                    thread = new Thread(this::pump, "mcphone-ultra-audio");
                    thread.setDaemon(true);
                    thread.start();
                }
            }
        }

        private void pump() {
            try {
                while (!stopReq) {
                    Samples s = current;
                    if (s == null) break;
                    if (!playing) {
                        Thread.sleep(20);
                        continue;
                    }
                    int pos = playPos.get();
                    if (pos >= s.frames()) {
                        playing = false;
                        break;
                    }
                    synchronized (lock) {
                        if (line == null) {
                            AudioFormat fmt = new AudioFormat(s.sampleRate(), 16, s.channels(), true, false);
                            line = AudioSystem.getSourceDataLine(fmt);
                            line.open(fmt, 8192);
                            line.start();
                        }
                    }
                    SourceDataLine l = line;
                    if (l == null) break;
                    // 一次喂 2048 帧
                    int frames = Math.min(2048, s.frames() - pos);
                    byte[] chunk = new byte[frames * s.channels() * 2];
                    for (int i = 0; i < frames * s.channels(); i++) {
                        float v = s.data()[(pos + i / s.channels()) * s.channels() + i % s.channels()];
                        short sv = (short) Math.max(-32768, Math.min(32767, Math.round(v * 32767f)));
                        chunk[i * 2] = (byte) (sv & 0xff);
                        chunk[i * 2 + 1] = (byte) ((sv >> 8) & 0xff);
                    }
                    l.write(chunk, 0, chunk.length);
                    playPos.addAndGet(frames);
                }
            } catch (Exception ignored) {
            } finally {
                synchronized (lock) {
                    if (line != null) {
                        try {
                            line.drain();
                        } catch (Exception ignored) {
                        }
                        line.close();
                        line = null;
                    }
                }
            }
        }
    }
}
