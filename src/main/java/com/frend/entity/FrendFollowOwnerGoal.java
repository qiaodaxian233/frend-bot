package com.frend.entity;

import com.frend.FrendConfig;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.EnumSet;

/**
 * 跟随主人(FOLLOW 模式专用)。
 *
 * <p>行为参考原版 FollowOwnerGoal 但不依赖 TameableEntity:
 * 距离超过 followStartDistance 开始小跑跟随,追到 followStopDistance 停;
 * 超过 teleportDistance 视为跑丢,允许时兜底传送到主人身边 2~4 格的安全落点
 * (设计原则是尽量不瞬移,这只是防止永久卡死的保险丝),并说一句话交代。
 */
public class FrendFollowOwnerGoal extends Goal {

    private final FrendEntity frend;
    private PlayerEntity owner;
    private int repathCooldown = 0;

    public FrendFollowOwnerGoal(FrendEntity frend) {
        this.frend = frend;
        this.setControls(EnumSet.of(Goal.Control.MOVE, Goal.Control.LOOK));
    }

    @Override
    public boolean canStart() {
        if (frend.getMode() != FrendEntity.Mode.FOLLOW) return false;
        PlayerEntity o = frend.getOwnerPlayer();
        if (o == null || !o.isAlive() || o.isSpectator()) return false;
        double start = FrendConfig.get().followStartDistance;
        if (frend.squaredDistanceTo(o) < start * start) return false;
        this.owner = o;
        return true;
    }

    @Override
    public boolean shouldContinue() {
        if (frend.getMode() != FrendEntity.Mode.FOLLOW) return false;
        if (owner == null || !owner.isAlive() || owner.isSpectator()) return false;
        double stop = FrendConfig.get().followStopDistance;
        return frend.squaredDistanceTo(owner) > stop * stop;
    }

    @Override
    public void start() {
        this.repathCooldown = 0;
    }

    @Override
    public void stop() {
        this.owner = null;
        frend.getNavigation().stop();
    }

    @Override
    public void tick() {
        FrendConfig c = FrendConfig.get();
        frend.getLookControl().lookAt(owner, 10.0f, frend.getMaxLookPitchChange());

        if (--repathCooldown <= 0) {
            repathCooldown = 10;
            frend.getNavigation().startMovingTo(owner, c.followSpeed);
        }

        // 跑丢兜底
        if (c.allowTeleportWhenLost
                && frend.squaredDistanceTo(owner) > c.teleportDistance * c.teleportDistance) {
            if (tryTeleportNearOwner()) {
                frend.getNavigation().stop();
                frend.sayDelayed("差点跟丢你,我抄近路追上来了。");
            }
        }
    }

    /** 在主人周围 2~4 格找一个「脚下有实体、身位两格无碰撞」的落点传送。 */
    private boolean tryTeleportNearOwner() {
        World world = frend.getWorld();
        BlockPos base = owner.getBlockPos();
        for (int attempt = 0; attempt < 12; attempt++) {
            int dx = frend.getRandom().nextInt(7) - 3; // -3 ~ 3
            int dz = frend.getRandom().nextInt(7) - 3;
            if (Math.abs(dx) < 2 && Math.abs(dz) < 2) continue; // 别贴脸
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos pos = base.add(dx, dy, dz);
                if (canStandAt(world, pos)) {
                    frend.requestTeleport(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean canStandAt(World world, BlockPos pos) {
        return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()
                && world.getBlockState(pos.up()).getCollisionShape(world, pos.up()).isEmpty()
                && !world.getBlockState(pos.down()).getCollisionShape(world, pos.down()).isEmpty();
    }
}
