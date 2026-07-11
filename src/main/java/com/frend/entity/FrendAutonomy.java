package com.frend.entity;

import com.frend.FrendConfig;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.ItemTags;

/**
 * v0.5 自主行动:不等主人下命令,自己判断"该做的事"。
 *
 * <p><b>红线不变</b>:决策 100% 本地规则(优先级阶梯,不是 LLM——模型永远只管说话不管做事);
 * 每次自主开工都<b>先打招呼</b>;主人一句"收工/跟我来"随时打断(走既有的任务打断链路);
 * 平时不捡地上的东西、不打玩家的规矩全部继承。
 *
 * <p>决策阶梯(每个决策周期从上往下,命中即止):
 * <ol>
 *   <li><b>包快满了</b> → 自己回家存箱子(家在本维度才去);FOLLOW 模式不擅自离队,只口头建议;</li>
 *   <li>v0.16/v0.20 <b>自给自足链</b>:缺工具/可升级 → 自己造;火把见底 → 自己搓;
 *       攒了生矿 → 开炉烧;庄稼熟了 → 顺手收一茬补种(<b>钓鱼刻意不进阶梯</b>:
 *       钓鱼是情调不是家务,等朋友开口一起去);</li>
 *   <li><b>有斧头</b> → 附近砍树;</li>
 *   <li><b>有镐子</b> → 附近凿石头;</li>
 *   <li><b>啥工具都没有</b> → 提一嘴"给我把工具"(长冷却,不刷屏)。</li>
 * </ol>
 * 只有 STAY(待命)状态闲置超过 {@code autonomyIdleSeconds} 秒才自主开工——
 * 跟随/回家/干活中都不抢戏。每次决策后进入 {@code autonomyCooldownSeconds} 冷却,防抽风。
 *
 * <p>另带<b>环境闲话</b>(可关):日出/日落/开始下雨时说一句,每个触发点带独立去重
 * (日出日落按游戏天数记,一天一次),主人不在身边不说(说给空气听没意义)。
 */
public class FrendAutonomy {

    private final FrendEntity frend;

    /** STAY 状态连续闲置 tick 数。 */
    private int idleTicks = 0;

    /** 自主决策冷却(tick)。任何一次决策(含"没工具"抱怨)后置位。 */
    private int decisionCooldown = 0;

    /** FOLLOW 模式"包满了"口头建议的独立冷却(tick)。 */
    private int suggestCooldown = 0;

    // ===== 环境闲话去重 =====
    private long lastDawnDay = -1;
    private long lastDuskDay = -1;
    private boolean wasRaining = false;
    /** v0.16 "天黑不出去浪"每晚一次去重。 */
    private long lastNightSaidDay = -1;

    // ===== 快速失败退避:自主开的工秒结束(附近没活干) → 下次冷却×3,防止每两分钟空转唠叨 =====
    private boolean selfStarted = false;
    private int selfStartAge = 0;

    public FrendAutonomy(FrendEntity frend) {
        this.frend = frend;
    }

    /** 由 FrendEntity#mobTick 每 tick 调(仅服务端)。 */
    public void tick(FrendConfig c) {
        if (decisionCooldown > 0) decisionCooldown--;
        if (suggestCooldown > 0) suggestCooldown--;

        if (!c.autonomyEnabled) { idleTicks = 0; return; }

        if (c.autonomyChatter) envChatter(c);

        // 自主任务收尾检测:开工不到 6 秒就结束 = 附近没活干,退避
        if (selfStarted && !frend.isWorking()) {
            selfStarted = false;
            if (frend.age - selfStartAge < 120) {
                decisionCooldown = c.autonomyCooldownSeconds * 20 * 3;
            }
        }

        FrendEntity.Mode mode = frend.getMode();

        // FOLLOW:不擅自离队,包满了只建议
        if (mode == FrendEntity.Mode.FOLLOW) {
            idleTicks = 0;
            if (suggestCooldown <= 0 && inventoryFullness() >= c.autonomyDepositAtFullness
                    && frend.hasHome()) {
                suggestCooldown = 20 * 600; // 10 分钟一次,不唠叨
                frend.sayDelayed("哎,我包快满了……找个机会让我回家存一趟?说\"存箱子\"就行。");
            }
            return;
        }

        // 干活中 / 赶路回家:不打扰
        if (mode != FrendEntity.Mode.STAY || frend.isWorking()) {
            idleTicks = 0;
            return;
        }

        // STAY 待命:攒闲置时间,到点自己找活
        idleTicks++;
        if (decisionCooldown > 0) return;
        if (idleTicks < c.autonomyIdleSeconds * 20) return;

        decide(c);
    }

    // ===================== 决策阶梯 =====================

    private void decide(FrendConfig c) {
        decisionCooldown = c.autonomyCooldownSeconds * 20;
        idleTicks = 0;
        selfStarted = true;           // 下面所有分支都算一次自主决策
        selfStartAge = frend.age;     // (纯喊话分支也置位无妨:isWorking=false 会立即触发退避,正好加长抱怨间隔)

        // 0) v0.16 天黑收敛:夜里不接新活(正在干的不打断),守着别浪——像个懂事的人
        if (c.nightCaution && isNight()) {
            long day = frend.getWorld().getTimeOfDay() / 24000L;
            if (lastNightSaidDay != day && ownerNearby(c)) {
                lastNightSaidDay = day;
                frend.sayDelayed("天黑了,怪多,我先不出去浪——有事天亮再干。");
            }
            return;
        }

        // 1) 包快满 → 回家存(家得在这个维度,不然走不过去)
        if (inventoryFullness() >= c.autonomyDepositAtFullness
                && frend.hasHome() && frend.isHomeInThisDimension()) {
            frend.startTask(new com.frend.entity.task.DepositTask(frend),
                    "包快满了,我自己回趟家存箱子,存完就回来。");
            return;
        }

        // 1.5) v0.16 自给自足:家伙不齐先自己造;火把见底自己搓——不再"没镐伸手要"
        if (c.selfSufficient && com.frend.entity.task.CraftTask.shouldCraftTools(frend)) {
            frend.startTask(new com.frend.entity.task.CraftTask(frend,
                            com.frend.entity.task.CraftTask.Goal.TOOLS),
                    "家伙不齐,我自己鼓捣两件——叮叮当当别嫌吵。");
            return;
        }
        if (c.selfSufficient && c.autoTorch
                && com.frend.entity.task.CraftTask.shouldCraftTorches(frend)) {
            frend.startTask(new com.frend.entity.task.CraftTask(frend,
                            com.frend.entity.task.CraftTask.Goal.TORCHES),
                    "火把见底了,搓一把备着。");
            return;
        }
        // 1.6) v0.20 铁器链:攒了生铁有燃料 → 开炉去烧(烧完材料到位,升级铁器由 CraftTask 自然接手)
        if (c.selfSufficient && com.frend.entity.task.SmeltTask.shouldSmelt(frend)) {
            frend.startTask(new com.frend.entity.task.SmeltTask(frend),
                    "攒了些生矿,我去开炉烧一炉——铁家伙不远了。");
            return;
        }
        // 1.7) v0.20 顺手收庄稼:附近有熟透的 → 收一茬补种(只动熟的,青苗一根不碰)
        if (c.autonomyFarm && com.frend.entity.task.FarmTask.hasMatureCropNearby(frend)) {
            frend.startTask(new com.frend.entity.task.FarmTask(frend),
                    "地里庄稼熟了,我去收一茬,种子给你补回去。");
            return;
        }

        // 2) 有斧头 → 砍树;3) 有镐子 → 凿石头;都没有且允许徒手 → 徒手砍树兜底
        boolean hasAxe  = !frend.findUsableTool(ItemTags.AXES).isEmpty();
        boolean hasPick = !frend.findUsableTool(ItemTags.PICKAXES).isEmpty();

        // v0.16 白手起家自举:啥都没有、材料也没有 → 徒手撸树(树→木镐→石头→石器,链条自己转起来)
        if (c.selfSufficient && !hasAxe && !hasPick
                && !com.frend.entity.task.CraftTask.shouldCraftTools(frend)) {
            frend.startTask(new com.frend.entity.task.ChopTreeTask(frend),
                    "白手起家,先撸树!有树就有镐,有镐啥都有。");
            return;
        }

        if (hasAxe) {
            frend.startTask(new com.frend.entity.task.ChopTreeTask(frend),
                    "闲着也是闲着,我去附近砍点木头——要我停就喊\"收工\"。");
            return;
        }
        if (hasPick) {
            frend.startTask(new com.frend.entity.task.MineTask(frend,
                            com.frend.entity.task.MineTask.Kind.STONE),
                    "我去附近凿点石头备着,喊\"收工\"我就停。");
            return;
        }
        if (!c.autonomyRequireTool) {
            frend.startTask(new com.frend.entity.task.ChopTreeTask(frend),
                    "没工具就徒手锤点木头吧……喊\"收工\"我就停。");
            return;
        }

        // 4) 没工具:提一嘴就闭嘴(决策冷却兜底,不会连环抱怨)
        frend.sayDelayed("闲着没事干……给我把斧头或镐子(放我背包里),我就能自己找活干。");
    }

    // ===================== 环境闲话 =====================

    private void envChatter(FrendConfig c) {
        PlayerEntity owner = frend.getOwnerPlayer();
        if (owner == null || frend.squaredDistanceTo(owner) > c.chatRadius * c.chatRadius) return;

        long worldTime = frend.getWorld().getTime();
        long day = worldTime / 24000L;
        long tod = frend.getWorld().getTimeOfDay() % 24000L;

        // 日出(一天一次)
        if (tod < 400 && lastDawnDay != day) {
            lastDawnDay = day;
            frend.sayDelayed("天亮了,又是新的一天。");
            return;
        }
        // 日落(一天一次)
        if (tod >= 12800 && tod <= 13300 && lastDuskDay != day) {
            lastDuskDay = day;
            frend.sayDelayed("天要黑了,怪该出来了……我盯着你身后。");
            return;
        }
        // 开始下雨(状态沿触发)
        boolean raining = frend.getWorld().isRaining(); // 【待编译验证】World#isRaining
        if (raining && !wasRaining) {
            frend.sayDelayed("下雨了……要不找个地方避避?");
        }
        wasRaining = raining;
    }

    // ===================== 工具 =====================

    /** 背包非空格占比(0~1)。 */
    private double inventoryFullness() {
        var inv = frend.getInventory();
        int used = 0;
        for (int i = 0; i < inv.size(); i++) {
            if (!inv.getStack(i).isEmpty()) used++;
        }
        return used / (double) inv.size();
    }

    /** v0.16 夜里(约 13000~23000 tick,刷怪时段)。 */
    private boolean isNight() {
        long tod = frend.getWorld().getTimeOfDay() % 24000L;
        return tod >= 13000 && tod <= 23000;
    }

    /** 主人在聊天半径内(说话给人听,不对空气讲)。 */
    private boolean ownerNearby(FrendConfig c) {
        PlayerEntity owner = frend.getOwnerPlayer();
        return owner != null && frend.squaredDistanceTo(owner) <= c.chatRadius * c.chatRadius;
    }
}
