package com.frend.pathing;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * v0.23 地形改造寻路(作者两次点名"参考 Baritone"):原版 mob 寻路把世界当成不可改的,
 * 路不通就是不通;Baritone 的核心思想是——<b>"挖一块"和"垫一块"本身就是路径的一步</b>,
 * 一并进 A* 算代价。本类是这个思想的 frend 版微缩实现。
 *
 * <p><b>出处与授权声明</b>:Baritone 是 LGPL-3.0 开源项目(github.com/cabaletta/baritone,
 * Copyright Baritone contributors)。v0.23 起为思路引用;<b>v0.24 起经作者明示授权
 * ("搬代码也不是不行……可以吃透他"),本文件含忠实改写自 Baritone 的算法与公式</b>
 * (挖速公式 ToolSet#calculateSpeedVsBlock、搭桥判定 MovementTraverse 的 backplace 分支),
 * 因此本项目整体采用 LGPL-3.0(见仓库根 LICENSE),出处按此保留。学到的记账:
 * <ol>
 *   <li>ActionCosts:代价一律以 tick 计(走一格 4.633,跳一格额外 ≈2);COST_INF 取 1e6
 *       而非 MAX_VALUE——代价会相加,MAX_VALUE 一加就溢出成负数(leijurv 注释原话的教训);</li>
 *   <li>Moves 枚举:TRAVERSE/ASCEND/DESCEND/PILLAR/DOWNWARD 各自一个代价函数,
 *       非法返回 INF,合法返回 tick 数(含挡路方块的挖掘耗时、垫块的放置耗时);</li>
 *   <li>getMiningDurationTicks:流体不挖(INF)、avoidBreaking 危险不挖(INF)、
 *       头顶悬沙要把连锁下落也算进代价;</li>
 *   <li>AStarPathFinder 的 bestSoFar:<b>到不了终点就返回"离目标最近的部分路径"</b>——
 *       走近一点也比原地干瞪眼强;外加节点数/耗时双保险熔断。</li>
 *   <li>v0.25 续吃三口:MovementDiagonal(斜走省 41% 路,两肩必须双通防切角)、
 *       MovementFall 的精神(超 3 格带伤下落,疼痛计价,血厚才肯跳)、
 *       后台计算的<b>本质</b>——不卡刻。Baritone 敢开线程是因为自带世界快照
 *       (BlockStateInterface);原版 World 非线程安全,我们改<b>主线程分片续算</b>
 *       (Session,每 tick 限节点数+纳秒双预算),零卡顿同效果,风险小十倍,取舍在案。</li>
 * </ol>
 *
 * <p><b>frend 版收敛(跟 Baritone 不同的地方,防拆家红线优先)</b>:
 * 挖掘白名单 = 天然方块(石头族/泥土族/沙/沙砾/原木/树叶/矿石),与 TunnelTask 同规——
 * <b>人造方块(木板/玻璃/混凝土……)在寻路里等于基岩</b>,宁可绕十里不碰一块;
 * 不游泳(水路 = 不通,v1 收敛);DIG_DOWN 只许挖一层(脚下的下面必须实心,不打无底洞);
 * 同步单次计算,节点 2400/耗时 10ms 双熔断,不卡服务端 tick。
 */
public final class FrendPathfinder {

    // ===== 代价表(tick 计,学 ActionCosts 的口径,数值自算) =====
    private static final double WALK = 4.633;        // 20 / 4.317 m/s
    private static final double JUMP_EXTRA = 2.0;     // 上一格的额外耗时
    private static final double PLACE = 4.0;          // 掏方块+放置一次
    private static final double PILLAR_NUDGE = 2.0;   // 垫块吃材料,轻微劝退:能走就别垫
    private static final double[] FALL_COST = {0, 1.0, 3.0, 7.0}; // 落 1/2/3 格(无伤)

    /** 摔落计价:3 格内查表;超 3 格 = 带伤下落(学 MovementFall 的精神),每格伤按 12 tick 心理价。 */
    private static double fallCost(int drop) {
        return drop <= 3 ? FALL_COST[drop] : 7.0 + (drop - 3) * 12.0;
    }
    private static final double BRIDGE_WALK = 8.0;    // 学 MovementTraverse:搭桥按潜行速度计,比走贵
    public static final double COST_INF = 1_000_000;  // 学 Baritone:别用 MAX_VALUE,相加会溢出

    private static final int MAX_RADIUS = 40;          // 搜索半径箱
    private static final long TIME_BUDGET_NANOS = 10_000_000L; // 10ms 熔断

    public enum MoveType { WALK, ASCEND, DESCEND, PILLAR, DIG_DOWN, BRIDGE }

    private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** 一步:类型 + 落脚点 + 计划要挖的方块(执行时会重校验) + 是否垫块。 */
    public record Step(MoveType type, BlockPos to, List<BlockPos> toBreak, boolean place) {}

    /** 挖穿耗时提供器(tick)——由调用方按自己包里的工具算(忠实移植 ToolSet 公式);null 用粗估。 */
    public interface BreakClock { double ticksFor(BlockState state, BlockPos pos); }

    private FrendPathfinder() {}

    // ===================== 对外入口 =====================

    /**
     * 从 start(脚位)找到"距 goal 中心 ≤ reach"的路。找不到完整路时,若部分路径能明显走近
     * (至少省 2 格),返回部分路径;彻底没戏返回 null。同步计算,自带熔断。
     *
     * @param scaffoldBudget 包里可用的垫块数(0 = 不考虑 PILLAR)
     */
    public static List<Step> find(World world, BlockPos start, BlockPos goal,
                                  double reach, int scaffoldBudget, int maxNodes, BreakClock clock) {
        Session s = new Session(world, start, goal, reach, scaffoldBudget, maxNodes, 3, clock);
        if (!s.tickCalc(maxNodes, TIME_BUDGET_NANOS)) s.finish(); // 同步便捷入口:一片算完,超时收部分路
        return s.result();
    }

    /**
     * v0.25 可续算会话(吃"异步"的本质=不卡刻):调用方每 tick 给一小片预算(节点数+纳秒双限),
     * 算完(成败皆算完)返回 true,result() 取路——大预算长途也不卡服务端一刻。
     */
    public static final class Session {
        private final World world;
        private final BlockPos start, goal;
        private final double reachSq;
        private final int scaffoldBudget, maxNodes, maxDrop;
        private final BreakClock clock;
        private final Map<Long, Node> all = new HashMap<>();
        private final PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        private Node best;
        private double bestH;
        private final double startH;
        private int expanded = 0;
        private boolean done = false;
        private List<Step> result = null;

        public Session(World world, BlockPos start, BlockPos goal, double reach,
                       int scaffoldBudget, int maxNodes, int maxDrop, BreakClock clock) {
            this.world = world;
            this.start = start;
            this.goal = goal;
            this.reachSq = reach * reach;
            this.scaffoldBudget = scaffoldBudget;
            this.maxNodes = maxNodes;
            this.maxDrop = Math.max(1, Math.min(FALL_HARD_CAP, maxDrop));
            this.clock = clock;
            Node startNode = new Node(start.getX(), start.getY(), start.getZ());
            startNode.g = 0;
            startNode.f = heuristic(startNode, goal);
            all.put(key(startNode.x, startNode.y, startNode.z), startNode);
            open.add(startNode);
            best = startNode;
            startH = bestH = heuristic(startNode, goal);
        }

        /** 推进一片:最多 nodeBudget 个节点或 nanoBudget 纳秒。返回是否算完。 */
        public boolean tickCalc(int nodeBudget, long nanoBudget) {
            if (done) return true;
            long deadline = System.nanoTime() + nanoBudget;
            int sliceEnd = expanded + nodeBudget;
            while (!open.isEmpty() && expanded < sliceEnd && expanded < maxNodes) {
                if ((expanded & 0x3F) == 0 && System.nanoTime() > deadline) return false; // 本片时间到,下 tick 续
                Node cur = open.poll();
                if (cur.closed) continue;
                cur.closed = true;
                expanded++;
                if (distSqToCenter(cur, goal) <= reachSq) { // 到了
                    result = reconstruct(cur);
                    done = true;
                    return true;
                }
                double h = heuristic(cur, goal);
                if (h < bestH) { bestH = h; best = cur; }
                expand(world, cur, goal, start, scaffoldBudget, maxDrop, all, open, clock);
            }
            if (open.isEmpty() || expanded >= maxNodes) finish();
            return done;
        }

        /** 收尾:没到终点则看部分路径值不值(至少比出发点近 2 格)。 */
        public void finish() {
            if (done) return;
            done = true;
            if (startH - bestH >= 2 * WALK) result = reconstruct(best);
        }

        /** 算完后取路;null = 彻底没戏。 */
        public List<Step> result() { return result; }
    }

    // ===================== 扩展(五种移动,学 Moves 的组织法) =====================

    private static final int FALL_HARD_CAP = 8; // 再高真摔死,多少血都不许

    private static void expand(World world, Node cur, BlockPos goal, BlockPos origin,
                               int scaffoldBudget, int maxDrop, Map<Long, Node> all,
                               PriorityQueue<Node> open, BreakClock clock) {
        int x = cur.x, y = cur.y, z = cur.z;
        // 半径箱:出发点为中心,别越搜越野
        if (Math.abs(x - origin.getX()) > MAX_RADIUS || Math.abs(z - origin.getZ()) > MAX_RADIUS
                || Math.abs(y - origin.getY()) > 24) return;

        for (Direction d : Direction.Type.HORIZONTAL) {
            int nx = x + d.getOffsetX(), nz = z + d.getOffsetZ();

            // --- TRAVERSE / DESCEND:平走,或走出边缘落 1~3 格 ---
            double breaks = 0;
            List<BlockPos> toBreak = new ArrayList<>(2);
            breaks += passOrBreak(world, new BlockPos(nx, y, nz), toBreak, clock);
            breaks += passOrBreak(world, new BlockPos(nx, y + 1, nz), toBreak, clock);
            if (breaks < COST_INF) {
                if (standable(world, new BlockPos(nx, y - 1, nz))) {
                    relax(all, open, cur, nx, y, nz, WALK + breaks, MoveType.WALK, toBreak, false, goal);
                } else {
                    for (int drop = 1; drop <= maxDrop; drop++) { // 走下去(3 格内无伤;更深=带伤下落,血厚才敢)
                        BlockPos floor = new BlockPos(nx, y - drop - 1, nz);
                        BlockPos air = new BlockPos(nx, y - drop, nz);
                        if (!passable(world, air)) break; // 落道里有东西挡着,摔不下去
                        if (standable(world, floor)) {
                            relax(all, open, cur, nx, y - drop, nz,
                                    WALK + breaks + fallCost(drop), MoveType.DESCEND, toBreak, false, goal);
                            break;
                        }
                    }
                    // BRIDGE(忠实改写 MovementTraverse 的 bridge/backplace 分支,作者授权):
                    // 落脚点没地板 → 贴着脚下的块放一块走过去。跨沟/跨水都靠它;岩浆上不放
                    // (Baritone 查 5 向 canPlaceAgainst;我们的节点按构造必有立足,背放永远可用,
                    // 故省掉该扫描——mob 直接放置,无玩家右键合法性问题,简化记录在案)。
                    if (cur.placed < scaffoldBudget && breaks < COST_INF) {
                        BlockPos floorPos = new BlockPos(nx, y - 1, nz);
                        BlockState fs = world.getBlockState(floorPos);
                        // 【待编译验证】AbstractBlockState#isReplaceable
                        if (fs.isReplaceable() && !fs.getFluidState().isIn(FluidTags.LAVA)) {
                            Node n = relax(all, open, cur, nx, y, nz,
                                    BRIDGE_WALK + PLACE + breaks, MoveType.BRIDGE, toBreak, true, goal);
                            if (n != null) n.placed = cur.placed + 1;
                        }
                    }
                }
            }

            // --- ASCEND:上一格(落脚地板必须已实心,垫块交给 PILLAR 管) ---
            if (standable(world, new BlockPos(nx, y, nz))) {
                double b2 = 0;
                List<BlockPos> tb2 = new ArrayList<>(3);
                b2 += passOrBreak(world, new BlockPos(x, y + 2, z), tb2, clock);     // 起跳头顶
                b2 += passOrBreak(world, new BlockPos(nx, y + 1, nz), tb2, clock);   // 落点脚
                b2 += passOrBreak(world, new BlockPos(nx, y + 2, nz), tb2, clock);   // 落点头
                if (b2 < COST_INF) {
                    relax(all, open, cur, nx, y + 1, nz,
                            WALK + JUMP_EXTRA + b2, MoveType.ASCEND, tb2, false, goal);
                }
            }
        }

        // --- DIAGONAL(学 MovementDiagonal):斜走省 41% 路,像人;不挖不垫,
        //     两个直角"肩膀"必须双通(Baritone 同规:斜穿实体拐角会卡住/受伤) ---
        for (int[] dd : DIAGONALS) {
            int nx = x + dd[0], nz = z + dd[1];
            if (!standable(world, new BlockPos(nx, y - 1, nz))) continue;
            if (!passable(world, new BlockPos(nx, y, nz)) || !passable(world, new BlockPos(nx, y + 1, nz))) continue;
            if (!passable(world, new BlockPos(x + dd[0], y, z)) || !passable(world, new BlockPos(x + dd[0], y + 1, z))) continue;
            if (!passable(world, new BlockPos(x, y, z + dd[1])) || !passable(world, new BlockPos(x, y + 1, z + dd[1]))) continue;
            relax(all, open, cur, nx, y, nz, WALK * 1.4142, MoveType.WALK, List.of(), false, goal);
        }

        // --- PILLAR:原地垫一块上 1 格(材料预算内) ---
        if (cur.placed < scaffoldBudget) {
            List<BlockPos> tb = new ArrayList<>(1);
            double b = passOrBreak(world, new BlockPos(x, y + 2, z), tb, clock); // 头顶开路(常见是树叶)
            if (b < COST_INF) {
                Node n = relax(all, open, cur, x, y + 1, z,
                        JUMP_EXTRA + PLACE + PILLAR_NUDGE + b, MoveType.PILLAR, tb, true, goal);
                if (n != null) n.placed = cur.placed + 1;
            }
        }

        // --- DIG_DOWN:挖脚下一层(下面的下面必须实心——只下一层,不打无底洞) ---
        BlockPos under = new BlockPos(x, y - 1, z);
        BlockPos under2 = new BlockPos(x, y - 2, z);
        if (standable(world, under2)) {
            double b = breakCost(world, under, world.getBlockState(under), clock);
            if (b > 0 && b < COST_INF) {
                List<BlockPos> tb = new ArrayList<>(1);
                tb.add(under);
                relax(all, open, cur, x, y - 1, z, b + 2.0, MoveType.DIG_DOWN, tb, false, goal);
            }
        }
    }

    /** 松弛一条边;更优则入队,返回新节点(供 PILLAR 记材料账),否则 null。 */
    private static Node relax(Map<Long, Node> all, PriorityQueue<Node> open, Node from,
                              int x, int y, int z, double edgeCost, MoveType type,
                              List<BlockPos> toBreak, boolean place, BlockPos goal) {
        long k = key(x, y, z);
        Node n = all.get(k);
        double g = from.g + edgeCost;
        if (n == null) {
            n = new Node(x, y, z);
            all.put(k, n);
        } else if (n.closed || g >= n.g) {
            return null;
        }
        n.g = g;
        n.f = g + heuristic(n, goal);
        n.parent = from;
        n.via = new Step(type, new BlockPos(x, y, z), List.copyOf(toBreak), place);
        n.placed = from.placed;
        open.add(n); // 惰性去重:旧条目留在堆里,poll 出来发现 closed 就跳过
        return n;
    }

    // ===================== 方块判定(与 FrendTask/TunnelTask 同规) =====================

    /** 能直接穿过返回 0;能挖(天然+安全)返回挖掘代价并记入 toBreak;否则 INF。 */
    private static double passOrBreak(World world, BlockPos p, List<BlockPos> toBreak, BreakClock clock) {
        if (passable(world, p)) return 0;
        double c = breakCost(world, p, world.getBlockState(p), clock);
        if (c < COST_INF) toBreak.add(p);
        return c;
    }

    /** 可通行:无流体且无碰撞箱(草丛/花能穿;水路不通,v1 不游泳)。【待编译验证】getCollisionShape */
    public static boolean passable(World world, BlockPos p) {
        BlockState s = world.getBlockState(p);
        return s.getFluidState().isEmpty() && s.getCollisionShape(world, p).isEmpty();
    }

    /** 可站立:实心且顶上没流体(岩浆湖边别站)。 */
    public static boolean standable(World world, BlockPos floor) {
        BlockState s = world.getBlockState(floor);
        return s.isSolidBlock(world, floor) && world.getFluidState(floor.up()).isEmpty()
                && !s.isOf(Blocks.MAGMA_BLOCK); // 站上去掉血,绕开
    }

    /**
     * 挖掘代价(tick):天然白名单之外 = 基岩(INF,防拆家红线);危险 = INF;
     * 耗时由 clock 按真工具算(忠实移植 ToolSet 公式),没 clock 用粗估 25+硬度×35。
     */
    public static double breakCost(World world, BlockPos p, BlockState s, BreakClock clock) {
        if (!naturalBreakable(s)) return COST_INF;
        if (s.getHardness(world, p) < 0) return COST_INF; // 基岩
        if (dangerous(world, p)) return COST_INF;
        double cost = ticksOf(world, p, s, clock);
        for (int dy = 1; dy <= 2; dy++) { // 学 getMiningDurationTicks:悬沙连锁也计价
            BlockPos ap = p.up(dy);
            BlockState above = world.getBlockState(ap);
            if (above.getBlock() instanceof FallingBlock) cost += ticksOf(world, ap, above, clock);
            else break;
        }
        return cost;
    }

    private static double ticksOf(World world, BlockPos p, BlockState s, BreakClock clock) {
        if (clock != null) return clock.ticksFor(s, p);
        return 25 + s.getHardness(world, p) * 35;
    }

    /** 危险判定(与 FrendTask#miningDanger 同规,执行层复验也用它):贴岩浆/顶液体。 */
    public static boolean dangerous(World world, BlockPos p) {
        for (Direction d : Direction.values()) {
            if (world.getFluidState(p.offset(d)).isIn(FluidTags.LAVA)) return true;
        }
        return !world.getFluidState(p.up()).isEmpty();
    }

    /** 天然方块白名单(与 TunnelTask#tunnelMinable 同规,外加树系):名单之外一概不碰。 */
    public static boolean naturalBreakable(BlockState s) {
        return s.isIn(BlockTags.BASE_STONE_OVERWORLD) || s.isIn(BlockTags.BASE_STONE_NETHER)
                || s.isOf(Blocks.COBBLESTONE) || s.isOf(Blocks.DIRT) || s.isOf(Blocks.GRASS_BLOCK)
                || s.isOf(Blocks.GRAVEL) || s.isOf(Blocks.SAND) || s.isOf(Blocks.RED_SAND)
                || s.isOf(Blocks.SANDSTONE) || s.isOf(Blocks.SNOW_BLOCK)
                || s.isIn(BlockTags.LOGS) || s.isIn(BlockTags.LEAVES)
                || s.isIn(BlockTags.COAL_ORES) || s.isIn(BlockTags.IRON_ORES)
                || s.isIn(BlockTags.COPPER_ORES) || s.isIn(BlockTags.GOLD_ORES)
                || s.isIn(BlockTags.REDSTONE_ORES) || s.isIn(BlockTags.LAPIS_ORES)
                || s.isIn(BlockTags.DIAMOND_ORES) || s.isIn(BlockTags.EMERALD_ORES);
    }

    // ===================== 内务 =====================

    private static double heuristic(Node n, BlockPos goal) {
        double dx = goal.getX() + 0.5 - (n.x + 0.5);
        double dy = goal.getY() + 0.5 - (n.y + 0.5);
        double dz = goal.getZ() + 0.5 - (n.z + 0.5);
        return Math.sqrt(dx * dx + dy * dy + dz * dz) * WALK;
    }

    private static double distSqToCenter(Node n, BlockPos goal) {
        double dx = goal.getX() + 0.5 - (n.x + 0.5);
        double dy = goal.getY() + 0.5 - (n.y + 1.0); // 用眼位比脚位更接近"够得着"的语义
        double dz = goal.getZ() + 0.5 - (n.z + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    private static List<Step> reconstruct(Node end) {
        ArrayList<Step> steps = new ArrayList<>();
        for (Node n = end; n != null && n.via != null; n = n.parent) steps.add(n.via);
        java.util.Collections.reverse(steps);
        return steps.isEmpty() ? null : steps;
    }

    private static long key(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    private static final class Node {
        final int x, y, z;
        double g = Double.MAX_VALUE, f;
        Node parent;
        Step via;
        boolean closed;
        int placed; // 到这一步为止垫了几块(PILLAR 材料预算)

        Node(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }
}
