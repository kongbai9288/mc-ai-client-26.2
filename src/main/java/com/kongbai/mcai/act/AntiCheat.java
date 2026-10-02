package com.kongbai.mcai.act;

import com.kongbai.mcai.config.Config;
import com.kongbai.mcai.util.Json;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * 拟人化 + 反作弊拉回自适应。
 *
 * <h3>为什么需要它</h3>
 * 在真实服务器上（尤其是带反插件的），AI 的"机械完美"动作本身就是特征：
 * <ul>
 *   <li>攻击间隔恒定 5 tick —— 人类做不到；</li>
 *   <li>视角一 tick 转 180° —— 瞬移瞄准；</li>
 *   <li>连续 200 次操作零抖动 —— 脚本。</li>
 * </ul>
 * 服务器不会直接封你，而是<b>拉回</b>（rubber-band / setback）：你的动作被撤销，
 * 表现为"走过去又被弹回来"。AI 如果不知道这件事，会以为自己没走到，
 * 于是反复重试，越试越像机器人。
 *
 * <h3>本模块做的三件事</h3>
 * <ol>
 *   <li><b>拟人化</b>：攻击间隔随机化、视角限速、动作抖动；</li>
 *   <li><b>拉回检测</b>：识别"我明明往前走了，位置却被服务器拽回去"；</li>
 *   <li><b>自适应降速</b>：拉回越多，动作越保守，直到稳定为止。</li>
 * </ol>
 *
 * <p>对外暴露为 AI 可调用的 {@code anticheat_tune} 工具，AI 可以主动调参。
 */
public final class AntiCheat {

    // ---- 可调参数（AI 可以通过 anticheat_tune 修改）----
    private volatile int attackMinTicks;
    private volatile int attackMaxTicks;
    private volatile float lookStepDegrees;
    private volatile int jitterEvery;
    /** 全局节流倍率：1.0 正常，>1 表示主动放慢。 */
    private volatile double throttle;
    /** 是否允许连续挖/放（关闭后每次之间强制间隔）。 */
    private volatile boolean humanizeBreakPlace = true;

    // ---- 运行状态 ----
    private long lastAttackTick = -1000;
    private long lastActionTick = -1000;
    private int actionCounter;
    private int setbackCount;
    private long lastSetbackTick = -1000;

    private Vec3 lastPos;
    private long lastPosTick;

    private static AntiCheat INSTANCE;

    public static AntiCheat get() {
        if (INSTANCE == null) {
            synchronized (AntiCheat.class) {
                if (INSTANCE == null) {
                    INSTANCE = new AntiCheat();
                }
            }
        }
        return INSTANCE;
    }

    private AntiCheat() {
        Config c = Config.get();
        this.attackMinTicks = c.attackMinIntervalTicks;
        this.attackMaxTicks = c.attackMaxIntervalTicks;
        this.lookStepDegrees = c.lookStepDegrees;
        this.jitterEvery = c.jitterEvery;
        this.throttle = 1.0;
    }

    // ==================================================================
    // 攻击节流
    // ==================================================================

    /**
     * 现在是否允许攻击。
     * 间隔在 [min, max] 之间随机，模拟人类手速的抖动。
     */
    public boolean canAttack(long tick) {
        if (tick - lastAttackTick < attackMinTicks) {
            return false;
        }
        if (tick - lastAttackTick < attackMinTicks + rand(attackMaxTicks - attackMinTicks + 1)) {
            return false;
        }
        return true;
    }

    public void markAttack(long tick) {
        lastAttackTick = tick;
    }

    /** 攻击冷却剩余 tick。 */
    public long attackCooldown(long tick) {
        long wait = attackMinTicks + rand(Math.max(0, attackMaxTicks - attackMinTicks + 1));
        long left = wait - (tick - lastAttackTick);
        return Math.max(0, left);
    }

    // ==================================================================
    // 通用动作节流
    // ==================================================================

    /** 通用动作（放置 / 使用 / 切换）是否该插入额外等待。 */
    public boolean shouldWait(long tick) {
        if (jitterEvery <= 0) {
            return false;
        }
        if (lastActionTick >= 0 && tick - lastActionTick < 2) {
            return true;
        }
        actionCounter++;
        if (actionCounter % jitterEvery == 0) {
            // 每 N 次操作插入一次 1~3 tick 的停顿
            return (tick - lastActionTick) < (2 + rand(2));
        }
        return false;
    }

    public void markAction(long tick) {
        lastActionTick = tick;
    }

    // ==================================================================
    // 视角限速
    // ==================================================================

    /**
     * 把目标角度限制到单 tick 最大可转角度内，返回本 tick 实际应转到的角度。
     *
     * @param current 当前角度
     * @param target  目标角度
     * @return 本 tick 允许到达的角度
     */
    public float stepAngle(float current, float target) {
        float max = lookStepDegrees / (float) Math.max(1.0, throttle);
        float diff = normalizeAngle(target - current);
        if (Math.abs(diff) <= max) {
            return target;
        }
        return current + Math.signum(diff) * max;
    }

    private static float normalizeAngle(float a) {
        while (a > 180.0F) a -= 360.0F;
        while (a < -180.0F) a += 360.0F;
        return a;
    }

    // ==================================================================
    // 拉回检测
    // ==================================================================

    /**
     * 每 tick 调用，检测是否被服务器拉回。
     *
     * <p>判定：玩家处于"我们让它移动"的状态，但位移方向与预期相反或几乎为零，
     * 且位移幅度超过阈值 —— 这是典型的 setback 特征。
     *
     * @return 本次是否判定为拉回
     */
    public boolean tickPosition(LocalPlayer player, long tick) {
        if (player == null) {
            return false;
        }
        Vec3 now = player.position();
        if (lastPos == null) {
            lastPos = now;
            lastPosTick = tick;
            return false;
        }
        boolean setback = false;
        long dt = Math.max(1, tick - lastPosTick);
        if (dt <= 20) { // 只在 1 秒窗口内判定，避免跨维度误判
            double moved = now.distanceTo(lastPos);
            double horizontal = Math.hypot(now.x - lastPos.x, now.z - lastPos.z);
            // 垂直方向大幅回落 + 水平几乎不动 = 被拉回
            boolean verticalSnap = (lastPos.y - now.y) > 0.5 && horizontal < 0.5;
            // 反向弹回：位移方向与我们记录的移动意图相反且距离大
            boolean longSnap = moved > 1.5 && dt <= 3;
            if (verticalSnap || longSnap) {
                setback = true;
                setbackCount++;
                lastSetbackTick = tick;
                // 每次拉回都把节流拉紧一档
                throttle = Math.min(4.0, throttle + 0.35);
            }
        } else if (tick - lastSetbackTick > 100) {
            // 长时间没被拉回，缓慢放松节流
            throttle = Math.max(1.0, throttle - 0.02);
        }
        lastPos = now;
        lastPosTick = tick;
        return setback;
    }

    public int setbackCount() { return setbackCount; }
    public double throttle() { return throttle; }

    public void resetSetbacks() {
        setbackCount = 0;
        throttle = 1.0;
    }

    // ==================================================================
    // AI 可调参数（anticheat_tune 工具）
    // ==================================================================

    /**
     * 调整参数。
     *
     * @param args 可含：attack_min_ticks / attack_max_ticks / look_step_degrees /
     *             jitter_every / throttle / humanize_break_place / reset
     * @return 调整后的完整状态
     */
    public Json.Obj tune(Map<String, Object> args) {
        if (args == null) {
            return status();
        }
        if (Json.bool(args, "reset", false)) {
            Config c = Config.get();
            attackMinTicks = c.attackMinIntervalTicks;
            attackMaxTicks = c.attackMaxIntervalTicks;
            lookStepDegrees = c.lookStepDegrees;
            jitterEvery = c.jitterEvery;
            throttle = 1.0;
            setbackCount = 0;
            return status();
        }
        int minTicks = Json.integer(args, "attack_min_ticks", -1);
        if (minTicks >= 0) attackMinTicks = Math.max(1, minTicks);
        int maxTicks = Json.integer(args, "attack_max_ticks", -1);
        if (maxTicks >= 0) attackMaxTicks = Math.max(attackMinTicks, maxTicks);
        float look = (float) Json.dbl(args, "look_step_degrees", -1);
        if (look > 0) lookStepDegrees = Math.min(180.0F, look);
        int jitter = Json.integer(args, "jitter_every", -1);
        if (jitter >= 0) jitterEvery = jitter;
        double th = Json.dbl(args, "throttle", -1);
        if (th > 0) throttle = Math.min(8.0, Math.max(0.5, th));
        if (args.containsKey("humanize_break_place")) {
            humanizeBreakPlace = Json.bool(args, "humanize_break_place", humanizeBreakPlace);
        }
        return status();
    }

    public Json.Obj status() {
        Json.Obj o = Json.obj();
        o.put("ok", true);
        o.put("attack_min_ticks", attackMinTicks);
        o.put("attack_max_ticks", attackMaxTicks);
        o.put("look_step_degrees", lookStepDegrees);
        o.put("jitter_every", jitterEvery);
        o.put("throttle", Math.round(throttle * 100.0) / 100.0);
        o.put("humanize_break_place", humanizeBreakPlace);
        o.put("setback_count", setbackCount);
        o.put("last_setback_tick", lastSetbackTick);
        o.put("hint", setbackCount > 0
                ? "检测到被服务器拉回，已自动降速。可调大 attack_min_ticks 或 throttle 进一步放慢。"
                : "未检测到拉回，当前速率正常。");
        return o;
    }

    /** 建议 AI 在某个服务器该用什么参数。用于给出可读的调参建议。 */
    public Json.Obj recommend() {
        Json.Obj o = Json.obj();
        if (setbackCount == 0) {
            o.put("level", "normal");
            o.put("attack_min_ticks", 9);
            o.put("look_step_degrees", 18.0);
        } else if (setbackCount < 5) {
            o.put("level", "cautious");
            o.put("attack_min_ticks", 12);
            o.put("look_step_degrees", 12.0);
        } else {
            o.put("level", "strict");
            o.put("attack_min_ticks", 16);
            o.put("look_step_degrees", 8.0);
        }
        o.put("reason", "基于已检测到的拉回次数 " + setbackCount);
        return o;
    }

    private static int rand(int bound) {
        return bound <= 0 ? 0 : (int) (Math.random() * bound);
    }
}
