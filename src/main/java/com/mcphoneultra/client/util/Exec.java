package com.mcphoneultra.client.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 外部进程助手：Termux 终端、Python 运行器、ffmpeg 抽帧都靠它。 */
public final class Exec {

    public static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    private static volatile String pythonPath = "";
    private static volatile String ffmpegPath = "";
    private static volatile boolean probed = false;

    private Exec() {}

    public static synchronized void probe() {
        if (probed) return;
        probed = true;
        pythonPath = findOnPath("python").or(() -> findOnPath("python3")).orElse("");
        ffmpegPath = findOnPath("ffmpeg").orElse("");
    }

    public static String pythonPath() {
        probe();
        return pythonPath;
    }

    /**
     * 给外部程序（python / ffmpeg / cmd）用的绝对路径：统一正斜线。
     * Windows 的 Path.toString() 是反斜线，直接拼进命令行参数时，
     * 遇到 %05d 这类 glob、或脚本里再拼一次路径，很容易出现 // 或 \ 混用
     * 导致的「找不到文件」。转成 C:/.../ 之后 python 与 ffmpeg 都吃得下。
     */
    public static String norm(Path p) {
        return p.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    public static String ffmpegPath() {
        probe();
        return ffmpegPath;
    }

    /** 在 PATH 里找可执行文件，返回第一个命中（Windows 会补全 .exe）。 */
    public static Optional<String> findOnPath(String name) {
        List<String> candidates = new ArrayList<>();
        if (WINDOWS) {
            candidates.add(name + ".exe");
            candidates.add(name + ".bat");
            candidates.add(name + ".cmd");
        } else {
            candidates.add(name);
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(java.util.regex.Pattern.quote(System.getProperty("path.separator")))) {
                if (dir.isEmpty()) continue;
                for (String cand : candidates) {
                    Path p = Paths.get(dir, cand);
                    if (Files.isRegularFile(p) && Files.isExecutable(p)) return Optional.of(p.toString());
                }
            }
        }
        return Optional.empty();
    }

    /** 跑一条命令并等到结束，把 stdout 拼出来。超时由调用方负责（用 runAsync 时）。 */
    public static Process start(String... cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        return pb.start();
    }

    /** 跑完一条命令，拿回退出码 + 合并输出。会等它结束，别用于长驻进程。 */
    public static Result run(String... cmd) {
        try {
            Process p = start(cmd);
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Thread t1 = pump(p.getInputStream(), out);
            Thread t2 = pump(p.getErrorStream(), err);
            int code = p.waitFor();
            t1.join(2000);
            t2.join(2000);
            return new Result(code, out.toString(), err.toString());
        } catch (Exception e) {
            return new Result(-1, "", e.toString());
        }
    }

    /** 启动一个「交互式 shell」进程：Windows 用 cmd，其他用 sh。 */
    public static Process startShell(Path cwd) throws IOException {
        ProcessBuilder pb;
        if (WINDOWS) {
            pb = new ProcessBuilder("cmd.exe");
        } else {
            pb = new ProcessBuilder("/bin/sh");
        }
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        return pb.start();
    }

    /** 读进程输出流，追加进 StringBuilder（限制最大长度）。 */
    public static Thread pump(java.io.InputStream in, StringBuilder sink, int maxLen) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (sink) {
                        sink.append(line).append('\n');
                        if (sink.length() > maxLen) sink.delete(0, sink.length() - maxLen);
                    }
                }
            } catch (IOException ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    public static Thread pump(java.io.InputStream in, StringBuilder sink) {
        return pump(in, sink, 20_000);
    }

    /** 后台线程执行，结束时把结果喂给回调（在调用线程）。 */
    public static void runAsync(String[] cmd, Consumer<Result> onDone) {
        Thread t = new Thread(() -> {
            Result r = run(cmd);
            if (onDone != null) onDone.accept(r);
        });
        t.setDaemon(true);
        t.start();
    }

    /** 后台跑一个会持续输出的进程，输出走回调。返回句柄可关停。 */
    public static Handle spawn(List<String> cmd, Charset charset, Consumer<String> onOutput) {
        AtomicBoolean stop = new AtomicBoolean(false);
        Thread t = new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), charset))) {
                    String line;
                    while (!stop.get() && (line = r.readLine()) != null) {
                        onOutput.accept(line + "\n");
                    }
                }
                p.waitFor();
            } catch (Exception e) {
                onOutput.accept(e.toString() + "\n");
            }
        });
        t.setDaemon(true);
        t.start();
        return new Handle(stop, t);
    }

    public record Result(int code, String stdout, String stderr) {
        public String combined() {
            if (stderr == null || stderr.isEmpty()) return stdout;
            if (stdout == null || stdout.isEmpty()) return stderr;
            return stdout + stderr;
        }
    }

    public static final class Handle {
        private final AtomicBoolean stop;
        private final Thread thread;

        Handle(AtomicBoolean stop, Thread thread) {
            this.stop = stop;
            this.thread = thread;
        }

        public void stop() {
            stop.set(true);
            thread.interrupt();
        }
    }
}
