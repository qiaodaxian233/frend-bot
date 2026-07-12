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
