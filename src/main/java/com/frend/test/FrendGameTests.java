package com.frend.test;

import com.frend.FrendConfig;
import com.frend.entity.FrendEntity;
import com.frend.entity.FrendMemory;
import com.frend.pathing.FrendPathfinder;
import com.frend.registry.ModEntities;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.HuskEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * v0.26 全自动测试(作者点单:"不需要我手动测试"):Minecraft 原版 GameTest 框架 + Fabric 接口。
 * <b>一条命令:{@code ./gradlew runGametest}</b>——起无头测试服务器,逐个搭考场、召 frend、
 * 下任务、验结果、出报告(build/gametest-report.xml),全程不进游戏,失败非零退出适合 CI。
 *
 * <p>考场 = 16×8×16 空结构(data/frend/structure/empty16.nbt),每个测试自己铺地板搭道具;
 * 地板用<b>磨制安山岩</b>——不在 MineTask 匹配里、不在寻路挖掘白名单里,谁也不许拆考场。
 *
 * <p><b>自动测得了的(10 关)</b>:活着/砍树/悬空树登高/够不着不刷屏/寻路拒人造/寻路搭桥/
 * 挖石头/种田补种/开炉出锭/看家杀怪。<b>自动测不了的(还得人)</b>:钓鱼(等待随机 10~30s,
 * 测试嫌慢)、重逢与纪念日的"催泪感"(机器只能验字符串,验不了眼眶)、LLM 闲聊(要外部服务)——
 * 它是朋友,朋友的可爱只有你能验收。
 *
 * <p>【待编译验证】本文件整体属高危新面:GameTest 注解字段(templateName/tickLimit)、
 * TestContext 的 getAbsolutePos/setBlockState/getBlockState/spawnEntity/assertTrue/succeedWhen、
 * FabricGameTest 接口路径(fabric-api.gametest.v1)。报错优先怀疑这些名字。
 */
public final class FrendGameTests implements FabricGameTest {

    private static final String ARENA = "frend:empty16";

    // ===================== 公共道具 =====================

    /** 测试统一配置:自主关掉(只测点名的任务)、干活加速、战斗与看家打开。幂等,每关开头调。 */
    private static void tune() {
        FrendConfig c = FrendConfig.get();
        c.autonomyEnabled = false;
        c.combatEnabled = true;
        c.guardWhenStay = true;
        c.chopTicksPerBlock = 4;
        c.mineTicksPerBlock = 4;
    }

    /** 铺考场地板(y=0,磨制安山岩:不可挖不可拆,考场不塌)。 */
    private static void floor(TestContext ctx) {
        for (int x = 2; x <= 14; x++)
            for (int z = 2; z <= 14; z++)
                ctx.setBlockState(new BlockPos(x, 0, z), Blocks.POLISHED_ANDESITE.getDefaultState());
    }

    private static FrendEntity spawnFrend(TestContext ctx, int x, int y, int z) {
        FrendEntity f = new FrendEntity(ModEntities.FREND, ctx.getWorld());
        BlockPos abs = ctx.getAbsolutePos(new BlockPos(x, y, z));
        f.refreshPositionAndAngles(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 0.0f, 0.0f);
        ctx.getWorld().spawnEntity(f);
        return f;
    }

    private static void give(FrendEntity f, ItemStack... stacks) {
        for (ItemStack st : stacks) f.getInventory().addStack(st);
    }

    private static boolean invHas(FrendEntity f, Item item) {
        for (int i = 0; i < f.getInventory().size(); i++) {
            if (f.getInventory().getStack(i).isOf(item)) return true;
        }
        return false;
    }

    // ===================== 第 1 关:活着 =====================

    @GameTest(templateName = ARENA, tickLimit = 100)
    public void frendSpawnsAndLives(TestContext ctx) {
        tune();
        floor(ctx);
        FrendEntity f = spawnFrend(ctx, 8, 1, 8);
        ctx.succeedWhen(() -> ctx.assertTrue(f.isAlive(), "frend 召出来就没活下来"));
    }

    // ===================== 第 2 关:砍树(整棵收) =====================

    @GameTest(templateName = ARENA, tickLimit = 1200)
    public void chopTreeCollectsLogs(TestContext ctx) {
        tune();
        floor(ctx);
        for (int y = 1; y <= 3; y++) ctx.setBlockState(new BlockPos(12, y, 8), Blocks.OAK_LOG.getDefaultState());
        FrendEntity f = spawnFrend(ctx, 4, 1, 8);
        give(f, new ItemStack(Items.IRON_AXE));
        f.startTask(new com.frend.entity.task.ChopTreeTask(f), null);
        ctx.succeedWhen(() -> {
            for (int y = 1; y <= 3; y++) {
                ctx.assertTrue(ctx.getBlockState(new BlockPos(12, y, 8)).isAir(), "第 " + y + " 层原木还立着");
            }
            ctx.assertTrue(invHas(f, Items.OAK_LOG), "树砍了但木头没进它包");
        });
    }

    // ===================== 第 3 关:悬空树 → 登高柱(实测首修的回归考) =====================

    @GameTest(templateName = ARENA, tickLimit = 1800)
    public void hoverLogScaffoldsUp(TestContext ctx) {
        tune();
        floor(ctx);
        ctx.setBlockState(new BlockPos(8, 5, 8), Blocks.OAK_LOG.getDefaultState()); // 悬空,脚上 4 格
        FrendEntity f = spawnFrend(ctx, 8, 1, 8);
        give(f, new ItemStack(Items.DIRT, 8));
        f.startTask(new com.frend.entity.task.ChopTreeTask(f), null);
        ctx.succeedWhen(() -> {
            ctx.assertTrue(ctx.getBlockState(new BlockPos(8, 5, 8)).isAir(), "悬空原木没砍下来(登高柱没起作用)");
            ctx.assertTrue(invHas(f, Items.OAK_LOG), "砍了但没进包");
        });
    }

    // ===================== 第 4 关:够不着要认账收工,不许无限"换一棵"(首份报障的回归考) =====================

    @GameTest(templateName = ARENA, tickLimit = 400)
    public void unreachableGivesUpFast(TestContext ctx) {
        tune();
        floor(ctx);
        ctx.setBlockState(new BlockPos(8, 7, 8), Blocks.OAK_LOG.getDefaultState()); // 脚上 6 格,超 workReach
        FrendEntity f = spawnFrend(ctx, 8, 1, 8); // 不给垫脚材料 → 只能放弃
        f.startTask(new com.frend.entity.task.ChopTreeTask(f), null);
        ctx.succeedWhen(() -> {
            ctx.assertTrue(!f.hasActiveTask(), "够不着还赖着不收工(死循环回归)");
            ctx.assertTrue(!ctx.getBlockState(new BlockPos(8, 7, 8)).isAir(), "没材料居然砍到了?判定漏了");
        });
    }

    // ===================== 第 5 关:寻路拒人造(防拆家红线) =====================

    @GameTest(templateName = ARENA, tickLimit = 100)
    public void pathfinderRefusesManMade(TestContext ctx) {
        tune();
        // 地板一条走廊 x4..14, z6..10;基岩墙 x=9 全高,只留木板塞子
        for (int x = 4; x <= 14; x++)
            for (int z = 6; z <= 10; z++)
                ctx.setBlockState(new BlockPos(x, 0, z), Blocks.POLISHED_ANDESITE.getDefaultState());
        for (int y = 1; y <= 6; y++)
            for (int z = 5; z <= 11; z++)
                ctx.setBlockState(new BlockPos(9, y, z), Blocks.BEDROCK.getDefaultState());
        ctx.setBlockState(new BlockPos(9, 1, 8), Blocks.OAK_PLANKS.getDefaultState());
        ctx.setBlockState(new BlockPos(9, 2, 8), Blocks.OAK_PLANKS.getDefaultState());

        BlockPos start = ctx.getAbsolutePos(new BlockPos(5, 1, 8));
        BlockPos goal = ctx.getAbsolutePos(new BlockPos(13, 1, 8));
        List<FrendPathfinder.Step> blocked = FrendPathfinder.find(
                ctx.getWorld(), start, goal, 1.2, 0, 4000, null);
        ctx.assertTrue(blocked == null, "唯一通路是木板,居然规划出路了——人造方块红线被穿透!");

        // 对照组:塞子换成泥土(天然) → 必须能规划出路,且路里真的要挖那两块
        ctx.setBlockState(new BlockPos(9, 1, 8), Blocks.DIRT.getDefaultState());
        ctx.setBlockState(new BlockPos(9, 2, 8), Blocks.DIRT.getDefaultState());
        List<FrendPathfinder.Step> open = FrendPathfinder.find(
                ctx.getWorld(), start, goal, 1.2, 0, 4000, null);
        ctx.assertTrue(open != null, "塞子换成泥土还找不到路(挖掘寻路失灵)");
        boolean digs = open.stream().anyMatch(s -> !s.toBreak().isEmpty());
        ctx.assertTrue(digs, "找到路了但没计划挖任何块(不合常理)");
        ctx.succeedWhen(() -> {});
    }

    // ===================== 第 6 关:寻路搭桥(v0.24) =====================

    @GameTest(templateName = ARENA, tickLimit = 100)
    public void pathfinderPlansBridge(TestContext ctx) {
        tune();
        // 两座孤岛,中间 3 格深渊(结构外即虚空,落不下去也绕不过去)
        for (int x = 4; x <= 7; x++)
            for (int z = 6; z <= 10; z++)
                ctx.setBlockState(new BlockPos(x, 0, z), Blocks.POLISHED_ANDESITE.getDefaultState());
        for (int x = 11; x <= 14; x++)
            for (int z = 6; z <= 10; z++)
                ctx.setBlockState(new BlockPos(x, 0, z), Blocks.POLISHED_ANDESITE.getDefaultState());

        BlockPos start = ctx.getAbsolutePos(new BlockPos(5, 1, 8));
        BlockPos goal = ctx.getAbsolutePos(new BlockPos(13, 1, 8));
        List<FrendPathfinder.Step> path = FrendPathfinder.find(
                ctx.getWorld(), start, goal, 1.2, 8, 4000, null);
        ctx.assertTrue(path != null, "有 8 块料在手,3 格沟居然过不去");
        boolean bridges = path.stream().anyMatch(s -> s.type() == FrendPathfinder.MoveType.BRIDGE);
        ctx.assertTrue(bridges, "过沟的路里没有一步是搭桥(BRIDGE 未生效)");
        ctx.succeedWhen(() -> {});
    }

    // ===================== 第 7 关:挖石头 =====================

    @GameTest(templateName = ARENA, tickLimit = 1200)
    public void mineTaskMinesStone(TestContext ctx) {
        tune();
        floor(ctx);
        ctx.setBlockState(new BlockPos(12, 1, 8), Blocks.STONE.getDefaultState());
        ctx.setBlockState(new BlockPos(12, 2, 8), Blocks.STONE.getDefaultState());
        FrendEntity f = spawnFrend(ctx, 5, 1, 8);
        give(f, new ItemStack(Items.STONE_PICKAXE));
        f.startTask(new com.frend.entity.task.MineTask(f, com.frend.entity.task.MineTask.Kind.STONE), null);
        ctx.succeedWhen(() -> {
            ctx.assertTrue(ctx.getBlockState(new BlockPos(12, 1, 8)).isAir()
                            && ctx.getBlockState(new BlockPos(12, 2, 8)).isAir(),
                    "石头没挖完");
            ctx.assertTrue(invHas(f, Items.COBBLESTONE), "挖了石头但圆石没进包");
        });
    }

    // ===================== 第 8 关:种田(只收熟的 + 当场补种) =====================

    @GameTest(templateName = ARENA, tickLimit = 900)
    public void farmHarvestsAndReplants(TestContext ctx) {
        tune();
        floor(ctx);
        ctx.setBlockState(new BlockPos(10, 1, 8), Blocks.FARMLAND.getDefaultState());
        ctx.setBlockState(new BlockPos(10, 2, 8),
                Blocks.WHEAT.getDefaultState().with(CropBlock.AGE, 7)); // 熟透
        FrendEntity f = spawnFrend(ctx, 6, 1, 8);
        give(f, new ItemStack(Items.WHEAT_SEEDS, 4)); // 预给种子,补种不靠捡拾时序
        f.startTask(new com.frend.entity.task.FarmTask(f), null);
        ctx.succeedWhen(() -> {
            var state = ctx.getBlockState(new BlockPos(10, 2, 8));
            ctx.assertTrue(state.isOf(Blocks.WHEAT), "收完没补种(坑空着)");
            ctx.assertTrue(state.get(CropBlock.AGE) == 0, "补种的不是新苗(age!=0)");
        });
    }

    // ===================== 第 9 关:开炉烧铁(真熔炉全链路) =====================

    @GameTest(templateName = ARENA, tickLimit = 2000)
    public void smeltProducesIronIngot(TestContext ctx) {
        tune();
        floor(ctx);
        ctx.setBlockState(new BlockPos(11, 1, 8), Blocks.FURNACE.getDefaultState());
        FrendEntity f = spawnFrend(ctx, 6, 1, 8);
        give(f, new ItemStack(Items.RAW_IRON, 3), new ItemStack(Items.COAL, 2));
        f.startTask(new com.frend.entity.task.SmeltTask(f), null);
        ctx.succeedWhen(() -> ctx.assertTrue(invHas(f, Items.IRON_INGOT),
                "三块生铁进炉,一块铁锭没出来(装料/火候/收炉哪步断了)"));
    }

    // ===================== 第 10 关:看家杀怪(用尸壳,白天不自燃不作弊) =====================

    @GameTest(templateName = ARENA, tickLimit = 1200)
    public void guardKillsHostile(TestContext ctx) {
        tune();
        floor(ctx);
        FrendEntity f = spawnFrend(ctx, 6, 1, 8);
        give(f, new ItemStack(Items.STONE_SWORD));
        f.setMode(FrendEntity.Mode.STAY); // 看家岗位就在脚下
        HuskEntity husk = ctx.spawnEntity(EntityType.HUSK, new BlockPos(10, 1, 8));
        ctx.succeedWhen(() -> ctx.assertTrue(!husk.isAlive(), "怪进了岗位圈,它没动手(看家分支没触发)"));
    }

    // ===================== 附加关:记忆 NBT 往返(纯逻辑,不用地形) =====================

    @GameTest(templateName = ARENA, tickLimit = 60)
    public void memoryNbtRoundtrip(TestContext ctx) {
        FrendMemory a = new FrendMemory();
        a.initFirstMet(24000L);
        a.addNote("钻石在河边");
        FrendMemory b = new FrendMemory();
        b.fromNbt(a.toNbt());
        ctx.succeedWhen(() -> {
            ctx.assertTrue(b.hasNotes(), "记事在 NBT 往返里丢了");
            ctx.assertTrue(a.daysTogether(48000L) == b.daysTogether(48000L), "相识天数往返后对不上");
        });
    }
}
