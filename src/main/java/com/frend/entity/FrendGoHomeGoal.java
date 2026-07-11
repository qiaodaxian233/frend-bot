package com.frend.entity;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.BlockPos;

import java.util.EnumSet;

/**
 * 回家(GO_HOME 模式专用)。
 *
 * <p>向家坐标寻路;到家(2.5 格内)自动转 STAY 并汇报。
 * 长时间(约 15 秒)寻路无进展则放弃,原地 STAY 并说明走不过去。
 * 家在别的维度时不启动(mobTick 不管这个,由指令层在下发 GO_HOME 前提示)。
 */
public class FrendGoHomeGoal extends Goal {

    private static final double ARRIVE_DIST_SQ = 2.5 * 2.5;
    private static final int GIVE_UP_TICKS = 20 * 15;

    private final FrendEntity frend;
    private int repathCooldown = 0;
    private int stuckTicks = 0;
    private double lastDistSq = Double.MAX_VALUE;

    public FrendGoHomeGoal(FrendEntity frend) {
        this.frend = frend;
        this.setControls(EnumSet.of(Goal.Control.MOVE, Goal.Control.LOOK));
    }

    @Override
    public boolean canStart() {
        return frend.getMode() == FrendEntity.Mode.GO_HOME && frend.isHomeInThisDimension();
    }

    @Override
    public boolean shouldContinue() {
        return frend.getMode() == FrendEntity.Mode.GO_HOME && frend.isHomeInThisDimension();
    }

    @Override
    public void start() {
        repathCooldown = 0;
        stuckTicks = 0;
        lastDistSq = Double.MAX_VALUE;
    }

    @Override
    public void stop() {
        frend.getNavigation().stop();
    }

    @Override
    public void tick() {
        BlockPos home = frend.getHomePos();
        double distSq = frend.getBlockPos().getSquaredDistance(home);

        if (distSq <= ARRIVE_DIST_SQ) {
            frend.setMode(FrendEntity.Mode.STAY);
            frend.sayDelayed("到家啦,我在这儿看家,要出发喊我一声。");
            return;
        }

        if (--repathCooldown <= 0) {
            repathCooldown = 20;
            frend.navigateSmart(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 1.1); // v0.12 长途分段:家在几百格外也走得回
        }

        // 无进展检测:距离没有变得更近就累计,太久放弃
        if (distSq < lastDistSq - 0.5) {
            lastDistSq = distSq;
            stuckTicks = 0;
        } else if (++stuckTicks > GIVE_UP_TICKS) {
            frend.setMode(FrendEntity.Mode.STAY);
            frend.sayDelayed("这条路我走不过去……我先在这儿等你,回头你带我回去。");
        }
    }
}
