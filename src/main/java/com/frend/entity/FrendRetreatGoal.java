package com.frend.entity;

import com.frend.FrendConfig;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.player.PlayerEntity;

import java.util.EnumSet;

/**
 * 撤退 Goal:配合 {@link FrendCombatGoal} 的低血撤退逻辑。
 * CombatGoal 标记撤退状态、RetreatGoal 负责真正跑路 + 计时结束后恢复。
 *
 * 优先级高于 CombatGoal。
 */
public class FrendRetreatGoal extends Goal {

    private final FrendEntity frend;
    private final FrendCombatGoal combatGoal;
    private int retreatTicks = 0;

    public FrendRetreatGoal(FrendEntity frend, FrendCombatGoal combatGoal) {
        this.frend = frend;
        this.combatGoal = combatGoal;
        setControls(EnumSet.of(Control.MOVE));
    }

    @Override
    public boolean canStart() {
        return combatGoal.isInRetreating() && frend.getHealth() <= (float) FrendConfig.get().retreatBelowHealth;
    }

    @Override
    public boolean shouldContinue() {
        return retreatTicks > 0 && frend.getHealth() <= (float) FrendConfig.get().retreatBelowHealth;
    }

    @Override
    public void start() {
        retreatTicks = FrendConfig.get().retreatDurationSeconds * 20;
    }

    @Override
    public void stop() {
        retreatTicks = 0;
    }

    @Override
    public void tick() {
        if (retreatTicks > 0) retreatTicks--;

        PlayerEntity owner = frend.getOwnerPlayer();
        if (owner != null) {
            // 跑到主人身边
            double d = frend.squaredDistanceTo(owner);
            if (d > 9.0) {
                frend.getNavigation().startMovingTo(owner, FrendConfig.get().followSpeed * 1.3);
            } else {
                frend.getNavigation().stop();
            }
        } else {
            // v0.27 修真虫(自动测试首捕):主人不在,原逻辑是站桩挨打到死(尸壳只剩 0.37 血,它先倒了)
            // ——没有身后可绕,就背向敌人跑,活着比姿势重要。
            net.minecraft.entity.LivingEntity foe = frend.getAttacker();
            if (foe != null && foe.isAlive()) {
                net.minecraft.util.math.Vec3d diff = frend.getPos().subtract(foe.getPos());
                if (diff.lengthSquared() > 1.0E-4) {
                    net.minecraft.util.math.Vec3d away = diff.normalize();
                    frend.getNavigation().startMovingTo(frend.getX() + away.x * 8.0, frend.getY(),
                            frend.getZ() + away.z * 8.0, FrendConfig.get().followSpeed * 1.3);
                }
            }
        }

        if (retreatTicks <= 0) {
            frend.sayDelayed("好多了,再来!");
        }
    }
}
