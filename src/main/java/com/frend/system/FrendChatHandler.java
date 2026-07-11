package com.frend.system;

import com.frend.Frend;
import com.frend.FrendConfig;
import com.frend.entity.FrendEntity;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 聊天处理器,双后端:
 *
 * <p><b>规则层(默认,完全离线)</b>:关键词 contains 匹配 → 切模式 / 模板回话。
 * 没匹配上、也没在跟 frend 说话,就保持沉默,不刷屏。
 *
 * <p><b>LLM 层(可选,config.chatBackend = "openai")</b>:闲聊交给 OpenAI 兼容接口
 * (本地 Ollama / LM Studio / 云端 OpenAI 同协议)。铁律:
 * <ul>
 *   <li>指令关键词(跟我来/停下/过来/回家/报告)<b>永远走规则</b>,模型输出永远不会被解析成游戏操作;</li>
 *   <li>请求全异步 + 节流,失败/超时自动退回规则模板,绝不卡主线程、绝不刷屏。</li>
 * </ul>
 *
 * <p>"像人"的两个小机关:frend 说完话后 conversationWindowSeconds 秒内,主人说话
 * 不用喊名字也算在跟它聊(对话延续);回话统一走 sayDelayed 随机延迟(不秒回)。
 */
public final class FrendChatHandler {
    private FrendChatHandler() {}

    private static final Random RANDOM = new Random();

    // ===== 指令关键词(永远走规则;顺序敏感:FOLLOW 要在 COME 前,存箱子要在回家前) =====
    private static final String[] KEY_FOLLOW = {"跟我来", "跟着我", "跟上", "跟我走", "follow"};
    private static final String[] KEY_STAY   = {"停下", "待在这", "待着", "别动", "原地", "stay", "stop"};
    private static final String[] KEY_COME   = {"过来", "来我这", "come"};
    private static final String[] KEY_HOME   = {"回家", "go home"};
    private static final String[] KEY_STATUS = {"报告", "状态", "怎么样", "status"};
    private static final String[] KEY_NAME   = {"frend", "朋友"};
    private static final String[] KEY_CHOP    = {"砍树", "砍点木头", "砍木头", "chop"};
    private static final String[] KEY_STONE   = {"挖石头", "挖点石头", "挖石"};
    private static final String[] KEY_ORE     = {"挖矿", "挖煤", "挖铁", "mine"};
    private static final String[] KEY_DEPOSIT = {"存箱子", "回家存", "存东西", "去存", "deposit"};
    private static final String[] KEY_WORKSTOP = {"收工", "别干了", "别挖了", "别砍了", "休息吧"};

    // ===== v0.3 战斗关键词 =====
    private static final String[] KEY_COMBAT_ON  = {"保护我", "打怪", "战斗模式", "去打", "帮我打", "冲啊", "攻击"};
    private static final String[] KEY_COMBAT_OFF = {"别打了", "住手", "不用打", "停止攻击", "收剑"};

    // ===== v0.4 记忆关键词 =====
    private static final String[] KEY_MEMORY = {"还记得", "记得吗", "认识多久", "多少天", "战绩", "杀了多少", "干了多少", "回忆"};

    // ===== v0.5 自主行动开关关键词 =====
    private static final String[] KEY_AUTO_ON  = {"自由活动", "自己找事", "自己安排", "别闲着", "看着办"};
    private static final String[] KEY_AUTO_OFF = {"别自作主张", "听我指挥", "等我命令", "别瞎忙"};

    // ===== v0.10 朋友关键词:起名 / 让它记事 / 问它记了什么 =====
    private static final String[] KEY_RENAME = {"你以后叫", "以后你叫", "你就叫", "给你起名", "给你取名", "你的名字是"};
    /** 长的在前:先匹配带标点的,兜底裸"记住"。 */
    private static final String[] KEY_NOTE = {"记住:", "记住：", "记住,", "记住，", "帮我记住", "记住"};
    private static final String[] KEY_NOTES_RECALL = {"你记得什么", "记住什么", "记住啥", "记着什么", "帮我记了什么", "备忘"};

    // ===== 闲聊关键词(规则模式的回话;LLM 模式下作为失败兜底) =====
    private static final String[] KEY_GREET  = {"你好", "在吗", "嗨", "hello", "hi"};
    private static final String[] KEY_THANKS = {"谢谢", "辛苦", "thank"};
    private static final String[] KEY_PRAISE = {"真棒", "厉害", "好样的", "干得好", "nice", "good job"};
    private static final String[] KEY_BYE    = {"再见", "拜拜", "晚安", "我下了", "bye"};

    // ===== 模板池 =====
    private static final String[] R_FOLLOW = {"好嘞,跟紧你!", "走!我殿后。", "来了来了,别走太快。"};
    private static final String[] R_STAY   = {"行,我在这儿等你。", "收到,原地待命。", "好,那我看看风景。"};
    private static final String[] R_COME   = {"马上到!", "来啦来啦!", "等我一下,这就过去。"};
    private static final String[] R_HOME_OK = {"好,我先回家守着,路上小心。", "收到,回家喽。"};
    private static final String[] R_HOME_NONE = {"咱还没定过家呢……用 /frend home set 在你想安家的地方定一个?"};
    private static final String[] R_HOME_FAR = {"家不在这个维度,我自己走不过去,你带我过去吧。"};
    private static final String[] R_GREET  = {"我在呢!", "嘿,叫我干嘛?", "在在在,怎么了?", "咋啦,想我了?"};
    private static final String[] R_THANKS = {"跟我还客气什么。", "小事一桩!", "嘿嘿,应该的。"};
    private static final String[] R_PRAISE = {"嘿嘿,过奖过奖。", "那必须的!", "被你夸得都不好意思了。"};
    private static final String[] R_BYE    = {"好,回见!", "晚安,我守着家。", "拜拜,路上当心。"};
    private static final String[] R_FALLBACK = {
            "这个我还听不太懂……现在我会:跟我来 / 停下 / 过来 / 回家 / 报告状态。",
            "唔,没太明白。要不换个说法?",
            "嗯……你再说明白点?我脑子还比较简单。"
    };

    public static void register() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            try {
                handle(sender, message.getSignedContent());
            } catch (Exception e) {
                Frend.LOGGER.error("[frend] 聊天处理失败", e);
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

        boolean llmBackend = "openai".equalsIgnoreCase(FrendConfig.get().chatBackend);

        for (FrendEntity frend : frends) {
            // 1) 指令永远走规则(红线:模型不控游戏)
            if (handleCommand(frend, sender, text, raw)) continue;

            // 2) 闲聊:关键词模板 / LLM
            String smallTalk = smallTalkOrNull(text);
            // v0.10 起过名字的话,喊名字也算在叫它(hasCustomName 避免拿默认实体名误判)
            boolean calledByName = frend.hasCustomName()
                    && text.contains(frend.getDisplayName().getString().toLowerCase());
            boolean addressed = matches(text, KEY_NAME) || calledByName || frend.inConversationWindow();

            if (llmBackend && (addressed || smallTalk != null)) {
                frend.rememberChat("user", raw);
                llmChat(frend, sender, raw, smallTalk);
            } else if (smallTalk != null) {
                frend.rememberChat("user", raw);
                frend.sayDelayed(smallTalk);
            } else if (addressed) {
                frend.rememberChat("user", raw);
                frend.sayDelayed(pick(R_FALLBACK));
            }
            // 都没沾边 → 沉默,不刷屏
        }
    }

    /** 指令关键词。命中返回 true(已处理)。 */
    private static boolean handleCommand(FrendEntity frend, ServerPlayerEntity sender, String text, String raw) {
        // v0.10 起名/记事必须最先判:名字和笔记内容里可能夹带工作关键词
        // ("给你起名砍树侠"/"记住:明天去挖矿"),放后面会被干活关键词截胡。内容从 raw 取,保留大小写。
        if (matches(text, KEY_NOTES_RECALL)) { // 问"记住什么"要抢在裸"记住"解析之前
            frend.sayDelayed(frend.getMemory().notesLine());
            return true;
        }
        String newName = extractAfter(raw, KEY_RENAME);
        if (newName != null) {
            frend.renameBy(stripTail(newName));
            return true;
        }
        String note = extractAfter(raw, KEY_NOTE);
        if (note != null) {
            note = stripLead(stripTail(note));
            if (note.isEmpty() || note.length() > 60) {
                frend.sayDelayed("要记什么?说\"记住:xxx\",60 个字以内。");
            } else {
                frend.getMemory().addNote(note);
                frend.sayDelayed("记下了,忘不了。");
            }
            return true;
        }
        // 干活类先判:"回家存箱子"含"回家",存箱子必须抢在 HOME 前
        if (matches(text, KEY_DEPOSIT)) {
            frend.startTask(new com.frend.entity.task.DepositTask(frend), "好,我回家把东西存箱子里。");
        } else if (matches(text, KEY_CHOP)) {
            frend.startTask(new com.frend.entity.task.ChopTreeTask(frend), "收到,砍树去!");
        } else if (matches(text, KEY_STONE)) {
            frend.startTask(new com.frend.entity.task.MineTask(frend, com.frend.entity.task.MineTask.Kind.STONE), "好,我去凿点石头。");
        } else if (matches(text, KEY_ORE)) {
            frend.startTask(new com.frend.entity.task.MineTask(frend, com.frend.entity.task.MineTask.Kind.ORE), "找煤铁去,有露头的都归咱。");
        } else if (matches(text, KEY_WORKSTOP) && frend.isWorking()) {
            frend.stopTask("收工!");
        } else if (matches(text, KEY_FOLLOW)) {
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
        } else if (matches(text, KEY_COMBAT_ON)) {
            FrendConfig.get().combatEnabled = true;
            frend.sayDelayed("收到!遇到怪我来解决。");
        } else if (matches(text, KEY_COMBAT_OFF)) {
            FrendConfig.get().combatEnabled = false;
            frend.sayDelayed("好,我不动手了,你说打才打。");
        } else if (matches(text, KEY_AUTO_ON)) {
            FrendConfig.get().autonomyEnabled = true;
            frend.sayDelayed("好嘞,那我自己看着办——该砍砍、该存存,你随时喊\"收工\"。");
        } else if (matches(text, KEY_AUTO_OFF)) {
            FrendConfig.get().autonomyEnabled = false;
            frend.sayDelayed("收到,没你的话我不乱动。");
        } else if (matches(text, KEY_MEMORY)) {
            frend.sayDelayed(frend.getMemory().recapLine(frend.getWorld().getTime()));
        } else if (matches(text, KEY_STATUS)) {
            frend.sayDelayed(statusLine(frend));
        } else {
            return false;
        }
        return true;
    }

    /** 闲聊关键词命中则给一句模板,否则 null。 */
    private static String smallTalkOrNull(String text) {
        if (matches(text, KEY_THANKS)) return pick(R_THANKS);
        if (matches(text, KEY_PRAISE)) return pick(R_PRAISE);
        if (matches(text, KEY_BYE)) return pick(R_BYE);
        if (matches(text, KEY_GREET)) return pick(R_GREET);
        return null;
    }

    // ===================== LLM 闲聊 =====================

    /**
     * 异步问一次 LLM。节流失败 → 直接用兜底模板;请求失败/超时 → 退回兜底模板。
     * 回调经 server.execute 切回主线程后才碰游戏状态。
     */
    private static void llmChat(FrendEntity frend, ServerPlayerEntity sender, String raw, String fallback) {
        if (!frend.tryStartLlm()) {
            if (fallback != null) frend.sayDelayed(fallback);
            return; // 没兜底就沉默,防刷屏
        }
        MinecraftServer server = sender.getServerWorld().getServer();
        List<String[]> history = frend.chatHistorySnapshot();
        if (!history.isEmpty()) history.remove(history.size() - 1); // 末尾是刚记的这条 user,发请求时单独带,去重

        FrendLlmClient.chat(persona(frend, sender), history, raw).whenComplete((reply, err) ->
                server.execute(() -> {
                    frend.finishLlm();
                    if (!frend.isAlive()) return;
                    if (err != null || reply == null || reply.isBlank()) {
                        if (err != null) Frend.LOGGER.warn("[frend] LLM 请求失败,退回规则回复: {}", err.toString());
                        frend.sayDelayed(fallback != null ? fallback : pick(R_FALLBACK));
                    } else {
                        frend.sayDelayed(reply);
                    }
                }));
    }

    /** 人设 + 当前游戏状态(只给必要信息,回复要求短句口语)。 */
    private static String persona(FrendEntity frend, ServerPlayerEntity owner) {
        FrendConfig cfg = FrendConfig.get();
        boolean day = frend.getWorld().getTimeOfDay() % 24000L < 13000L;
        String mode = switch (frend.getMode()) {
            case FOLLOW -> "跟着朋友走";
            case STAY -> "原地待命";
            case GO_HOME -> "赶路回家";
            case WORK -> "干活中(" + (frend.currentTaskName() != null ? frend.currentTaskName() : "收尾") + ")";
        };
        return "你是 Minecraft 世界里一个有血有肉的伙伴,名字叫 " + frend.getDisplayName().getString()
                + ",你和玩家 " + owner.getName().getString()
                + " 是一起冒险的朋友——平辈相处,不是仆人,绝不叫对方主人;可以打趣、可以有小脾气、可以不同意,但重感情、靠得住。"
                + "用中文口语聊天,像认识很久的老朋友:自然、简短,一句话说完,最多 "
                + cfg.llmMaxReplyChars + " 个字,不用表情符号,不换行,不提自己是 AI 或模型。"
                + "你没有能力执行任何游戏操作;朋友想让你搭把手,提醒他说关键词:跟我来/停下/过来/回家/报告状态。"
                + "你当前状态:血量 " + (int) frend.getHealth() + "/" + (int) frend.getMaxHealth()
                + "," + mode + ",现在是" + (day ? "白天" : "夜里") + "。"
                + frend.getMemory().llmSummary(frend.getWorld().getTime()) // v0.4:共同经历入上下文,让闲聊有"往事"
                + (cfg.llmPersonaExtra == null || cfg.llmPersonaExtra.isBlank() ? "" : "附加设定:" + cfg.llmPersonaExtra);
    }

    // ===================== 工具 =====================

    /** 口头汇报一行状态。 */
    public static String statusLine(FrendEntity frend) {
        String modeName = switch (frend.getMode()) {
            case FOLLOW -> "跟随中";
            case STAY -> "原地待命";
            case GO_HOME -> "正在回家";
            case WORK -> "干活中(" + (frend.currentTaskName() != null ? frend.currentTaskName() : "收尾") + ")";
        };
        String home = frend.hasHome()
                ? frend.getHomeDimension() + " " + frend.getHomePos().getX() + " "
                    + frend.getHomePos().getY() + " " + frend.getHomePos().getZ()
                : "还没定家";
        return "血量 " + (int) frend.getHealth() + "/" + (int) frend.getMaxHealth()
                + ",当前" + modeName + ",家:" + home + "。" + frend.getMemory().statusBrief();
    }

    private static boolean matches(String text, String[] keys) {
        for (String k : keys) {
            if (text.contains(k)) return true;
        }
        return false;
    }

    /** v0.10 找到第一个命中的前缀关键词,返回其后的内容;都没命中返回 null。按数组顺序,长关键词放前面。 */
    private static String extractAfter(String raw, String[] keys) {
        for (String k : keys) {
            int i = raw.indexOf(k);
            if (i >= 0) return raw.substring(i + k.length());
        }
        return null;
    }

    /** 去掉口语尾巴:吧/了/哦/呀/标点/引号/空白。 */
    private static String stripTail(String s) {
        String t = s.trim();
        while (!t.isEmpty()) {
            char c = t.charAt(t.length() - 1);
            if ("吧了哦呀啊。.!！?？~\"”“'‘’「」『』,，".indexOf(c) >= 0 || Character.isWhitespace(c)) {
                t = t.substring(0, t.length() - 1);
            } else break;
        }
        return t.trim();
    }

    /** 去掉开头残留的冒号/逗号/空白(裸"记住"匹配时内容前常带标点)。 */
    private static String stripLead(String s) {
        String t = s.trim();
        while (!t.isEmpty()) {
            char c = t.charAt(0);
            if (":：,，、\"“'‘".indexOf(c) >= 0 || Character.isWhitespace(c)) {
                t = t.substring(1);
            } else break;
        }
        return t.trim();
    }

    private static String pick(String[] pool) {
        return pool[RANDOM.nextInt(pool.length)];
    }
}
