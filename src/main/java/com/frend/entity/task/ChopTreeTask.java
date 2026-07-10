package com.frend.entity.task;

import com.frend.FrendConfig;
import com.frend.entity.FrendEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 砍树:搜半径内最近的原木 → BFS 收整棵树的原木(含斜向枝干) → 站到树根旁逐块砍。
 *
 * <p>决策(记入 DEVLOG):够得着树根就把整棵连通原木处理完,不真的爬树——
 * 高处枝干"隔空"砍,换来的是不卡寻路、不留半棵悬空树,v0.2 的取舍。
 * 斧头可选:有斧头快一倍并消耗耐久,耐久见底自动弃用;没斧头徒手慢慢锤。
 */
public class ChopTreeTask extends FrendTask {

    private static final int MAX_TREE_BLOCKS = 48;

    private final List<BlockPos> tree = new ArrayList<>(); // 当前这棵树的原木,底部优先
    private int chopped = 0;
    private boolean saidNoAxe = false;

    public ChopTreeTask(FrendEntity frend) {
        super(frend);
    }

    @Override
    public String name() { return "砍树"; }

    @Override
    public boolean tick() {
        FrendConfig cfg = FrendConfig.get();

        if (chopped >= cfg.maxBlocksPerJob) {
            frend.say("砍了 " + chopped + " 块原木,先收工!都在我包里。");
            clearBreaking();
            return false;
        }

        if (tree.isEmpty()) {
            BlockPos base = findNearestLog(cfg);
            if (base == null) {
                frend.say(chopped == 0 ? "附近没找着树……带我去有树的地方再喊我砍。"
                        : "这片的树砍完了,一共 " + chopped + " 块原木。");
                clearBreaking();
                return false;
            }
            collectTree(base);
        }

        BlockPos target = tree.get(0);
        // 站到这棵树最低一块的旁边即可(高处枝干隔空处理,见类注释)
        BlockPos stand = tree.get(0);
        if (!moveNear(stand, cfg.workReach)) {
            if (stuckTicks() > 20 * 8) { // 8 秒走不到 → 这棵放弃
                frend.say("这棵树我过不去,换一棵。");
                tree.clear();
                resetStuck();
            }
            return true;
        }

        ItemStack axe = frend.findUsableTool(ItemTags.AXES);
        if (axe.isEmpty() && !saidNoAxe && chopped == 0) {
            saidNoAxe = true;
            frend.sayDelayed("没斧头,我先用手锤,有斧头能快一倍。");
        }
        int perBlock = axe.isEmpty() ? cfg.chopTicksPerBlock * 2 : cfg.chopTicksPerBlock;

        if (breakTick(target, perBlock)) {
            tree.remove(0);
            chopped++;
            frend.getMemory().addChopped(1); // v0.4 记忆埋点
            if (!axe.isEmpty()) frend.damageTool(axe);
        }
        return true;
    }

    /** 搜索半径内最近的原木(取整棵树最低那块附近的入口即可)。 */
    private BlockPos findNearestLog(FrendConfig cfg) {
        int r = (int) cfg.workSearchRadius;
        BlockPos me = frend.getBlockPos();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.iterate(me.add(-r, -r, -r), me.add(r, r, r))) {
            if (!frend.getWorld().getBlockState(p).isIn(BlockTags.LOGS)) continue;
            double d = p.getSquaredDistance(me);
            if (d < bestD) {
                bestD = d;
                best = p.toImmutable();
            }
        }
        return best;
    }

    /** 从入口 BFS 收连通原木(3×3×3 邻域,兼容斜枝),按 低→高、近→远 排序。 */
    private void collectTree(BlockPos start) {
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty() && seen.size() < MAX_TREE_BLOCKS) {
            BlockPos cur = queue.poll();
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos n = cur.add(dx, dy, dz);
                        if (seen.contains(n)) continue;
                        if (frend.getWorld().getBlockState(n).isIn(BlockTags.LOGS)) {
                            seen.add(n.toImmutable());
                            queue.add(n);
                        }
                    }
        }
        tree.clear();
        tree.addAll(seen);
        BlockPos me = frend.getBlockPos();
        tree.sort((a, b) -> {
            if (a.getY() != b.getY()) return Integer.compare(a.getY(), b.getY());
            return Double.compare(a.getSquaredDistance(me), b.getSquaredDistance(me));
        });
    }

    @Override
    public void onStop() {
        clearBreaking();
    }
}
