package com.frend.system;

import com.frend.FrendConfig;
import com.frend.entity.FrendEntity;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 规则层聊天(设计文档三层聊天的第一层,不需要任何模型,完全离线)。
 *
 * <p>监听玩家公屏聊天(fabric-message-api-v1 的 CHAT_MESSAGE,待编译验证):
 * 玩家在 chatRadius 内、且附近有自己的 frend 时,按关键词理解意图 →
 * 切模式 / 模板回话;没匹配上且没喊 frend 名字就保持沉默,不刷屏。
 *
 * <p>v0.4 在这一层之上接本地 LLM(Ollama):关键词没命中的话交给模型理解成意图 DSL,
 * 模型只产出意图、不直接碰游戏状态。
 */
public final class FrendChatHandler {
    private FrendChatHandler() {}

    private static final Random RANDOM = new Random();

    // ===== 关键词表(contains 匹配;英文统一转小写比较) =====
    private static final String[] KEY_FOLLOW = {"跟我来", "跟着我", "跟上", "跟我走", "follow"};
    private static final String[] KEY_STAY   = {"停下", "待在这", "待着", "别动", "原地", "stay", "stop"};
    private static final String[] KEY_COME   = {"过来", "来我这", "come"};
    private static final String[] KEY_HOME   = {"回家", "go home"};
    private static final String[] KEY_GREET  = {"你好", "在吗", "嗨", "hello", "hi"};
    private static final String[] KEY_THANKS = {"谢谢", "辛苦", "thank"};
    private static final String[] KEY_STATUS = {"报告", "状态", "怎么样", "status"};
    private static final String[] KEY_NAME   = {"frend", "朋友"};

    // ===== 模板池 =====
    private static final String[] R_FOLLOW = {"好嘞,跟紧你!", "走!我殿后。", "来了来了,别走太快。"};
    private static final String[] R_STAY   = {"行,我在这儿等你。", "收到,原地待命。", "好,那我看看风景。"};
    private static final String[] R_COME   = {"马上到!", "来啦来啦!", "等我一下,这就过去。"};
    private static final String[] R_HOME_OK = {"好,我先回家守着,路上小心。", "收到,回家喽。"};
    private static final String[] R_HOME_NONE = {"咱还没定过家呢……用 /frend home set 在你想安家的地方定一个?"};
    private static final String[] R_HOME_FAR = {"家不在这个维度,我自己走不过去,你带我过去吧。"};
    private static final String[] R_GREET  = {"我在呢!", "嘿,叫我干嘛?", "在在在,怎么了?"};
    private static final String[] R_THANKS = {"跟我还客气什么。", "小事一桩!", "嘿嘿,应该的。"};
    private static final String[] R_FALLBACK = {
            "这个我还听不太懂……现在我会:跟我来 / 停下 / 过来 / 回家 / 报告状态。",
            "等我接上本地大脑(v0.4)就能听懂这种话了,现在先用简单点的指令吧。"
    };

    public static void register() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            try {
                handle(sender, message.getSignedContent());
            } catch (Exception e) {
                com.frend.Frend.LOGGER.error("[frend] 聊天处理失败", e);
            }
        });
    }

    private static void handle(ServerPlayerEntity sender, String raw) {
        if (raw == null || raw.isBlank()) return;
        String text = raw.toLowerCase(Locale.ROOT);

        double r = FrendConfig.get().chatRadius;
        List<FrendEntity> frends = sender.getServerWorld().getEntitiesByClass(FrendEntity.class,
                sender.getBoundingBox().expand(r),
                f -> f.isAlive() && f.isOwner(sender));
        if (frends.isEmpty()) return;

        for (FrendEntity frend : frends) {
            if (matches(text, KEY_FOLLOW)) {
                frend.setMode(FrendEntity.Mode.FOLLOW);
                frend.sayDelayed(pick(R_FOLLOW));
            } else if (matches(text, KEY_STAY)) {
                frend.setMode(FrendEntity.Mode.STAY);
                frend.sayDelayed(pick(R_STAY));
            } else if (matches(text, KEY_COME)) {
                frend.setMode(FrendEntity.Mode.FOLLOW);
                frend.getNavigation().startMovingTo(sender, FrendConfig.get().followSpeed);
                frend.sayDelayed(pick(R_COME));
            } else if (matches(text, KEY_HOME)) {
                if (!frend.hasHome()) {
                    frend.sayDelayed(pick(R_HOME_NONE));
                } else if (!frend.isHomeInThisDimension()) {
                    frend.sayDelayed(pick(R_HOME_FAR));
                } else {
                    frend.setMode(FrendEntity.Mode.GO_HOME);
                    frend.sayDelayed(pick(R_HOME_OK));
                }
            } else if (matches(text, KEY_STATUS)) {
                frend.sayDelayed(statusLine(frend));
            } else if (matches(text, KEY_THANKS)) {
                frend.sayDelayed(pick(R_THANKS));
            } else if (matches(text, KEY_GREET)) {
                frend.sayDelayed(pick(R_GREET));
            } else if (matches(text, KEY_NAME)) {
                // 喊了名字但没听懂 → 兜底回话;什么都没喊到就保持沉默,不刷屏
                frend.sayDelayed(pick(R_FALLBACK));
            }
        }
    }

    /** 口头汇报一行状态。 */
    public static String statusLine(FrendEntity frend) {
        String modeName = switch (frend.getMode()) {
            case FOLLOW -> "跟随中";
            case STAY -> "原地待命";
            case GO_HOME -> "正在回家";
        };
        String home = frend.hasHome()
                ? frend.getHomeDimension() + " " + frend.getHomePos().getX() + " "
                    + frend.getHomePos().getY() + " " + frend.getHomePos().getZ()
                : "还没定家";
        return "血量 " + (int) frend.getHealth() + "/" + (int) frend.getMaxHealth()
                + ",当前" + modeName + ",家:" + home + "。";
    }

    private static boolean matches(String text, String[] keys) {
        for (String k : keys) {
            if (text.contains(k)) return true;
        }
        return false;
    }

    private static String pick(String[] pool) {
        return pool[RANDOM.nextInt(pool.length)];
    }
}
