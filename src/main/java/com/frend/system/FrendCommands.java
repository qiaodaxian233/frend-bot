package com.frend.system;

import com.frend.FrendConfig;
import com.frend.entity.FrendEntity;
import com.frend.registry.ModEntities;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * /frend 指令树(不需要 OP,人人可用自己的 frend;写法照 yongye/ModCommands)。
 *
 * <pre>
 * /frend summon    召唤(每人默认 1 个,可配)
 * /frend follow    跟随
 * /frend stay      停留
 * /frend come      过来(远了允许兜底传送)
 * /frend home set  把家定在你脚下
 * /frend home go   让它回家
 * /frend status    汇报状态
 * /frend dismiss   解散(掉落背包后消失)
 * </pre>
 *
 * 指令只作用于「你自己的」frend:summon 之外的子命令都在附近 128 格里找主人是你的个体。
 */
public final class FrendCommands {
    private FrendCommands() {}

    private static final double COMMAND_RADIUS = 128.0;

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> dispatcher.register(
                CommandManager.literal("frend")

                        .then(CommandManager.literal("summon").executes(FrendCommands::summon))

                        .then(CommandManager.literal("follow").executes(ctx -> forEachOwned(ctx, f -> {
                            f.setMode(FrendEntity.Mode.FOLLOW);
                            f.sayDelayed("好嘞,跟紧你!");
                        })))

                        .then(CommandManager.literal("stay").executes(ctx -> forEachOwned(ctx, f -> {
                            f.setMode(FrendEntity.Mode.STAY);
                            f.sayDelayed("行,我在这儿等你。");
                        })))

                        .then(CommandManager.literal("come").executes(FrendCommands::come))

                        .then(CommandManager.literal("home")
                                .then(CommandManager.literal("set").executes(FrendCommands::homeSet))
                                .then(CommandManager.literal("go").executes(FrendCommands::homeGo)))

                        .then(CommandManager.literal("status").executes(FrendCommands::status))
                        .then(CommandManager.literal("memory").executes(FrendCommands::memory))

                        // v0.10 朋友,不是仆人:起名字。聊天说"你以后叫XX"也行。
                        .then(CommandManager.literal("name")
                                .then(CommandManager.argument("名字", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                        .executes(ctx -> forEachOwned(ctx, f ->
                                                f.renameBy(com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "名字"))))))

                        .then(CommandManager.literal("work")
                                .then(CommandManager.literal("chop").executes(ctx -> forEachOwned(ctx, f ->
                                        f.startTask(new com.frend.entity.task.ChopTreeTask(f), "收到,砍树去!"))))
                                .then(CommandManager.literal("stone").executes(ctx -> forEachOwned(ctx, f ->
                                        f.startTask(new com.frend.entity.task.MineTask(f, com.frend.entity.task.MineTask.Kind.STONE), "好,我去凿点石头。"))))
                                .then(CommandManager.literal("ore").executes(ctx -> forEachOwned(ctx, f ->
                                        f.startTask(new com.frend.entity.task.MineTask(f, com.frend.entity.task.MineTask.Kind.ORE), "找煤铁去,有露头的都归咱。"))))
                                .then(CommandManager.literal("tunnel").executes(ctx -> forEachOwned(ctx, f ->
                                        f.startTask(new com.frend.entity.task.TunnelTask(f, com.frend.entity.task.TunnelTask.Kind.TUNNEL), "好,朝我脸冲的方向掘进,见矿顺手掏!"))))
                                .then(CommandManager.literal("deep").executes(ctx -> forEachOwned(ctx, f ->
                                        f.startTask(new com.frend.entity.task.TunnelTask(f, com.frend.entity.task.TunnelTask.Kind.DEEP), "下矿喽!挖楼梯下到矿层再直着掏,跟我后面别掉坑里。"))))
                                .then(CommandManager.literal("deposit").executes(ctx -> forEachOwned(ctx, f ->
                                        f.startTask(new com.frend.entity.task.DepositTask(f), "好,我回家把东西存箱子里。"))))
                                .then(CommandManager.literal("farm").executes(ctx -> forEachOwned(ctx, f ->
                                        f.startTask(new com.frend.entity.task.FarmTask(f), "好,收庄稼去——熟的收,青的留,种子补回去。"))))
                                .then(CommandManager.literal("fish").executes(ctx -> forEachOwned(ctx, f ->
                                        f.startTask(new com.frend.entity.task.FishTask(f), "钓鱼喽……你也来?坐着发会儿呆挺好。"))))
                                .then(CommandManager.literal("smelt").executes(ctx -> forEachOwned(ctx, f ->
                                        f.startTask(new com.frend.entity.task.SmeltTask(f), "开炉!有啥烧啥。"))))
                                .then(CommandManager.literal("stop").executes(ctx -> forEachOwned(ctx, f ->
                                        f.stopTask("收工!"))))
                        )

                        // v0.20 看家
                        .then(CommandManager.literal("guard")
                                .then(CommandManager.literal("on").executes(ctx -> {
                                    FrendConfig.get().guardWhenStay = true;
                                    ctx.getSource().sendFeedback(() -> net.minecraft.text.Text.literal("[frend] 看家已开启(待命时主动清剿岗位附近的怪)"), false);
                                    return forEachOwned(ctx, f -> {
                                        f.setMode(com.frend.entity.FrendEntity.Mode.STAY);
                                        f.sayDelayed("放心去吧,家有我盯着。");
                                    });
                                }))
                                .then(CommandManager.literal("off").executes(ctx -> {
                                    FrendConfig.get().guardWhenStay = false;
                                    ctx.getSource().sendFeedback(() -> net.minecraft.text.Text.literal("[frend] 看家已关闭(待命只自卫)"), false);
                                    return forEachOwned(ctx, f -> f.sayDelayed("行,站岗只看不动手。"));
                                }))
                        )

                        // v0.3 战斗
                        .then(CommandManager.literal("combat")
                                .then(CommandManager.literal("on").executes(ctx -> {
                                    FrendConfig.get().combatEnabled = true;
                                    ctx.getSource().sendFeedback(() -> net.minecraft.text.Text.literal("[frend] 战斗模式已开启"), false);
                                    return forEachOwned(ctx, f -> f.sayDelayed("收到,遇怪就上!"));
                                }))
                                .then(CommandManager.literal("off").executes(ctx -> {
                                    FrendConfig.get().combatEnabled = false;
                                    ctx.getSource().sendFeedback(() -> net.minecraft.text.Text.literal("[frend] 战斗模式已关闭"), false);
                                    return forEachOwned(ctx, f -> f.sayDelayed("好,我按兵不动。"));
                                }))
                        )

                        // v0.5 自主行动开关
                        .then(CommandManager.literal("auto")
                                .then(CommandManager.literal("on").executes(ctx -> {
                                    FrendConfig.get().autonomyEnabled = true;
                                    ctx.getSource().sendFeedback(() -> net.minecraft.text.Text.literal("[frend] 自主行动已开启"), false);
                                    return forEachOwned(ctx, f -> f.sayDelayed("好嘞,那我自己看着办——该砍砍、该存存。"));
                                }))
                                .then(CommandManager.literal("off").executes(ctx -> {
                                    FrendConfig.get().autonomyEnabled = false;
                                    ctx.getSource().sendFeedback(() -> net.minecraft.text.Text.literal("[frend] 自主行动已关闭"), false);
                                    return forEachOwned(ctx, f -> f.sayDelayed("收到,没你的话我不乱动。"));
                                }))
                        )

                        .then(CommandManager.literal("dismiss").executes(FrendCommands::dismiss))
        ));
    }

    // ===================== 子命令实现 =====================

    private static int summon(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity p = ctx.getSource().getPlayer();
        if (p == null) {
            ctx.getSource().sendError(Text.literal("只能由玩家执行"));
            return 0;
        }
        ServerWorld w = p.getServerWorld();

        int max = FrendConfig.get().maxFrendsPerPlayer;
        List<FrendEntity> owned = w.getEntitiesByClass(FrendEntity.class,
                p.getBoundingBox().expand(256.0), f -> f.isAlive() && f.isOwner(p));
        if (owned.size() >= max) {
            ctx.getSource().sendError(Text.literal("你已经有 " + owned.size() + " 个 frend 了(上限 " + max + ",可在 config/frend.json 调)"));
            return 0;
        }

        FrendEntity frend = new FrendEntity(ModEntities.FREND, w);
        frend.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ(), p.getYaw(), 0.0f);
        frend.setOwner(p);
        frend.setMode(FrendEntity.Mode.FOLLOW);
        w.spawnEntity(frend);
        frend.sayDelayed("嗨," + p.getName().getString() + "!以后我就跟你混了。跟我说「跟我来 / 停下 / 回家」都行。");

        ctx.getSource().sendFeedback(() -> Text.literal("frend 已召唤").formatted(Formatting.AQUA), false);
        return 1;
    }

    private static int come(CommandContext<ServerCommandSource> ctx) {
        return forEachOwned(ctx, f -> {
            ServerPlayerEntity p = ctx.getSource().getPlayer();
            f.setMode(FrendEntity.Mode.FOLLOW);
            f.getNavigation().startMovingTo(p, FrendConfig.get().followSpeed);
            f.sayDelayed("来啦来啦!");
        });
    }

    private static int homeSet(CommandContext<ServerCommandSource> ctx) {
        return forEachOwned(ctx, f -> {
            ServerPlayerEntity p = ctx.getSource().getPlayer();
            f.setHome(p.getBlockPos(), p.getServerWorld().getRegistryKey().getValue().toString());
            f.sayDelayed("记下了!这里(" + p.getBlockPos().getX() + " " + p.getBlockPos().getY()
                    + " " + p.getBlockPos().getZ() + ")就是咱家。");
        });
    }

    private static int homeGo(CommandContext<ServerCommandSource> ctx) {
        return forEachOwned(ctx, f -> {
            if (!f.hasHome()) {
                f.sayDelayed("咱还没定过家呢,先用 /frend home set 定一个?");
            } else if (!f.isHomeInThisDimension()) {
                f.sayDelayed("家不在这个维度,我自己走不过去,你带我过去吧。");
            } else {
                f.setMode(FrendEntity.Mode.GO_HOME);
                f.sayDelayed("好,我先回家守着。");
            }
        });
    }

    private static int status(CommandContext<ServerCommandSource> ctx) {
        return forEachOwned(ctx, f -> f.sayDelayed(FrendChatHandler.statusLine(f)));
    }

    /** v0.4:frend 口头回忆战绩与大事记。 */
    private static int memory(CommandContext<ServerCommandSource> ctx) {
        return forEachOwned(ctx, f -> f.sayDelayed(f.getMemory().recapLine(f.getWorld().getTime())));
    }

    private static int dismiss(CommandContext<ServerCommandSource> ctx) {
        return forEachOwned(ctx, f -> {
            com.frend.system.FrendSoul.save(f); // v0.18 走之前把魂存好,下次召回来还是它
            f.say("那……我先回去啦,东西都还你。想我了就召我——我记着咱们的一切呢。");
            f.dropAllItems();
            f.discard();
        });
    }

    // ===================== 工具 =====================

    /** 对附近 128 格内、主人是执行者的所有 frend 执行 action;一个都没有则报错提示。 */
    private static int forEachOwned(CommandContext<ServerCommandSource> ctx, java.util.function.Consumer<FrendEntity> action) {
        ServerPlayerEntity p = ctx.getSource().getPlayer();
        if (p == null) {
            ctx.getSource().sendError(Text.literal("只能由玩家执行"));
            return 0;
        }
        List<FrendEntity> owned = p.getServerWorld().getEntitiesByClass(FrendEntity.class,
                p.getBoundingBox().expand(COMMAND_RADIUS), f -> f.isAlive() && f.isOwner(p));
        if (owned.isEmpty()) {
            ctx.getSource().sendError(Text.literal("附近 " + (int) COMMAND_RADIUS + " 格内没有你的 frend(先 /frend summon,或走近一点)"));
            return 0;
        }
        owned.forEach(action);
        return owned.size();
    }
}
