package com.frend.system;

import com.frend.Frend;
import com.frend.FrendConfig;
import com.frend.entity.FrendEntity;
import com.frend.registry.ModEntities;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v0.21 全测试面板:把 TESTPLAN 七关搬进游戏——聊天栏可点击的测试台。
 *
 * <pre>
 * /frend test              总面板(七关进度)
 * /frend test 1..7         关卡面板(每步:[布置]一键摆考场 [✔][✘]记结果)
 * /frend test check        自检(不用动手打的那部分:注册/存档/配置/魂档读写)
 * /frend test report       汇总报告(测完把这段复制回来报障)
 * /frend test skipdays N   模拟"你离线了 N 天刚回来"(测重逢催泪)
 * /frend test days N       相识天数直接 +N(测纪念日 10/100/365)
 * /frend test reset        清空测试进度(要点二次确认)
 * </pre>
 *
 * <b>需要权限等级 2(单机开作弊 / 服务器 OP)</b>——布置考场要发东西、刷怪、调时间,
 * 这是测试工具不是玩法,普通玩家不该有。进度存 {@code config/frend/testpanel.json},
 * 跨重启不丢;[✘] 的步骤在报告里单列,照 TESTPLAN 报障格式贴回来即可。
 *
 * <p>设计取舍:面板是<b>聊天栏可点击文本</b>而不是 GUI Screen——服务端纯文本零客户端
 * 依赖,API 面小(Text/ClickEvent/HoverEvent 三件),沙箱不能编译的项目里能少赌一个是一个。
 */
public final class FrendTestPanel {

    private FrendTestPanel() {}

    // ===================== 剧本数据 =====================

    /** 一键布置考场的动作:返回一句"我干了什么"的反馈。 */
    private interface Setup {
        String run(ServerPlayerEntity p);
    }

    private record Step(String desc, String expect, String setupHint, Setup setup) {
        Step(String desc, String expect) { this(desc, expect, null, null); }
    }

    private record Gate(String title, String minutes, Step[] steps) {}

    private static final Gate[] GATES = buildGates();

    private static Gate[] buildGates() {
        return new Gate[]{

            new Gate("活着", "v0.1 · 10 分钟", new Step[]{
                new Step("/frend summon 召唤", "史蒂夫皮肤实体出现并打招呼;/frend status 有报告"),
                new Step("走远它跟上;跑出 48 格", "兜底传送到你身边"),
                new Step("右键它打开背包,放把斧头", "27 格背包界面;斧头它会自己拿来用",
                        "发你 1 把铁斧(放进它背包用)",
                        p -> { give(p, Items.IRON_AXE, 1); return "铁斧 ×1 已发你"; }),
                new Step("/frend home set → stay → 走远说\"回家\"", "它自己走回家"),
                new Step("退出重进存档", "它还在,背包还在,模式合理"),
            }),

            new Gate("嘴", "v0.2/0.10/0.18 · 10 分钟", new Step[]{
                new Step("附近说\"你好\"", "回话;15 秒内不喊名字也接话(对话延续窗口)"),
                new Step("说\"你以后叫小白\"", "头顶变小白;之后喊\"小白\"它应"),
                new Step("\"记住:钻石在河边\" → \"你记得什么\"", "先\"记下了\",后能复述"),
                new Step("同一句短话(如\"走起\")说 3 遍", "\"这话我跟你学的\";之后闲聊可能蹦出来"),
                new Step("(可选)接 Ollama 说\"累了,先回去吧\"", "config chatBackend=openai 后,LLM 听懂是回家(v0.17 意图)"),
            }),

            new Gate("手", "v0.2/0.13/0.16 · 15 分钟", new Step[]{
                new Step("给斧头说\"砍树\"", "整棵收,带破坏进度动画,木头进它包",
                        "发你 1 把铁斧",
                        p -> { give(p, Items.IRON_AXE, 1); return "铁斧 ×1 已发你,转交它后说\"砍树\""; }),
                new Step("清空它背包说\"自由活动\"", "全智能自举:徒手撸树→造木镐→凿石→(给煤)搓火把→包满回家存箱子,全程零指令",
                        "清空它的背包(测试用,里面东西会消失!)",
                        p -> forFrend(p, f -> { f.getInventory().clear(); return "它的背包已清空,说\"自由活动\"看白手起家"; })),
                new Step("山脚说\"挖隧道\";路线上垫两块木板", "1x2 直巷不歪;碰到木板\"像有人修的\"停工",
                        "发你 8 块橡木木板(垫它路线上当\"人造方块\")",
                        p -> { give(p, Items.OAK_PLANKS, 8); return "橡木木板 ×8 已发你"; }),
                new Step("平原说\"下矿\"", "楼梯到 -58 转平巷,每 4 步左右开分支(鱼骨巷型)"),
                new Step("家旁放箱子说\"存箱子\"", "工具干粮自留,其余入箱",
                        "发你 1 个箱子",
                        p -> { give(p, Items.CHEST, 1); return "箱子 ×1 已发你,摆家旁边"; }),
            }),

            new Gate("拳", "v0.3/0.8/0.14/0.19 · 15 分钟", new Step[]{
                new Step("石剑夜战僵尸", "起跳落刀有暴击粒子,间隙侧移不站桩",
                        "石剑塞它包里 + 天调到夜里 + 你身边 8 格刷 1 只僵尸",
                        p -> {
                            String r = giveFrend(p, new ItemStack(Items.STONE_SWORD));
                            night(p); spawnMob(p, EntityType.ZOMBIE, 8, 0);
                            return r + ";已入夜,僵尸 ×1 在你东边 8 格";
                        }),
                new Step("弓箭远程骷髅", ">9 格拉弓射(带提前量),贴脸自动换剑",
                        "弓+箭 32 塞它包里 + 你东边 15 格刷 1 只骷髅",
                        p -> {
                            String r = giveFrend(p, new ItemStack(Items.BOW), new ItemStack(Items.ARROW, 32));
                            night(p); spawnMob(p, EntityType.SKELETON, 15, 0);
                            return r + ";骷髅 ×1 在你东边 15 格";
                        }),
                new Step("苦力怕教训:故意让它被炸两回", "之后见苦力怕站得明显更远(知识改变行为)",
                        "你东边 10 格刷 1 只苦力怕(炸完再点一次布置)",
                        p -> { spawnMob(p, EntityType.CREEPER, 10, 0); return "苦力怕 ×1 在你东边 10 格,小心你自己"; }),
                new Step("你被怪打它支援;它被疣猪兽打还手(下界自卫)", "支援秒到;下界僵尸猪灵它不主动,被打才还手",
                        "你身边 6 格刷 1 只僵尸打你",
                        p -> { night(p); spawnMob(p, EntityType.ZOMBIE, 6, 0); return "僵尸 ×1 在你东边 6 格,挨一下看它反应"; }),
                new Step("打到残血", "\"我不行了先撤\"绕你身后跑,不硬拼",
                        "把它血量压到 2 颗心(4 点)",
                        p -> forFrend(p, f -> { f.setHealth(4.0f); return "它血量已压到 2 颗心,给它个敌人看撤不撤"; })),
            }),

            new Gate("心", "v0.11/0.15/0.18 · 15 分钟", new Step[]{
                new Step("你残血且它包里有面包", "朝你扔一份\"接着!\";只剩一份时\"最后一口了,你吃\"",
                        "面包 ×3 塞它包里 + 你血量压到 3 颗心",
                        p -> {
                            String r = giveFrend(p, new ItemStack(Items.BREAD, 3));
                            p.setHealth(6.0f);
                            return r + ";你的血量已压到 3 颗心,站它跟前等投喂";
                        }),
                new Step("你故意死一次", "它喊\"东西我去收!\"赶去收掉落;复活走近全数归还\"点点?\";之后路过死点有提醒"),
                new Step("离线重逢分级问候", "3 天\"我数着日子呢\";35 天催泪长句(见到你才说,不对空气喊)",
                        "模拟\"你离线 3 天刚回来\"(35 天用 /frend test skipdays 35)",
                        p -> {
                            FrendSoul.debugQueueReunion(p.getUuid(), 3);
                            return "已模拟离线 3 天——走到它跟前听它说;催泪版:/frend test skipdays 35";
                        }),
                new Step("dismiss 后重召;开新存档再召", "\"是你!咱们的事我一件没忘\"(灵魂);新档同样认得你,名字战绩都在"),
                new Step("相识纪念日(第 10/100/365 天)", "到日子它自己说那句(一生各一次,跨档也只说一次)",
                        "把相识天数直接拨到下一个纪念日",
                        p -> forFrend(p, f -> {
                            long now = f.getMemory().daysTogether(f.getWorld().getTime());
                            long target = now < 10 ? 10 : (now < 100 ? 100 : 365);
                            if (now >= 365) return "相识已 " + now + " 天,纪念日全过完了(365 是最后一个)";
                            f.getMemory().debugAddDays(target - now);
                            return "相识天数已拨到第 " + target + " 天,在它旁边等它反应(最多半分钟)";
                        })),
            }),

            new Gate("腿", "v0.9/0.12 · 10 分钟", new Step[]{
                new Step("装木门带它进屋", "自己开门,走过随手关上",
                        "发你 1 扇橡木门",
                        p -> { give(p, Items.OAK_DOOR, 1); return "橡木门 ×1 已发你"; }),
                new Step("隔河喊\"跟我来\"", "直接游过来,不绕路"),
                new Step("家设 300 格外说\"回家\"", "分段寻路走到,不说\"走不过去\"(48 格上限内部分段)"),
                new Step("你进下界", "≈4 秒后它追过来\"等等我\";见僵尸猪灵不动手"),
                new Step("走三个没去过的群系;问\"见过什么\"", "\"头一回来,又长一分见识\";总结能数家珍"),
            }),

            new Gate("家", "v0.20 · 15 分钟", new Step[]{
                new Step("门口说\"看家\",夜里怪靠近", "\"家门口撒野?!\"主动出击;打完自己溜达回岗位",
                        "入夜 + 你身边 10 格刷 2 只僵尸",
                        p -> {
                            night(p);
                            spawnMob(p, EntityType.ZOMBIE, 10, 0);
                            spawnMob(p, EntityType.ZOMBIE, 0, 10);
                            return "已入夜,僵尸 ×2 就位——先说\"看家\"再看它清场";
                        }),
                new Step("用怪把它往远处引", "追出约 28 格放弃回头\"不追了……家要紧\"(拴绳防风筝)"),
                new Step("给生铁说\"烧铁\";再试只给原木;你的炉子里放东西", "找炉/自己盘炉,装料点火出炉入包;只有原木先烧木炭救急;有料的炉子跳过不碰",
                        "生铁 ×6 + 煤 ×4 + 圆石 ×8 塞它包里(木炭救急测法:清掉煤只给原木)",
                        p -> giveFrend(p, new ItemStack(Items.RAW_IRON, 6),
                                new ItemStack(Items.COAL, 4), new ItemStack(Items.COBBLESTONE, 8))),
                new Step("给它木镐+3 铁锭+2 木棍", "不等镐用坏,自主决策\"鸟枪换炮\"直接造铁镐换上",
                        "木镐 ×1 + 铁锭 ×3 + 木棍 ×2 塞它包里",
                        p -> giveFrend(p, new ItemStack(Items.WOODEN_PICKAXE),
                                new ItemStack(Items.IRON_INGOT, 3), new ItemStack(Items.STICK, 2))),
                new Step("种一片小麦催熟说\"种田\";收走它的种子再试", "只收金黄的青苗不碰;收完当场补种;缺种收工汇报\"有 X 个坑没补上\"",
                        "发你 小麦种子 ×16 + 骨粉 ×32 + 铁锄 ×1(自己种一片催熟)",
                        p -> {
                            give(p, Items.WHEAT_SEEDS, 16); give(p, Items.BONE_MEAL, 32); give(p, Items.IRON_HOE, 1);
                            return "种子/骨粉/锄头已发你,种好催熟后说\"种田\"";
                        }),
                new Step("给鱼竿说\"钓鱼\";中途打它一下", "走到露天水边站定,10~30 秒一条;挨打立刻收竿应战;竿断收工",
                        "鱼竿 ×1 塞它包里",
                        p -> giveFrend(p, new ItemStack(Items.FISHING_ROD))),
                new Step("观察:它闲着时从不自己跑去钓鱼", "钓鱼不进自主决策——是情调,不是家务"),
            }),
        };
    }

    // ===================== 进度存取 =====================

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** "关.步"(1 起) → "P"通过 / "F"不过;没记录 = 未测。 */
    private static Map<String, String> results = null;

    private static Path resultPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("frend").resolve("testpanel.json");
    }

    private static Map<String, String> results() {
        if (results != null) return results;
        try {
            Path p = resultPath();
            if (Files.exists(p)) {
                results = GSON.fromJson(Files.readString(p),
                        new TypeToken<LinkedHashMap<String, String>>(){}.getType());
            }
        } catch (Exception e) {
            Frend.LOGGER.warn("[frend] 测试进度读取失败(从零开始): {}", e.toString());
        }
        if (results == null) results = new LinkedHashMap<>();
        return results;
    }

    private static void saveResults() {
        try {
            Path p = resultPath();
            Files.createDirectories(p.getParent());
            Files.writeString(p, GSON.toJson(results()));
        } catch (Exception e) {
            Frend.LOGGER.warn("[frend] 测试进度保存失败: {}", e.toString());
        }
    }

    // ===================== 指令树 =====================

    public static LiteralArgumentBuilder<ServerCommandSource> commandTree() {
        return CommandManager.literal("test")
                .requires(src -> src.hasPermissionLevel(2)) // 发东西刷怪调时间,OP 才配
                .executes(ctx -> { showOverview(ctx.getSource()); return 1; })

                .then(CommandManager.literal("check").executes(FrendTestPanel::selfCheck))
                .then(CommandManager.literal("report").executes(ctx -> { report(ctx.getSource()); return 1; }))

                .then(CommandManager.literal("reset")
                        .executes(ctx -> {
                            ctx.getSource().sendFeedback(() -> Text.literal("[frend] 这会清空全部测试进度!确认请点 ")
                                    .formatted(Formatting.YELLOW)
                                    .append(btn("[真的清空]", "/frend test reset confirm", "点了就没了", Formatting.RED)), false);
                            return 1;
                        })
                        .then(CommandManager.literal("confirm").executes(ctx -> {
                            results().clear();
                            saveResults();
                            ctx.getSource().sendFeedback(() -> Text.literal("[frend] 测试进度已清空,从头再来。").formatted(Formatting.GRAY), false);
                            showOverview(ctx.getSource());
                            return 1;
                        })))

                .then(CommandManager.literal("go")
                        .then(CommandManager.argument("关", IntegerArgumentType.integer(1, GATES.length))
                                .then(CommandManager.argument("步", IntegerArgumentType.integer(1, 9))
                                        .executes(FrendTestPanel::runSetup))))
                .then(CommandManager.literal("pass")
                        .then(CommandManager.argument("关", IntegerArgumentType.integer(1, GATES.length))
                                .then(CommandManager.argument("步", IntegerArgumentType.integer(1, 9))
                                        .executes(ctx -> mark(ctx, "P")))))
                .then(CommandManager.literal("fail")
                        .then(CommandManager.argument("关", IntegerArgumentType.integer(1, GATES.length))
                                .then(CommandManager.argument("步", IntegerArgumentType.integer(1, 9))
                                        .executes(ctx -> mark(ctx, "F")))))

                // 时间调试钩:没有它们,重逢/纪念日永远没法当场测
                .then(CommandManager.literal("skipdays")
                        .then(CommandManager.argument("天数", IntegerArgumentType.integer(0, 100000))
                                .executes(ctx -> {
                                    ServerPlayerEntity p = ctx.getSource().getPlayer();
                                    if (p == null) return 0;
                                    int d = IntegerArgumentType.getInteger(ctx, "天数");
                                    FrendSoul.debugQueueReunion(p.getUuid(), d);
                                    ctx.getSource().sendFeedback(() -> Text.literal(
                                            "[frend] 已模拟\"你离线了 " + d + " 天刚回来\"——走到它跟前(聊天半径内)听它说。")
                                            .formatted(Formatting.AQUA), false);
                                    return 1;
                                })))
                .then(CommandManager.literal("days")
                        .then(CommandManager.argument("天数", IntegerArgumentType.integer(1, 100000))
                                .executes(ctx -> {
                                    ServerPlayerEntity p = ctx.getSource().getPlayer();
                                    if (p == null) return 0;
                                    int d = IntegerArgumentType.getInteger(ctx, "天数");
                                    String fb = forFrend(p, f -> {
                                        f.getMemory().debugAddDays(d);
                                        return "相识天数 +" + d + ",现在是第 "
                                                + f.getMemory().daysTogether(f.getWorld().getTime()) + " 天";
                                    });
                                    String finalFb = fb;
                                    ctx.getSource().sendFeedback(() -> Text.literal("[frend] " + finalFb).formatted(Formatting.AQUA), false);
                                    return 1;
                                })))

                .then(CommandManager.argument("关", IntegerArgumentType.integer(1, GATES.length))
                        .executes(ctx -> {
                            showGate(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "关"));
                            return 1;
                        }));
    }

    // ===================== 面板渲染 =====================

    private static void showOverview(ServerCommandSource src) {
        src.sendFeedback(() -> Text.literal("━━━ frend 全测试面板 ━━━").formatted(Formatting.GOLD, Formatting.BOLD), false);
        int totalP = 0, totalF = 0, totalAll = 0;
        for (int g = 1; g <= GATES.length; g++) {
            Gate gate = GATES[g - 1];
            int pass = 0, fail = 0;
            for (int s = 1; s <= gate.steps().length; s++) {
                String r = results().get(g + "." + s);
                if ("P".equals(r)) pass++;
                else if ("F".equals(r)) fail++;
            }
            int untested = gate.steps().length - pass - fail;
            totalP += pass; totalF += fail; totalAll += gate.steps().length;

            Formatting nameColor = fail > 0 ? Formatting.RED : (untested == 0 ? Formatting.GREEN : Formatting.WHITE);
            MutableText line = Text.literal(" ")
                    .append(btn("[第" + cn(g) + "关 " + gate.title() + "]", "/frend test " + g,
                            gate.minutes() + " · 点开测", nameColor))
                    .append(Text.literal("  ✔" + pass).formatted(Formatting.GREEN))
                    .append(Text.literal(" ✘" + fail).formatted(fail > 0 ? Formatting.RED : Formatting.DARK_GRAY))
                    .append(Text.literal(" ⬜" + untested).formatted(Formatting.GRAY));
            src.sendFeedback(() -> line, false);
        }
        int fTotalP = totalP, fTotalF = totalF, fAll = totalAll;
        src.sendFeedback(() -> Text.literal(" 进度 " + (fTotalP + fTotalF) + "/" + fAll
                        + "(✔" + fTotalP + " ✘" + fTotalF + ") ").formatted(Formatting.GRAY)
                .append(btn("[自检]", "/frend test check", "不用动手打的那部分,先跑这个", Formatting.AQUA))
                .append(Text.literal(" "))
                .append(btn("[报告]", "/frend test report", "汇总一份,测完复制回来报障", Formatting.YELLOW))
                .append(Text.literal(" "))
                .append(btn("[重置]", "/frend test reset", "清空全部进度(有二次确认)", Formatting.DARK_GRAY)), false);
    }

    private static void showGate(ServerCommandSource src, int g) {
        Gate gate = GATES[g - 1];
        src.sendFeedback(() -> Text.literal("━━ 第" + cn(g) + "关:" + gate.title() + "(" + gate.minutes() + ")━━")
                .formatted(Formatting.GOLD, Formatting.BOLD), false);
        for (int s = 1; s <= gate.steps().length; s++) {
            Step step = gate.steps()[s - 1];
            String r = results().get(g + "." + s);
            MutableText mark = "P".equals(r) ? Text.literal("✔").formatted(Formatting.GREEN)
                    : "F".equals(r) ? Text.literal("✘").formatted(Formatting.RED)
                    : Text.literal("⬜").formatted(Formatting.DARK_GRAY);

            MutableText line = Text.literal(" " + s + " ").formatted(Formatting.GRAY)
                    .append(mark)
                    .append(Text.literal(" " + step.desc() + " ").styled(st -> st.withColor(Formatting.WHITE)
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Text.literal("【预期】" + step.expect())))));
            if (step.setup() != null) {
                line.append(btn("[布置]", "/frend test go " + g + " " + s, step.setupHint(), Formatting.AQUA))
                        .append(Text.literal(" "));
            }
            line.append(btn("[✔]", "/frend test pass " + g + " " + s, "这步过了", Formatting.GREEN))
                    .append(btn("[✘]", "/frend test fail " + g + " " + s, "这步不对劲(报告里会单列)", Formatting.RED));
            src.sendFeedback(() -> line, false);
        }
        src.sendFeedback(() -> Text.literal(" ")
                .append(btn("[↩ 总面板]", "/frend test", "回七关一览", Formatting.GRAY)), false);
    }

    private static int runSetup(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity p = ctx.getSource().getPlayer();
        if (p == null) { ctx.getSource().sendError(Text.literal("只能由玩家执行")); return 0; }
        int g = IntegerArgumentType.getInteger(ctx, "关");
        int s = IntegerArgumentType.getInteger(ctx, "步");
        Step step = stepAt(g, s);
        if (step == null) { ctx.getSource().sendError(Text.literal("没有这一步")); return 0; }
        if (step.setup() == null) {
            ctx.getSource().sendFeedback(() -> Text.literal("[frend] 这步没有自动布置,照描述手动来。").formatted(Formatting.GRAY), false);
            return 1;
        }
        try {
            String fb = step.setup().run(p);
            ctx.getSource().sendFeedback(() -> Text.literal("[frend·布置] " + fb).formatted(Formatting.AQUA), false);
        } catch (Exception e) {
            ctx.getSource().sendError(Text.literal("布置失败:" + e));
            Frend.LOGGER.warn("[frend] 测试布置失败 {}.{}", g, s, e);
        }
        return 1;
    }

    private static int mark(CommandContext<ServerCommandSource> ctx, String result) {
        int g = IntegerArgumentType.getInteger(ctx, "关");
        int s = IntegerArgumentType.getInteger(ctx, "步");
        if (stepAt(g, s) == null) { ctx.getSource().sendError(Text.literal("没有这一步")); return 0; }
        results().put(g + "." + s, result);
        saveResults();
        showGate(ctx.getSource(), g); // 记完原地刷新,顺手测下一步
        return 1;
    }

    private static void report(ServerCommandSource src) {
        src.sendFeedback(() -> Text.literal("===== frend 测试报告(整段复制回来报障)=====").formatted(Formatting.GOLD), false);
        int totalP = 0, totalF = 0, total = 0;
        StringBuilder fails = new StringBuilder();
        for (int g = 1; g <= GATES.length; g++) {
            Gate gate = GATES[g - 1];
            int pass = 0, fail = 0;
            for (int s = 1; s <= gate.steps().length; s++) {
                String r = results().get(g + "." + s);
                if ("P".equals(r)) pass++;
                else if ("F".equals(r)) { fail++; fails.append("  ✘ ").append(g).append(".").append(s)
                        .append(" ").append(gate.steps()[s - 1].desc()).append("\n"); }
            }
            total += gate.steps().length; totalP += pass; totalF += fail;
            String line = "第" + cn(g) + "关 " + gate.title() + ": ✔" + pass + " ✘" + fail
                    + " ⬜" + (gate.steps().length - pass - fail);
            src.sendFeedback(() -> Text.literal(line), false);
        }
        if (fails.length() > 0) {
            src.sendFeedback(() -> Text.literal("不过的步骤(报障请写:做了什么/它做了什么/期望什么,有报错贴 log):").formatted(Formatting.RED), false);
            String fl = fails.toString().stripTrailing();
            src.sendFeedback(() -> Text.literal(fl).formatted(Formatting.RED), false);
        }
        int fP = totalP, fF = totalF, fT = total;
        src.sendFeedback(() -> Text.literal("总计: ✔" + fP + " ✘" + fF + " ⬜" + (fT - fP - fF) + " / 共 " + fT
                + " · config v" + FrendConfig.get().configVersion).formatted(Formatting.GOLD), false);
    }

    // ===================== 自检(不用动手打的那部分) =====================

    private static int selfCheck(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        src.sendFeedback(() -> Text.literal("━━ frend 自检 ━━").formatted(Formatting.GOLD, Formatting.BOLD), false);

        // 1. 实体注册
        try {
            var id = Registries.ENTITY_TYPE.getId(ModEntities.FREND);
            ok(src, "实体注册: " + id);
        } catch (Exception e) { bad(src, "实体注册: " + e); }

        // 2. 配置
        try {
            FrendConfig c = FrendConfig.get();
            ok(src, "配置: v" + c.configVersion + " · chatBackend=" + c.chatBackend
                    + " · 自主=" + c.autonomyEnabled + " · 看家=" + c.guardWhenStay
                    + " · 灵魂=" + c.soulEnabled + " · 知识=" + c.knowledgeEnabled);
            if ("openai".equalsIgnoreCase(c.chatBackend) && (c.openaiBaseUrl == null || c.openaiBaseUrl.isBlank())) {
                bad(src, "LLM 配置: chatBackend=openai 但 openaiBaseUrl 是空的,会一直退回规则模板");
            }
        } catch (Exception e) { bad(src, "配置: " + e); }

        // 3. 魂档目录可写 + NBT 读写往返
        try {
            Path dir = FabricLoader.getInstance().getConfigDir().resolve("frend").resolve("souls");
            Files.createDirectories(dir);
            Path probe = dir.resolve("selfcheck.dat");
            NbtCompound out = new NbtCompound();
            out.putString("Probe", "frend");
            out.putLong("Millis", System.currentTimeMillis());
            NbtIo.writeCompressed(out, probe);
            NbtCompound in = NbtIo.readCompressed(probe, NbtSizeTracker.ofUnlimitedBytes());
            Files.deleteIfExists(probe);
            if ("frend".equals(in.getString("Probe"))) ok(src, "灵魂档读写往返: " + dir);
            else bad(src, "灵魂档往返内容不符(写进去的没读回来)");
        } catch (Exception e) { bad(src, "灵魂档读写: " + e); }

        // 4. 附近 frend 状态
        ServerPlayerEntity p = src.getPlayer();
        if (p != null) {
            List<FrendEntity> owned = owned(p);
            if (owned.isEmpty()) {
                src.sendFeedback(() -> Text.literal(" · 附近没有你的 frend(/frend summon 后再跑一次能看到状态行)").formatted(Formatting.GRAY), false);
            } else {
                for (FrendEntity f : owned) {
                    String s = "你的 frend: " + FrendChatHandler.statusLine(f)
                            + " · 模式=" + f.getMode() + " · 岗位=" + f.getGuardAnchor().toShortString();
                    ok(src, s);
                }
            }
        }

        // 5. 测试进度
        long done = results().size();
        src.sendFeedback(() -> Text.literal(" · 测试进度已记 " + done + " 步(/frend test 看面板)").formatted(Formatting.GRAY), false);
        return 1;
    }

    private static void ok(ServerCommandSource src, String s)  { src.sendFeedback(() -> Text.literal(" ✔ " + s).formatted(Formatting.GREEN), false); }
    private static void bad(ServerCommandSource src, String s) { src.sendFeedback(() -> Text.literal(" ✘ " + s).formatted(Formatting.RED), false); }

    // ===================== 工具 =====================

    private static Step stepAt(int g, int s) {
        if (g < 1 || g > GATES.length) return null;
        Step[] steps = GATES[g - 1].steps();
        return (s < 1 || s > steps.length) ? null : steps[s - 1];
    }

    /** 可点击按钮(点击跑指令,悬浮出说明)。 */
    private static MutableText btn(String label, String command, String hover, Formatting color) {
        return Text.literal(label).styled(st -> st.withColor(color)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Text.literal(hover == null ? command : hover))));
    }

    private static List<FrendEntity> owned(ServerPlayerEntity p) {
        return p.getServerWorld().getEntitiesByClass(FrendEntity.class,
                p.getBoundingBox().expand(128.0), f -> f.isAlive() && f.isOwner(p));
    }

    /** 对第一只自己的 frend 执行;没有则返回提示语。 */
    private static String forFrend(ServerPlayerEntity p, java.util.function.Function<FrendEntity, String> fn) {
        List<FrendEntity> owned = owned(p);
        if (owned.isEmpty()) return "附近 128 格没有你的 frend——先 /frend summon";
        return fn.apply(owned.get(0));
    }

    private static void give(ServerPlayerEntity p, Item item, int count) {
        p.getInventory().offerOrDrop(new ItemStack(item, count));
    }

    /** 直接塞进 frend 背包(它每 2 秒自动换装最好的武器/工具)。 */
    private static String giveFrend(ServerPlayerEntity p, ItemStack... stacks) {
        return forFrend(p, f -> {
            StringBuilder sb = new StringBuilder();
            for (ItemStack st : stacks) {
                if (sb.length() > 0) sb.append("、");
                sb.append(st.getName().getString()).append(" ×").append(st.getCount());
                ItemStack rest = f.getInventory().addStack(st);
                if (!rest.isEmpty()) f.dropStack(rest); // 它包满了就掉脚边,别吞
            }
            return sb + " 已塞进它背包";
        });
    }

    /** 在玩家东/南方向 (dx,dz) 格处刷怪(考场请选开阔平地)。【待编译验证】EntityType#spawn 三参 */
    private static void spawnMob(ServerPlayerEntity p, EntityType<?> type, int dx, int dz) {
        BlockPos pos = p.getBlockPos().add(dx, 0, dz);
        type.spawn(p.getServerWorld(), pos, SpawnReason.COMMAND);
    }

    /** 调到夜里(13000,刚天黑不烧僵尸)。 */
    private static void night(ServerPlayerEntity p) {
        p.getServerWorld().setTimeOfDay(13000L);
    }

    private static String cn(int n) {
        String[] c = {"一", "二", "三", "四", "五", "六", "七", "八", "九"};
        return n >= 1 && n <= 9 ? c[n - 1] : String.valueOf(n);
    }
}
