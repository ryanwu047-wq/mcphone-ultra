package com.mcphoneultra.client.util;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import java.io.OutputStream;
import java.util.function.Consumer;

/**
 * 手机里的 JavaScript 运行时 —— 基于 GraalJS（随本扩展 jarJar 打包，玩家无需另装）。
 * 提供 console.log/error/warn 与 print()，超时会强制中止。
 */
public final class JsEngine {

    private JsEngine() {}

    public static String run(String code, int timeoutMs) {
        if (code == null || code.isBlank()) return "（沒有程式碼）";
        StringBuilder out = new StringBuilder();
        Context[] holder = new Context[1];
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
        Throwable[] err = new Throwable[1];

        Thread t = new Thread(() -> {
            try (Context ctx = Context.newBuilder("js")
                    .option("engine.WarnInterpreterOnly", "false")
                    .allowAllAccess(true)
                    .out(OutputStream.nullOutputStream())
                    .err(OutputStream.nullOutputStream())
                    .build()) {
                holder[0] = ctx;
                Value bindings = ctx.getBindings("js");
                bindings.putMember("print", (Consumer<Object>) o -> append(out, String.valueOf(o) + "\n"));
                bindings.putMember("console", new JsConsole(out));
                ctx.eval("js", code);
            } catch (Throwable e) {
                err[0] = e;
            } finally {
                done.set(true);
            }
        }, "mcphone-ultra-js");
        t.setDaemon(true);
        t.start();

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!done.get() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                break;
            }
        }
        if (!done.get()) {
            try {
                if (holder[0] != null) holder[0].close(true);
            } catch (Throwable ignored) {
            }
            append(out, "\n【執行逾時（超過 " + (timeoutMs / 1000) + " 秒），已強制中止】\n");
            return out.toString();
        }
        if (err[0] != null) {
            String msg = err[0].getMessage();
            append(out, "\n【執行錯誤】" + (msg == null ? err[0].toString() : msg) + "\n");
        }
        return out.toString();
    }

    private static void append(StringBuilder sb, String s) {
        synchronized (sb) {
            sb.append(s);
            if (sb.length() > 30_000) sb.delete(0, sb.length() - 30_000);
        }
    }

    /** 从 JS 侧可见的 console 对象。 */
    public static final class JsConsole {
        private final StringBuilder out;

        JsConsole(StringBuilder out) {
            this.out = out;
        }

        public void log(Object... args) {
            logLine(args);
        }

        public void error(Object... args) {
            logLine(args);
        }

        public void warn(Object... args) {
            logLine(args);
        }

        public void info(Object... args) {
            logLine(args);
        }

        private void logLine(Object... args) {
            StringBuilder sb = new StringBuilder();
            if (args != null) {
                for (Object o : args) sb.append(o == null ? "null" : o).append(' ');
            }
            append(out, sb.toString().stripTrailing() + "\n");
        }
    }
}
