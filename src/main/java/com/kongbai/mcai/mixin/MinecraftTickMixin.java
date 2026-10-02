package com.kongbai.mcai.mixin;

import com.kongbai.mcai.McAiMod;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端 tick 钩子。
 *
 * <p>和 Baritone 的做法一致：直接 Mixin {@code Minecraft.tick()}，
 * 因此<b>不需要 Fabric API</b>，整个 mod 只依赖 fabric-loader。
 *
 * <p>注入点选在 {@code HEAD}：执行器需要在游戏处理输入之前把意图写入
 * {@code player.input}，否则这一 tick 仍然是键盘的状态。
 */
@Mixin(Minecraft.class)
public class MinecraftTickMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void mcai$onTickHead(CallbackInfo ci) {
        try {
            Minecraft mc = (Minecraft) (Object) this;
            long tick = mc.level == null ? 0L : mc.level.getGameTime();
            McAiMod.onClientTick(tick);
        } catch (Throwable ignored) {
            // tick 钩子里绝不能抛异常，否则会中断游戏主循环
        }
    }
}
