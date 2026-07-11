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
    private int rescues = 0;          // 我救你:你被打,我干掉了攻击者
    private int blocksChopped = 0;
    private int blocksMined = 0;

    // ===== v0.10 朋友,不是仆人:情谊是双向的 =====
    /** 你救我:攻击我的怪被你干掉了。朋友之间,我记得我为你挡的刀,更记得你为我拔的剑。 */
    private int ownerSaves = 0;
    /** 你送我的装备次数(穿戴道谢时计)。 */
    private int gifts = 0;
    /** 你让我记住的事("记住:xxx",FIFO 上限 {@value #MAX_NOTES} 条)。 */
    private static final int MAX_NOTES = 8;
    private final Deque<String> notes = new ArrayDeque<>();

    /** v0.11 你倒下过的地方(编码 "维度|x|y|z",FIFO 上限 {@value #MAX_DEATH_SPOTS} 条)——朋友记得你在哪栽过跟头。 */
    private static final int MAX_DEATH_SPOTS = 3;
    private final Deque<String> deathSpots = new ArrayDeque<>();

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
            case 100 -> record(worldTime, "击杀满 100").thenSay("一百只!给我立个碑吧,哈哈。");
            case 500 -> record(worldTime, "击杀满 500").thenSay("五百了……这些年跟你走南闯北,值了。");
            default  -> null;
        };
    }

    /** 记一次我救你(击杀了正在攻击你的怪)。返回一句感慨给 frend 说。 */
    public String recordRescue(String mobName, long worldTime) {
        rescues++;
        record(worldTime, "从" + (mobName == null ? "怪物" : mobName) + "手里拉了你一把");
        if (rescues == 1) return "刚才好险……以后有我在,不会让它们碰到你。";
        return null; // 后续由战斗喊话覆盖,不额外煽情
    }

    /**
     * v0.10 记一次你救我(打我的怪被你干掉了)。返回该说的感谢,后续感谢频率由调用方冷却控制。
     */
    public String recordOwnerSave(String mobName, long worldTime) {
        ownerSaves++;
        record(worldTime, "你从" + (mobName == null ? "怪物" : mobName) + "手里救了我");
        if (ownerSaves == 1) return "你刚……救了我?这份情我记一辈子。";
        return "多谢!又欠你一回,咱俩这算扯平了又欠上。";
    }

    /** v0.10 你送我装备(穿戴道谢处计数,不额外说话)。 */
    public void recordGift() { gifts++; }

    /** v0.10 你让我记住的事。超上限挤掉最旧的。 */
    public void addNote(String note) {
        notes.addLast(note);
        while (notes.size() > MAX_NOTES) notes.removeFirst();
    }

    public boolean hasNotes() { return !notes.isEmpty(); }

    /** 复述你让我记的事(口头,一句话)。 */
    public String notesLine() {
        if (notes.isEmpty()) return "你还没让我记过什么事——说\"记住:xxx\"我就记下。";
        StringBuilder sb = new StringBuilder("你让我记着的事:");
        int i = 1;
        for (String n : notes) sb.append(i++ == 1 ? "" : ";").append(n);
        return sb.toString();
    }

    /** v0.11 记一次你的倒下(地点入册 + 大事记)。 */
    public void recordOwnerDeath(String dim, net.minecraft.util.math.BlockPos pos, long worldTime) {
        deathSpots.addLast(dim + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ());
        while (deathSpots.size() > MAX_DEATH_SPOTS) deathSpots.removeFirst();
        record(worldTime, "你没能撑住,倒在了 (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ") 附近");
    }

    /** v0.11 找 near 附近 radius 格内最近的"你倒下过的地方";同维度才算,没有返回 null。 */
    public net.minecraft.util.math.BlockPos nearestDeathSpot(String dim, net.minecraft.util.math.BlockPos near, int radius) {
        net.minecraft.util.math.BlockPos best = null;
        double bestSq = (double) radius * radius;
        for (String s : deathSpots) {
            String[] p = s.split("\\|");
            if (p.length != 4 || !p[0].equals(dim)) continue;
            try {
                net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(
                        Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                double d = near.getSquaredDistance(pos);
                if (d <= bestSq) { bestSq = d; best = pos; }
            } catch (NumberFormatException ignored) { }
        }
        return best;
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

    /** 口头回忆一行(规则聊天 / /frend memory 用)。朋友口吻:说我为你做的,也说你为我做的。 */
    public String recapLine(long worldTime) {
        StringBuilder sb = new StringBuilder();
        sb.append("咱俩一起冒险第 ").append(daysTogether(worldTime)).append(" 天了。");
        if (kills > 0) {
            sb.append("我帮你干掉了 ").append(kills).append(" 只怪");
            if (rescues > 0) sb.append(",危急关头拉过你 ").append(rescues).append(" 把");
            sb.append(";");
        }
        if (ownerSaves > 0) {
            sb.append("你也救过我 ").append(ownerSaves).append(" 次——这个我可没忘;");
        }
        if (blocksChopped + blocksMined > 0) {
            sb.append("砍了 ").append(blocksChopped).append(" 块木头、挖了 ")
              .append(blocksMined).append(" 块矿石;");
        }
        if (kills == 0 && ownerSaves == 0 && blocksChopped + blocksMined == 0) {
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
          .append(kills).append(" 只怪、危急关头救过对方 ").append(rescues).append(" 次、对方也救过你 ")
          .append(ownerSaves).append(" 次、砍木 ")
          .append(blocksChopped).append(" 块、挖矿 ").append(blocksMined).append(" 块。");
        if (!events.isEmpty()) {
            sb.append("近期大事:");
            int i = 0;
            for (var it = events.descendingIterator(); it.hasNext() && i < 3; i++) {
                sb.append(it.next()).append(";");
            }
        }
        if (!notes.isEmpty()) {
            sb.append("对方特意让你记住的事(聊天时可自然提起):");
            for (String n : notes) sb.append(n).append(";");
        }
        return sb.toString();
    }

    /** 状态汇报里的战绩短句(空战绩返回空串)。 */
    public String statusBrief() {
        if (kills == 0 && rescues == 0) return "";
        return "战绩:击杀 " + kills + (rescues > 0 ? "、救你 " + rescues + " 次" : "")
                + (ownerSaves > 0 ? ",你救我 " + ownerSaves + " 次" : "") + "。";
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
        tag.putInt("OwnerSaves", ownerSaves);
        tag.putInt("Gifts", gifts);
        NbtList list = new NbtList();
        for (String e : events) list.add(NbtString.of(e));
        tag.put("Events", list);
        NbtList noteList = new NbtList();
        for (String n : notes) noteList.add(NbtString.of(n));
        tag.put("Notes", noteList);
        NbtList spotList = new NbtList();
        for (String s : deathSpots) spotList.add(NbtString.of(s));
        tag.put("DeathSpots", spotList);
        return tag;
    }

    public void fromNbt(NbtCompound tag) {
        firstMetTime = tag.getLong("FirstMet");
        kills = tag.getInt("Kills");
        rescues = tag.getInt("Rescues");
        blocksChopped = tag.getInt("Chopped");
        blocksMined = tag.getInt("Mined");
        lastKillName = tag.getString("LastKill");
        ownerSaves = tag.getInt("OwnerSaves"); // 旧档无此键返回 0,无痛升级
        gifts = tag.getInt("Gifts");
        events.clear();
        NbtList list = tag.getList("Events", NbtElement.STRING_TYPE);
        for (int i = 0; i < list.size(); i++) events.addLast(list.getString(i));
        notes.clear();
        NbtList noteList = tag.getList("Notes", NbtElement.STRING_TYPE);
        for (int i = 0; i < noteList.size(); i++) notes.addLast(noteList.getString(i));
        deathSpots.clear();
        NbtList spotList = tag.getList("DeathSpots", NbtElement.STRING_TYPE);
        for (int i = 0; i < spotList.size(); i++) deathSpots.addLast(spotList.getString(i));
    }
}
