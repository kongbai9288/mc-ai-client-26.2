package com.kongbai.mcai.platform;

import com.kongbai.mcai.util.Json;

import java.lang.reflect.Method;

/**
 * ViaFabricPlus 跨版本支持。
 *
 * <h3>为什么要它</h3>
 * 26.2 客户端只能直接连 26.2 服务器。装了 ViaFabricPlus 之后，同一个客户端
 * 可以连 1.8 ~ 26.2 的任意服务器——这样"一个客户端打天下"，不用为每个版本
 * 分别编译 mod。
 *
 * <h3>我们在这里做什么</h3>
 * 反射探测 ViaFabricPlus（5.0.0+，MC 26.2 官方支持），拿到<b>当前连接的
 * 目标协议版本</b>，然后：
 * <ol>
 *   <li>把版本信息塞进每次观察，AI 知道自己在跟哪个版本的服务器打交道；</li>
 *   <li>按版本给出行为差异提示（1.8 无攻击冷却可连点；1.9+ 有冷却要等武器恢复）；</li>
 *   <li>反作弊参数按版本自动给出建议（旧版服务器的反插件通常更敏感）。</li>
 * </ol>
 *
 * <p>同样是软探测：ViaFabricPlus 不存在时，一律按"原生 26.2"处理。
 */
public final class ViaLink {

    /** ViaFabricPlus 5.0 的类名候选（不同版本包名有迁移）。 */
    private static final String[] MAIN_CLASSES = {
            "com.viaversion.viafabricplus.ViaFabricPlus",
            "de.florianmichael.viafabricplus.ViaFabricPlus",
    };

    private static volatile Boolean available;
    private static Class<?> mainClass;
    private static String failureReason = "";

    private ViaLink() {}

    public static boolean available() {
        if (available != null) {
            return available;
        }
        synchronized (ViaLink.class) {
            if (available != null) {
                return available;
            }
            for (String cn : MAIN_CLASSES) {
                try {
                    mainClass = Class.forName(cn);
                    available = true;
                    failureReason = "";
                    return true;
                } catch (Throwable ignored) {
                    // 下一个
                }
            }
            available = false;
            failureReason = "类路径中未找到 ViaFabricPlus";
            return false;
        }
    }

    public static String failureReason() {
        return failureReason;
    }

    /**
     * 当前连接的目标协议版本。
     *
     * @return 形如 {@code 1.20.1} / {@code 26.2}；拿不到时返回 null（表示原生版本）
     */
    public static String targetVersion() {
        if (!available() || mainClass == null) {
            return null;
        }
        // 5.0：ViaFabricPlus.api().protocolTranslation()...
        // 旧版：ViaFabricPlus.getImpl().getTargetVersion()
        try {
            Object api = tryInvokeStatic(mainClass, "api");
            if (api != null) {
                Object pt = tryInvoke(api, "protocolTranslation");
                if (pt != null) {
                    for (String mn : new String[] {
                            "getTargetVersion", "getTargetProtocolVersion", "getDisplayVersion"}) {
                        Object v = tryInvoke(pt, mn);
                        if (v != null) {
                            return normalize(v);
                        }
                    }
                }
            }
            Object impl = tryInvokeStatic(mainClass, "getImpl");
            if (impl != null) {
                for (String mn : new String[] {
                        "getTargetVersion", "getTargetProtocolVersion", "getDisplayVersion"}) {
                    Object v = tryInvoke(impl, mn);
                    if (v != null) {
                        return normalize(v);
                    }
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 是否连接到了与客户端原生版本不同的服务器。 */
    public static boolean isTranslating() {
        String v = targetVersion();
        return v != null && !v.equals("26.2") && !v.startsWith("26.2");
    }

    /** 目标版本的主要版本号（1.8 → 8，1.20.1 → 20，26.2 → 26）。 */
    public static Integer targetMinor() {
        String v = targetVersion();
        if (v == null) {
            return null;
        }
        try {
            String[] parts = v.split("\\.");
            if (v.startsWith("26.") || v.startsWith("25.")) {
                return Integer.parseInt(parts[0]);
            }
            if (parts.length >= 2 && parts[0].equals("1")) {
                return Integer.parseInt(parts[1]);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================================================================
    // 行为差异
    // ==================================================================

    /**
     * 生成当前服务器版本下的行为提示。
     *
     * <p>这是 AI 最容易踩坑的地方：同一个"攻击"动作，在 1.8 服务器上是连点，
     * 在 1.9+ 上必须等武器冷却，否则打出来的伤害只有 1/3。
     */
    public static Json.Obj behaviorHints() {
        Json.Obj o = Json.obj();
        String v = targetVersion();
        o.put("target_version", v == null ? "26.2 (native)" : v);
        o.put("via_active", isTranslating());

        Integer minor = targetMinor();
        if (minor == null) {
            o.put("attack_cooldown", "unknown");
            o.put("hint", "无法确定服务器版本，按 1.9+ 保守策略（等武器冷却）执行");
            return o;
        }
        if (minor <= 8) {
            o.put("attack_cooldown", false);
            o.put("attack_advice", "1.8 及更旧：无攻击冷却，可高频连点，伤害与频率正相关");
            o.put("sprint_hit", true);
            o.put("shield", false);
            o.put("hint", "旧版战斗：连点 + 冲刺击退最优；没有盾牌，格挡不可用");
        } else if (minor >= 9) {
            o.put("attack_cooldown", true);
            o.put("attack_advice", "1.9 及更新：攻击有冷却，必须等武器恢复再挥，否则伤害大幅衰减");
            o.put("shield", minor >= 9);
            o.put("hint", "新版战斗：攻击间隔至少 10 tick，配合盾牌防御");
        } else {
            o.put("attack_cooldown", true);
            o.put("hint", "按 26.x 原生规则执行");
        }

        // 反作弊建议：旧版服务器反插件普遍更敏感
        Json.Obj ac = Json.obj();
        if (minor <= 8) {
            ac.put("risk", "high");
            ac.put("attack_min_ticks", 6);
            ac.put("look_step_degrees", 15.0);
            ac.put("note", "1.8 服务器常见反插件对高频点击与瞬转敏感，但仍需保持一定频率才有伤害");
        } else if (minor <= 20) {
            ac.put("risk", "medium");
            ac.put("attack_min_ticks", 10);
            ac.put("look_step_degrees", 14.0);
            ac.put("note", "近版本服务器：遵循攻击冷却节奏，天然更像人类");
        } else {
            ac.put("risk", "medium");
            ac.put("attack_min_ticks", 10);
            ac.put("look_step_degrees", 16.0);
            ac.put("note", "26.x：按默认拟人化参数即可");
        }
        o.put("anticheat_suggestion", ac);
        return o;
    }

    /** 供 /health 用的状态摘要。 */
    public static Json.Obj status() {
        Json.Obj o = Json.obj();
        o.put("available", available());
        o.put("target_version", targetVersion() == null ? "native 26.2" : targetVersion());
        o.put("translating", isTranslating());
        if (!available()) {
            o.put("detail", failureReason);
            o.put("hint", "安装 ViaFabricPlus 5.0.0+ 即可用一个客户端连接 1.8 ~ 26.2 的任意服务器");
        }
        return o;
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private static Object tryInvokeStatic(Class<?> c, String name) {
        try {
            Method m = c.getMethod(name);
            return m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object tryInvoke(Object target, String name) {
        try {
            Method m = target.getClass().getMethod(name);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String normalize(Object v) {
        String s = String.valueOf(v);
        // ProtocolVersion 对象的 toString 形如 "1.20.1" 或 "1.20.1 (754)"
        int sp = s.indexOf(' ');
        if (sp > 0) {
            s = s.substring(0, sp);
        }
        s = s.replace("(", "").replace(")", "").trim();
        return s;
    }
}
