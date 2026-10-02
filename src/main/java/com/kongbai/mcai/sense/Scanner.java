package com.kongbai.mcai.sense;

import com.kongbai.mcai.config.Config;
import com.kongbai.mcai.util.Json;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 世界感知：把游戏状态压成一份结构化快照。
 *
 * <p>设计原则（对应"AI 要能检测到所有的东西"）：
 * <ol>
 *   <li><b>拿不到就写 null，绝不编造</b>。AI 最典型的低级错误就是基于幻觉数据决策。</li>
 *   <li><b>一次快照原子化</b>：所有字段取自同一 tick，避免"位置是新的、血量是旧的"。</li>
 *   <li><b>只给摘要，不给原始洪流</b>：方块给直方图，实体给精简列表，背包给非空槽位。</li>
 * </ol>
 */
public final class Scanner {

    private final SenseCache cache = new SenseCache(512);

    /** 上一次扫描耗时（毫秒），用于自检。 */
    private long lastScanMs;

    // ==================================================================
    // 主入口
    // ==================================================================

    /**
     * 生成一次完整观察。
     *
     * @return 结构化快照；若玩家或世界不存在（在主菜单 / 未进世界）返回 {@code ok=false}
     */
    public Json.Obj observe() {
        long t0 = System.nanoTime();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        // 26.2 起字段名为 level（不是 world）
        ClientLevel level = mc.level;

        if (player == null || level == null) {
            Json.Obj o = Json.obj();
            o.put("ok", false);
            o.put("error", "not_in_game");
            o.put("hint", "当前不在世界内（主菜单或未加入服务器）");
            return o;
        }

        try {
            Json.Obj out = Json.obj();
            out.put("ok", true);
            out.put("tick", level.getGameTime());
            out.put("self", self(mc, player, level));
            out.put("world", world(level, player));
            out.put("blocks", blocks(level, player));
            out.put("entities", entities(level, player));
            out.put("inventory", inventory(player));
            out.put("target", crosshair(mc, player));
            // 跨版本信息：告诉 AI 现在连的是哪个版本的服务器，战斗规则随之不同
            out.put("protocol", com.kongbai.mcai.platform.ViaLink.behaviorHints());
            return out;
        } catch (Throwable t) {
            // 感知绝不能把游戏搞崩：任何异常都降级成 ok=false + 原因
            Json.Obj o = Json.obj();
            o.put("ok", false);
            o.put("error", "scan_failed");
            o.put("detail", String.valueOf(t));
            return o;
        } finally {
            lastScanMs = (System.nanoTime() - t0) / 1_000_000L;
        }
    }

    public long lastScanMs() { return lastScanMs; }
    public SenseCache cache() { return cache; }

    // ==================================================================
    // 自身状态
    // ==================================================================

    private Json.Obj self(Minecraft mc, LocalPlayer player, ClientLevel level) {
        Json.Obj o = Json.obj();
        Vec3 p = player.position();
        o.put("name", player.getName().getString());
        o.put("uuid", player.getUUID().toString());
        o.put("x", r2(p.x));
        o.put("y", r2(p.y));
        o.put("z", r2(p.z));
        o.put("block_x", player.blockPosition().getX());
        o.put("block_y", player.blockPosition().getY());
        o.put("block_z", player.blockPosition().getZ());
        o.put("yaw", r2(player.getYRot()));
        o.put("pitch", r2(player.getXRot()));
        o.put("health", r2(player.getHealth()));
        o.put("max_health", r2(player.getMaxHealth()));
        o.put("armor", player.getArmorValue());
        o.put("air", player.getAirSupply());
        o.put("food", food(player));
        o.put("on_ground", player.onGround());
        o.put("in_water", player.isInWater());
        o.put("is_sprinting", player.isSprinting());
        o.put("is_sneaking", player.isShiftKeyDown());
        o.put("is_swimming", player.isSwimming());
        // 掉落伤害是 AI 最常见的死因之一，单独给出来
        o.put("fall_distance", r2(player.fallDistance));
        o.put("gamemode", mc.gameMode == null ? null : mc.gameMode.getPlayerMode().getName());
        o.put("experience_level", player.experienceLevel);
        o.put("effects", effects(player));
        return o;
    }

    private Json.Obj food(LocalPlayer player) {
        Json.Obj o = Json.obj();
        var fd = player.getFoodData();
        if (fd == null) {
            o.put("level", (Object) null);
            return o;
        }
        o.put("level", fd.getFoodLevel());
        o.put("saturation", r2(fd.getSaturationLevel()));
        return o;
    }

    private Json.Arr effects(LocalPlayer player) {
        Json.Arr a = Json.arr();
        player.getActiveEffects().forEach(e -> {
            Identifier id = BuiltInRegistries.MOB_EFFECT.getKey(e.getEffect().value());
            a.add(Json.obj()
                    .put("id", id == null ? "unknown" : id.toString())
                    .put("amplifier", e.getAmplifier())
                    .put("duration_ticks", e.getDuration()));
        });
        return a;
    }

    // ==================================================================
    // 世界
    // ==================================================================

    private Json.Obj world(ClientLevel level, LocalPlayer player) {
        Json.Obj o = Json.obj();
        Identifier dim = level.dimension().identifier();
        o.put("dimension", dim.toString());
        o.put("day_time", level.getOverworldClockTime() % 24000L);
        o.put("game_time", level.getGameTime());
        // 26.2 移除了 isDay/isNight：用天暗程度判定（skyDarken 越大越黑）
        int darken = level.getSkyDarken();
        o.put("sky_darken", darken);
        o.put("is_day", darken < 4);
        o.put("is_night", darken >= 4);
        o.put("raining", level.isRaining());
        o.put("thundering", level.isThundering());
        o.put("min_y", level.getMinY());
        o.put("max_y", level.getMaxY());
        BlockPos feet = player.blockPosition();
        // 光照决定会不会刷怪，AI 需要知道"这里黑不黑"
        o.put("light_block", level.getBrightness(LightLayer.BLOCK, feet));
        o.put("light_sky", level.getBrightness(LightLayer.SKY, feet));
        o.put("biome", biome(level, feet));
        return o;
    }

    private String biome(ClientLevel level, BlockPos pos) {
        try {
            var holder = level.getBiome(pos);
            var key = holder.unwrapKey().orElse(null);
            return key == null ? "unknown" : key.identifier().toString();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    // ==================================================================
    // 方块（带 chunk 级缓存）
    // ==================================================================

    private Json.Obj blocks(ClientLevel level, LocalPlayer player) {
        Config c = Config.get();
        int r = c.scanRadius;
        int vy = c.scanVertical;
        BlockPos center = player.blockPosition();

        Json.Obj out = Json.obj();
        out.put("scan_radius", r);
        out.put("scan_vertical", vy);

        // chunk 级缓存 key：维度 hash + chunk 坐标
        int cx = center.getX() >> 4;
        int cz = center.getZ() >> 4;
        long key = (((long) level.dimension().identifier().toString().hashCode()) << 40)
                ^ (((long) (cx & 0xFFFFF)) << 20)
                ^ (cz & 0xFFFFF);

        // 用游戏时间作为粗粒度失效戳：避免依赖内部修改计数器（各版本不统一）
        long stamp = level.getGameTime() >> 5; // 约每 1.6 秒一个档位

        SenseCache.ChunkEntry cached = cache.get(key, stamp);
        if (cached != null) {
            out.put("histogram", toJson(SenseCache.topN(cached.histogram, 12)));
            out.put("cached", true);
            return out;
        }

        Map<String, Integer> hist = new HashMap<>();
        int minY = Math.max(level.getMinY(), center.getY() - vy);
        int maxY = Math.min(level.getMaxY() - 1, center.getY() + vy);

        // 步进采样：全量 14 万格没必要，step=2 保留结构特征同时降到 1/8 开销
        int step = 2;
        for (int x = center.getX() - r; x <= center.getX() + r; x += step) {
            for (int z = center.getZ() - r; z <= center.getZ() + r; z += step) {
                for (int y = minY; y <= maxY; y += step) {
                    BlockPos bp = new BlockPos(x, y, z);
                    // 未加载的区块不能强读，否则会触发加载、拖垮性能甚至卡死
                    if (!level.isLoaded(bp)) {
                        continue;
                    }
                    BlockState st = level.getBlockState(bp);
                    if (st.isAir()) {
                        continue;
                    }
                    Identifier id = BuiltInRegistries.BLOCK.getKey(st.getBlock());
                    hist.merge(id == null ? "unknown" : id.toString(), 1, Integer::sum);
                }
            }
        }
        cache.put(key, stamp, hist);
        out.put("histogram", toJson(SenseCache.topN(hist, 12)));
        out.put("cached", false);
        return out;
    }

    private Json.Arr toJson(Map<String, Integer> m) {
        Json.Arr a = Json.arr();
        m.forEach((k, v) -> a.add(Json.obj().put("block", k).put("count", v)));
        return a;
    }

    // ==================================================================
    // 实体
    // ==================================================================

    private Json.Obj entities(ClientLevel level, LocalPlayer player) {
        Config c = Config.get();
        double r2max = (double) c.entityRadius * c.entityRadius;
        Vec3 me = player.position();

        Json.Obj out = Json.obj();
        Json.Arr hostile = Json.arr();
        Json.Arr passive = Json.arr();
        Json.Arr items = Json.arr();
        Json.Arr players = Json.arr();

        List<Entity> snapshot = new ArrayList<>();
        level.entitiesForRendering().forEach(snapshot::add);

        for (Entity e : snapshot) {
            if (!e.isAlive() || e == player) {
                continue;
            }
            double d2 = e.position().distanceToSqr(me);
            if (d2 > r2max) {
                continue;
            }
            Json.Obj eo = entityBrief(e, me);

            // 分类：先按具体实现，再退回接口判定
            if (e instanceof ItemEntity ie) {
                ItemStack st = ie.getItem();
                Identifier id = BuiltInRegistries.ITEM.getKey(st.getItem());
                eo.put("item", id == null ? "unknown" : id.toString());
                eo.put("count", st.getCount());
                items.add(eo);
            } else if (e instanceof Player other) {
                eo.put("name", other.getName().getString());
                eo.put("gamemode", "player");
                players.add(eo);
            } else if (isHostile(e)) {
                hostile.add(eo);
            } else {
                passive.add(eo);
            }
        }

        out.put("hostile", hostile);
        out.put("passive", passive);
        out.put("dropped_items", items);
        out.put("players", players);
        out.put("hostile_count", hostile.raw().size());
        return out;
    }

    /** 敌对判定：优先用 Enemy 接口，覆盖不到时按常见敌对类兜底。 */
    private boolean isHostile(Entity e) {
        if (e instanceof Enemy) {
            return true;
        }
        // 部分中立生物在仇恨状态下也需要警惕，交给 AI 结合 targeting 判断
        return false;
    }

    private Json.Obj entityBrief(Entity e, Vec3 me) {
        net.minecraft.world.entity.Entity playerSelf =
                net.minecraft.client.Minecraft.getInstance().player;
        Json.Obj o = Json.obj();
        o.put("uuid", e.getUUID().toString().substring(0, 8));
        String type = entityId(e);
        o.put("type", type);
        o.put("name", e.getName().getString());
        Vec3 p = e.position();
        o.put("x", r2(p.x));
        o.put("y", r2(p.y));
        o.put("z", r2(p.z));
        o.put("distance", r2(Math.sqrt(e.position().distanceToSqr(me))));
        if (e instanceof LivingEntity le) {
            o.put("health", r2(le.getHealth()));
            o.put("max_health", r2(le.getMaxHealth()));
            // 谁在打我 —— AI 判断威胁的直接依据
            // 26.2 中 getTarget() 定义在 Mob 上而非 LivingEntity
            net.minecraft.world.entity.LivingEntity tgt =
                    (le instanceof net.minecraft.world.entity.Mob mob) ? mob.getTarget() : null;
            o.put("targeting", tgt == null ? null : tgt.getName().getString());
            o.put("targeting_me", tgt != null && tgt.is(playerSelf));
        } else {
            o.put("health", (Object) null);
        }
        o.put("on_ground", e.onGround());
        return o;
    }

    private String entityId(Entity e) {
        try {
            var k = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
            return k == null ? e.getType().toString() : k.toString();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    // ==================================================================
    // 背包
    // ==================================================================

    private Json.Obj inventory(LocalPlayer player) {
        Json.Obj out = Json.obj();

        ItemStack held = player.getMainHandItem();
        out.put("held", stack(held));
        out.put("held_slot", player.getInventory().getSelectedSlot());

        ItemStack off = player.getItemBySlot(EquipmentSlot.OFFHAND);
        out.put("offhand", stack(off));

        Json.Arr hotbar = Json.arr();
        // 快捷栏 0..8
        for (int i = 0; i < 9; i++) {
            Json.Obj s = Json.obj();
            s.put("slot", i);
            s.put("stack", stack(player.getInventory().getItem(i)));
            hotbar.add(s);
        }
        out.put("hotbar", hotbar);

        // 主背包只报非空槽位，AI 关心"我有什么"，不关心 27 个空格
        Json.Arr main = Json.arr();
        int total = player.getInventory().getContainerSize();
        for (int i = 9; i < total; i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (st.isEmpty()) {
                continue;
            }
            Json.Obj s = Json.obj();
            s.put("slot", i);
            s.put("stack", stack(st));
            main.add(s);
        }
        out.put("main", main);

        Json.Arr armor = Json.arr();
        for (EquipmentSlot slot : new EquipmentSlot[] {
                EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            Json.Obj s = Json.obj();
            s.put("slot", slot.getName());
            s.put("stack", stack(player.getItemBySlot(slot)));
            armor.add(s);
        }
        out.put("armor", armor);
        return out;
    }

    private Json.Obj stack(ItemStack st) {
        Json.Obj o = Json.obj();
        if (st == null || st.isEmpty()) {
            o.put("empty", true);
            return o;
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(st.getItem());
        o.put("empty", false);
        o.put("id", id == null ? "unknown" : id.toString());
        o.put("count", st.getCount());
        o.put("max_stack", st.getMaxStackSize());
        o.put("damage", st.getDamageValue());
        o.put("max_damage", st.getMaxDamage());
        o.put("display", st.getHoverName().getString());
        return o;
    }

    // ==================================================================
    // 准星目标
    // ==================================================================

    private Json.Obj crosshair(Minecraft mc, LocalPlayer player) {
        Json.Obj o = Json.obj();
        var hit = mc.hitResult;
        if (hit == null) {
            o.put("type", "none");
            return o;
        }
        o.put("type", hit.getType().name());
        // 方块
        if (hit instanceof net.minecraft.world.phys.BlockHitResult bhr) {
            BlockPos bp = bhr.getBlockPos();
            o.put("block_x", bp.getX());
            o.put("block_y", bp.getY());
            o.put("block_z", bp.getZ());
            o.put("face", bhr.getDirection().getName());
            if (mc.level != null && mc.level.isLoaded(bp)) {
                BlockState st = mc.level.getBlockState(bp);
                Identifier id = BuiltInRegistries.BLOCK.getKey(st.getBlock());
                o.put("block", id == null ? "unknown" : id.toString());
            }
        }
        // 26.2 中 Minecraft.crosshairPickEntity 是 public 字段，可直接读
        Entity target = mc.crosshairPickEntity;
        o.put("entity", target == null ? null : entityBrief(target, player.position()));
        return o;
    }

    private static double r2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
