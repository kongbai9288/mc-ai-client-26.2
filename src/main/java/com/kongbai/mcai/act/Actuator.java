package com.kongbai.mcai.act;

import com.kongbai.mcai.util.Json;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 动作执行层：把 AI 的"意图"翻译成持续若干 tick 的真实操作。
 *
 * <p><b>这是整个架构的核心。</b>AI 只发一次"去 100,64,100"，
 * 之后几百 tick 的移动、跳跃、挖方块全部在这里本地完成，不再打扰 AI。
 *
 * <p>每个动作都有明确的生命周期：
 * <pre>
 *   idle → running（每 tick 推进）→ done / failed / timeout
 * </pre>
 * 执行结果会被记录，下一次观察时回传给 AI，形成闭环。
 */
public final class Actuator {

    public enum Kind { NONE, GOTO, MINE, ATTACK, FOLLOW, FLEE, WAIT }

    private Kind kind = Kind.NONE;
    private String label = "";
    private long startedTick;
    private long deadlineTick;
    private int targetX, targetY, targetZ;
    private Entity attackTarget;
    private String lastError;
    private int ticksStuck;
    private Vec3 lastPos;

    /** 移动意图由 tick() 写进 input。 */
    private final AiInput input = new AiInput();
    private boolean inputInstalled;

    private float targetYaw;
    private float targetPitch;
    private boolean lookActive;

    private static Actuator INSTANCE;

    public static Actuator get() {
        if (INSTANCE == null) {
            synchronized (Actuator.class) {
                if (INSTANCE == null) INSTANCE = new Actuator();
            }
        }
        return INSTANCE;
    }

    private Actuator() {}

    // ==================================================================
    // 每 tick 推进（由 MixinMinecraftTick 调用）
    // ==================================================================

    public void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            return;
        }
        long tick = level.getGameTime();

        installInput(player);

        // 拉回检测
        AntiCheat.get().tickPosition(player, tick);

        if (kind == Kind.NONE) {
            input.active = false;
            input.clear();
            return;
        }

        // 超时保护：任何动作都有上限，杜绝"卡死在某个意图上"
        if (tick > deadlineTick) {
            finish(false, "timeout");
            return;
        }

        input.active = true;
        input.clear();

        switch (kind) {
            case GOTO -> tickGoto(player, tick);
            case FLEE -> tickFlee(player, tick);
            case ATTACK -> tickAttack(mc, player, tick);
            case WAIT -> { /* 什么都不做，等时间到 */ }
            default -> finish(false, "unsupported_kind");
        }

        // 视角平滑：每 tick 只允许转一定角度
        if (lookActive) {
            float yaw = AntiCheat.get().stepAngle(player.getYRot(), targetYaw);
            float pitch = AntiCheat.get().stepAngle(player.getXRot(), targetPitch);
            player.setYRot(yaw);
            player.setXRot(pitch);
        }
    }

    private void installInput(LocalPlayer player) {
        if (!inputInstalled && player.input != input) {
            player.input = input;
            inputInstalled = true;
        }
    }

    private void tickGoto(LocalPlayer player, long tick) {
        double dx = (targetX + 0.5) - player.getX();
        double dz = (targetZ + 0.5) - player.getZ();
        double distSq = dx * dx + dz * dz;

        if (distSq < 1.5 * 1.5 && Math.abs(player.getY() - targetY) <= 2.0) {
            finish(true, "arrived");
            return;
        }

        face(player, dx, dz);
        input.forward = true;
        input.sprint = distSq > 36; // 远了才冲刺

        // 卡住检测：位置几乎没变且在移动 → 尝试跳跃（可能被台阶/坑挡住）
        Vec3 now = player.position();
        if (lastPos != null && now.distanceTo(lastPos) < 0.02) {
            ticksStuck++;
            if (ticksStuck > 5) {
                input.jump = true;
            }
            if (ticksStuck > 60) {
                finish(false, "stuck");
                return;
            }
        } else {
            ticksStuck = 0;
        }
        lastPos = now;
    }

    private void tickFlee(LocalPlayer player, long tick) {
        // 逃离：背对目标方向跑
        double dx = player.getX() - (targetX + 0.5);
        double dz = player.getZ() - (targetZ + 0.5);
        double d = Math.hypot(dx, dz);
        if (d > 24) {
            finish(true, "escaped");
            return;
        }
        face(player, dx, dz);
        input.forward = true;
        input.sprint = true;
        // 血量低时不跳（避免摔伤），否则保持跳跃跨障碍
        if (player.getHealth() > 10 && player.onGround() && (tick % 12 == 0)) {
            input.jump = true;
        }
    }

    private void tickAttack(Minecraft mc, LocalPlayer player, long tick) {
        if (attackTarget == null || !attackTarget.isAlive()
                || attackTarget.distanceTo(player) > 6.0) {
            finish(true, "target_gone");
            return;
        }
        // 看向目标
        Vec3 tp = attackTarget.position();
        double dx = tp.x - player.getX();
        double dy = (tp.y + attackTarget.getEyeHeight() * 0.6) - (player.getY() + player.getEyeHeight());
        double dz = tp.z - player.getZ();
        double horiz = Math.hypot(dx, dz);
        targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0F);
        targetPitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        lookActive = true;

        // 距离太远就走近
        if (horiz > 3.2) {
            face(player, dx, dz);
            input.forward = true;
            return;
        }

        // 冷却到了才挥刀
        if (AntiCheat.get().canAttack(tick)) {
            mc.gameMode.attack(player, attackTarget);
            player.swing(InteractionHand.MAIN_HAND);
            AntiCheat.get().markAttack(tick);
            AntiCheat.get().markAction(tick);
        }
    }

    private void face(LocalPlayer player, double dx, double dz) {
        targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0F);
        lookActive = true;
        // 让 yaw 走最短路径，避免绕远路转圈
        targetYaw = player.getYRot() + normalizeAngle(targetYaw - player.getYRot());
    }

    private static float normalizeAngle(float a) {
        while (a > 180.0F) a -= 360.0F;
        while (a < -180.0F) a += 360.0F;
        return a;
    }

    // ==================================================================
    // 动作入口（由工具层调用）
    // ==================================================================

    /** 前往坐标。有 Baritone 优先用 Baritone（能挖能搭桥），否则用内置直线导航。 */
    public Json.Obj goTo(Minecraft mc, int x, int y, int z, long timeoutTicks) {
        if (BaritoneLink.available()) {
            boolean ok = BaritoneLink.run("goto", x + " " + y + " " + z);
            if (ok) {
                kind = Kind.WAIT;
                label = "baritone:goto " + x + " " + y + " " + z;
                startedTick = nowTick(mc);
                deadlineTick = startedTick + timeoutTicks;
                return ack("goto", true, "已交给 Baritone 寻路（会自己挖/搭桥/爬）",
                        Json.obj().put("engine", "baritone").put("x", x).put("y", y).put("z", z));
            }
        }
        start(Kind.GOTO, "goto " + x + " " + y + " " + z, timeoutTicks, mc);
        targetX = x; targetY = y; targetZ = z;
        lastPos = null;
        ticksStuck = 0;
        return ack("goto", true, "使用内置导航（直线，遇障碍会跳）",
                Json.obj().put("engine", "builtin").put("x", x).put("y", y).put("z", z));
    }

    /** 挖指定方块。优先 Baritone（会自动寻路过去挖）。 */
    public Json.Obj mine(Minecraft mc, String blockId, int count, long timeoutTicks) {
        if (BaritoneLink.available()) {
            boolean ok = BaritoneLink.run("mine", count > 0 ? count + " " + blockId : blockId);
            if (ok) {
                kind = Kind.WAIT;
                label = "baritone:mine " + blockId;
                startedTick = nowTick(mc);
                deadlineTick = startedTick + timeoutTicks;
                return ack("mine", true, "已交给 Baritone", Json.obj()
                        .put("engine", "baritone").put("block", blockId).put("count", count));
            }
        }
        return ack("mine", false, "内置挖矿尚未实现，请先安装 Baritone（26.2 分支）", null);
    }

    /** 攻击指定实体（按 UUID 前 8 位匹配，避免 AI 抄错长 UUID）。 */
    public Json.Obj attack(Minecraft mc, String uuidPrefix, long timeoutTicks) {
        Entity target = findEntity(mc, uuidPrefix);
        if (target == null) {
            return ack("attack", false, "找不到该实体（它可能已死亡或超出范围）", null);
        }
        start(Kind.ATTACK, "attack " + uuidPrefix, timeoutTicks, mc);
        attackTarget = target;
        return ack("attack", true, "开始攻击", Json.obj()
                .put("uuid", uuidPrefix)
                .put("type", target.getType().toString())
                .put("distance", round(target.distanceTo(mc.player))));
    }

    /** 远离指定坐标。 */
    public Json.Obj flee(Minecraft mc, int x, int y, int z, long timeoutTicks) {
        start(Kind.FLEE, "flee", timeoutTicks, mc);
        targetX = x; targetY = y; targetZ = z;
        return ack("flee", true, "开始逃离", Json.obj().put("from_x", x).put("from_z", z));
    }

    /** 右键使用（对着准星或空手）。 */
    public Json.Obj use(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null) {
            return ack("use", false, "不在游戏中", null);
        }
        AntiCheat.get().markAction(nowTick(mc));
        mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        p.swing(InteractionHand.MAIN_HAND);
        return ack("use", true, "已使用主手物品", null);
    }

    /** 在指定位置的指定面放方块。 */
    public Json.Obj place(Minecraft mc, int x, int y, int z, String face) {
        LocalPlayer p = mc.player;
        ClientLevel lv = mc.level;
        if (p == null || lv == null || mc.gameMode == null) {
            return ack("place", false, "不在游戏中", null);
        }
        BlockPos bp = new BlockPos(x, y, z);
        if (!lv.isLoaded(bp)) {
            return ack("place", false, "目标位置所在区块未加载", null);
        }
        Direction dir = parseFace(face);
        if (dir == null) {
            return ack("place", false, "face 参数无效，应为 up/down/north/south/east/west", null);
        }
        BlockState st = lv.getBlockState(bp);
        if (st.isAir()) {
            return ack("place", false, "目标位置是空气，无法贴着它放置", null);
        }
        // 精确的命中点：位置中心 + 面偏移，模拟真实右键
        Vec3 hit = Vec3.atCenterOf(bp).add(
                new Vec3(dir.getStepX(), dir.getStepY(), dir.getStepZ()).scale(0.5));
        BlockHitResult bhr = new BlockHitResult(hit, dir, bp, false);
        AntiCheat.get().markAction(nowTick(mc));
        var result = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, bhr);
        return ack("place", true, "已尝试放置", Json.obj().put("at", x + "," + y + "," + z)
                .put("face", dir.getName()).put("result", String.valueOf(result)));
    }

    /** 挖掉指定位置（原地挖，不走寻路）。 */
    public Json.Obj breakBlock(Minecraft mc, int x, int y, int z, long timeoutTicks) {
        LocalPlayer p = mc.player;
        ClientLevel lv = mc.level;
        if (p == null || lv == null || mc.gameMode == null) {
            return ack("break", false, "不在游戏中", null);
        }
        BlockPos bp = new BlockPos(x, y, z);
        if (!lv.isLoaded(bp)) {
            return ack("break", false, "区块未加载", null);
        }
        if (p.position().distanceTo(Vec3.atCenterOf(bp)) > 5.0) {
            return ack("break", false, "距离太远（需 ≤5 格），请先靠近", null);
        }
        if (lv.getBlockState(bp).isAir()) {
            return ack("break", false, "该位置已经是空气", null);
        }
        mc.gameMode.startDestroyBlock(bp, Direction.UP);
        AntiCheat.get().markAction(nowTick(mc));
        return ack("break", true, "已开始挖掘（需要持续按住，AI 请随后再次观察确认）",
                Json.obj().put("at", x + "," + y + "," + z));
    }

    /** 松开挖掘键。 */
    public Json.Obj stopBreak(Minecraft mc) {
        if (mc.gameMode != null) {
            mc.gameMode.stopDestroyBlock();
        }
        return ack("stop_break", true, "已停止挖掘", null);
    }

    /** 切换快捷栏。 */
    public Json.Obj selectSlot(Minecraft mc, int slot) {
        LocalPlayer p = mc.player;
        if (p == null) {
            return ack("select_slot", false, "不在游戏中", null);
        }
        if (slot < 0 || slot > 8) {
            return ack("select_slot", false, "slot 必须在 0..8", null);
        }
        p.getInventory().setSelectedSlot(slot);
        AntiCheat.get().markAction(nowTick(mc));
        return ack("select_slot", true, "已切换到快捷栏 " + slot,
                Json.obj().put("slot", slot)
                        .put("item", p.getInventory().getItem(slot).getItem().toString()));
    }

    /** 停止一切动作。 */
    public Json.Obj stop(Minecraft mc) {
        BaritoneLink.stop();
        kind = Kind.NONE;
        input.clear();
        input.active = false;
        lookActive = false;
        attackTarget = null;
        lastError = null;
        if (mc.gameMode != null) {
            mc.gameMode.stopDestroyBlock();
        }
        return ack("stop", true, "已停止全部动作", null);
    }

    /** 让 AI 等待若干 tick。 */
    public Json.Obj wait(Minecraft mc, long ticks) {
        start(Kind.WAIT, "wait", Math.max(1, ticks), mc);
        return ack("wait", true, "等待中", Json.obj().put("ticks", ticks));
    }

    // ==================================================================
    // 状态
    // ==================================================================

    public Json.Obj status(Minecraft mc) {
        Json.Obj o = Json.obj();
        o.put("ok", true);
        o.put("kind", kind.name());
        o.put("label", label);
        boolean running = kind != Kind.NONE;
        o.put("running", running);
        if (running) {
            long now = nowTick(mc);
            o.put("elapsed_ticks", now - startedTick);
            o.put("remaining_ticks", Math.max(0, deadlineTick - now));
        }
        o.put("last_error", lastError);
        o.put("baritone_active", BaritoneLink.isActive());
        o.put("stuck_ticks", ticksStuck);
        return o;
    }

    public boolean isBusy() {
        return kind != Kind.NONE;
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private void start(Kind k, String lbl, long timeout, Minecraft mc) {
        this.kind = k;
        this.label = lbl;
        this.startedTick = nowTick(mc);
        this.deadlineTick = startedTick + Math.max(1, timeout);
        this.lastError = null;
        this.ticksStuck = 0;
        this.lastPos = null;
        this.attackTarget = null;
    }

    private void finish(boolean ok, String reason) {
        this.kind = Kind.NONE;
        this.lastError = ok ? null : reason;
        this.input.clear();
        this.input.active = false;
        this.lookActive = false;
        this.attackTarget = null;
    }

    private long nowTick(Minecraft mc) {
        return mc.level == null ? 0 : mc.level.getGameTime();
    }

    private Entity findEntity(Minecraft mc, String prefix) {
        if (mc.level == null || mc.player == null || prefix == null) {
            return null;
        }
        Entity found = null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!e.isAlive() || e == mc.player) {
                continue;
            }
            String full = e.getUUID().toString();
            if (full.startsWith(prefix) || prefix.equalsIgnoreCase(e.getName().getString())) {
                if (found == null || e.distanceTo(mc.player) < found.distanceTo(mc.player)) {
                    found = e;
                }
            }
        }
        return found;
    }

    private Direction parseFace(String face) {
        if (face == null) return Direction.UP;
        return switch (face.toLowerCase()) {
            case "up" -> Direction.UP;
            case "down" -> Direction.DOWN;
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> null;
        };
    }

    private Json.Obj ack(String action, boolean ok, String msg, Json.Obj data) {
        Json.Obj o = Json.obj();
        o.put("ok", ok);
        o.put("action", action);
        o.put("message", msg);
        if (data != null) {
            o.put("data", data);
        }
        return o;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
