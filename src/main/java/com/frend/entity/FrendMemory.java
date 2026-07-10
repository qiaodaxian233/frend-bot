package com.frend.entity;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * v0.4 长期记忆:frend 和主人共同经历的持久档案。
 *
 * <p>三层内容:
 * <ol>
 *   <li><b>计数器</b>:相识时间 / 击杀数 / 救主次数 / 砍树挖矿方块数——数字型战绩;</li>
 *   <li><b>大事记</b>:最近 {@value #MAX_EVENTS} 条"值得一提"的事件(带游戏天数戳),
 *       击杀里程碑、救主、大额收工都会记一笔;</li>
 *   <li><b>输出</b>:{@link #recapLine} 给规则聊天/指令用的口头回忆,
 *       {@link #llmSummary} 给 LLM 人设注入共同经历(纯文本上下文,红线不变:模型不控游戏)。</li>
 * </ol>
 *
 * <p>随实体 NBT 持久化({@code FrendMemory} 子标签),frend 死了记忆一起消失——
 * 这是刻意设计:它不是存档数据库,是"这一个伙伴"的一生。
 */
public class FrendMemory {

    private static final int MAX_EVENTS = 12;

    /** 相识时刻(world time tick;0 = 还没初始化)。 */
    private long firstMetTime = 0L;

    private int kills = 0;
    private int rescues = 0;          // 救主:主人被打,frend 干掉了攻击者
    private int blocksChopped = 0;
    private int blocksMined = 0;

    /** 最近击杀的怪物名(口头回忆用)。 */
    private String lastKillName = "";

    /** 大事记(旧的挤掉)。 */
    private final Deque<String> events = new ArrayDeque<>();

    // ===================== 记录 =====================

    public void initFirstMet(long worldTime) {
        if (firstMetTime == 0L) firstMetTime = Math.max(1L, worldTime);
    }

    /** 一起冒险的游戏天数(从 1 起算)。 */
    public long daysTogether(long worldTime) {
        if (firstMetTime == 0L) return 1;
        return Math.max(1, (worldTime - firstMetTime) / 24000L + 1);
    }

    /**
     * 记一次击杀。命中里程碑(1/10/50/100/500)则返回一句感慨,否则 null。
     * 调用方拿到非 null 就让 frend 说出来——里程碑一辈子只触发一次,不刷屏。
     */
    public String recordKill(String mobName, long worldTime) {
        kills++;
        lastKillName = mobName == null ? "" : mobName;
        return switch (kills) {
            case 1   -> record(worldTime, "第一次干掉了" + lastKillName)
                        .thenSay("这是我帮你打倒的第一只怪,记下了!");
            case 10  -> record(worldTime, "击杀满 10").thenSay("不知不觉都打倒 10 只怪了,我越来越熟练了。");
            case 50  -> record(worldTime, "击杀满 50").thenSay("50 只怪!咱俩也算身经百战了。");
            case 100 -> record(worldTime, "击杀满 100").thenSay("一百只!主人,给我立个碑吧,哈哈。");
            case 500 -> record(worldTime, "击杀满 500").thenSay("五百了……这些年跟你走南闯北,值了。");
            default  -> null;
        };
    }

    /** 记一次救主(击杀了正在攻击主人的怪)。返回一句感慨给 frend 说。 */
    public String recordRescue(String mobName, long worldTime) {
        rescues++;
        record(worldTime, "从" + (mobName == null ? "怪物" : mobName) + "手里救下主人");
        if (rescues == 1) return "刚才好险……以后有我在,不会让它们碰到你。";
        return null; // 后续救主由战斗喊话覆盖,不额外煽情
    }

    public void addChopped(int n) { blocksChopped += n; }
    public void addMined(int n)   { blocksMined += n; }

    /** 手动记一条大事(如大额收工),带游戏天数戳。 */
    public FrendMemory record(long worldTime, String what) {
        events.addLast("第" + daysTogether(worldTime) + "天:" + what);
        while (events.size() > MAX_EVENTS) events.removeFirst();
        return this;
    }

    /** 链式小语法糖:record(...).thenSay("...") 让 recordKill 返回感慨。 */
    private String thenSay(String line) { return line; }

    // ===================== 输出 =====================

    /** 口头回忆一行(规则聊天 / /frend memory 用)。 */
    public String recapLine(long worldTime) {
        StringBuilder sb = new StringBuilder();
        sb.append("咱俩一起冒险第 ").append(daysTogether(worldTime)).append(" 天了。");
        if (kills > 0) {
            sb.append("我帮你干掉了 ").append(kills).append(" 只怪");
            if (rescues > 0) sb.append(",危急关头救过你 ").append(rescues).append(" 次");
            sb.append(";");
        }
        if (blocksChopped + blocksMined > 0) {
            sb.append("砍了 ").append(blocksChopped).append(" 块木头、挖了 ")
              .append(blocksMined).append(" 块矿石;");
        }
        if (kills == 0 && blocksChopped + blocksMined == 0) {
            sb.append("虽然还没干出什么大事,但好日子在后头。");
        } else if (!events.isEmpty()) {
            sb.append("最难忘的是").append(events.peekLast()).append("。");
        }
        return sb.toString();
    }

    /** 给 LLM 人设注入的共同经历摘要(短,省 token)。 */
    public String llmSummary(long worldTime) {
        StringBuilder sb = new StringBuilder();
        sb.append("你们相识 ").append(daysTogether(worldTime)).append(" 个游戏日,你累计击杀 ")
          .append(kills).append(" 只怪、救主 ").append(rescues).append(" 次、砍木 ")
          .append(blocksChopped).append(" 块、挖矿 ").append(blocksMined).append(" 块。");
        if (!events.isEmpty()) {
            sb.append("近期大事:");
            int i = 0;
            for (var it = events.descendingIterator(); it.hasNext() && i < 3; i++) {
                sb.append(it.next()).append(";");
            }
        }
        return sb.toString();
    }

    /** 状态汇报里的战绩短句(空战绩返回空串)。 */
    public String statusBrief() {
        if (kills == 0 && rescues == 0) return "";
        return "战绩:击杀 " + kills + (rescues > 0 ? "、救主 " + rescues : "") + "。";
    }

    // ===================== NBT =====================

    public NbtCompound toNbt() {
        NbtCompound tag = new NbtCompound();
        tag.putLong("FirstMet", firstMetTime);
        tag.putInt("Kills", kills);
        tag.putInt("Rescues", rescues);
        tag.putInt("Chopped", blocksChopped);
        tag.putInt("Mined", blocksMined);
        tag.putString("LastKill", lastKillName);
        NbtList list = new NbtList();
        for (String e : events) list.add(NbtString.of(e));
        tag.put("Events", list);
        return tag;
    }

    public void fromNbt(NbtCompound tag) {
        firstMetTime = tag.getLong("FirstMet");
        kills = tag.getInt("Kills");
        rescues = tag.getInt("Rescues");
        blocksChopped = tag.getInt("Chopped");
        blocksMined = tag.getInt("Mined");
        lastKillName = tag.getString("LastKill");
        events.clear();
        NbtList list = tag.getList("Events", NbtElement.STRING_TYPE); // 【待编译验证】NbtElement.STRING_TYPE 常量
        for (int i = 0; i < list.size(); i++) events.addLast(list.getString(i));
    }
}
