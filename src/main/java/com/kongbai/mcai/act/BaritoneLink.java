package com.kongbai.mcai.act;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Baritone 软集成层。
 *
 * <p><b>为什么是反射而不是依赖？</b>
 * 本项目要保持独立：Baritone 不写进 {@code build.gradle} 的依赖里，
 * 也不打进最终 jar。这样：
 * <ul>
 *   <li>没装 Baritone 也能用 —— 自动降级到内置导航；</li>
 *   <li>装了任意 26.2 兼容版本的 Baritone 都能用 —— 不受版本绑定；</li>
 *   <li>不会因为 Baritone 缺失而在启动时崩溃。</li>
 * </ul>
 *
 * <p>所有调用都包在 try 里：反射失败只影响这一个能力，不影响游戏。
 *
 * <p>用到的 Baritone API（已对照 26.2 分支源码核实）：
 * <pre>
 *   BaritoneAPI.getProvider().getPrimaryBaritone()
 *   IBaritone.getCommandManager().execute(String)      // 执行 #goto / #mine ...
 *   IBaritone.getPathingBehavior()                      // isPathing / cancelEverything
 *   IBaritone.getPathingControlManager()
 *   BaritoneAPI.getSettings()                           // 各项 Setting&lt;T&gt;.value
 * </pre>
 */
public final class BaritoneLink {

    private static volatile Boolean available;
    private static Object primary;            // IBaritone
    private static Object commandManager;     // ICommandManager
    private static Object settings;           // Settings
    private static Object pathingBehavior;   // IPathingBehavior
    private static String failureReason;

    private BaritoneLink() {}

    /** Baritone 是否可用（结果缓存，只探测一次）。 */
    public static boolean available() {
        if (available != null) {
            return available;
        }
        synchronized (BaritoneLink.class) {
            if (available != null) {
                return available;
            }
            available = probe();
            return available;
        }
    }

    public static String failureReason() {
        return failureReason == null ? "" : failureReason;
    }

    private static boolean probe() {
        try {
            Class<?> api = Class.forName("baritone.api.BaritoneAPI");
            Object provider = api.getMethod("getProvider").invoke(null);
            primary = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
            if (primary == null) {
                failureReason = "getPrimaryBaritone() 返回 null";
                return false;
            }
            commandManager = call(primary, "getCommandManager");
            settings = Class.forName("baritone.api.BaritoneAPI").getMethod("getSettings").invoke(null);
            pathingBehavior = call(primary, "getPathingBehavior");
            failureReason = "";
            return true;
        } catch (Throwable t) {
            failureReason = String.valueOf(t);
            primary = null;
            commandManager = null;
            settings = null;
            pathingBehavior = null;
            return false;
        }
    }

    // ==================================================================
    // 命令执行
    // ==================================================================

    /**
     * 执行一条 Baritone 命令（不需要井号前缀，内部自动补）。
     *
     * <p>例：{@code run("goto", "100 64 100")}、{@code run("mine", "diamond_ore")}
     *
     * @return true 表示 Baritone 接受了该命令
     */
    public static boolean run(String command, String args) {
        if (!available()) {
            return false;
        }
        try {
            String line = "#" + command + (args == null || args.isBlank() ? "" : " " + args);
            Method m = commandManager.getClass().getMethod("execute", String.class);
            Object r = m.invoke(commandManager, line);
            return r instanceof Boolean b ? b : true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 停止当前所有 Baritone 进程。 */
    public static boolean stop() {
        if (!available()) {
            return false;
        }
        try {
            Object pb = pathingBehavior;
            if (pb == null) {
                return false;
            }
            tryInvoke(pb, "cancelEverything");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Baritone 当前是否正在执行进程。 */
    public static boolean isActive() {
        if (!available() || pathingBehavior == null) {
            return false;
        }
        try {
            Object r = tryInvoke(pathingBehavior, "isPathing");
            return r instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================================================================
    // 设置读写（Setting<T>.value）
    // ==================================================================

    /**
     * 读取一个 Baritone 设置项。
     *
     * @param name 设置名，如 {@code mobDefense}、{@code autoEat}
     * @return 值；不存在或不可用时返回 null
     */
    public static Object setting(String name) {
        if (!available() || settings == null) {
            return null;
        }
        try {
            var f = settings.getClass().getField(name);
            Object s = f.get(settings);
            if (s == null) {
                return null;
            }
            var vf = s.getClass().getField("value");
            return vf.get(s);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 写入一个 Baritone 设置项。
     *
     * @param name  设置名
     * @param value 目标值，类型必须与 Setting&lt;T&gt; 的 T 一致
     */
    public static boolean setSetting(String name, Object value) {
        if (!available() || settings == null) {
            return false;
        }
        try {
            var f = settings.getClass().getField(name);
            Object s = f.get(settings);
            if (s == null) {
                return false;
            }
            var vf = s.getClass().getField("value");
            Class<?> t = vf.getType();
            Object cvt = convert(value, t);
            if (cvt == null && value != null) {
                return false;
            }
            vf.set(s, cvt);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 导出 AI 关心的关键设置，供决策时参考。 */
    public static Map<String, Object> keySettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : new String[] {
                "allowSprint", "allowBreak", "allowPlace", "avoidance",
                "mobDefense", "mobDefenseRadius", "mobDefenseFleeHealthPercent",
                "mobDefenseEvadeWhenUnarmed", "autoEat", "autoEatThreshold"}) {
            m.put(k, setting(k));
        }
        return m;
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private static Object convert(Object v, Class<?> t) {
        if (v == null) {
            return null;
        }
        if (t == Boolean.TYPE || t == Boolean.class) {
            return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
        }
        if (t == Integer.TYPE || t == Integer.class) {
            return v instanceof Number n ? n.intValue() : (int) Double.parseDouble(String.valueOf(v));
        }
        if (t == Double.TYPE || t == Double.class) {
            return v instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(v));
        }
        if (t == Float.TYPE || t == Float.class) {
            return v instanceof Number n ? n.floatValue() : Float.parseFloat(String.valueOf(v));
        }
        if (t.isAssignableFrom(v.getClass())) {
            return v;
        }
        return null;
    }

    private static Object call(Object target, String method) throws Exception {
        Method m = target.getClass().getMethod(method);
        return m.invoke(target);
    }

    private static Object tryInvoke(Object target, String method) {
        try {
            return call(target, method);
        } catch (Throwable t) {
            return null;
        }
    }
}
