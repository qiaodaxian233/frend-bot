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

    // ===================== v0.23 开路寻路(学 Baritone:挖/垫都是路径的一步) =====================
    // 用法:任务里把 moveNear 换成 moveNearSmart 即可。先信原版寻路 3 秒;没戏就调
    // FrendPathfinder 算一条"允许挖天然方块、允许垫块"的路,逐步执行——挖走 breakTick
    // (带破坏动画),垫走 pillarUpTick(带音效节奏),失败自动回落原版 + stuck 计数照旧。

    private java.util.List<com.frend.pathing.FrendPathfinder.Step> carvePath = null;
    private int carveIndex = 0;
    private int carveStepTicks = 0;
    private int carveCooldown = 0;   // 算过没找到路的冷却,防止每 tick 白算
    private boolean saidCarve = false;
    /** v0.25 分片续算会话(每 tick 3ms/300 节点,大预算长途也不卡刻)。 */
    private com.frend.pathing.FrendPathfinder.Session carveCalc = null;
    private BlockPos carveGoal = null; // 会话/路径对应的目标,目标一换旧账作废

    /** 正在照开出来的路走(调用方在这期间别触发自己的放弃逻辑)。 */
    protected boolean isCarving() { return carvePath != null; }

    /** 包里可垫的方块总数(封顶 16,寻路预算用)。 */
    private int scaffoldBudget() {
        int n = 0;
        var inv = frend.getInventory();
        for (int i = 0; i < inv.size() && n < 16; i++) {
            if (isScaffoldItem(inv.getStack(i).getItem())) n += inv.getStack(i).getCount();
        }
        return Math.min(16, n);
    }

    /**
     * 智能接近:够得着返回 true。原版寻路优先;卡 3 秒转开路 A*(挖天然方块/垫块都算路);
     * 开路也没戏就回落原版寻路 + stuck 累计,调用方原有的放弃逻辑照常工作。
     */
    protected boolean moveNearSmart(BlockPos pos, double reach) {
        double d2 = frend.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (d2 <= reach * reach) {
            carvePath = null;
            carveCalc = null;
            stuckTicks = 0;
            frend.getNavigation().stop();
            return true;
        }
        if (carveGoal != null && !carveGoal.equals(pos)) { // 目标换了,旧路旧账全作废
            carvePath = null;
            carveCalc = null;
            carveGoal = null;
        }
        if (carvePath != null) {
            followCarveTick();
            stuckTicks = 0; // 开路进行中不算卡死(它自己有单步 6 秒的看门狗)
            return false;
        }

        // v0.25 分片续算:每 tick 一小片(3ms/300 节点),算路期间原版寻路照蹭,不卡刻
        if (carveCalc != null) {
            if (carveCalc.tickCalc(300, 3_000_000L)) {
                var path = carveCalc.result();
                carveCalc = null;
                if (path != null) {
                    carvePath = path;
                    carveIndex = 0;
                    carveStepTicks = 0;
                    boolean modifies = path.stream().anyMatch(s -> s.place() || !s.toBreak().isEmpty());
                    if (modifies && !saidCarve) {
                        saidCarve = true;
                        frend.sayDelayed("这路不通……我自己开一条,挖的都是天然方块,放心。");
                    }
                    followCarveTick();
                    stuckTicks = 0;
                    return false;
                }
                carveCooldown = 100; // 彻底没戏,5 秒后再议
            }
        }
        if (carveCooldown > 0) carveCooldown--;

        boolean arrived = moveNear(pos, reach); // 原版寻路(带 stuck 累计)
        if (arrived) {
            carveCalc = null;
            return true;
        }

        if (stuckTicks > 60 && carveCooldown == 0 && carveCalc == null) { // 3 秒原版没戏 → 起一个开路会话
            int maxDrop = frend.getHealth() > 14.0f ? 6 : 3; // 血厚才肯带伤跳崖(学 MovementFall 的精神)
            carveCalc = new com.frend.pathing.FrendPathfinder.Session(
                    frend.getWorld(), frend.getBlockPos(), pos, reach, scaffoldBudget(), 6000, maxDrop,
                    this::carveTicksFor);
            carveGoal = pos.toImmutable();
        }
        return false;
    }

    /** 照开出来的路走一 tick:先挖挡路的(重校验),再垫/再走;单步 6 秒不过 = 弃路回落。 */
    private void followCarveTick() {
        if (carveIndex >= carvePath.size()) { carvePath = null; return; }
        var step = carvePath.get(carveIndex);
        if (++carveStepTicks > 20 * 6) { carvePath = null; return; }

        BlockPos to = step.to();
        double dx = to.getX() + 0.5 - frend.getX();
        double dz = to.getZ() + 0.5 - frend.getZ();
        if (dx * dx + dz * dz <= 0.45 && Math.abs(to.getY() - frend.getY()) <= 0.6) {
            carveIndex++;                      // 这步到位,下一步
            carveStepTicks = 0;
            if (carveIndex >= carvePath.size()) carvePath = null;
            return;
        }

        // 垫块步:交给登高柱机关(自带节奏/音效/回收账本)
        if (step.type() == com.frend.pathing.FrendPathfinder.MoveType.PILLAR) {
            frend.getNavigation().stop();
            if (!pillarUpTick(255)) carvePath = null; // 垫不了(材料没了/头顶硬)= 弃路
            return;
        }

        // 先清挡路方块(执行时重校验:世界可能变了,不天然/危险了就弃路)
        for (BlockPos b : step.toBreak()) {
            var state = frend.getWorld().getBlockState(b);
            if (state.isAir() || com.frend.pathing.FrendPathfinder.passable(frend.getWorld(), b)) continue;
            if (!com.frend.pathing.FrendPathfinder.naturalBreakable(state)
                    || com.frend.pathing.FrendPathfinder.dangerous(frend.getWorld(), b)) {
                carvePath = null; // 情况变了(有人放了箱子/涌了岩浆),这条路作废
                return;
            }
            frend.getNavigation().stop();
            int ticks = (int) Math.max(4, carveTicksFor(state, b));
            if (breakTick(b, ticks)) {
                var tool = bestToolFor(state); // 真用了工具就真掉耐久,账要诚实
                if (!tool.isEmpty()) frend.damageTool(tool);
            }
            return; // 一 tick 只磨一块
        }

        // 搭桥步(v0.24,忠实改写 MovementTraverse 的 backplace):落脚点没地板先放一块
        if (step.type() == com.frend.pathing.FrendPathfinder.MoveType.BRIDGE) {
            BlockPos floor = to.down();
            var fs = frend.getWorld().getBlockState(floor);
            if (!fs.isSolidBlock(frend.getWorld(), floor)) {
                frend.getNavigation().stop();
                if (!placeBridgeBlock(floor)) { carvePath = null; return; }
                return; // 放完/节奏中,下 tick 再走
            }
            // 地板就位 → 落到下面正常走
        }

        if (step.type() == com.frend.pathing.FrendPathfinder.MoveType.DIG_DOWN) {
            return; // 脚下挖穿后重力自己接手,等下落到位
        }
        // 大落差(>3 格带伤下落):原版导航不认这种路,用 MoveControl 直线走出边缘,重力接手
        // 【待编译验证】MoveControl#moveTo(double,double,double,double speed)
        if (step.type() == com.frend.pathing.FrendPathfinder.MoveType.DESCEND
                && frend.getY() - to.getY() > 3.2) {
            frend.getNavigation().stop();
            frend.getMoveControl().moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5,
                    FrendConfig.get().followSpeed);
            return;
        }
        // 走:相邻一格,原版导航足够可靠(上一格的跳跃它自己会)
        frend.getNavigation().startMovingTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5,
                FrendConfig.get().followSpeed);
    }

    /**
     * 挖穿耗时(tick)——忠实移植 Baritone ToolSet#calculateSpeedVsBlock(LGPL,作者授权,出处在案),
     * 换成 Yarn 映射:speed=stack.getMiningSpeedMultiplier;对上工具(或本不挑工具)按 硬度×30/速度,
     * 不对按 硬度×100/速度——这正是原版玩家的挖掘公式。附魔效率项略(frend 的工具没附魔)。
     * 【待编译验证】ItemStack#getMiningSpeedMultiplier / #isSuitableFor / BlockState#isToolRequired
     */
    protected double carveTicksFor(net.minecraft.block.BlockState state, BlockPos pos) {
        float hardness = state.getHardness(frend.getWorld(), pos);
        if (hardness < 0) return com.frend.pathing.FrendPathfinder.COST_INF;
        net.minecraft.item.ItemStack tool = bestToolFor(state);
        float speed = tool.isEmpty() ? 1.0f : Math.max(1.0f, tool.getMiningSpeedMultiplier(state));
        boolean proper = !state.isToolRequired() || (!tool.isEmpty() && tool.isSuitableFor(state));
        return Math.max(4, hardness * (proper ? 30 : 100) / speed);
    }

    /** 包里对这方块挖得最快的工具(学 ToolSet#getBestSlot 的选法);没有返回 EMPTY 徒手。 */
    private net.minecraft.item.ItemStack bestToolFor(net.minecraft.block.BlockState state) {
        var inv = frend.getInventory();
        net.minecraft.item.ItemStack best = net.minecraft.item.ItemStack.EMPTY;
        float bestSpeed = 1.0f;
        for (int i = 0; i < inv.size(); i++) {
            net.minecraft.item.ItemStack st = inv.getStack(i);
            if (st.isEmpty()) continue;
            float sp = st.getMiningSpeedMultiplier(state);
            if (sp > bestSpeed) { bestSpeed = sp; best = st; }
        }
        return best;
    }

    /**
     * 搭桥放一块(消耗脚手架材料):桥<b>不拆</b>——柱子没用还丑所以拆,桥是路,
     * 拆桥要么把自己困在对岸要么把路还回沟里,留着下次还能走(取舍记录于 DEVLOG)。
     */
    private boolean placeBridgeBlock(BlockPos floor) {
        if (scaffoldCooldown > 0) { scaffoldCooldown--; return true; } // 节奏中
        int slot = findScaffoldSlot();
        if (slot < 0) return false;
        var world = frend.getWorld();
        var fs = world.getBlockState(floor);
        if (!fs.isReplaceable() || fs.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.LAVA)) {
            return false; // 情况变了(岩浆涌过来了),不放
        }
        net.minecraft.item.ItemStack st = frend.getInventory().getStack(slot);
        var block = net.minecraft.block.Block.getBlockFromItem(st.getItem());
        st.decrement(1);
        world.setBlockState(floor, block.getDefaultState());
        world.playSound(null, floor, block.getDefaultState().getSoundGroup().getPlaceSound(),
                net.minecraft.sound.SoundCategory.BLOCKS, 0.8f, 1.0f);
        frend.swingHand(net.minecraft.util.Hand.MAIN_HAND, true);
        scaffoldCooldown = 6;
        return true;
    }

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
            // v0.19 挖掘知识一处全收(所有任务的破坏都过这里);首见钻石之类的一次性感慨也在此
            String blockId = net.minecraft.registry.Registries.BLOCK.getId(
                    frend.getWorld().getBlockState(pos).getBlock()).toString();
            String firstLine = frend.getKnowledge().recordMined(blockId);
            if (firstLine != null) frend.sayDelayed(firstLine);
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

    // ===================== v0.22 脚手架:登高柱(实测首修,作者点名"不会给自己脚下搭方块") =====================
    // 场景:悬空树/高处树根/坡顶目标,寻路永远到不了 → 像玩家一样脚下垫方块搭柱子上去。
    // 规矩:只用包里的废料方块(土/圆石系);自己垫的自己拆,材料回包,不留柱子不改地形。
    // 取舍(记入 DEVLOG):上升用"瞬移 1 格+原脚下放块"模拟原地跳放——服务端 mob 的真物理跳跃
    // 不可控(JumpControl 只服务寻路),8 tick 一层的节奏+放块音效+挥手,观感接近玩家搭柱。

    /** 登高柱:垫过的方块,栈顶 = 最上面那块(= 当前脚下)。 */
    private final java.util.ArrayDeque<BlockPos> scaffold = new java.util.ArrayDeque<>();
    private int scaffoldCooldown = 0;

    /** 脚手架材料白名单:废料方块,不心疼。 */
    private static boolean isScaffoldItem(net.minecraft.item.Item it) {
        return it == net.minecraft.item.Items.DIRT
                || it == net.minecraft.item.Items.COBBLESTONE
                || it == net.minecraft.item.Items.COBBLED_DEEPSLATE
                || it == net.minecraft.item.Items.NETHERRACK;
    }

    protected boolean hasScaffoldMaterial() {
        return findScaffoldSlot() >= 0;
    }

    private int findScaffoldSlot() {
        var inv = frend.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            if (isScaffoldItem(inv.getStack(i).getItem())) return i;
        }
        return -1;
    }

    /** 已搭的柱子高度(格)。 */
    protected int scaffoldHeight() { return scaffold.size(); }

    /**
     * 登高一层(约 8 tick 一层):头顶是树叶先敲开,然后脚下垫一块把自己抬 1 格。
     * 返回 true = 这 tick 在登高/开路/冷却(调用方 return true 等着);
     * 返回 false = 登不了(没材料 / 到上限 / 头顶是硬方块)。
     */
    protected boolean pillarUpTick(int maxHeight) {
        if (scaffoldCooldown > 0) { scaffoldCooldown--; return true; }
        if (scaffold.size() >= maxHeight) return false;
        int slot = findScaffoldSlot();
        if (slot < 0) return false;

        var world = frend.getWorld();
        BlockPos feet = frend.getBlockPos();
        BlockPos head2 = feet.up(2); // 抬 1 格后脑袋要占的位置
        var hs = world.getBlockState(head2);
        if (!hs.isAir()) {
            if (hs.isIn(net.minecraft.registry.tag.BlockTags.LEAVES)) {
                breakTick(head2, 4); // 树叶挡头,顺手敲开(徒手树叶本来就快)
                return true;
            }
            return false; // 顶上是硬方块,这条路登不上去
        }

        net.minecraft.item.ItemStack st = frend.getInventory().getStack(slot);
        var block = net.minecraft.block.Block.getBlockFromItem(st.getItem());
        st.decrement(1);
        frend.refreshPositionAndAngles(frend.getX(), feet.getY() + 1.0, frend.getZ(),
                frend.getYaw(), frend.getPitch());
        world.setBlockState(feet, block.getDefaultState());
        // 【待编译验证】BlockState#getSoundGroup / BlockSoundGroup#getPlaceSound
        world.playSound(null, feet, block.getDefaultState().getSoundGroup().getPlaceSound(),
                net.minecraft.sound.SoundCategory.BLOCKS, 0.8f, 1.0f);
        frend.swingHand(net.minecraft.util.Hand.MAIN_HAND, true);
        scaffold.push(feet.toImmutable());
        scaffoldCooldown = 8;
        return true;
    }

    /**
     * 拆登高柱一层(约 6 tick 一层):拆自己脚下那块,材料直接回包(不掉落免得弹飞),
     * 实体自然落 1 格。返回 true = 已拆完(没柱子也算拆完),false = 还在拆。
     */
    protected boolean tearDownScaffoldTick() {
        if (scaffold.isEmpty()) return true;
        if (scaffoldCooldown > 0) { scaffoldCooldown--; return false; }
        reclaimScaffoldBlock(scaffold.pop());
        scaffoldCooldown = 6;
        return scaffold.isEmpty();
    }

    /** 立刻拆光柱子(任务被打断时来不及演逐层动画的兜底,onStop 用)。 */
    protected void discardScaffoldNow() {
        while (!scaffold.isEmpty()) reclaimScaffoldBlock(scaffold.pop());
    }

    /** 拆一块柱子:还是我们的材料就回包;已被别人拆走/换过就不凭空造物资。 */
    private void reclaimScaffoldBlock(BlockPos pos) {
        var world = frend.getWorld();
        var state = world.getBlockState(pos);
        if (state.isAir() || !isScaffoldItem(state.getBlock().asItem())) return;
        world.breakBlock(pos, false, frend); // 不掉落,直接回包
        world.setBlockBreakingInfo(frend.getId(), pos, -1);
        net.minecraft.item.ItemStack back = new net.minecraft.item.ItemStack(state.getBlock().asItem());
        net.minecraft.item.ItemStack rest = frend.getInventory().addStack(back);
        if (!rest.isEmpty()) frend.dropStack(rest);
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
        // v0.15 读 Baritone MovementHelper#avoidBreaking 学的:正上方是液体永远回避(挖开当头浇);
        // 侧面的流动水可容忍,岩浆仍然六邻全禁(咱比它怂,怂得有理)。
        if (!world.getFluidState(p.up()).isEmpty()) {
            return "那块头顶顶着水,挖开淋一身,跳过。";
        }
        for (int dy = 1; dy <= 2; dy++) {
            if (world.getBlockState(p.up(dy)).getBlock() instanceof net.minecraft.block.FallingBlock) {
                return "那块头顶悬着沙子,挖了会被砸,跳过。";
            }
        }
        return null;
    }
}
