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
 * <p><b>API 名字来源与两次教训</b>:①succeedWhen 是 Mojang 映射名,Yarn 无此方法
 * (稀疏克隆 FabricMC/yarn@1.21.1 查 TestContext.mapping 实证);②addFinalTask <b>实跑证明
 * 是单发不是轮询</b>(首跑 1.6 秒 9 关全挂在第 1 秒,任务根本没来得及干)——于是不再依赖
 * 任何未实证的轮询方法,用<b>已实证原语自建轮询</b>:runAtTick(method_35951)每 10 tick
 * 查一次,条件成立 complete()(method_36036),到点最后一查不吞异常 = 正式判负。见 pollUntil。
 *
 * <p><b>考场教训(首跑)</b>:GameTest 世界是<b>平原不是虚空</b>——考场四面不封死,
 * 寻路就从考场外的真实草地绕过去(搭桥关找到了不搭桥的路,红线关"绕出路了")。
 * 修法:寻路两关用全基岩密室(sealedRoom),搭桥关的沟里灌水(顺便逼出"水上搭桥")。
 * 另:11 关并排同跑,看家关的尸壳可能跟隔壁考场的 frend 隔墙互殴 → batchId 单开隔离。
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

    /**
     * 自建轮询(只用实跑证过的原语):每 10 tick 跑一次 criterion,不抛 = 达标 complete();
     * 到 limit 前最后一查不吞异常 = 正式判负。limit 必须小于注解 tickLimit。
     */
    private static void pollUntil(TestContext ctx, int limit, Runnable criterion) {
        final boolean[] done = {false};
        for (int t = 10; t < limit - 10; t += 10) {
            ctx.runAtTick(t, () -> {
                if (done[0]) return;
                try {
                    criterion.run();
                    done[0] = true;
                    ctx.complete();
                } catch (Throwable ignored) {
                    // 还没达标,下个 10 tick 再看
                }
            });
        }
        ctx.runAtTick(limit - 5, () -> {
            if (!done[0]) criterion.run(); // 这次不吞:抛出即判负,报错就是断言消息
        });
    }

    /**
     * 全基岩密室(首跑教训:测试世界是平原不是虚空,不封死寻路就绕外面的草地):
     * 外壳 x3..15 z5..11,地板 y0 天花 y4,四壁 y1..3,内空 3 格高。
     */
    private static void sealedRoom(TestContext ctx) {
        var bedrock = Blocks.BEDROCK.getDefaultState();
        for (int x = 3; x <= 15; x++)
            for (int z = 5; z <= 11; z++) {
                ctx.setBlockState(new BlockPos(x, 0, z), bedrock);
                ctx.setBlockState(new BlockPos(x, 4, z), bedrock);
            }
        for (int y = 1; y <= 3; y++) {
            for (int x = 3; x <= 15; x++) {
                ctx.setBlockState(new BlockPos(x, y, 5), bedrock);
                ctx.setBlockState(new BlockPos(x, y, 11), bedrock);
            }
            for (int z = 5; z <= 11; z++) {
                ctx.setBlockState(new BlockPos(3, y, z), bedrock);
                ctx.setBlockState(new BlockPos(15, y, z), bedrock);
            }
        }
    }

    /** 现场快照:终判失败时拼进消息,下一份报告直接看穿死因。 */
    private static String dump(FrendEntity f) {
        int items = 0;
        for (int i = 0; i < f.getInventory().size(); i++) if (!f.getInventory().getStack(i).isEmpty()) items++;
        return " | frend@" + f.getBlockPos().toShortString() + " 活着=" + f.isAlive()
                + " 任务=" + (f.currentTaskName() != null ? f.currentTaskName() : "无") + " 包=" + items + "格"
                + " 最后说=「" + (f.getLastSaid() != null ? f.getLastSaid() : "(没说过话)") + "」";
    }

    /** 挖矿诊断:以 frend 为心 ±16 复刻 MineTask 三重筛(匹配/露头/安全),看石头死在哪道筛子上。 */
    private static String scanStones(TestContext ctx, FrendEntity f) {
        int matched = 0, exposedN = 0;
        BlockPos me = f.getBlockPos();
        for (BlockPos p : BlockPos.iterate(me.add(-16, -16, -16), me.add(16, 16, 16))) {
            var st = f.getWorld().getBlockState(p);
            if (!(st.isOf(Blocks.STONE) || st.isOf(Blocks.DEEPSLATE) || st.isOf(Blocks.COBBLESTONE))) continue;
            matched++;
            for (var dir : net.minecraft.util.math.Direction.values()) {
                if (f.getWorld().getBlockState(p.offset(dir)).isAir()) { exposedN++; break; }
            }
        }
        return " 扫描[匹配=" + matched + " 露头=" + exposedN + "]";
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
        pollUntil(ctx, 80, () -> ctx.assertTrue(f.isAlive(), "frend 召出来就没活下来"));
    }

    // ===================== 第 2 关:砍树(整棵收) =====================

    @GameTest(templateName = ARENA, tickLimit = 1200, batchId = "frendChop")
    public void chopTreeCollectsLogs(TestContext ctx) {
        tune();
        floor(ctx);
        for (int y = 1; y <= 3; y++) ctx.setBlockState(new BlockPos(12, y, 8), Blocks.OAK_LOG.getDefaultState());
        FrendEntity f = spawnFrend(ctx, 4, 1, 8);
        give(f, new ItemStack(Items.IRON_AXE));
        f.startTask(new com.frend.entity.task.ChopTreeTask(f), null);
        pollUntil(ctx, 1150, () -> {
            for (int y = 1; y <= 3; y++) {
                final int fy = y;
                ctx.assertTrue(ctx.getBlockState(new BlockPos(12, fy, 8)).isAir(), "第 " + fy + " 层原木还立着" + dump(f));
            }
            ctx.assertTrue(invHas(f, Items.OAK_LOG), "树砍了但木头没进它包" + dump(f));
        });
    }

    // ===================== 第 3 关:悬空树 → 登高柱(实测首修的回归考) =====================

    @GameTest(templateName = ARENA, tickLimit = 1800, batchId = "frendHover")
    public void hoverLogScaffoldsUp(TestContext ctx) {
        tune();
        floor(ctx);
        ctx.setBlockState(new BlockPos(8, 5, 8), Blocks.OAK_LOG.getDefaultState()); // 悬空,脚上 4 格
        FrendEntity f = spawnFrend(ctx, 8, 1, 8);
        give(f, new ItemStack(Items.DIRT, 8));
        f.startTask(new com.frend.entity.task.ChopTreeTask(f), null);
        pollUntil(ctx, 1750, () -> {
            ctx.assertTrue(ctx.getBlockState(new BlockPos(8, 5, 8)).isAir(), "悬空原木没砍下来(登高柱没起作用)" + dump(f));
            ctx.assertTrue(invHas(f, Items.OAK_LOG), "砍了但没进包" + dump(f));
        });
    }

    // ===================== 第 4 关:够不着要认账收工,不许无限"换一棵"(首份报障的回归考) =====================

    @GameTest(templateName = ARENA, tickLimit = 400, batchId = "frendUnreach")
    public void unreachableGivesUpFast(TestContext ctx) {
        tune();
        floor(ctx);
        ctx.setBlockState(new BlockPos(8, 7, 8), Blocks.OAK_LOG.getDefaultState()); // 脚上 6 格,超 workReach
        FrendEntity f = spawnFrend(ctx, 8, 1, 8); // 不给垫脚材料 → 只能放弃
        f.startTask(new com.frend.entity.task.ChopTreeTask(f), null);
        pollUntil(ctx, 380, () -> {
            ctx.assertTrue(!f.hasActiveTask(), "够不着还赖着不收工(死循环回归)" + dump(f));
            ctx.assertTrue(!ctx.getBlockState(new BlockPos(8, 7, 8)).isAir(), "没材料居然砍到了?判定漏了");
        });
    }

    // ===================== 第 5 关:寻路拒人造(防拆家红线) =====================

    @GameTest(templateName = ARENA, tickLimit = 100)
    public void pathfinderRefusesManMade(TestContext ctx) {
        tune();
        sealedRoom(ctx); // 密室:唯一出路只能是中间那堵墙上的塞子
        var bedrock = Blocks.BEDROCK.getDefaultState();
        for (int y = 1; y <= 3; y++)
            for (int z = 6; z <= 10; z++)
                ctx.setBlockState(new BlockPos(9, y, z), bedrock); // 中隔墙
        ctx.setBlockState(new BlockPos(9, 1, 8), Blocks.OAK_PLANKS.getDefaultState()); // 木板塞子
        ctx.setBlockState(new BlockPos(9, 2, 8), Blocks.OAK_PLANKS.getDefaultState());

        BlockPos start = ctx.getAbsolutePos(new BlockPos(5, 1, 8));
        BlockPos goal = ctx.getAbsolutePos(new BlockPos(13, 1, 8));
        List<FrendPathfinder.Step> blocked = FrendPathfinder.find(
                ctx.getWorld(), start, goal, 1.2, 0, 4000, null);
        // 注意:find() 按设计会返回"部分路径"(bestSoFar,走近点也比干瞪眼强)——非空不等于违规。
        // 红线的真正断言是:①一步都不许计划挖木板;②带着木板塞子永远到不了终点。
        BlockPos plugLow = ctx.getAbsolutePos(new BlockPos(9, 1, 8));
        BlockPos plugHigh = ctx.getAbsolutePos(new BlockPos(9, 2, 8));
        if (blocked != null) {
            for (FrendPathfinder.Step st : blocked) {
                for (BlockPos b : st.toBreak()) {
                    ctx.assertTrue(!b.equals(plugLow) && !b.equals(plugHigh),
                            "规划里要挖木板——人造方块红线被穿透!");
                }
            }
            BlockPos last = blocked.get(blocked.size() - 1).to();
            double dx = goal.getX() + 0.5 - (last.getX() + 0.5);
            double dy = goal.getY() + 0.5 - (last.getY() + 1.0);
            double dz = goal.getZ() + 0.5 - (last.getZ() + 0.5);
            ctx.assertTrue(dx * dx + dy * dy + dz * dz > 1.2 * 1.2,
                    "带着木板塞子居然走到了终点(密室必有泄漏)");
        }

        // 对照组:塞子换成泥土(天然) → 必须能规划出路,且路里真的要挖那两块
        ctx.setBlockState(new BlockPos(9, 1, 8), Blocks.DIRT.getDefaultState());
        ctx.setBlockState(new BlockPos(9, 2, 8), Blocks.DIRT.getDefaultState());
        List<FrendPathfinder.Step> open = FrendPathfinder.find(
                ctx.getWorld(), start, goal, 1.2, 0, 4000, null);
        ctx.assertTrue(open != null, "塞子换成泥土还找不到路(挖掘寻路失灵)");
        boolean digs = open.stream().anyMatch(s -> !s.toBreak().isEmpty());
        ctx.assertTrue(digs, "找到路了但没计划挖任何块(不合常理)");
        ctx.complete(); // 断言全部同步跑完
    }

    // ===================== 第 6 关:寻路搭桥(v0.24) =====================

    @GameTest(templateName = ARENA, tickLimit = 100)
    public void pathfinderPlansBridge(TestContext ctx) {
        tune();
        sealedRoom(ctx); // 密室 + 水沟:落不下去(水不可走)、垫不了柱(水里没立足)——只剩搭桥一条路
        for (int x = 8; x <= 10; x++)
            for (int z = 6; z <= 10; z++)
                ctx.setBlockState(new BlockPos(x, 0, z), Blocks.WATER.getDefaultState()); // 地板挖成水沟

        BlockPos start = ctx.getAbsolutePos(new BlockPos(5, 1, 8));
        BlockPos goal = ctx.getAbsolutePos(new BlockPos(13, 1, 8));
        List<FrendPathfinder.Step> path = FrendPathfinder.find(
                ctx.getWorld(), start, goal, 1.2, 8, 4000, null);
        ctx.assertTrue(path != null, "有 8 块料在手,3 格水沟居然过不去");
        boolean bridges = path.stream().anyMatch(s -> s.type() == FrendPathfinder.MoveType.BRIDGE);
        ctx.assertTrue(bridges, "过沟的路里没有一步是搭桥(BRIDGE 未生效)");
        ctx.complete(); // 断言全部同步跑完
    }

    // ===================== 第 7 关:挖石头 =====================

    @GameTest(templateName = ARENA, tickLimit = 1200, batchId = "frendMine")
    public void mineTaskMinesStone(TestContext ctx) {
        tune();
        floor(ctx);
        ctx.setBlockState(new BlockPos(12, 1, 8), Blocks.STONE.getDefaultState());
        ctx.setBlockState(new BlockPos(12, 2, 8), Blocks.STONE.getDefaultState());
        FrendEntity f = spawnFrend(ctx, 5, 1, 8);
        give(f, new ItemStack(Items.STONE_PICKAXE));
        f.startTask(new com.frend.entity.task.MineTask(f, com.frend.entity.task.MineTask.Kind.STONE), null);
        pollUntil(ctx, 1150, () -> {
            ctx.assertTrue(ctx.getBlockState(new BlockPos(12, 1, 8)).isAir()
                            && ctx.getBlockState(new BlockPos(12, 2, 8)).isAir(),
                    "石头没挖完" + scanStones(ctx, f) + dump(f));
            ctx.assertTrue(invHas(f, Items.COBBLESTONE), "挖了石头但圆石没进包" + dump(f));
        });
    }

    // ===================== 第 8 关:种田(只收熟的 + 当场补种) =====================

    @GameTest(templateName = ARENA, tickLimit = 900, batchId = "frendFarm")
    public void farmHarvestsAndReplants(TestContext ctx) {
        tune();
        floor(ctx);
        ctx.setBlockState(new BlockPos(10, 1, 8), Blocks.FARMLAND.getDefaultState());
        ctx.setBlockState(new BlockPos(10, 2, 8),
                Blocks.WHEAT.getDefaultState().with(CropBlock.AGE, 7)); // 熟透
        FrendEntity f = spawnFrend(ctx, 6, 1, 8);
        give(f, new ItemStack(Items.WHEAT_SEEDS, 4)); // 预给种子,补种不靠捡拾时序
        f.startTask(new com.frend.entity.task.FarmTask(f), null);
        pollUntil(ctx, 850, () -> {
            var state = ctx.getBlockState(new BlockPos(10, 2, 8));
            ctx.assertTrue(state.isOf(Blocks.WHEAT), "收完没补种(坑空着)");
            ctx.assertTrue(state.get(CropBlock.AGE) == 0, "补种的不是新苗(age!=0)");
        });
    }

    // ===================== 第 9 关:开炉烧铁(真熔炉全链路) =====================

    @GameTest(templateName = ARENA, tickLimit = 2000, batchId = "frendSmelt")
    public void smeltProducesIronIngot(TestContext ctx) {
        tune();
        floor(ctx);
        ctx.setBlockState(new BlockPos(11, 1, 8), Blocks.FURNACE.getDefaultState());
        FrendEntity f = spawnFrend(ctx, 6, 1, 8);
        give(f, new ItemStack(Items.RAW_IRON, 3), new ItemStack(Items.COAL, 2));
        f.startTask(new com.frend.entity.task.SmeltTask(f), null);
        pollUntil(ctx, 1950, () -> ctx.assertTrue(invHas(f, Items.IRON_INGOT),
                "三块生铁进炉,一块铁锭没出来(装料/火候/收炉哪步断了)" + dump(f)));
    }

    // ===================== 第 10 关:看家杀怪(用尸壳,白天不自燃不作弊) =====================

    @GameTest(templateName = ARENA, tickLimit = 1200, batchId = "frendGuard") // 单开批次:防尸壳跟隔壁考场隔墙互殴
    public void guardKillsHostile(TestContext ctx) {
        tune();
        floor(ctx);
        FrendEntity f = spawnFrend(ctx, 6, 1, 8);
        // 铁剑+铁甲+盾:上轮石剑裸装 1v1 尸壳打成确定性的一换一(尸壳剩 0.368 血它先倒)——
        // 这关考的是看家分支触发与清场,不是拳头硬度的极限平衡,装备给足。
        give(f, new ItemStack(Items.IRON_SWORD), new ItemStack(Items.IRON_CHESTPLATE), new ItemStack(Items.SHIELD));
        f.setMode(FrendEntity.Mode.STAY); // 看家岗位就在脚下
        HuskEntity husk = ctx.spawnEntity(EntityType.HUSK, new BlockPos(10, 1, 8));
        pollUntil(ctx, 1150, () -> ctx.assertTrue(!husk.isAlive(), "怪进了岗位圈,它没动手(看家分支没触发)"
                + " 尸壳血=" + husk.getHealth() + "@" + husk.getBlockPos().toShortString() + dump(f)));
    }

    // ===================== 附加关:记忆 NBT 往返(纯逻辑,不用地形) =====================

    @GameTest(templateName = ARENA, tickLimit = 60)
    public void memoryNbtRoundtrip(TestContext ctx) {
        FrendMemory a = new FrendMemory();
        a.initFirstMet(24000L);
        a.addNote("钻石在河边");
        FrendMemory b = new FrendMemory();
        b.fromNbt(a.toNbt());
        ctx.addInstantFinalTask(() -> {
            ctx.assertTrue(b.hasNotes(), "记事在 NBT 往返里丢了");
            ctx.assertTrue(a.daysTogether(48000L) == b.daysTogether(48000L), "相识天数往返后对不上");
        });
    }
}
