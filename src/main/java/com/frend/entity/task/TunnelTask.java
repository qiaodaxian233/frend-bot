package com.frend.entity.task;

import com.frend.FrendConfig;
import com.frend.entity.FrendEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * v0.13 挖矿路径规划(Baritone 思路,frend 红线):隧道掘进 / 楼梯下矿。
 *
 * <p>两种模式:
 * <ul>
 *   <li><b>TUNNEL 平巷</b>:沿开工时的朝向(取整到东南西北)挖 1x2 直巷,最多 tunnelMaxLength 格;</li>
 *   <li><b>DEEP 下矿</b>:先按标准楼梯法(每步前进 1 降 1,断面 3 高)挖到 deepMineTargetY 矿层,
 *       到层后自动转平巷继续掘进,长度预算共享。</li>
 * </ul>
 *
 * <p>红线与安全(缺一不可,任何一条不满足<b>整条道就地收工</b>——隧道是有方向的,没法像散挖那样跳块绕开):
 * <ul>
 *   <li><b>白名单防拆家</b>:只挖天然石头族(BASE_STONE 系/圆石/泥土/沙砾)和矿石——木板、玻璃、
 *       黑曜石、任何像"有人修的"方块一概不碰,停下来报告;</li>
 *   <li><b>v0.6 挖掘避险</b>(基类 miningDanger):贴岩浆不挖、头顶悬沙不挖;</li>
 *   <li><b>渗水检测</b>:断面邻块有水 → 再挖就灌,收工;</li>
 *   <li><b>挖穿溶洞</b>:前方落脚点悬空 → 不搭桥(红线:不放置方块),停下叫你来看。</li>
 * </ul>
 *
 * <p>见矿顺手掏:每挖穿一块,扫它的六邻,露出来的矿石(全矿种)排队掏走——只掏露头,<b>不追矿脉</b>
 * (追脉会越挖越歪,把直巷挖成蚁穴)。火把由实体层 autoTorch 免费覆盖;镐耐久/没镐规矩同 MineTask。
 */
public class TunnelTask extends FrendTask {

    public enum Kind { TUNNEL, DEEP }

    private final Kind kind;
    /** 掘进方向(开工时朝向取整,之后不变——直巷的意义就是直)。 */
    private Direction dir = null;
    /** 巷道游标:frend 当前应站的那格(脚)。 */
    private BlockPos cursor = null;

    private int advanced = 0;      // 已掘进步数
    private int blocksMined = 0;   // 总破坏方块数(含矿)
    private int oresMined = 0;     // 其中矿石数
    /** 见矿顺手掏的队列。 */
    private final Deque<BlockPos> oreQueue = new ArrayDeque<>();

    public TunnelTask(FrendEntity frend, Kind kind) {
        super(frend);
        this.kind = kind;
    }

    @Override
    public String name() { return kind == Kind.TUNNEL ? "挖矿道" : "下矿"; }

    @Override
    public boolean tick() {
        FrendConfig cfg = FrendConfig.get();

        // 开工定向定点
        if (dir == null) {
            dir = frend.getHorizontalFacing();
            cursor = frend.getBlockPos().toImmutable();
        }

        // 镐是硬门槛
        ItemStack pick = frend.findUsableTool(ItemTags.PICKAXES);
        if (pick.isEmpty()) {
            frend.say(blocksMined == 0 ? "掘进得有镐,给我一把呗(放我背包里就行)。" : doneLine("镐子不行了"));
            clearBreaking();
            return false;
        }

        // 长度预算
        if (advanced >= cfg.tunnelMaxLength) {
            frend.say(doneLine("这条道够长了"));
            clearBreaking();
            return false;
        }

        // 1) 有矿先掏矿(就在巷壁上,一两步的事)
        if (!oreQueue.isEmpty()) {
            BlockPos ore = oreQueue.peekFirst();
            if (!isAnyOre(frend.getWorld().getBlockState(ore)) || miningDanger(ore) != null) {
                oreQueue.pollFirst(); // 没了/不安全就放弃这块,不纠结
                return true;
            }
            if (!moveNear(ore, cfg.workReach)) {
                if (stuckTicks() > 20 * 6) { oreQueue.pollFirst(); resetStuck(); }
                return true;
            }
            if (breakTick(ore, cfg.mineTicksPerBlock)) {
                oreQueue.pollFirst();
                blocksMined++; oresMined++;
                frend.getMemory().addMined(1);
                frend.damageTool(pick);
                scanForOres(ore);
            }
            return true;
        }

        // 2) 掘进断面:下矿未到层走楼梯(3 高),否则平巷(2 高);自上而下挖(沙砾先落先处理)
        boolean stair = kind == Kind.DEEP && frend.getBlockY() > cfg.deepMineTargetY;
        BlockPos front = cursor.offset(dir);
        BlockPos[] face = stair
                ? new BlockPos[]{front.up(), front, front.down()}
                : new BlockPos[]{front.up(), front};

        for (BlockPos p : face) {
            BlockState state = frend.getWorld().getBlockState(p);
            if (state.isAir()) continue;

            // 红线:白名单外(像有人修的/挖不动的)整条道收工
            if (!tunnelMinable(state)) {
                frend.say("前面这块不是天然石头,像是有人修的(或者压根挖不动),这条道到这儿。" + tally());
                clearBreaking();
                return false;
            }
            // 避险:隧道有方向,绕不开 → 收工
            String danger = miningDanger(p);
            if (danger != null) {
                frend.say(danger + "这条道到头了。" + tally());
                clearBreaking();
                return false;
            }
            // 渗水:断面邻块有水,再挖就灌
            if (waterAdjacent(p)) {
                frend.say("前面渗水,再挖就灌进来了,到这儿吧。" + tally());
                clearBreaking();
                return false;
            }

            if (!moveNear(p, cfg.workReach)) {
                if (stuckTicks() > 20 * 8) {
                    frend.say("走不到掘进面,这条道先到这儿。" + tally());
                    clearBreaking();
                    return false;
                }
                return true;
            }
            if (breakTick(p, cfg.mineTicksPerBlock)) {
                blocksMined++;
                frend.getMemory().addMined(1);
                frend.damageTool(pick);
                scanForOres(p);
            }
            return true; // 一 tick 只磨一块
        }

        // 3) 断面全空 → 前进一步
        BlockPos next = stair ? front.down() : front;
        // 落脚点悬空 = 挖穿溶洞:红线不搭桥,停下叫人
        if (frend.getWorld().getBlockState(next.down()).isAir()) {
            frend.say("挖穿了,前面是个洞,黑黢黢的……我不跳,你来看看?" + tally());
            clearBreaking();
            return false;
        }
        cursor = next.toImmutable();
        advanced++;
        moveNear(cursor, 1.2); // 跟上巷道头
        return true;
    }

    /** 白名单:天然石头族 + 全矿种。名单之外(木板/玻璃/黑曜石/基岩……)一概不碰。 */
    private boolean tunnelMinable(BlockState s) {
        return s.isIn(BlockTags.BASE_STONE_OVERWORLD)  // stone/deepslate/granite/diorite/andesite/tuff
                || s.isIn(BlockTags.BASE_STONE_NETHER) // netherrack/basalt/blackstone
                || s.isOf(Blocks.COBBLESTONE) || s.isOf(Blocks.DIRT) || s.isOf(Blocks.GRAVEL)
                || isAnyOre(s);
    }

    /** 全矿种(顺手掏的判定;煤铁铜金红青钻绿 + 下界金/远古残骸)。 */
    private boolean isAnyOre(BlockState s) {
        return s.isIn(BlockTags.COAL_ORES) || s.isIn(BlockTags.IRON_ORES)
                || s.isIn(BlockTags.COPPER_ORES) || s.isIn(BlockTags.GOLD_ORES)
                || s.isIn(BlockTags.REDSTONE_ORES) || s.isIn(BlockTags.LAPIS_ORES)
                || s.isIn(BlockTags.DIAMOND_ORES) || s.isIn(BlockTags.EMERALD_ORES)
                || s.isOf(Blocks.NETHER_QUARTZ_ORE) || s.isOf(Blocks.ANCIENT_DEBRIS);
    }

    /** 挖穿一块后扫它的六邻,新露头的矿排队(只掏露头,不追脉)。 */
    private void scanForOres(BlockPos broken) {
        for (Direction d : Direction.values()) {
            BlockPos p = broken.offset(d);
            if (isAnyOre(frend.getWorld().getBlockState(p)) && !oreQueue.contains(p)) {
                oreQueue.addLast(p.toImmutable());
            }
        }
    }

    private boolean waterAdjacent(BlockPos p) {
        for (Direction d : Direction.values()) {
            if (frend.getWorld().getFluidState(p.offset(d)).isIn(FluidTags.WATER)) return true;
        }
        return false;
    }

    private String tally() {
        return "掘进 " + advanced + " 格,破 " + blocksMined + " 块" + (oresMined > 0 ? ",顺出 " + oresMined + " 块矿" : "") + "。";
    }

    private String doneLine(String why) {
        return why + ",收工!" + tally() + "东西都在我包里。";
    }

    @Override
    public void onStop() {
        clearBreaking();
    }
}
