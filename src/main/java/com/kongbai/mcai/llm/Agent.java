package com.kongbai.mcai.llm;

import com.kongbai.mcai.act.Actuator;
import com.kongbai.mcai.config.Config;
import com.kongbai.mcai.util.Json;
import com.kongbai.mcai.platform.Env;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 驱动循环。
 *
 * <h3>两种模式</h3>
 * <ul>
 *   <li><b>standard</b>：注入系统提示词，告诉模型它是谁、能用什么、要注意什么。
 *       适合通用模型（GPT / GLM / 通义 等）。</li>
 *   <li><b>harness</b>：<b>不注入任何系统提示词</b>，完全由 AI 自己管理。
 *       这是为 DeepSeek Harness 准备的：模型本身已经在 Harness 里被组织好，
 *       工具描述自带完整语义，再叠一层提示词只会干扰它。
 *       此模式下我们只负责：给观察、给工具、执行结果、收尾。</li>
 * </ul>
 *
 * <h3>防低级错误的几条硬约束</h3>
 * <ol>
 *   <li><b>每轮重新观察</b>：世界在两次 LLM 往返之间已经变了，
 *       拿旧数据决策是"打空气 / 挖错方块"的根源。</li>
 *   <li><b>原生 function calling</b>：不解析自然语言里的伪 JSON。</li>
 *   <li><b>工具错误必须回传</b>：模型需要知道自己错了才能改。</li>
 *   <li><b>步数上限</b>：单轮最多 N 次工具调用，防死循环烧 token。</li>
 *   <li><b>失败熔断</b>：连续失败 N 次暂停自主循环，交给人类。</li>
 * </ol>
 */
public final class Agent {

    private static final int MAX_STEPS_PER_ROUND = 12;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean roundInFlight = new AtomicBoolean(false);
    private final LlmClient client = new LlmClient();

    private Thread worker;
    private volatile String goal = "";
    private volatile int consecutiveFailures;
    private volatile String lastError;
    private volatile long roundsCompleted;
    private volatile long lastRoundMs;
    private volatile boolean pausedByFailures;

    /** 最近一次对话的精简记录，供调试接口查看。 */
    private final List<String> trace = new ArrayList<>();

    private static Agent INSTANCE;

    public static Agent get() {
        if (INSTANCE == null) {
            synchronized (Agent.class) {
                if (INSTANCE == null) INSTANCE = new Agent();
            }
        }
        return INSTANCE;
    }

    private Agent() {}

    // ==================================================================
    // 生命周期
    // ==================================================================

    /** 设置当前目标（人类通过 API 或聊天下达）。 */
    public void setGoal(String g) {
        this.goal = g == null ? "" : g.trim();
        this.consecutiveFailures = 0;
        this.pausedByFailures = false;
        log("goal <- " + goal);
    }

    public String goal() { return goal; }

    public void start() {
        if (!Env.bridgeEnabled()) {
            lastError = "手机启动器环境已禁用 AI 驱动";
            return;
        }
        if (running.getAndSet(true)) {
            return;
        }
        worker = new Thread(this::loop, "mcai-agent");
        worker.setDaemon(true);
        worker.start();
        log("agent started, mode=" + Config.get().mode);
    }

    public void stop() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        log("agent stopped");
    }

    public boolean isRunning() { return running.get(); }
    public boolean isRoundInFlight() { return roundInFlight.get(); }
    public long roundsCompleted() { return roundsCompleted; }
    public long lastRoundMs() { return lastRoundMs; }
    public String lastError() { return lastError; }
    public List<String> trace() { return List.copyOf(trace); }

    /** 手动触发一轮（不等定时器）。 */
    public Json.Obj runOnce() {
        if (!Env.bridgeEnabled()) {
            return Json.obj().put("ok", false).put("error", Env.reason());
        }
        if (!roundInFlight.compareAndSet(false, true)) {
            return Json.obj().put("ok", false).put("error", "上一轮尚未结束");
        }
        try {
            return round();
        } finally {
            roundInFlight.set(false);
        }
    }

    // ==================================================================
    // 主循环
    // ==================================================================

    private void loop() {
        Config c = Config.get();
        while (running.get()) {
            try {
                if (pausedByFailures) {
                    Thread.sleep(c.decisionIntervalMs * 4);
                    continue;
                }
                if (goal == null || goal.isBlank()) {
                    Thread.sleep(500);
                    continue;
                }
                // 上一个动作还没做完时不打断它——这是"持久操作"的关键
                if (Actuator.get().isBusy()) {
                    Thread.sleep(250);
                    continue;
                }
                if (!roundInFlight.compareAndSet(false, true)) {
                    Thread.sleep(200);
                    continue;
                }
                try {
                    round();
                } finally {
                    roundInFlight.set(false);
                }
                Thread.sleep(c.decisionIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                lastError = String.valueOf(t);
                log("loop error: " + t);
                sleepQuietly(2000);
            }
        }
    }

    /** 执行一轮完整决策。 */
    private Json.Obj round() {
        long t0 = System.currentTimeMillis();
        Config c = Config.get();
        try {
            // 1) 新鲜观察（必须在主线程读游戏状态，走 Tools.invoke）
            String obsJson = com.kongbai.mcai.bridge.Tools.invoke("observe", Map.of());
            Map<String, Object> obs = Json.parseObj(obsJson);
            boolean ok = Json.bool(obs, "ok", false);
            if (!ok) {
                // 不在游戏里：不算失败，安静等待
                lastError = "observe failed: " + Json.str(obs, "error", "unknown");
                return Json.obj().put("ok", false).put("skipped", true)
                        .put("reason", lastError);
            }

            // 2) 组装消息
            List<Map<String, Object>> messages = new ArrayList<>();
            if (!c.isHarnessMode()) {
                messages.add(LlmClient.system(systemPrompt()));
            }
            String userText = c.isHarnessMode()
                    ? harnessTurn(obs)
                    : standardTurn(obs);
            messages.add(LlmClient.user(userText));

            List<Map<String, Object>> tools = com.kongbai.mcai.bridge.Tools.schemas();

            // 3) 多步工具调用
            int steps = 0;
            while (steps++ < MAX_STEPS_PER_ROUND) {
                LlmClient.Reply reply = client.chat(messages, tools);

                if (reply.toolCalls().isEmpty()) {
                    // 没有工具调用 = 本轮结束（模型在陈述或已完成）
                    consecutiveFailures = 0;
                    roundsCompleted++;
                    log("round done, content=" + trim(reply.content(), 120));
                    return Json.obj().put("ok", true)
                            .put("steps", steps - 1)
                            .put("finished", true)
                            .put("content", trim(reply.content(), 500));
                }

                // assistant 消息（带 tool_calls）必须原样回填，否则模型会"失忆"
                List<Map<String, Object>> calls = new ArrayList<>();
                for (LlmClient.ToolCall tc : reply.toolCalls()) {
                    Map<String, Object> fn = new java.util.LinkedHashMap<>();
                    fn.put("id", tc.id());
                    fn.put("type", "function");
                    Map<String, Object> f = new java.util.LinkedHashMap<>();
                    f.put("name", tc.name());
                    f.put("arguments", tc.arguments());
                    fn.put("function", f);
                    calls.add(fn);
                }
                String assistantContent = reply.content() == null ? "" : reply.content();
                messages.add(new java.util.LinkedHashMap<>(assistantMsg(assistantContent, calls)));

                // 4) 逐个执行并把结果回灌
                for (LlmClient.ToolCall tc : reply.toolCalls()) {
                    Map<String, Object> args = safeArgs(tc.arguments());
                    String result = com.kongbai.mcai.bridge.Tools.invoke(tc.name(), args);
                    log("tool " + tc.name() + " -> " + trim(result, 160));
                    messages.add(LlmClient.toolResult(tc.id(), tc.name(),
                            result == null ? "{\"ok\":false,\"error\":\"timeout\"}" : result));

                    // finish 工具 = 显式结束
                    if ("finish".equals(tc.name())) {
                        consecutiveFailures = 0;
                        roundsCompleted++;
                        return Json.obj().put("ok", true)
                                .put("steps", steps)
                                .put("finished", true)
                                .put("content", result);
                    }
                }
                // 执行完工具后，下一步决策前补一次新观察
                String fresh = com.kongbai.mcai.bridge.Tools.invoke("observe", Map.of());
                Map<String, Object> freshObs = Json.parseObj(fresh);
                if (Json.bool(freshObs, "ok", false)) {
                    messages.add(LlmClient.user(c.isHarnessMode()
                            ? harnessFollowUp(freshObs)
                            : standardFollowUp(freshObs)));
                }
            }
            roundsCompleted++;
            return Json.obj().put("ok", true).put("steps", MAX_STEPS_PER_ROUND)
                    .put("note", "达到单轮步数上限，交给下一轮继续");

        } catch (Throwable t) {
            consecutiveFailures++;
            lastError = String.valueOf(t);
            log("round error: " + t);
            if (consecutiveFailures >= Config.get().maxConsecutiveFailures) {
                pausedByFailures = true;
                log("连续失败过多，自主循环已暂停");
            }
            return Json.obj().put("ok", false).put("error", lastError)
                    .put("consecutive_failures", consecutiveFailures);
        } finally {
            lastRoundMs = System.currentTimeMillis() - t0;
        }
    }

    // ==================================================================
    // 提示词
    // ==================================================================

    private String systemPrompt() {
        return """
                你在操控一个真实的 Minecraft 26.2 客户端，目标是完成人类交给你的任务。

                工作方式：
                1. 你会拿到一份当前世界的真实快照（observe 工具）。所有判断都要基于它，不要凭空想象。
                2. 你通过工具下达"意图"，本地执行器会把意图持续执行若干 tick —— 你不需要每帧操作。
                3. 动作发起后，用 observe 或 action_status 看进展，不要重复发起同一个动作。
                4. 拿不准就先 observe；发现正在做的事是错的，先 stop_all 再重新规划。

                必须遵守：
                - 坐标是整数格坐标。距离用 observe 返回的 distance 字段，不要自己猜。
                - 血量低于 40% 或敌人多于 2 个时，优先 flee 保命，不要硬拼。
                - 在服务器上不要瞬移、不要瞬间转向、不要恒定频率攻击 —— 这会被判定为脚本并被拉回。
                  如果发现走过去又被弹回来，调用 anticheat_tune 放慢动作。
                - 没有的工具不要编造；某个能力不可用时（如未安装 Baritone），
                  工具会明确告诉你，此时改用其他可用手段或调用 finish 说明情况。
                - 任务完成或无法继续时，调用 finish 并写明原因。
                """;
    }

    private String standardTurn(Map<String, Object> obs) {
        return "当前目标：" + goal + "\n\n当前世界快照：\n" + Json.write(obs);
    }

    private String standardFollowUp(Map<String, Object> obs) {
        return "执行后的新状态：\n" + Json.write(obs)
                + "\n\n继续推进目标：" + goal;
    }

    /** harness 模式：零提示词，只给事实。工具描述自带语义。 */
    private String harnessTurn(Map<String, Object> obs) {
        return "goal=" + goal + "\nobservation=" + Json.write(obs);
    }

    private String harnessFollowUp(Map<String, Object> obs) {
        return "observation=" + Json.write(obs);
    }

    private Map<String, Object> assistantMsg(String content, List<Map<String, Object>> calls) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("role", "assistant");
        m.put("content", content == null ? "" : content);
        m.put("tool_calls", calls);
        return m;
    }

    // ==================================================================
    // 工具方法
    // ==================================================================

    private Map<String, Object> safeArgs(String raw) {
        try {
            if (raw == null || raw.isBlank()) {
                return Map.of();
            }
            Map<String, Object> m = Json.parseObj(raw);
            return m == null ? Map.of() : m;
        } catch (Throwable t) {
            return Map.of();
        }
    }

    private void log(String s) {
        synchronized (trace) {
            trace.add(System.currentTimeMillis() % 100000 + " | " + trim(s, 300));
            if (trace.size() > 200) {
                trace.subList(0, 100).clear();
            }
        }
    }

    public void clearTrace() {
        synchronized (trace) {
            trace.clear();
        }
    }

    private static String trim(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
