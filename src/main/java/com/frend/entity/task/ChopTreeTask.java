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
 *
 * <p>v0.22 实测首修(作者实测:悬空树反复"换一棵"刷屏死循环):
 * ① 放弃的树<b>整棵</b>进黑名单,findNearestLog 不再重选(之前"换一棵"是假的,换来换去同一棵);
 * ② 站到树正下方但目标在头顶够不着 → <b>搭登高柱</b>(垫包里的土/圆石,砍完拆干净材料回包);
 * ③ 垫不了(没材料/到顶)才真放弃;连弃 3 棵说明这片地形不行,收工如实汇报,不无限磨。
 */
public class ChopTreeTask extends FrendTask {

    private static final int MAX_TREE_BLOCKS = 48;

    private final List<BlockPos> tree = new ArrayList<>(); // 当前这棵树的原木,底部优先
    private int chopped = 0;
    private boolean saidNoAxe = false;
    /** v0.22 放弃过的原木(整棵进名单),findNearestLog 跳过——"换一棵"要真的换。 */
    private final Set<BlockPos> unreachable = new HashSet<>();
    private int givenUp = 0;
    private boolean saidNoScaffold = false;
    private static final int SCAFFOLD_MAX = 8; // 登高柱上限(格),再高的树不伺候

    public ChopTreeTask(FrendEntity frend) {
        super(frend);
    }

    @Override
    public String name() { return "砍树"; }

    @Override
    public boolean tick() {
        FrendConfig cfg = FrendConfig.get();

        if (chopped >= cfg.maxBlocksPerJob) {
            if (!tearDownScaffoldTick()) return true; // 收工前把柱子拆干净
            frend.say("砍了 " + chopped + " 块原木,先收工!都在我包里。");
            clearBreaking();
            return false;
        }

        if (tree.isEmpty()) {
            if (!tearDownScaffoldTick()) return true; // 换树之间先落地,柱子拆干净材料回包
            if (givenUp >= 3) { // 连弃 3 棵:这片地形不行,收工(话在 giveUpTree 已说)
                clearBreaking();
                return false;
            }
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
        if (!moveNearSmart(target, cfg.workReach)) { // v0.23 开路寻路:原版没戏就自己挖/垫出一条
            if (isCarving()) return true; // 正在开路,别触发放弃逻辑
            // 已在树正下方、目标在头顶(悬空树/高枝) → 搭登高柱,而不是干瞪眼
            double dx = target.getX() + 0.5 - frend.getX();
            double dz = target.getZ() + 0.5 - frend.getZ();
            if (dx * dx + dz * dz <= 2.5 * 2.5 && target.getY() > frend.getBlockPos().getY()) {
                frend.getNavigation().stop(); // 登高时别让寻路拽着乱走,人得站稳
                if (pillarUpTick(SCAFFOLD_MAX)) {
                    resetStuck();
                    return true; // 在登高/敲树叶/冷却,下 tick 继续
                }
                // 登不了:没材料 / 到上限 / 头顶硬方块 → 这棵真放弃
                if (!hasScaffoldMaterial() && !saidNoScaffold) {
                    saidNoScaffold = true;
                    frend.say("上面那截够不着,包里又没土没圆石垫脚……有废料给我点,这种树我就能上。");
                }
                giveUpTree();
                return true;
            }
            if (stuckTicks() > 20 * 8) { // 8 秒走不到 → 这棵放弃(整棵进黑名单)
                frend.say("这棵树我过不去,先换一棵。");
                giveUpTree();
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
            if (unreachable.contains(p)) continue; // v0.22 放弃过的不再重选
            if (com.frend.system.FrendCrew.claimedByOther(frend, p)) continue; // v0.27 同伴认领的树不抢
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
        for (BlockPos p : tree) com.frend.system.FrendCrew.claim(frend, p); // v0.27 认领整棵:你砍那棵我砍这棵
    }

    /** 放弃当前这棵:整棵剩余原木进黑名单(不然下轮又选中同棵的另一块);连弃 3 棵收工。 */
    private void giveUpTree() {
        for (BlockPos p : tree) com.frend.system.FrendCrew.release(frend, p); // v0.27 弃的树让给同伴(它也许有垫脚料)
        unreachable.addAll(tree); // tree 里就是这棵剩下的全部原木
        tree.clear();
        resetStuck();
        givenUp++;
        if (givenUp >= 3) {
            frend.say(chopped == 0 ? "这片的树尽是够不着的,我先歇了……换个好走的林子再喊我?"
                    : "够得着的都砍了(" + chopped + " 块),剩下几棵实在上不去,先收工。");
        }
    }

    @Override
    public void onStop() {
        clearBreaking();
        discardScaffoldNow(); // 被打断也不留柱子
    }
}
