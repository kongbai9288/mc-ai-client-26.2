package com.kongbai.mcai.act;

import java.lang.reflect.Method;

/**
 * Litematica（投影 / 打印机）软集成层。
 *
 * <p>和 {@link BaritoneLink} 同一套思路：<b>反射软探测，不做编译依赖</b>。
 * 这样没装 Litematica 也能正常玩，装了就能用它的投影与 EasyPlace 打印。
 *
 * <p>探测目标（Litematica for 26.2，sakura-ryoko / masady 各分支通用）：
 * <ul>
 *   <li>{@code fi.dy.masa.litematica.data.DataManager} —— 承载已加载的投影</li>
 *   <li>{@code fi.dy.masa.litematica.data.SchematicPlacementManager} —— 投影放置</li>
 *   <li>Easy Place 开关 —— 即"打印机"：开启后手持方块自动按投影放置</li>
 * </ul>
 *
 * <p>由于不同 fork 的类名 / 方法名有出入，这里对每个能力做<b>多点探测</b>：
 * 依次尝试多个候选类名，全部失败就降级。绝不会因为 Litematica 缺失或
 * 版本不符而让游戏崩掉。
 */
public final class LitematicaLink {

    private static volatile Boolean available;
    private static String failureReason = "";
    private static Class<?> dataManagerClass;
    private static Object dataManager;
    private static Method easyPlaceSetter;

    private LitematicaLink() {}

    public static boolean available() {
        if (available != null) {
            return available;
        }
        synchronized (LitematicaLink.class) {
            if (available != null) {
                return available;
            }
            available = probe();
            return available;
        }
    }

    public static String failureReason() {
        return failureReason;
    }

    private static boolean probe() {
        // 各 fork 的 DataManager 路径候选
        String[] candidates = {
                "fi.dy.masa.litematica.data.DataManager",
                "fi.dy.masa.litematica.data.DataManager", // 保持显式（不同 fork 同名）
        };
        for (String cn : candidates) {
            try {
                dataManagerClass = Class.forName(cn);
                Method g = dataManagerClass.getMethod("getInstance");
                dataManager = g.invoke(null);
                if (dataManager != null) {
                    failureReason = "";
                    return true;
                }
            } catch (Throwable ignored) {
                // 换下一个候选
            }
        }
        // 退一步：只要类路径上存在 Litematica 就算可用（能力降级）
        try {
            Class.forName("fi.dy.masa.litematica.Litematica");
            dataManagerClass = null;
            dataManager = null;
            failureReason = "";
            return true;
        } catch (Throwable t) {
            dataManagerClass = null;
            dataManager = null;
            failureReason = String.valueOf(t);
            return false;
        }
    }

    // ==================================================================
    // 打印（Easy Place）
    // ==================================================================

    /**
     * 开启 / 关闭 Easy Place（打印机模式）。
     * 开启后，只要手里拿着对应方块对着投影右键，就会自动按投影精确放置。
     */
    public static boolean setEasyPlace(boolean on) {
        if (!available()) {
            return false;
        }
        // 候选：DataManager.setEasyPlaceMode / Configs.Generic.EASY_PLACE_MODE
        try {
            if (dataManager != null) {
                try {
                    Method m = dataManager.getClass().getMethod("setEasyPlaceMode", boolean.class);
                    m.invoke(dataManager, on);
                    return true;
                } catch (NoSuchMethodException ignored) {
                    // 走配置路径
                }
            }
            Class<?> cfg = Class.forName("fi.dy.masa.litematica.config.Configs$Generic");
            Object field = null;
            for (java.lang.reflect.Field f : cfg.getFields()) {
                if (f.getName().equalsIgnoreCase("EASY_PLACE_MODE")
                        || f.getName().equalsIgnoreCase("EASY_PLACE")) {
                    field = f.get(null);
                    break;
                }
            }
            if (field != null) {
                // 配置项通常是 IConfigBoolean：setBooleanValue(boolean)
                for (String mn : new String[] {"setBooleanValue", "setValue"}) {
                    try {
                        Method m = field.getClass().getMethod(mn, boolean.class);
                        m.invoke(field, on);
                        return true;
                    } catch (NoSuchMethodException ignored) {
                        // 继续尝试
                    }
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前打印机是否开启。 */
    public static Boolean isEasyPlace() {
        if (!available()) {
            return null;
        }
        try {
            if (dataManager != null) {
                try {
                    Method m = dataManager.getClass().getMethod("isEasyPlaceMode");
                    Object r = m.invoke(dataManager);
                    return r instanceof Boolean b ? b : null;
                } catch (NoSuchMethodException ignored) {
                    // 落下去
                }
            }
            Class<?> cfg = Class.forName("fi.dy.masa.litematica.config.Configs$Generic");
            for (java.lang.reflect.Field f : cfg.getFields()) {
                if (f.getName().equalsIgnoreCase("EASY_PLACE_MODE")
                        || f.getName().equalsIgnoreCase("EASY_PLACE")) {
                    Object v = f.get(null);
                    for (String mn : new String[] {"getBooleanValue", "getValue"}) {
                        try {
                            Method m = v.getClass().getMethod(mn);
                            Object r = m.invoke(v);
                            return r instanceof Boolean b ? b : null;
                        } catch (NoSuchMethodException ignored) {
                            // 继续
                        }
                    }
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 已加载投影数量（拿不到返回 -1）。 */
    public static int placementCount() {
        if (!available() || dataManager == null) {
            return -1;
        }
        try {
            Method m = dataManager.getClass().getMethod("getSchematicPlacementManager");
            Object pm = m.invoke(dataManager);
            if (pm == null) {
                return -1;
            }
            for (String mn : new String[] {"getAllPlacements", "getPlacements"}) {
                try {
                    Method g = pm.getClass().getMethod(mn);
                    Object list = g.invoke(pm);
                    if (list instanceof java.util.Collection<?> c) {
                        return c.size();
                    }
                } catch (NoSuchMethodException ignored) {
                    // 继续
                }
            }
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 打印当前状态摘要。 */
    public static String status() {
        StringBuilder sb = new StringBuilder();
        sb.append(available() ? "已检测到 Litematica" : "未安装 Litematica");
        if (available()) {
            Boolean ep = isEasyPlace();
            sb.append(" | EasyPlace=").append(ep == null ? "unknown" : ep);
            int n = placementCount();
            sb.append(" | 已加载投影=").append(n < 0 ? "unknown" : n);
        } else if (!failureReason.isBlank()) {
            sb.append(" (").append(abbreviate(failureReason)).append(')');
        }
        return sb.toString();
    }

    private static String abbreviate(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "...";
    }
}
