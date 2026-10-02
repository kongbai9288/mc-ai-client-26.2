package com.kongbai.mcai;

import com.kongbai.mcai.act.Actuator;
import com.kongbai.mcai.act.BaritoneLink;
import com.kongbai.mcai.bridge.HttpApi;
import com.kongbai.mcai.bridge.McAiModLog;
import com.kongbai.mcai.bridge.MainThread;
import com.kongbai.mcai.config.Config;
import com.kongbai.mcai.llm.Agent;
import com.kongbai.mcai.platform.Env;

import net.fabricmc.api.ClientModInitializer;

/**
 * MCAI 入口。
 *
 * <h3>启动顺序</h3>
 * <ol>
 *   <li>环境探测：手机启动器上直接"装死"，不启动任何后台线程。</li>
 *   <li>加载配置。</li>
 *   <li>启动本地桥接 API（REST + MCP），供外部程序驱动。</li>
 *   <li>按需启动自主 AI 循环。</li>
 * </ol>
 *
 * <p>tick 钩子在 {@code MinecraftTickMixin} 里，负责推进执行器和主线程任务队列。
 */
public final class McAiMod implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        try {
            Config cfg = Config.get();

            // 1) 环境探测 —— 这一步必须先做，手机端后面全部跳过
            if (Env.isMobileLauncher()) {
                McAiModLog.warn("运行在手机启动器上（" + Env.reason()
                        + "）。为防崩溃，AI 驱动与桥接 API 已全部关闭；"
                        + "游戏本体不受影响。");
                return;
            }

            McAiModLog.info("启动中 | MC 26.2 | 模式=" + cfg.mode
                    + " | Baritone=" + (BaritoneLink.available() ? "已检测到" : "未安装（用内置执行器）"));

            // 可选增强：都是软探测，缺失不影响运行
            if (com.kongbai.mcai.platform.ViaLink.available()) {
                String tv = com.kongbai.mcai.platform.ViaLink.targetVersion();
                McAiModLog.info("ViaFabricPlus 已就绪，目标服务器版本=" + (tv == null ? "原生 26.2" : tv)
                        + "（同一客户端可连 1.8 ~ 26.2 任意服务器）");
            }
            McAiModLog.info("Litematica: " + com.kongbai.mcai.act.LitematicaLink.status());

            // 2) 桥接 API
            HttpApi.get().start();

            // 3) 自主循环：配置了目标才会真正开始动
            if (cfg.autonomous && !cfg.apiKey.isBlank()) {
                Agent.get().start();
                McAiModLog.info("自主循环已就绪。请通过 POST http://127.0.0.1:"
                        + HttpApi.get().port() + "/goal {\"goal\":\"...\"} 下达目标");
            } else {
                McAiModLog.warn("未设置 MCAI_API_KEY 或 autonomous=false，"
                        + "AI 循环未启动；桥接 API 仍可外部调用（由外部程序提供 LLM）。");
            }

        } catch (Throwable t) {
            // 初始化失败绝不能让游戏崩掉
            McAiModLog.error("初始化失败，MCAI 已停用", t);
        }
    }

    /**
     * 由 mixin 每 tick 调用。
     *
     * @param tick 当前游戏 tick
     */
    public static void onClientTick(long tick) {
        try {
            // 主线程任务队列必须最先排空：工具调用都靠它
            MainThread.drain();

            if (!Env.bridgeEnabled()) {
                return;
            }
            Actuator.get().tick(net.minecraft.client.Minecraft.getInstance());
        } catch (Throwable t) {
            // 单 tick 异常不该终止整个游戏；只记录一次避免刷屏
            if (tick % 200 == 0) {
                McAiModLog.error("tick 处理异常", t);
            }
        }
    }
}
