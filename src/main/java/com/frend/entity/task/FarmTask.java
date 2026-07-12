package com.frend.entity.task;

import com.frend.FrendConfig;
import com.frend.entity.FrendEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;

/**
 * v0.20 种田:收熟庄稼 + 补种。规矩:
 * <ul>
 *   <li><b>只收熟透的</b>(CropBlock#isMature),青苗一根不碰——收青苗是祸害庄稼;</li>
 *   <li><b>收一茬补一茬</b>:收完从背包补种同种作物(种子多半就是刚收的掉落,任务捡拾会收进包);
 *       手头暂时没种子的坑记在小本上,拿到种子回头补,补不上如实汇报;</li>
 *   <li>支持小麦/胡萝卜/土豆/甜菜(原版四大田);耕地本身一块不动。</li>
 * </ul>
 * 已知小账(记录于 DEVLOG):实体从耕地上跳落可能踩坏耕地,原版机制,暂不专门规避。
 */
public class FarmTask extends FrendTask {

    private BlockPos target = null;
    private int harvested = 0;
    /** 补种欠账:收了还没来得及补种的坑(pos + 原作物),拿到种子回头补。 */
    private final ArrayDeque<long[]> pendingReplant = new ArrayDeque<>(); // [posLong, blockId, expireAge]
    private int missedReplant = 0;

    public FarmTask(FrendEntity frend) {
        super(frend);
    }

    @Override
    public String name() { return "收庄稼"; }

    @Override
    public boolean tick() {
        FrendConfig cfg = FrendConfig.get();

        // 先清补种欠账(种子多半是上一茬刚捡的)
        tryReplant();

        if (harvested >= cfg.maxBlocksPerJob) {
            finishLine();
            return false;
        }

        if (target == null || !isMatureCrop(frend.getWorld().getBlockState(target))) {
            target = findNearestMature(cfg);
            if (target != null) com.frend.system.FrendCrew.claim(frend, target); // v0.27 认领
            if (target == null) {
                if (pendingReplant.isEmpty()) {
                    finishLine();
                    return false;
                }
                return true; // 没得收了,把补种欠账清完再走
            }
        }

        if (!moveNear(target, cfg.workReach)) {
            if (stuckTicks() > 20 * 8) {
                target = null;
                resetStuck();
            }
            return true;
        }

        // 庄稼一掰就下来,给 8 tick 做个弯腰动作
        BlockState state = frend.getWorld().getBlockState(target);
        Block cropBlock = state.getBlock();
        if (breakTick(target, 8)) {
            harvested++;
            frend.getKnowledge().recordHarvest(); // v0.21 销 v0.19 挂账:种田进见识
            // 记补种欠账(掉落的种子要几 tick 才落地被捡到,直接补种多半没种子)
            pendingReplant.add(new long[]{target.asLong(),
                    Block.getRawIdFromState(cropBlock.getDefaultState()), frend.age + 200});
            target = null;
        }
        return true;
    }

    @Override
    public void onStop() {
        clearBreaking();
    }

    private void finishLine() {
        clearBreaking();
        if (harvested == 0) {
            frend.say("地里没有熟透的——青苗我可不碰,过两天再来。");
        } else if (missedReplant > 0) {
            frend.say("收了 " + harvested + " 茬。有 " + missedReplant + " 个坑没种子补,你看着撒点?");
        } else {
            frend.say("收了 " + harvested + " 茬,种子都补回去了——地还是满的。");
        }
    }

    // ===================== 补种 =====================

    private void tryReplant() {
        int n = pendingReplant.size();
        for (int i = 0; i < n; i++) {
            long[] entry = pendingReplant.poll();
            if (entry == null) break;
            BlockPos pos = BlockPos.fromLong(entry[0]);
            BlockState cropState = Block.getStateFromRawId((int) entry[1]);
            if (frend.age > entry[2]) { missedReplant++; continue; } // 过期:一直没种子,记账收工时汇报
            if (!frend.getWorld().getBlockState(pos).isAir()
                    || !frend.getWorld().getBlockState(pos.down()).isOf(Blocks.FARMLAND)) {
                continue; // 坑没了(耕地被踩/被占),这笔销账
            }
            Item seed = seedFor(cropState.getBlock());
            if (seed == null) continue;
            if (frend.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
                    > FrendConfig.get().workReach * FrendConfig.get().workReach) {
                pendingReplant.add(entry); // 够不着先不补(收下一茬的路上会回来),种子先不动
                continue;
            }
            if (!takeOne(seed)) { pendingReplant.add(entry); continue; } // 还没种子,回队尾等
            frend.getWorld().setBlockState(pos, cropState.getBlock().getDefaultState());
            frend.swingHand(Hand.MAIN_HAND, true);
        }
    }

    private static Item seedFor(Block crop) {
        if (crop == Blocks.WHEAT) return Items.WHEAT_SEEDS;
        if (crop == Blocks.CARROTS) return Items.CARROT;
        if (crop == Blocks.POTATOES) return Items.POTATO;
        if (crop == Blocks.BEETROOTS) return Items.BEETROOT_SEEDS;
        return null;
    }

    private boolean takeOne(Item item) {
        var inv = frend.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && s.isOf(item)) {
                s.decrement(1);
                return true;
            }
        }
        return false;
    }

    // ===================== 找熟庄稼 =====================

    private BlockPos findNearestMature(FrendConfig cfg) {
        BlockPos me = frend.getBlockPos();
        int r = (int) cfg.workSearchRadius;
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.iterate(me.add(-r, -4, -r), me.add(r, 4, r))) {
            if (!isMatureCrop(frend.getWorld().getBlockState(p))) continue;
            if (com.frend.system.FrendCrew.claimedByOther(frend, p)) continue; // v0.27 同伴认领的不抢
            double d = me.getSquaredDistance(p);
            if (d < bestD) { bestD = d; best = p.toImmutable(); }
        }
        return best;
    }

    /** 熟透的四大田作物。【待编译验证】CropBlock#isMature(BlockState) 是否 public。 */
    private static boolean isMatureCrop(BlockState state) {
        return state.getBlock() instanceof CropBlock crop && crop.isMature(state);
    }

    // ===================== 给自主决策用的静态判断 =====================

    /** 附近有没有熟透的庄稼(自主行动"顺手收一茬"用;半径收窄到 12,别为一根麦子跑二里地)。 */
    public static boolean hasMatureCropNearby(FrendEntity f) {
        BlockPos me = f.getBlockPos();
        for (BlockPos p : BlockPos.iterate(me.add(-12, -4, -12), me.add(12, 4, 12))) {
            if (isMatureCrop(f.getWorld().getBlockState(p))) return true;
        }
        return false;
    }
}
