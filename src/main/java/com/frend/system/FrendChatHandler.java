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
    // v0.13:必须比 KEY_ORE 先匹配("挖矿道"含"挖矿","挖钻石"不含但归下矿)
    private static final String[] KEY_TUNNEL  = {"挖隧道", "打隧道", "挖矿道", "打矿道", "掘进", "tunnel"};
    private static final String[] KEY_DEEP    = {"挖深矿", "下矿", "挖钻石", "去挖钻", "deep mine"};
    // v0.16 合成
    private static final String[] KEY_CRAFT   = {"做工具", "造工具", "打工具", "合成工具", "craft"};
    private static final String[] KEY_TORCHC  = {"做火把", "搓火把", "合成火把"};
    // v0.20 过日子全家桶
    private static final String[] KEY_FARM  = {"种田", "收庄稼", "收麦子", "收菜", "farm"};
    private static final String[] KEY_FISH  = {"钓鱼", "去钓鱼", "fish"};
    private static final String[] KEY_SMELT = {"烧铁", "炼铁", "开炉", "烧一炉", "smelt"};
    // "不用看家"含"看家",OFF 必须先匹配
    private static final String[] KEY_GUARD_OFF = {"不用看家", "别看家", "不用守"};
    private static final String[] KEY_GUARD_ON  = {"看家", "守着家", "守家", "看好家", "guard"};
    private static final String[] KEY_DEPOSIT = {"存箱子", "回家存", "存东西", "去存", "deposit"};
    private static final String[] KEY_WORKSTOP = {"收工", "别干了", "别挖了", "别砍了", "休息吧"};

    // ===== v0.3 战斗关键词 =====
    private static final String[] KEY_COMBAT_ON  = {"保护我", "打怪", "战斗模式", "去打", "帮我打", "冲啊", "攻击"};
    private static final String[] KEY_COMBAT_OFF = {"别打了", "住手", "不用打", "停止攻击", "收剑"};

    // ===== v0.4 记忆关键词 =====
    private static final String[] KEY_MEMORY = {"还记得", "记得吗", "认识多久", "多少天", "战绩", "杀了多少", "干了多少", "回忆"};
    // v0.19 知识库
    private static final String[] KEY_KNOW = {"见过什么", "都知道什么", "见识", "去过哪", "学到什么"};

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

            // v0.18 学话:没被当成请求的短句才学(纯本地词频,3 次成诵;学会那刻得意一下)
            if (FrendConfig.get().phraseLearning) {
                String learnedNow = frend.getMemory().observePhrase(raw.trim());
                if (learnedNow != null) frend.sayDelayed("「" + learnedNow + "」——嘿嘿,这话我跟你学的。");
            }

            // 2) 闲聊:关键词模板 / LLM
            String smallTalk = smallTalkOrNull(text);
            // v0.10 起过名字的话,喊名字也算在叫它(hasCustomName 避免拿默认实体名误判)
            boolean calledByName = frend.hasCustomName()
                    && text.contains(frend.getDisplayName().getString().toLowerCase());
            boolean addressed = matches(text, KEY_NAME) || calledByName || frend.inConversationWindow();

            if (llmBackend && (addressed || smallTalk != null)) {
                frend.rememberChat("user", raw);
                // v0.17 意图解析开着 → 走"听懂人话"链路;关着 → 纯聊天(老行为)
                if (FrendConfig.get().llmIntentEnabled) llmIntentChat(frend, sender, raw, smallTalk);
                else llmChat(frend, sender, raw, smallTalk);
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
        } else if (matches(text, KEY_TUNNEL)) {
            frend.startTask(new com.frend.entity.task.TunnelTask(frend, com.frend.entity.task.TunnelTask.Kind.TUNNEL),
                    "好,朝我脸冲的方向掘进,见矿顺手掏!");
        } else if (matches(text, KEY_DEEP)) {
            frend.startTask(new com.frend.entity.task.TunnelTask(frend, com.frend.entity.task.TunnelTask.Kind.DEEP),
                    "下矿喽!挖楼梯下到矿层再直着掏,跟我后面别掉坑里。");
        } else if (matches(text, KEY_CRAFT)) {
            frend.startTask(new com.frend.entity.task.CraftTask(frend, com.frend.entity.task.CraftTask.Goal.TOOLS),
                    "好,我鼓捣两件家伙——叮叮当当别嫌吵。");
        } else if (matches(text, KEY_TORCHC)) {
            frend.startTask(new com.frend.entity.task.CraftTask(frend, com.frend.entity.task.CraftTask.Goal.TORCHES),
                    "搓火把喽,有煤就快。");
        } else if (matches(text, KEY_FARM)) {
            frend.startTask(new com.frend.entity.task.FarmTask(frend), "好,收庄稼去——熟的收,青的留,种子补回去。");
        } else if (matches(text, KEY_FISH)) {
            frend.startTask(new com.frend.entity.task.FishTask(frend), "钓鱼喽……你也来?坐着发会儿呆挺好。");
        } else if (matches(text, KEY_SMELT)) {
            frend.startTask(new com.frend.entity.task.SmeltTask(frend), "开炉!有啥烧啥。");
        } else if (matches(text, KEY_GUARD_OFF)) {
            FrendConfig.get().guardWhenStay = false;
            frend.sayDelayed("行,那我站岗只看不动手——真有怪打我我还是要还手的。");
        } else if (matches(text, KEY_GUARD_ON)) {
            frend.setMode(FrendEntity.Mode.STAY);
            FrendConfig.get().guardWhenStay = true;
            frend.sayDelayed("放心去吧,家有我盯着——摸进来的怪一只都别想走。");
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
        } else if (matches(text, KEY_KNOW)) {
            frend.sayDelayed(frend.getKnowledge().summaryLine());
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

    /**
     * v0.17 意图解析版 LLM 调用(还 v0.4 设计文档的账:"LLM 只产出意图,执行走白名单")。
     * <b>红线</b>:模型输出只被当成两样东西——一个白名单里的意图词 + 一句嘴上的话;
     * 白名单外的意图一律视为 none(纯聊天);JSON 解析不出来就把整段当聊天文本。
     * 模型永远没有直接触碰游戏状态的通道,执行统一走 executeIntent(和关键词同一套调用)。
     */
    private static void llmIntentChat(FrendEntity frend, ServerPlayerEntity sender, String raw, String fallback) {
        if (!frend.tryStartLlm()) {
            if (fallback != null) frend.sayDelayed(fallback);
            return;
        }
        MinecraftServer server = sender.getServerWorld().getServer();
        List<String[]> history = frend.chatHistorySnapshot();
        if (!history.isEmpty()) history.remove(history.size() - 1);

        FrendLlmClient.chat(persona(frend, sender, true), history, raw).whenComplete((reply, err) ->
                server.execute(() -> {
                    frend.finishLlm();
                    if (!frend.isAlive()) return;
                    if (err != null || reply == null || reply.isBlank()) {
                        if (err != null) Frend.LOGGER.warn("[frend] LLM 意图请求失败,退回规则回复: {}", err.toString());
                        frend.sayDelayed(fallback != null ? fallback : pick(R_FALLBACK));
                        return;
                    }
                    String[] parsed = parseIntentJson(reply);
                    if (parsed == null) { // 没按格式来?那就当它在聊天,原文照说(清洗后)
                        frend.sayDelayed(FrendLlmClient.sanitize(reply));
                        return;
                    }
                    String say = parsed[1];
                    int cap = FrendConfig.get().llmMaxReplyChars;
                    if (say.length() > cap) say = say.substring(0, cap);
                    boolean executed = executeIntent(frend, sender, parsed[0], say);
                    if (!executed && !say.isBlank()) {
                        frend.sayDelayed(say); // none/没听懂的意图 = 纯聊天
                    }
                }));
    }

    /** 从模型回复里抠出 {"intent":..,"say":..};抠不出返回 null。 */
    private static String[] parseIntentJson(String reply) {
        try {
            String t = reply.trim();
            int a = t.indexOf('{');
            int b = t.lastIndexOf('}');
            if (a < 0 || b <= a) return null;
            com.google.gson.JsonObject o = com.google.gson.JsonParser
                    .parseString(t.substring(a, b + 1)).getAsJsonObject();
            String intent = o.has("intent") ? o.get("intent").getAsString().trim().toLowerCase(Locale.ROOT) : "none";
            String say = o.has("say") ? o.get("say").getAsString() : "";
            return new String[]{intent, say};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * v0.17 白名单意图执行器——和 handleCommand 的关键词分支<b>同一套调用</b>,一行不多。
     * say 是模型嘴上那句话,执行成功时代替罐头台词说出来(有性格);
     * status/memory 例外:回真实数据,不让模型编。返回 false = 意图不在白名单/none,调用方按聊天处理。
     */
    private static boolean executeIntent(FrendEntity frend, ServerPlayerEntity sender, String intent, String say) {
        switch (intent) {
            case "follow" -> { frend.setMode(FrendEntity.Mode.FOLLOW); sayOr(frend, say, pick(R_FOLLOW)); }
            case "stay" -> { frend.setMode(FrendEntity.Mode.STAY); sayOr(frend, say, pick(R_STAY)); }
            case "come" -> {
                frend.setMode(FrendEntity.Mode.FOLLOW);
                frend.getNavigation().startMovingTo(sender, FrendConfig.get().followSpeed);
                sayOr(frend, say, pick(R_COME));
            }
            case "home" -> {
                if (!frend.hasHome()) frend.sayDelayed(pick(R_HOME_NONE));
                else if (!frend.isHomeInThisDimension()) frend.sayDelayed(pick(R_HOME_FAR));
                else { frend.setMode(FrendEntity.Mode.GO_HOME); sayOr(frend, say, pick(R_HOME_OK)); }
            }
            case "deposit" -> frend.startTask(new com.frend.entity.task.DepositTask(frend), orDefault(say, "好,我回家把东西存箱子里。"));
            case "chop" -> frend.startTask(new com.frend.entity.task.ChopTreeTask(frend), orDefault(say, "收到,砍树去!"));
            case "stone" -> frend.startTask(new com.frend.entity.task.MineTask(frend, com.frend.entity.task.MineTask.Kind.STONE), orDefault(say, "好,我去凿点石头。"));
            case "ore" -> frend.startTask(new com.frend.entity.task.MineTask(frend, com.frend.entity.task.MineTask.Kind.ORE), orDefault(say, "找煤铁去!"));
            case "tunnel" -> frend.startTask(new com.frend.entity.task.TunnelTask(frend, com.frend.entity.task.TunnelTask.Kind.TUNNEL), orDefault(say, "好,朝前掘进!"));
            case "deep" -> frend.startTask(new com.frend.entity.task.TunnelTask(frend, com.frend.entity.task.TunnelTask.Kind.DEEP), orDefault(say, "下矿喽!"));
            case "craft" -> frend.startTask(new com.frend.entity.task.CraftTask(frend, com.frend.entity.task.CraftTask.Goal.TOOLS), orDefault(say, "我鼓捣两件家伙。"));
            case "torch" -> frend.startTask(new com.frend.entity.task.CraftTask(frend, com.frend.entity.task.CraftTask.Goal.TORCHES), orDefault(say, "搓火把喽。"));
            case "farm" -> frend.startTask(new com.frend.entity.task.FarmTask(frend), orDefault(say, "收庄稼去,熟的收青的留。"));
            case "fish" -> frend.startTask(new com.frend.entity.task.FishTask(frend), orDefault(say, "钓鱼喽,一起发会儿呆?"));
            case "smelt" -> frend.startTask(new com.frend.entity.task.SmeltTask(frend), orDefault(say, "开炉!有啥烧啥。"));
            case "guard_on" -> {
                frend.setMode(FrendEntity.Mode.STAY);
                FrendConfig.get().guardWhenStay = true;
                sayOr(frend, say, "家有我盯着,放心去。");
            }
            case "guard_off" -> { FrendConfig.get().guardWhenStay = false; sayOr(frend, say, "行,站岗只看不动手。"); }
            case "stop" -> {
                if (frend.isWorking()) frend.stopTask(orDefault(say, "收工!"));
                else sayOr(frend, say, "我本来也没在忙,咋啦?");
            }
            case "combat_on" -> { FrendConfig.get().combatEnabled = true; sayOr(frend, say, "收到!遇到怪我来解决。"); }
            case "combat_off" -> { FrendConfig.get().combatEnabled = false; sayOr(frend, say, "好,我不动手了。"); }
            case "auto_on" -> { FrendConfig.get().autonomyEnabled = true; sayOr(frend, say, "好嘞,我自己看着办。"); }
            case "auto_off" -> { FrendConfig.get().autonomyEnabled = false; sayOr(frend, say, "收到,没你的话我不乱动。"); }
            case "status" -> frend.sayDelayed(statusLine(frend));                                    // 真实数据,不让模型编
            case "memory" -> frend.sayDelayed(frend.getMemory().recapLine(frend.getWorld().getTime())); // 同上
            default -> { return false; } // none 或白名单外 → 纯聊天
        }
        return true;
    }

    private static void sayOr(FrendEntity frend, String say, String fallback) {
        frend.sayDelayed(say == null || say.isBlank() ? fallback : say);
    }

    private static String orDefault(String say, String def) {
        return say == null || say.isBlank() ? def : say;
    }

    /** 人设 + 当前游戏状态(只给必要信息,回复要求短句口语)。 */
    private static String persona(FrendEntity frend, ServerPlayerEntity owner) {
        return persona(frend, owner, false);
    }

    /**
     * v0.17 双口径人设:intentMode=false 纯聊天(告知自己没有操作能力);
     * intentMode=true 意图解析(要求只输出一行 JSON,intent 从白名单选,选不出用 none)。
     */
    private static String persona(FrendEntity frend, ServerPlayerEntity owner, boolean intentMode) {
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
                + " 是一起冒险的朋友——平辈相处,不是仆人,绝不叫对方主人;对方从不命令你,只会请你帮忙,你乐意搭把手,偶尔打趣两句再动身;可以有小脾气、可以不同意,但重感情、靠得住。"
                + "用中文口语聊天,像认识很久的老朋友:自然、简短,一句话说完,最多 "
                + cfg.llmMaxReplyChars + " 个字,不用表情符号,不换行,不提自己是 AI 或模型。"
                + (intentMode
                    ? "这条消息如果是在请你做事,判断意图后只输出一行 JSON:{\"intent\":\"xxx\",\"say\":\"你顺口回的一句话\"}。"
                      + "intent 必须从这张清单里选,选不出就用 none(表示纯聊天):"
                      + "follow=跟着走/stay=原地等着/come=到我这来/home=回家/deposit=回家存箱子/"
                      + "chop=砍树/stone=挖石头/ore=找矿/tunnel=挖隧道/deep=下矿挖钻石/craft=做工具/torch=搓火把/"
                      + "farm=收庄稼种田/fish=钓鱼/smelt=开炉烧矿炼铁/guard_on=看家守家/guard_off=不用看家/"
                      + "stop=停下手头的活/combat_on=开打保护对方/combat_off=别打了/auto_on=自由活动/auto_off=听指挥/"
                      + "status=报告状态/memory=回忆往事/none=只是聊天。"
                      + "say 是你嘴上的回应,口语一句话。除了这一行 JSON 什么都别输出。"
                    : "你没有能力执行任何游戏操作;朋友想让你搭把手,提醒他说关键词:跟我来/停下/过来/回家/报告状态。")
                + "你当前状态:血量 " + (int) frend.getHealth() + "/" + (int) frend.getMaxHealth()
                + "," + mode + ",现在是" + (day ? "白天" : "夜里") + "。"
                + frend.getMemory().llmSummary(frend.getWorld().getTime()) // v0.4:共同经历入上下文,让闲聊有"往事"
                + frend.getKnowledge().llmBrief() // v0.19:见识入上下文,让闲聊有"阅历"
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
