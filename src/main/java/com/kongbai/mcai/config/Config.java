package com.kongbai.mcai.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 配置加载。
 *
 * <p>配置文件：{@code config/mcai/mcai.properties}
 * 密钥不写进配置文件，优先读环境变量 {@code MCAI_API_KEY}，避免泄露。
 */
public final class Config {

    // ---- LLM（OpenAI 兼容接口）----
    public String apiBase = "https://api.deepseek.com/v1";
    public String apiKey = "";
    public String model = "deepseek-chat";
    public double temperature = 0.2;
    public int maxTokens = 2048;
    public boolean stream = false;

    /** 驱动模式：standard（带系统提示词） / harness（完全由 AI 管理）。 */
    public String mode = "standard";

    /** AI 自主循环开关：开启后 AI 会持续观察-决策-执行，不需要人工下指令。 */
    public boolean autonomous = true;

    /** 自主循环的决策间隔（毫秒）。避免每 tick 打一次 LLM。 */
    public long decisionIntervalMs = 1500L;

    /** 连续失败多少次后暂停自主循环，交给用户处理。 */
    public int maxConsecutiveFailures = 5;

    // ---- 桥接层 ----
    public boolean bridgeEnabled = true;
    public String bridgeHost = "127.0.0.1";
    public int bridgePort = 8791;
    public String bridgeToken = "";

    // ---- 感知 ----
    /** 扫描半径（格）。 */
    public int scanRadius = 32;
    /** 扫描垂直范围（上下各多少格）。 */
    public int scanVertical = 16;
    /** 实体感知距离。 */
    public int entityRadius = 48;
    /** 观察缓存有效期（毫秒）。 */
    public long observationTtlMs = 250L;

    // ---- 反作弊拟人化 ----
    public boolean antiCheat = true;
    /** 攻击间隔最小 tick（MC 20 tick = 1s）。人类连点约 8~12 tick。 */
    public int attackMinIntervalTicks = 9;
    public int attackMaxIntervalTicks = 14;
    /** 视角转动每 tick 最大角度，模拟人手速度。 */
    public float lookStepDegrees = 18.0F;
    /** 每 N 次操作插入一次微小随机扰动，动作间隔不再是机械常量。 */
    public int jitterEvery = 5;

    private static Config INSTANCE;

    public static Config get() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    public static Config load() {
        Config c = new Config();
        Path dir = Path.of("config", "mcai");
        Path file = dir.resolve("mcai.properties");
        try {
            Files.createDirectories(dir);
            if (!Files.exists(file)) {
                writeDefault(file, c);
            } else {
                Properties p = new Properties();
                try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    p.load(in);
                }
                c.apiBase = p.getProperty("api.base", c.apiBase);
                c.model = p.getProperty("api.model", c.model);
                c.temperature = dbl(p, "api.temperature", c.temperature);
                c.maxTokens = integer(p, "api.max_tokens", c.maxTokens);
                c.mode = p.getProperty("agent.mode", c.mode);
                c.autonomous = bool(p, "agent.autonomous", c.autonomous);
                c.decisionIntervalMs = lng(p, "agent.decision_interval_ms", c.decisionIntervalMs);
                c.maxConsecutiveFailures = integer(p, "agent.max_consecutive_failures", c.maxConsecutiveFailures);
                c.bridgeEnabled = bool(p, "bridge.enabled", c.bridgeEnabled);
                c.bridgeHost = p.getProperty("bridge.host", c.bridgeHost);
                c.bridgePort = integer(p, "bridge.port", c.bridgePort);
                c.bridgeToken = p.getProperty("bridge.token", c.bridgeToken);
                c.scanRadius = integer(p, "sense.scan_radius", c.scanRadius);
                c.scanVertical = integer(p, "sense.scan_vertical", c.scanVertical);
                c.entityRadius = integer(p, "sense.entity_radius", c.entityRadius);
                c.observationTtlMs = lng(p, "sense.observation_ttl_ms", c.observationTtlMs);
                c.antiCheat = bool(p, "anticheat.enabled", c.antiCheat);
                c.attackMinIntervalTicks = integer(p, "anticheat.attack_min_ticks", c.attackMinIntervalTicks);
                c.attackMaxIntervalTicks = integer(p, "anticheat.attack_max_ticks", c.attackMaxIntervalTicks);
                c.lookStepDegrees = flt(p, "anticheat.look_step_degrees", c.lookStepDegrees);
                c.jitterEvery = integer(p, "anticheat.jitter_every", c.jitterEvery);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取 mcai 配置失败", e);
        }

        // 密钥只从环境变量取，绝不落盘
        String key = System.getenv("MCAI_API_KEY");
        if (key != null && !key.isBlank()) {
            c.apiKey = key.trim();
        }
        if (c.bridgeToken == null || c.bridgeToken.isBlank()) {
            c.bridgeToken = System.getenv("MCAI_BRIDGE_TOKEN");
            if (c.bridgeToken == null) {
                c.bridgeToken = "";
            }
        }
        return c;
    }

    public boolean isHarnessMode() {
        return "harness".equalsIgnoreCase(mode);
    }

    private static void writeDefault(Path file, Config c) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# MCAI 配置（Minecraft 26.2）\n");
        sb.append("# API Key 不要写这里，用环境变量 MCAI_API_KEY\n\n");
        sb.append("api.base=").append(c.apiBase).append('\n');
        sb.append("api.model=").append(c.model).append('\n');
        sb.append("api.temperature=").append(c.temperature).append('\n');
        sb.append("api.max_tokens=").append(c.maxTokens).append('\n');
        sb.append("agent.mode=").append(c.mode).append("   # standard | harness\n");
        sb.append("agent.autonomous=").append(c.autonomous).append('\n');
        sb.append("agent.decision_interval_ms=").append(c.decisionIntervalMs).append('\n');
        sb.append("agent.max_consecutive_failures=").append(c.maxConsecutiveFailures).append('\n');
        sb.append("bridge.enabled=").append(c.bridgeEnabled).append('\n');
        sb.append("bridge.host=").append(c.bridgeHost).append('\n');
        sb.append("bridge.port=").append(c.bridgePort).append('\n');
        sb.append("bridge.token=\n");
        sb.append("sense.scan_radius=").append(c.scanRadius).append('\n');
        sb.append("sense.scan_vertical=").append(c.scanVertical).append('\n');
        sb.append("sense.entity_radius=").append(c.entityRadius).append('\n');
        sb.append("sense.observation_ttl_ms=").append(c.observationTtlMs).append('\n');
        sb.append("anticheat.enabled=").append(c.antiCheat).append('\n');
        sb.append("anticheat.attack_min_ticks=").append(c.attackMinIntervalTicks).append('\n');
        sb.append("anticheat.attack_max_ticks=").append(c.attackMaxIntervalTicks).append('\n');
        sb.append("anticheat.look_step_degrees=").append(c.lookStepDegrees).append('\n');
        sb.append("anticheat.jitter_every=").append(c.jitterEvery).append('\n');
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private static int integer(Properties p, String k, int def) {
        try { return Integer.parseInt(p.getProperty(k, String.valueOf(def)).trim()); }
        catch (NumberFormatException e) { return def; }
    }
    private static long lng(Properties p, String k, long def) {
        try { return Long.parseLong(p.getProperty(k, String.valueOf(def)).trim()); }
        catch (NumberFormatException e) { return def; }
    }
    private static double dbl(Properties p, String k, double def) {
        try { return Double.parseDouble(p.getProperty(k, String.valueOf(def)).trim()); }
        catch (NumberFormatException e) { return def; }
    }
    private static float flt(Properties p, String k, float def) {
        try { return Float.parseFloat(p.getProperty(k, String.valueOf(def)).trim()); }
        catch (NumberFormatException e) { return def; }
    }
    private static boolean bool(Properties p, String k, boolean def) {
        String v = p.getProperty(k);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }
}
