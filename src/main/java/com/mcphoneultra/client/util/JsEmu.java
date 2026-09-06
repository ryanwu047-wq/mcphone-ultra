package com.mcphoneultra.client.util;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 持久化 GraalJS 模拟器宿主：NES（jsnes）/ GB（jsGB）/ GBA（gbajs2）。
 *
 * 每个实例独占一个 GraalJS Context，全部 JS 调用都在内部工作线程上执行，
 * 渲染线程只读 {@link #frameBytes()} 的字节数组，两者互不阻塞。
 */
public final class JsEmu {

    /** NES 手柄按钮编号（与 jsnes.Controller 常量一致） */
    public static final int NES_A = 0, NES_B = 1, NES_SELECT = 2, NES_START = 3,
            NES_UP = 4, NES_DOWN = 5, NES_LEFT = 6, NES_RIGHT = 7;

    public enum System { NES, GB, GBA }

    private final System system;
    private final Context ctx;
    private final Value bindings;
    private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicReference<Throwable> fatal = new AtomicReference<>();

    private volatile byte[] frameBytes;      // RGBA，渲染线程只读
    private volatile int frameW, frameH;
    private final AtomicBoolean romLoaded = new AtomicBoolean(false);

    private JsEmu(System system) throws IOException {
        this.system = system;
        this.ctx = Context.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .allowAllAccess(true)
                .out(OutputStream.nullOutputStream())
                .err(OutputStream.nullOutputStream())
                .build();
        this.bindings = ctx.getBindings("js");
        initScripts();
        Thread worker = new Thread(this::loop, "mcphone-ultra-emu-" + system.name().toLowerCase(Locale.ROOT));
        worker.setDaemon(true);
        worker.start();
    }

    /** 建一个模拟器实例；失败抛 IOException（脚本缺失/语法错误等）。 */
    public static JsEmu create(System system) throws IOException {
        return new JsEmu(system);
    }

    private void initScripts() throws IOException {
        try {
            switch (system) {
                case NES -> {
                    evalResource("assets/mcphone_ultra/emulator/nes.js");
                    evalResource("assets/mcphone_ultra/emulator/nes_wrap.js");
                    frameW = 256;
                    frameH = 240;
                }
                case GB -> {
                    evalResource("assets/mcphone_ultra/emulator/gb/gb_pre.js");
                    evalResource("assets/mcphone_ultra/emulator/gb/gb_core.js");
                    evalResource("assets/mcphone_ultra/emulator/gb/gb_wrap.js");
                    frameW = 160;
                    frameH = 144;
                }
                case GBA -> {
                    evalResource("assets/mcphone_ultra/emulator/gba/gba_core.js");
                    evalResource("assets/mcphone_ultra/emulator/gba/gba_wrap.js");
                    frameW = 240;
                    frameH = 160;
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("模擬器核心腳本載入失敗: " + e.getMessage(), e);
        }
    }

    private void evalResource(String path) throws IOException {
        try (InputStream in = JsEmu.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IOException("找不到資源 " + path);
            String src = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            ctx.eval("js", src);
        }
    }

    private void loop() {
        while (!closed.get()) {
            try {
                Runnable r = queue.poll(10, TimeUnit.MILLISECONDS);
                if (r != null) {
                    try {
                        r.run();
                    } catch (Throwable t) {
                        fatal.compareAndSet(null, t);
                    }
                }
            } catch (InterruptedException ignored) {
            }
        }
        try {
            ctx.close(true);
        } catch (Throwable ignored) {
        }
    }

    private void submit(Runnable r) {
        if (closed.get()) return;
        queue.add(r);
    }

    /** 在工作线程上同步执行一段脚本，返回结果。渲染线程不要调。 */
    private <T> T callSync(SyncCall<T> c) {
        java.util.concurrent.CompletableFuture<T> f = new java.util.concurrent.CompletableFuture<>();
        submit(() -> {
            try {
                f.complete(c.run(bindings));
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        try {
            return f.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("模擬器腳本執行失敗: " + e, e);
        }
    }

    private interface SyncCall<T> {
        T run(Value b) throws Exception;
    }

    /** 载入 ROM（字节数组 → base64 → JS）。异步排队，等下一次帧推进时生效。 */
    public void loadRom(byte[] rom) {
        if (rom == null || rom.length == 0) return;
        String b64 = Base64.getEncoder().encodeToString(rom);
        submit(() -> {
            try {
                switch (system) {
                    case NES -> bindings.getMember("__nesLoad").execute(b64);
                    case GB -> bindings.getMember("__gbLoad").execute(b64);
                    case GBA -> bindings.getMember("__gbaLoad").execute(b64, biosB64());
                }
                romLoaded.set(true);
                fatal.set(null);
            } catch (Throwable t) {
                fatal.compareAndSet(null, t);
            }
        });
    }

    private String biosB64() {
        try {
            byte[] b = java.nio.file.Files.readAllBytes(
                    com.mcphoneultra.client.io.Paths.file("files", "roms", "gba", "bios.bin"));
            if (b.length > 0) return Base64.getEncoder().encodeToString(b);
        } catch (Exception ignored) {
        }
        return "";
    }

    /** 推进一帧（在内部线程执行，立即返回）。渲染循环按时间调它即可；跟不上时自动丢帧。 */
    public void frame() {
        if (!romLoaded.get()) return;
        if (queue.size() > 2) return; // 模拟速度跟不上，丢帧保实时
        submit(() -> {
            try {
                switch (system) {
                    case NES -> bindings.getMember("__nesFrame").executeVoid();
                    case GB -> bindings.getMember("__gbFrame").executeVoid();
                    case GBA -> bindings.getMember("__gbaStep").executeVoid();
                }
                copyBuffer();
            } catch (Throwable t) {
                fatal.compareAndSet(null, t);
            }
        });
    }

    private void copyBuffer() {
        Value buf = switch (system) {
            case NES -> bindings.getMember("__nesBuffer").execute();
            case GB -> bindings.getMember("__gbBuffer").execute();
            case GBA -> bindings.getMember("__gbaBuffer").execute();
        };
        if (buf == null || buf.isNull()) return;
        byte[] out = toRgba(buf, frameW * frameH * 4);
        if (out != null) frameBytes = out;
    }

    /** JS 值（Uint8Array 或普通数组）→ RGBA 字节数组。 */
    private static byte[] toRgba(Value v, int expected) {
        try {
            return v.as(byte[].class);
        } catch (Throwable t) {
            if (!v.hasArrayElements()) return null;
            long n = v.getArraySize();
            byte[] out = new byte[(int) n];
            for (long i = 0; i < n; i++) {
                out[(int) i] = (byte) v.getArrayElement(i).asInt();
            }
            return out;
        }
    }

    /** 按键：controller=1 为主手柄（NES 用），button 见上方常量 / 键码（GB/GBA 用 GLFW 无关的 DOM keyCode）。 */
    public void key(int controllerOrKeyCode, int button, boolean down) {
        if (!romLoaded.get()) return;
        submit(() -> {
            try {
                switch (system) {
                    case NES -> bindings.getMember("__nesButton").execute(controllerOrKeyCode, button, down);
                    case GB -> bindings.getMember("__gbKey").execute(button, down);
                    case GBA -> bindings.getMember("__gbaKey").execute(button, down);
                }
            } catch (Throwable t) {
                fatal.compareAndSet(null, t);
            }
        });
    }

    public boolean isRomLoaded() {
        return romLoaded.get();
    }

    public byte[] frameBytes() {
        return frameBytes;
    }

    public int width() {
        return frameW;
    }

    public int height() {
        return frameH;
    }

    public String error() {
        Throwable t = fatal.get();
        return t == null ? "" : (t.getMessage() == null ? t.toString() : t.getMessage());
    }

    public void close() {
        closed.set(true);
    }

    /** 供 JS 编程 App 使用的单次执行入口（与 JsEngine 并存，但复用资源）。 */
    public static String runOnce(String code, int timeoutMs) {
        return JsEngine.run(code, timeoutMs);
    }
}
