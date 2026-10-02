package com.kongbai.mcai.platform;

import java.nio.file.Path;
import java.util.Locale;

/**
 * 运行环境探测。
 *
 * <p>核心职责：识别"手机启动器"环境。手机端（PojavLauncher / FCL / Amethyst /
 * Zalith / QuestCraft 等）跑的是 Android + 模拟的 AWT/GLFW 栈，内存小、没有独立
 * 窗口句柄，额外开一个 HTTP 服务并且让 AI 长时间连续操作，极易触发崩溃或 ANR。
 *
 * <p>一旦判定为手机启动器，{@link #bridgeEnabled()} 返回 false，桥接层整体关闭，
 * 只保留零开销的被动感知，保证游戏本体不受影响。
 */
public final class Env {

    /** 是否运行在手机启动器上（判定后不再变化）。 */
    private static final boolean MOBILE_LAUNCHER = detectMobileLauncher();

    /** 判定依据，用于日志与 / 调试接口，便于用户核对误判。 */
    private static final String REASON = detectReason();

    private Env() {}

    public static boolean isMobileLauncher() {
        return MOBILE_LAUNCHER;
    }

    /**
     * 桥接层（HTTP API + AI 驱动）是否允许启动。
     * 手机启动器上一律关闭。
     */
    public static boolean bridgeEnabled() {
        return !MOBILE_LAUNCHER;
    }

    public static String reason() {
        return REASON;
    }

    // ------------------------------------------------------------------
    // 探测逻辑
    // ------------------------------------------------------------------

    private static boolean detectMobileLauncher() {
        // 1) 环境变量：各家启动器基本都会注入
        for (String key : new String[] {
                "POJAV_LAUNCHER", "POJAV_HOME", "POJAV_RENDERER",
                "FCL_HOME", "FCL", "FCL_VERSION",
                "AMETHYST_HOME", "ZALITH_HOME",
                "JAVA_HOME", "HOME", "TMPDIR", "PREFIX"
        }) {
            String v = System.getenv(key);
            if (v != null && containsMobileToken(v)) {
                return true;
            }
        }

        // 2) 系统属性
        for (String key : new String[] {
                "user.home", "user.dir", "java.io.tmpdir",
                "java.home", "os.name", "os.version",
                "java.vm.name", "java.runtime.name", "java.vendor"
        }) {
            String v = System.getProperty(key);
            if (v != null && containsMobileToken(v)) {
                return true;
            }
        }

        // 3) Android 运行时类：Termux / 启动器自带 JVM 上一定存在
        if (classExists("android.os.Build") || classExists("android.app.Activity")) {
            return true;
        }

        // 4) 启动器自身的类或资源
        for (String cn : new String[] {
                "net.kdt.pojavlaunch.MainActivity",
                "net.kdt.pojavlaunch.PojavApplication",
                "cosmicode.freedomofpress.FCL",
                "com.movtery.zalithlauncher.MainActivity"
        }) {
            if (classExists(cn)) {
                return true;
            }
        }

        // 5) 文件系统指纹：Android 特有的目录布局
        for (String p : new String[] {
                "/system/build.prop",
                "/system/framework",
                "/data/data",
                "/sdcard",
                "/storage/emulated/0"
        }) {
            try {
                if (java.nio.file.Files.exists(Path.of(p))) {
                    // /sdcard 在部分 Linux 上也可能存在，配合 Android 类判定更稳
                    if (p.startsWith("/system") || p.startsWith("/data/data")) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
                // 路径不可读就当不存在，不能因为探测本身崩掉
            }
        }

        return false;
    }

    private static boolean containsMobileToken(String v) {
        String s = v.toLowerCase(Locale.ROOT);
        return s.contains("pojav")
                || s.contains("/fcl")
                || s.contains("fclauncher")
                || s.contains("freedomofpress")
                || s.contains("amethyst")
                || s.contains("zalith")
                || s.contains("questcraft")
                || s.contains("com.termux")
                || s.contains("/data/data/")
                || s.contains("/storage/emulated/");
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, Env.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String detectReason() {
        if (!MOBILE_LAUNCHER) {
            return "desktop";
        }
        StringBuilder sb = new StringBuilder("mobile-launcher:");
        for (String key : new String[] {"POJAV_LAUNCHER", "FCL_HOME", "user.home", "java.home"}) {
            String v = System.getenv(key);
            if (v == null) {
                v = System.getProperty(key);
            }
            if (v != null && containsMobileToken(v)) {
                sb.append(key).append(';');
            }
        }
        if (classExists("android.os.Build")) {
            sb.append("android.os.Build;");
        }
        return sb.toString();
    }
}
