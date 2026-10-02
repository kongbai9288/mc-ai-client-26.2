package com.kongbai.mcai.bridge;

import com.kongbai.mcai.act.Actuator;
import com.kongbai.mcai.act.AntiCheat;
import com.kongbai.mcai.act.BaritoneLink;
import com.kongbai.mcai.llm.LlmClient;
import com.kongbai.mcai.sense.Scanner;
import com.kongbai.mcai.util.Json;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 可用工具集。
 *
 * <p>每个工具的定义都遵循三条规则（对应"不要出现低级的 AI 错误"）：
 * <ol>
 *   <li><b>description 自带触发条件</b>：写清"什么时候该用、什么时候不该用"。
 *       在 harness 模式下没有系统提示词，工具描述就是唯一的说明书，必须自解释。</li>
 *   <li><b>参数严格 schema 化</b>：类型、范围、是否必填都写死，减少模型瞎填。</li>
 *   <li><b>返回值统一带 ok</b>：失败必须写明原因和下一步建议，不能静默失败。</li>
 * </ol>
 *
 * <p>所有执行都经 {@link MainThread} 调度到客户端线程，避免跨线程改游戏状态。
 */
public final class Tools {

    private static final Scanner SCANNER = new Scanner();

    private Tools() {}

    public static Scanner scanner() {
        return SCANNER;
    }

    // ==================================================================
    // 工具声明
    // ==================================================================

    /** 生成 OpenAI 格式的 tools 数组。 */
    public static List<Map<String, Object>> schemas() {
        List<Map<String, Object>> out = new ArrayList<>();

        out.add(LlmClient.tool("observe",
                "获取当前世界的完整快照：自身位置/血量/饥饿/装备、周围方块分布、附近生物（区分敌对与被动）、"
                        + "掉落物、背包内容、准星指向的方块与实体。"
                        + "在做任何决策前必须先调用它——你拿到的永远是这一刻的真实数据，"
                        + "不要凭上一轮的记忆行动。开销较高，同一轮决策中不要重复调用。",
                LlmClient.params(new LinkedHashMap<>(), null)));

        out.add(LlmClient.tool("go_to",
                "前往指定坐标。会自主寻路：能挖开挡路的方块、搭桥过沟、爬台阶。"
                        + "这是一个持续动作，调用后你会立刻拿到已受理的回执，"
                        + "随后用 observe 或 action_status 观察进展即可，不要反复重复调用同一个目标。"
                        + "需要精确到达某个位置时用它；只是想换个朝向请用 look_at。",
                LlmClient.params(props(p("x", "int", "目标 X 坐标"),
                        p("y", "int", "目标 Y 坐标（脚下高度）"),
                        p("z", "int", "目标 Z 坐标"),
                        p("timeout_ticks", "int", "超过多少 tick 放弃，默认 1200（约 60 秒）")),
                        List.of("x", "y", "z"))));

        out.add(LlmClient.tool("mine",
                "采集指定方块，直到采够数量或放弃。会自主寻找并前往目标方块。"
                        + "block_id 用完整命名空间，如 minecraft:diamond_ore、minecraft:oak_log。"
                        + "若未安装 Baritone，此能力不可用，返回中会明确说明。",
                LlmClient.params(props(p("block_id", "string", "方块 id，如 minecraft:iron_ore"),
                        p("count", "int", "目标数量，0 表示不限")),
                        List.of("block_id"))));

        out.add(LlmClient.tool("attack",
                "攻击指定目标。target 填 observe 返回的实体 uuid 前缀（8 位）或实体名。"
                        + "攻击有冷却（受拟人化设置约束），不要每 tick 调用一次——调用一次即可，"
                        + "执行器会自动持续攻击直到目标死亡或消失。"
                        + "血量过低时应当改用 flee 而不是继续攻击。",
                LlmClient.params(props(p("target", "string", "实体 uuid 前缀或名称"),
                        p("timeout_ticks", "int", "默认 600")),
                        List.of("target"))));

        out.add(LlmClient.tool("flee",
                "远离指定坐标。用于躲避危险（敌怪、岩浆、悬崖、其他玩家）。"
                        + "血量低于 40% 或被多个敌人围住时优先用它。",
                LlmClient.params(props(p("x", "int", "要远离的位置 X"),
                        p("y", "int", "要远离的位置 Y"),
                        p("z", "int", "要远离的位置 Z"),
                        p("timeout_ticks", "int", "默认 400")),
                        List.of("x", "y", "z"))));

        out.add(LlmClient.tool("place_block",
                "在指定方块的指定面上放置手中物品。目标位置必须是已存在的非空气方块，"
                        + "新方块会出现在它的指定面。需要先用 select_slot 选好要放的物品。",
                LlmClient.params(props(p("x", "int", "参照方块 X"),
                        p("y", "int", "参照方块 Y"),
                        p("z", "int", "参照方块 Z"),
                        p("face", "string", "up/down/north/south/east/west，默认 up")),
                        List.of("x", "y", "z"))));

        out.add(LlmClient.tool("break_block",
                "挖掉指定坐标的方块（原地挖，不会寻路过去）。距离必须 ≤5 格。"
                        + "想挖远处的矿脉请用 mine。",
                LlmClient.params(props(p("x", "int", "X"), p("y", "int", "Y"), p("z", "int", "Z")),
                        List.of("x", "y", "z"))));

        out.add(LlmClient.tool("stop_break", "松开挖掘键，停止当前挖掘。",
                LlmClient.params(new LinkedHashMap<>(), null)));

        out.add(LlmClient.tool("use_item",
                "右键使用主手物品（吃东西、放方块、开箱子、拉弓等）。"
                        + "需要选中正确的物品时先调用 select_slot。",
                LlmClient.params(new LinkedHashMap<>(), null)));

        out.add(LlmClient.tool("select_slot",
                "切换快捷栏到指定格子（0-8）。放方块、用工具、吃东西前都要先选好。"
                        + "用 observe 的 inventory.hotbar 查看每格是什么。",
                LlmClient.params(props(p("slot", "int", "0 到 8")), List.of("slot"))));

        out.add(LlmClient.tool("look_at",
                "把视角转向指定坐标。只改变朝向，不移动。用于观察远处目标。",
                LlmClient.params(props(p("x", "double", "目标 X"),
                        p("y", "double", "目标 Y"),
                        p("z", "double", "目标 Z")),
                        List.of("x", "y", "z"))));

        out.add(LlmClient.tool("wait",
                "等待若干 tick 后再观察（20 tick = 1 秒）。用于等农作物生长、"
                        + "等怪物靠近、等当前动作推进。不要用轮询空转代替它。",
                LlmClient.params(props(p("ticks", "int", "等待 tick 数")), List.of("ticks"))));

        out.add(LlmClient.tool("stop_all",
                "立即停止所有进行中的动作（寻路、攻击、挖掘）。"
                        + "发现正在做的事是错的、或陷入循环时，先调用它再重新规划。",
                LlmClient.params(new LinkedHashMap<>(), null)));

        out.add(LlmClient.tool("action_status",
                "查询当前正在执行的动作：类型、已耗时、剩余时间、是否卡住、上一次失败原因。"
                        + "调用它比重复发起同一个动作更省事。",
                LlmClient.params(new LinkedHashMap<>(), null)));

        out.add(LlmClient.tool("anticheat_tune",
                "调整拟人化参数，防止被服务器反作弊判定为脚本并被拉回（表现为走过去又被弹回来）。"
                        + "当你观察到 setback（反复回到原位、动作被撤销）时调用它，"
                        + "并把 attack_min_ticks 调大、throttle 调高（更慢更像人）。"
                        + "不带参数调用可查看当前状态和拉回次数；reset=true 恢复默认。",
                LlmClient.params(props(
                        p("attack_min_ticks", "int", "两次攻击之间最小 tick 数，越大越保守"),
                        p("attack_max_ticks", "int", "两次攻击之间最大 tick 数"),
                        p("look_step_degrees", "double", "每 tick 最大转向角度，越小越像人手"),
                        p("jitter_every", "int", "每多少次操作插入一次随机停顿"),
                        p("throttle", "double", "全局节流倍率，1.0 正常，2.0 表示放慢一倍"),
                        p("reset", "boolean", "true 恢复默认并清零拉回计数")),
                        null)));

        out.add(LlmClient.tool("baritone_setting",
                "读写 Baritone 的设置项。用于开关自动战斗（mobDefense）、自动进食（autoEat）、"
                        + "避让（avoidance）等。不带 value 表示读取，带 value 表示写入。"
                        + "常用项：mobDefense / mobDefenseRadius / mobDefenseFleeHealthPercent / "
                        + "autoEat / autoEatThreshold / allowSprint / allowBreak / allowPlace / avoidance。"
                        + "未安装 Baritone 时会明确返回不可用。",
                LlmClient.params(props(p("name", "string", "设置项名"),
                        p("value", "string", "要写入的值；留空表示读取")),
                        List.of("name"))));

        out.add(LlmClient.tool("baritone_command",
                "直接执行一条 Baritone 命令（逃生舱）。command 不带井号，如 goto / mine / build / "
                        + "farm / follow / explore / stop。仅当上面的标准工具覆盖不到你的需求时才用。",
                LlmClient.params(props(p("command", "string", "命令名，如 goto"),
                        p("args", "string", "参数，如 100 64 100")),
                        List.of("command"))));

        out.add(LlmClient.tool("load_schematic",
                "加载投影文件（.litematic / .schematic）并生成摘要：尺寸、材料清单、分层剖面。"
                        + "拿到的是压缩后的摘要而不是几十万行方块数据——先读摘要判断要建什么、"
                        + "要备哪些材料，再用 schematic_diff 定位当前该建哪一层。"
                        + "文件路径相对游戏目录，如 schematics/house.litematic。",
                LlmClient.params(props(p("path", "string", "投影文件路径"),
                        p("max_layers", "int", "最多输出多少层剖面，默认 16")),
                        List.of("path"))));

        out.add(LlmClient.tool("schematic_diff",
                "把已加载的投影与当前世界比对，列出缺失和放错的方块，并给出最近一批该放置的坐标。"
                        + "这是施工的核心：不要凭摘要硬猜位置，用它拿到确切坐标后逐个 place_block。"
                        + "max_results 建议 20 以内，一次拿太多反而做不完。",
                LlmClient.params(props(p("max_results", "int", "最多返回多少个待处理方块，默认 20"),
                        p("max_distance", "int", "只比对距离玩家多远的区域，默认 32")),
                        null)));

        out.add(LlmClient.tool("printer",
                "开关投影打印机（Litematica 的 Easy Place 模式）。开启后，只要手上有对应方块、"
                        + "对着投影方向右键，就会自动按投影精确放置，无需逐个指定坐标。"
                        + "建造大型结构时优先开启它，比手工 place_block 快得多。"
                        + "不带参数调用可查看当前状态；未安装 Litematica 时会明确返回不可用。",
                LlmClient.params(props(p("enabled", "boolean", "true 开启 / false 关闭")), null)));

        out.add(LlmClient.tool("finish",
                "当你判断当前任务已经完成，或者无法继续并需要人类介入时调用它。"
                        + "必须写明 reason。调用后本轮决策结束。",
                LlmClient.params(props(p("reason", "string", "完成或中止的原因")),
                        List.of("reason"))));

        return out;
    }

    // ==================================================================
    // 执行分发
    // ==================================================================

    /**
     * 执行一个工具调用。
     *
     * @return JSON 字符串结果（统一带 ok 字段）
     */
    public static String invoke(String name, Map<String, Object> args) {
        return MainThread.run(() -> {
            Minecraft mc = Minecraft.getInstance();
            Actuator act = Actuator.get();
            long defTimeout = 1200L;
            try {
                Object r = switch (name) {
                    case "observe" -> SCANNER.observe();
                    case "go_to" -> act.goTo(mc,
                            Json.integer(args, "x", 0),
                            Json.integer(args, "y", 64),
                            Json.integer(args, "z", 0),
                            Json.integer(args, "timeout_ticks", (int) defTimeout));
                    case "mine" -> act.mine(mc,
                            Json.str(args, "block_id", ""),
                            Json.integer(args, "count", 0),
                            Json.integer(args, "timeout_ticks", (int) defTimeout));
                    case "attack" -> act.attack(mc,
                            Json.str(args, "target", ""),
                            Json.integer(args, "timeout_ticks", 600));
                    case "flee" -> act.flee(mc,
                            Json.integer(args, "x", 0),
                            Json.integer(args, "y", 64),
                            Json.integer(args, "z", 0),
                            Json.integer(args, "timeout_ticks", 400));
                    case "place_block" -> act.place(mc,
                            Json.integer(args, "x", 0),
                            Json.integer(args, "y", 0),
                            Json.integer(args, "z", 0),
                            Json.str(args, "face", "up"));
                    case "break_block" -> act.breakBlock(mc,
                            Json.integer(args, "x", 0),
                            Json.integer(args, "y", 0),
                            Json.integer(args, "z", 0), 200L);
                    case "stop_break" -> act.stopBreak(mc);
                    case "use_item" -> act.use(mc);
                    case "select_slot" -> act.selectSlot(mc, Json.integer(args, "slot", 0));
                    case "look_at" -> lookAt(mc, Json.dbl(args, "x", 0),
                            Json.dbl(args, "y", 0), Json.dbl(args, "z", 0));
                    case "wait" -> act.wait(mc, Json.integer(args, "ticks", 20));
                    case "stop_all" -> act.stop(mc);
                    case "action_status" -> act.status(mc);
                    case "anticheat_tune" -> AntiCheat.get().tune(args);
                    case "baritone_setting" -> baritoneSetting(args);
                    case "baritone_command" -> baritoneCommand(args);
                    case "load_schematic" -> loadSchematic(args);
                    case "schematic_diff" -> schematicDiff(mc, args);
                    case "printer" -> printer(args);
                    case "finish" -> Json.obj().put("ok", true)
                            .put("finished", true)
                            .put("reason", Json.str(args, "reason", ""));
                    default -> Json.obj().put("ok", false)
                            .put("error", "unknown_tool")
                            .put("tool", name)
                            .put("hint", "没有这个工具，请从可用工具列表中选择");
                };
                return r instanceof String s ? s : Json.write(r);
            } catch (Throwable t) {
                return Json.write(Json.obj()
                        .put("ok", false)
                        .put("tool", name)
                        .put("error", String.valueOf(t)));
            }
        }, 30_000L);
    }

    private static Json.Obj lookAt(Minecraft mc, double x, double y, double z) {
        if (mc.player == null) {
            return Json.obj().put("ok", false).put("error", "not_in_game");
        }
        var p = mc.player;
        double dx = x - p.getX();
        double dy = y - (p.getY() + p.getEyeHeight());
        double dz = z - p.getZ();
        double horiz = Math.hypot(dx, dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0F);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        // 走拟人化限速，避免瞬转被判自瞄
        float sy = AntiCheat.get().stepAngle(p.getYRot(), yaw);
        float sp = AntiCheat.get().stepAngle(p.getXRot(), pitch);
        p.setYRot(sy);
        p.setXRot(sp);
        return Json.obj().put("ok", true)
                .put("requested_yaw", round(yaw)).put("requested_pitch", round(pitch))
                .put("applied_yaw", round(sy)).put("applied_pitch", round(sp))
                .put("note", "视角按拟人化限速逐 tick 转动，未完全到位时用 observe 确认");
    }

    private static Json.Obj baritoneSetting(Map<String, Object> args) {
        String name = Json.str(args, "name", "");
        String value = Json.str(args, "value", null);
        if (!BaritoneLink.available()) {
            return Json.obj().put("ok", false)
                    .put("error", "baritone_unavailable")
                    .put("detail", BaritoneLink.failureReason())
                    .put("hint", "安装 Minecraft 26.2 可用的 Baritone 后可启用该能力；"
                            + "当前内置执行器仍可完成基础移动与战斗");
        }
        if (value == null || value.isBlank()) {
            Object cur = BaritoneLink.setting(name);
            return Json.obj().put("ok", cur != null)
                    .put("name", name)
                    .put("value", cur == null ? null : String.valueOf(cur))
                    .put("all_key_settings", Json.write(BaritoneLink.keySettings()));
        }
        Object cur = BaritoneLink.setting(name);
        Object parsed = coerce(value, cur);
        boolean ok = BaritoneLink.setSetting(name, parsed);
        return Json.obj().put("ok", ok)
                .put("name", name)
                .put("set_to", String.valueOf(parsed))
                .put("message", ok ? "已更新" : "写入失败（类型不匹配或该项不存在）");
    }

    // ==================================================================
    // 投影 / 打印
    // ==================================================================

    /** 当前已加载的投影（同时只保留一份，够用且省内存）。 */
    private static volatile com.kongbai.mcai.sense.Schematic.Data LOADED_SCHEMATIC;
    private static volatile String LOADED_SCHEMATIC_PATH;

    private static Json.Obj loadSchematic(Map<String, Object> args) {
        String path = Json.str(args, "path", "");
        if (path.isBlank()) {
            return Json.obj().put("ok", false).put("error", "path 不能为空");
        }
        try {
            java.nio.file.Path p = java.nio.file.Path.of(path);
            if (!java.nio.file.Files.exists(p)) {
                // 也试试游戏目录下的 schematics 子目录
                p = java.nio.file.Path.of("schematics", path);
                if (!java.nio.file.Files.exists(p)) {
                    return Json.obj().put("ok", false)
                            .put("error", "file_not_found")
                            .put("path", path)
                            .put("hint", "文件不存在。路径相对游戏运行目录，"
                                    + "也可直接放 schematics/ 下用文件名引用");
                }
            }
            com.kongbai.mcai.sense.Schematic.Data d =
                    com.kongbai.mcai.sense.Schematic.parse(p);
            LOADED_SCHEMATIC = d;
            LOADED_SCHEMATIC_PATH = p.toString();
            int maxLayers = Json.integer(args, "max_layers", 16);
            Json.Obj out = com.kongbai.mcai.sense.Schematic.summarize(d, maxLayers);
            out.put("file", p.toString());
            out.put("printer", com.kongbai.mcai.act.LitematicaLink.status());
            return out;
        } catch (Throwable t) {
            return Json.obj().put("ok", false)
                    .put("error", "parse_failed")
                    .put("detail", String.valueOf(t))
                    .put("hint", "确认是 .litematic 或 .schematic 且文件未损坏");
        }
    }

    /**
     * 投影差异比对：拿投影里该有的方块，对比世界现在实际有的。
     *
     * <p>只比对玩家附近区域（maxDistance），避免扫描整个投影。
     */
    private static Json.Obj schematicDiff(net.minecraft.client.Minecraft mc,
                                          Map<String, Object> args) {
        com.kongbai.mcai.sense.Schematic.Data d = LOADED_SCHEMATIC;
        if (d == null) {
            return Json.obj().put("ok", false)
                    .put("error", "no_schematic")
                    .put("hint", "先用 load_schematic 加载投影");
        }
        if (d.decoded == null || d.decoded.isEmpty()) {
            return Json.obj().put("ok", false)
                    .put("error", "too_large")
                    .put("hint", "投影过大未解码，请用更小的投影或依赖 Litematica 的打印机模式");
        }
        if (mc.level == null || mc.player == null) {
            return Json.obj().put("ok", false).put("error", "not_in_game");
        }
        int maxResults = Json.integer(args, "max_results", 20);
        int maxDist = Json.integer(args, "max_distance", 32);

        net.minecraft.client.multiplayer.ClientLevel level = mc.level;
        net.minecraft.core.BlockPos origin = mc.player.blockPosition();
        net.minecraft.core.registries.BuiltInRegistries reg = null;

        Json.Arr missing = Json.arr();
        Json.Arr wrong = Json.arr();
        int scanned = 0, matched = 0;

        for (java.util.Map.Entry<String, int[]> e : d.regions.entrySet()) {
            int[] size = e.getValue();
            int[] idx = d.decoded.get(e.getKey());
            java.util.List<String> pal = d.palettes.get(e.getKey());
            if (idx == null || pal == null) continue;

            outer:
            for (int i = 0; i < idx.length && scanned < 200000; i++) {
                int blockId = idx[i];
                if (blockId >= pal.size()) continue;
                String want = pal.get(blockId);
                if (want == null || want.endsWith(":air")
                        || want.endsWith(":cave_air") || want.endsWith(":void_air")) {
                    continue;
                }
                // 索引 -> 相对坐标（x 最快，z 次之，y 最慢）
                int area = size[0] * size[2];
                if (area == 0) break;
                int y = i / area;
                int rem = i % area;
                int z = rem / size[0];
                int x = rem % size[0];

                int wx = origin.getX() + x;
                int wy = origin.getY() + y;
                int wz = origin.getZ() + z;
                if (Math.abs(x) > maxDist || Math.abs(z) > maxDist) continue;

                net.minecraft.core.BlockPos bp =
                        new net.minecraft.core.BlockPos(wx, wy, wz);
                if (!level.isLoaded(bp)) continue;
                scanned++;
                net.minecraft.world.level.block.state.BlockState st = level.getBlockState(bp);
                net.minecraft.resources.Identifier id =
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock());
                String have = id == null ? "unknown" : id.toString();

                if (st.isAir()) {
                    if (missing.raw().size() < maxResults) {
                        missing.add(Json.obj().put("x", wx).put("y", wy).put("z", wz)
                                .put("want", want).put("have", "air"));
                    }
                } else if (!have.equals(want)) {
                    if (wrong.raw().size() < maxResults) {
                        wrong.add(Json.obj().put("x", wx).put("y", wy).put("z", wz)
                                .put("want", want).put("have", have));
                    }
                } else {
                    matched++;
                }
                if (missing.raw().size() >= maxResults && wrong.raw().size() >= maxResults) {
                    break outer;
                }
            }
        }

        Json.Obj o = Json.obj();
        o.put("ok", true);
        o.put("schematic", d.name);
        o.put("origin", Json.obj().put("x", origin.getX())
                .put("y", origin.getY()).put("z", origin.getZ()));
        o.put("scanned", scanned);
        o.put("matched", matched);
        o.put("missing", missing);
        o.put("wrong", wrong);
        o.put("missing_count", missing.raw().size());
        o.put("wrong_count", wrong.raw().size());
        o.put("hint", missing.raw().isEmpty() && wrong.raw().isEmpty()
                ? "附近区域已与投影一致，可以移动位置继续比对，或任务完成"
                : "先选好对应材料（select_slot），再按坐标逐个 place_block；"
                  + "若装了 Litematica，更推荐开启 printer 让它自动放");
        return o;
    }

    private static Json.Obj printer(Map<String, Object> args) {
        com.kongbai.mcai.act.LitematicaLink linkStatus = null; // 仅静态方法调用
        boolean has = com.kongbai.mcai.act.LitematicaLink.available();
        if (!has) {
            return Json.obj().put("ok", false)
                    .put("error", "litematica_unavailable")
                    .put("detail", com.kongbai.mcai.act.LitematicaLink.failureReason())
                    .put("hint", "未安装 Litematica。仍可用 place_block 手工施工，"
                            + "或用 schematic_diff 拿坐标后逐个放置");
        }
        if (!args.containsKey("enabled")) {
            Boolean cur = com.kongbai.mcai.act.LitematicaLink.isEasyPlace();
            return Json.obj().put("ok", true)
                    .put("enabled", cur == null ? "unknown" : cur)
                    .put("status", com.kongbai.mcai.act.LitematicaLink.status());
        }
        boolean on = Json.bool(args, "enabled", true);
        boolean ok = com.kongbai.mcai.act.LitematicaLink.setEasyPlace(on);
        return Json.obj().put("ok", ok)
                .put("enabled", on)
                .put("message", ok
                        ? (on ? "打印机已开启：手持对应方块对着投影右键即可自动放置"
                              : "打印机已关闭")
                        : "设置失败（该 Litematica 版本接口不匹配）")
                .put("status", com.kongbai.mcai.act.LitematicaLink.status());
    }

    private static Json.Obj baritoneCommand(Map<String, Object> args) {
        if (!BaritoneLink.available()) {
            return Json.obj().put("ok", false)
                    .put("error", "baritone_unavailable")
                    .put("detail", BaritoneLink.failureReason());
        }
        String cmd = Json.str(args, "command", "");
        String argStr = Json.str(args, "args", "");
        boolean ok = BaritoneLink.run(cmd, argStr);
        return Json.obj().put("ok", ok)
                .put("command", "#" + cmd + " " + argStr)
                .put("message", ok ? "Baritone 已受理" : "命令被拒绝（名称或参数有误）");
    }

    private static Object coerce(String v, Object current) {
        if (current instanceof Boolean) return Boolean.parseBoolean(v);
        if (current instanceof Integer) return Integer.parseInt(v.trim());
        if (current instanceof Double) return Double.parseDouble(v.trim());
        if (current instanceof Float) return Float.parseFloat(v.trim());
        return v;
    }

    // ==================================================================
    // schema 小工具
    // ==================================================================

    private static Map<String, Object> props(Map<String, Object>... items) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map<String, Object> it : items) {
            m.putAll(it);
        }
        return m;
    }

    private static Map<String, Object> p(String name, String type, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("description", desc);
        // 不能用 Map.of：返回类型是 Map<String,Object>，而 Map.of 会推断成
        // Map<String,Map<String,Object>>，赋值不兼容
        Map<String, Object> wrap = new LinkedHashMap<>();
        wrap.put(name, m);
        return wrap;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
