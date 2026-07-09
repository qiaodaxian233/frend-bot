package com.frend.entity.task;

import com.frend.FrendConfig;
import com.frend.entity.FrendEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * 挖掘:STONE(石头/深板岩)或 ORE(煤矿/铁矿,含深板岩变种,走 BlockTags)。
 *
 * <p>规矩:
 * <ul>
 *   <li><b>必须有镐</b>,没镐开口要,任务不开工;镐耐久见底(≤ toolReserveDurability)收工并说明。</li>
 *   <li>只挖<b>露头</b>的目标(至少一面挨着空气)——不无脑打洞;也不挖自己脚下那块(不自埋)。</li>
 *   <li>不做避岩浆/火把(设计文档排到 v0.5),DEVLOG 有记。</li>
 * </ul>
 */
public class MineTask extends FrendTask {

    public enum Kind { STONE, ORE }

    private final Kind kind;
    private BlockPos target = null;
    private int mined = 0;

    public MineTask(FrendEntity frend, Kind kind) {
        super(frend);
        this.kind = kind;
    }

    @Override
    public String name() { return kind == Kind.STONE ? "挖石头" : "挖煤铁"; }

    @Override
    public boolean tick() {
        FrendConfig cfg = FrendConfig.get();

        ItemStack pick = frend.findUsableTool(ItemTags.PICKAXES);
        if (pick.isEmpty()) {
            frend.say(mined == 0 ? "没镐子挖不了,给我一把镐呗(放我背包里就行)。"
                    : "镐子不行了,先收工,挖了 " + mined + " 块。");
            clearBreaking();
            return false;
        }

        if (mined >= cfg.maxBlocksPerJob) {
            frend.say("挖了 " + mined + " 块,先收工!都在我包里。");
            clearBreaking();
            return false;
        }

        if (target == null || !matches(frend.getWorld().getBlockState(target))) {
            target = findNearest(cfg);
            if (target == null) {
                frend.say(mined == 0
                        ? (kind == Kind.STONE ? "附近没露头的石头,带我去山边或洞口再喊我。"
                                              : "附近没露头的煤铁,得找个矿洞或山壁。")
                        : "这片挖干净了,一共 " + mined + " 块。");
                clearBreaking();
                return false;
            }
        }

        if (!moveNear(target, cfg.workReach)) {
            if (stuckTicks() > 20 * 8) {
                target = null; // 这块过不去,换目标
                resetStuck();
            }
            return true;
        }

        if (breakTick(target, cfg.mineTicksPerBlock)) {
            mined++;
            frend.damageTool(pick);
            target = null;
        }
        return true;
    }

    private boolean matches(BlockState state) {
        if (kind == Kind.STONE) {
            return state.isOf(Blocks.STONE) || state.isOf(Blocks.DEEPSLATE) || state.isOf(Blocks.COBBLESTONE);
        }
        return state.isIn(BlockTags.COAL_ORES) || state.isIn(BlockTags.IRON_ORES);
    }

    /** 找最近的、至少一面露空气的目标方块;跳过自己脚下那块(不自埋)。 */
    private BlockPos findNearest(FrendConfig cfg) {
        int r = (int) cfg.workSearchRadius;
        BlockPos me = frend.getBlockPos();
        BlockPos below = me.down();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.iterate(me.add(-r, -r, -r), me.add(r, r, r))) {
            if (p.equals(below)) continue;
            if (!matches(frend.getWorld().getBlockState(p))) continue;
            if (!exposed(p)) continue;
            double d = p.getSquaredDistance(me);
            if (d < bestD) {
                bestD = d;
                best = p.toImmutable();
            }
        }
        return best;
    }

    private boolean exposed(BlockPos p) {
        for (Direction dir : Direction.values()) {
            if (frend.getWorld().getBlockState(p.offset(dir)).isAir()) return true;
        }
        return false;
    }

    @Override
    public void onStop() {
        clearBreaking();
    }
}
