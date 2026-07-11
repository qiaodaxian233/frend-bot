package com.frend.entity.task;

import com.frend.FrendConfig;
import com.frend.entity.FrendEntity;
import net.minecraft.util.math.BlockPos;

/**
 * 干活任务基类(v0.2)。任务是<b>不落盘</b>的瞬时状态:存档重载后 WORK 模式会退回 STAY。
 *
 * <p>约定:
 * <ul>
 *   <li>{@link #tick()} 每个服务端 tick 调一次;返回 false = 任务结束(收尾话自己说)。</li>
 *   <li>任务只在 Mode.WORK 下运行;主人一句"跟我来"切走模式,任务即作废(自然打断)。</li>
 *   <li>公共小机关:{@link #moveNear} 够不着先走过去(带卡死放弃),{@link #breakTick} 按耗时
 *       "慢慢挖"(挥手 + 破坏进度动画),不瞬间爆破——像人,不像指令方块。</li>
 * </ul>
 */
public abstract class FrendTask {

    protected final FrendEntity frend;

    /** 正在挖的方块与进度。 */
    private BlockPos breakingPos = null;
    private int breakProgress = 0;

    /** 走不动的累计 tick(moveNear 用)。 */
    private int stuckTicks = 0;

    protected FrendTask(FrendEntity frend) {
        this.frend = frend;
    }

    /** 每 tick 调;返回 false = 任务结束。 */
    public abstract boolean tick();

    /** 汇报用名称,如"砍树"。 */
    public abstract String name();

    /** 被打断/收工时调用(默认无事)。 */
    public void onStop() {}

    // ===================== 公共小机关 =====================

    /**
     * 够得着 pos 就返回 true;够不着朝它走,连续 stuckLimit tick 没走近视作卡死,
     * 返回 false 且 {@link #isStuck()} 为 true(调用方决定放弃谁)。
     */
    protected boolean moveNear(BlockPos pos, double reach) {
        double d2 = frend.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (d2 <= reach * reach) {
            stuckTicks = 0;
            frend.getNavigation().stop();
            return true;
        }
        frend.navigateSmart(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                FrendConfig.get().followSpeed); // v0.12 长途分段:存箱子回家那腿再远也走得动
        stuckTicks++;
        return false;
    }

    /** moveNear 连续多久没到位(tick)。 */
    protected int stuckTicks() { return stuckTicks; }

    protected void resetStuck() { stuckTicks = 0; }

    /**
     * 对 pos "慢慢挖"一 tick:朝它看、周期性挥手、播破坏进度动画;
     * 累计满 totalTicks 后真正破坏(掉落物照常掉)并返回 true。换目标自动重置进度。
     */
    protected boolean breakTick(BlockPos pos, int totalTicks) {
        if (!pos.equals(breakingPos)) {
            clearBreaking();
            breakingPos = pos.toImmutable();
            breakProgress = 0;
        }
        frend.getLookControl().lookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (breakProgress % 6 == 0) frend.swingHand(net.minecraft.util.Hand.MAIN_HAND, true);

        breakProgress++;
        int stage = Math.min(9, breakProgress * 10 / Math.max(1, totalTicks));
        // 【待编译验证】World#setBlockBreakingInfo(int breakerId, BlockPos, int stage 0-9/-1清除)
        frend.getWorld().setBlockBreakingInfo(frend.getId(), pos, stage);

        if (breakProgress >= totalTicks) {
            frend.getWorld().setBlockBreakingInfo(frend.getId(), pos, -1);
            frend.getWorld().breakBlock(pos, true, frend); // 掉落物由实体侧的"干活捡东西"收进背包
            breakingPos = null;
            breakProgress = 0;
            return true;
        }
        return false;
    }

    /** 清掉挖掘进度动画(任务结束/换目标时)。 */
    protected void clearBreaking() {
        if (breakingPos != null) {
            frend.getWorld().setBlockBreakingInfo(frend.getId(), breakingPos, -1);
            breakingPos = null;
            breakProgress = 0;
        }
    }

    /**
     * v0.6 挖掘避险,v0.13 提炼到基类共用(MineTask/TunnelTask):
     * 安全返回 null,不敢挖返回原因(给嘴上解释用)。
     * 规则:六邻贴岩浆不挖(挖穿被浇);头顶两格悬沙/沙砾不挖(挖了被砸)。
     */
    protected String miningDanger(BlockPos p) {
        if (!FrendConfig.get().mineSafetyEnabled) return null;
        var world = frend.getWorld();
        for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.values()) {
            if (world.getFluidState(p.offset(dir)).isIn(net.minecraft.registry.tag.FluidTags.LAVA)) {
                return "那块贴着岩浆,我不碰,命要紧。";
            }
        }
        for (int dy = 1; dy <= 2; dy++) {
            if (world.getBlockState(p.up(dy)).getBlock() instanceof net.minecraft.block.FallingBlock) {
                return "那块头顶悬着沙子,挖了会被砸,跳过。";
            }
        }
        return null;
    }
}
