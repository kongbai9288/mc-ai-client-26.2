package com.kongbai.mcai.act;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * 玩家输入接管器。
 *
 * <p>沿用 Baritone 在 26.2 上的做法：把 {@code player.input} 换成自己的子类，
 * 在 {@code tick()} 里按当前意图填充移动向量和按键状态。这样所有的移动都走
 * 正常的游戏输入管线，服务器看到的就是"一个在按 W 的玩家"，而不是被外部
 * 强行改坐标 —— 这是能不能在正常服务器上跑起来的分水岭。
 *
 * <p>26.2 的关键签名（已核对 Baritone 26.2 的 PlayerMovementInput）：
 * <pre>
 *   class ClientInput {
 *       Vec2 moveVector;                  // (leftImpulse, forwardImpulse)
 *       net.minecraft.world.entity.player.Input keyPresses;
 *   }
 *   Input(boolean up, boolean down, boolean left, boolean right,
 *         boolean jumping, boolean sneaking, boolean sprinting)
 * </pre>
 */
public final class AiInput extends ClientInput {

    /** 当前意图，由 Actuator 在 tick 前设置。 */
    public boolean forward;
    public boolean back;
    public boolean left;
    public boolean right;
    public boolean jump;
    public boolean sneak;
    public boolean sprint;

    /** 是否接管中。false 时把控制权交还给键盘，玩家仍能正常操作。 */
    public boolean active;

    @Override
    public void tick() {
        if (!active) {
            this.moveVector = new Vec2(0.0F, 0.0F);
            this.keyPresses = new Input(false, false, false, false, false, false, false);
            return;
        }
        float leftImpulse = 0.0F;
        float forwardImpulse = 0.0F;
        if (forward) forwardImpulse++;
        if (back) forwardImpulse--;
        if (left) leftImpulse++;
        if (right) leftImpulse--;
        if (sneak) {
            // 潜行移动减速：与游戏内行为一致，否则玩家会"跑得比潜行快"
            leftImpulse *= 0.3F;
            forwardImpulse *= 0.3F;
        }
        this.moveVector = new Vec2(leftImpulse, forwardImpulse);
        this.keyPresses = new Input(forward, back, left, right, jump, sneak, sprint);
    }

    public void clear() {
        forward = back = left = right = jump = sneak = sprint = false;
    }
}
