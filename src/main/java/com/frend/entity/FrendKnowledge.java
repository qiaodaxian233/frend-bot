package com.frend.entity;

import com.frend.FrendConfig;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.util.math.random.Random;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * v0.19 知识库:frend 的"见识"——一直学习,越活越像人。随灵魂跨存档,终身累积。
 *
 * <p><b>三层架构</b>(设计蓝图,DEVLOG m20 有全分类账):
 * <ol>
 *   <li><b>感知</b>:游戏事件必经之路埋钩子(挖掘走 FrendTask#breakTick 一处全收;
 *       击杀走战斗收尾+箭杀;受伤走 damage;探索走 mobTick 生物群系轮询;首次大事件走挖掘钩子);</li>
 *   <li><b>沉淀</b>:计数知识(打过什么/挖过什么/去过哪)+ <b>教训</b>(被苦力怕炸的次数
 *       直接变成它对苦力怕的安全距离——知识改变行为,这才叫学习);</li>
 *   <li><b>表达</b>:闲聊谈见识 / 问"见过什么"能答 / LLM 人设注入 / 战斗行为调参。</li>
 * </ol>
 *
 * <p>红线:纯本地统计与规则,LLM 只在表达层收到摘要文本。所有 Map/Set 带上限,满了忘最旧的
 * ——人也记不住所有事,记得住的才叫见识。
 */
public class FrendKnowledge {

    private static final int CAP_KILLS = 48, CAP_MINED = 48, CAP_HURT = 32, CAP_BIOMES = 64;

    /** 打过的怪(显示名 → 数量)。 */
    private final LinkedHashMap<String, Integer> kills = new LinkedHashMap<>();
    /** 挖过的方块(id → 数量)。 */
    private final LinkedHashMap<String, Integer> mined = new LinkedHashMap<>();
    /** 被谁伤过(显示名 → 次数)。 */
    private final LinkedHashMap<String, Integer> hurtBy = new LinkedHashMap<>();
    /** 去过的生物群系(id)。 */
    private final LinkedHashSet<String> biomes = new LinkedHashSet<>();
    /** 已发过的一次性感慨(首见钻石之类,一生一次)。 */
    private final LinkedHashSet<String> firsts = new LinkedHashSet<>();

    /** 教训:被苦力怕炸的次数(直接换算安全距离)。 */
    private int creeperBlasts = 0;
    /** v0.21 过日子见识:钓上来多少条(垃圾也算——钓鱼佬从不空军)。 */
    private int fishCaught = 0;
    /** v0.21 过日子见识:收过多少茬庄稼。 */
    private int cropsHarvested = 0;
    /** 自己死过几回(灵魂记得每一世)。 */
    private int myDeaths = 0;
    /** v0.28 清账:干翻过几个抢村子的强盗(袭击者)。 */
    private int raidersKilled = 0;
    /** v0.28 清账:被雷劈过几回(大难不死的勋章)。 */
    private int lightningHits = 0;
    /** v0.28 清账:听过几回唱片。 */
    private int jukeboxHeard = 0;
    /** v0.28 清账:认得的你家宠物(UUID,带上限忘最旧)。 */
    private final LinkedHashSet<String> petIds = new LinkedHashSet<>();
    /** 认过的宠物总数(petIds 会忘,总账单独记)。 */
    private int petsKnown = 0;
    private static final int CAP_PETS = 16;

    private static boolean off() { return !FrendConfig.get().knowledgeEnabled; }

    // ===================== 感知入口 =====================

    public void recordKill(String mobName) {
        if (off() || mobName == null) return;
        bump(kills, mobName, CAP_KILLS);
    }

    /** 挖掘知识 + 首见大事的一次性感慨(有话要说就返回,否则 null)。 */
    public String recordMined(String blockId) {
        if (off() || blockId == null) return null;
        bump(mined, blockId, CAP_MINED);
        if (blockId.contains("diamond_ore") && firsts.add("diamond")) {
            return "钻石!!头一回亲手挖到……这块的位置我记一辈子!";
        }
        if (blockId.contains("ancient_debris") && firsts.add("debris")) {
            return "远古残骸?!书上说的都是真的……咱们发达了。";
        }
        if (blockId.contains("emerald_ore") && firsts.add("emerald")) {
            return "绿宝石!第一次见真的,比听说的还好看。";
        }
        return null;
    }

    /** 受伤知识;苦力怕爆炸单独记教训。 */
    public void recordHurtBy(String mobName, boolean creeperBlast) {
        if (off()) return;
        if (mobName != null) bump(hurtBy, mobName, CAP_HURT);
        if (creeperBlast) creeperBlasts++;
    }

    /** 探索知识:头一回到这种地方返回一句感慨(带几个常见群系的中文味),否则 null。 */
    public String recordBiome(String biomeId) {
        if (off() || biomeId == null || !biomes.add(biomeId)) return null;
        while (biomes.size() > CAP_BIOMES) biomes.remove(biomes.iterator().next());
        String cn = biomeCn(biomeId);
        return cn != null
                ? "这就是" + cn + "?头一回来,记下了——又长一分见识。"
                : "这地方我头一回来……记下了,又长一分见识。";
    }

    public void recordMyDeath() { if (!off()) myDeaths++; }

    /** v0.21 钓鱼见识(销 v0.19 挂账)。 */
    public void recordFish() { if (!off()) fishCaught++; }

    /** v0.21 种田见识(销 v0.19 挂账)。 */
    public void recordHarvest() { if (!off()) cropsHarvested++; }

    // ===================== v0.28 清账五类 =====================

    /** Boss 击杀(type=wither/dragon):一生一次的高光时刻,有话说就返回。 */
    public String recordBossKilled(String type) {
        if (off()) return null;
        if ("wither".equals(type) && firsts.add("wither_kill")) {
            return "凋灵……我们把凋灵打倒了!这事我要记一辈子——不是谁都有这种朋友的。";
        }
        if ("dragon".equals(type) && firsts.add("dragon_kill")) {
            return "末影龙倒下的那一刻……我这辈子值了。跟你混,真好。";
        }
        return null;
    }

    /** Boss 目击(第一次见着活的,吓归吓,话得说硬气):一生一次。 */
    public String recordBossSeen(String type) {
        if (off()) return null;
        if ("wither".equals(type) && firsts.add("wither_seen")) {
            return "那、那就是凋灵?!……你要真打,我陪你。说好了,谁也不许先倒下。";
        }
        if ("dragon".equals(type) && firsts.add("dragon_seen")) {
            return "末影龙……书上画的都没它一半吓人。深呼吸——我在你旁边。";
        }
        return null;
    }

    /** 干翻袭击者:头一个有话说,第 10 个有里程碑。 */
    public String recordRaiderKill() {
        if (off()) return null;
        raidersKilled++;
        if (firsts.add("raider")) return "抢村子的强盗……这种怪我最看不惯。放马过来!";
        if (raidersKilled == 10) return "第 10 个强盗了——村里人该给咱立块碑。";
        return null;
    }

    /** 被雷劈了(damage 源判定):活下来就是勋章,一生一次的感慨。 */
    public String recordLightningHit() {
        if (off()) return null;
        lightningHits++;
        if (firsts.add("lightning")) {
            return "……我被雷劈了?!我还活着?哈、哈哈,我还活着!这事你可得帮我记着——我可是被雷劈过的人。";
        }
        return null;
    }

    /** 头一回听见打雷(不是被劈,是听见动静)。 */
    public String recordThunder() {
        if (off() || !firsts.add("thunder")) return null;
        return "打雷了……你听这动静。别站树底下,靠我近点。";
    }

    /** 认识你家新宠物(uuid 去重);认识了就返回一句欢迎。 */
    public String recordNewPet(String uuid, String petName) {
        if (off() || uuid == null || !petIds.add(uuid)) return null;
        while (petIds.size() > CAP_PETS) petIds.remove(petIds.iterator().next());
        petsKnown++;
        if (firsts.add("pet")) {
            return "咦,你带了个小家伙?" + petName + "是吧——以后咱们一起,多个伴。";
        }
        return "又多了个新伙伴?" + petName + ",记住了。你身边越来越热闹了。";
    }

    /** 听见唱片:头一回有感慨,之后只记账。 */
    public String recordJukebox() {
        if (off()) return null;
        jukeboxHeard++;
        if (firsts.add("jukebox")) return "这就是唱片的声音?……真好听。干完活能再放一遍吗?";
        return null;
    }

    // ===================== 知识改变行为 =====================

    /** 教训换算:被苦力怕炸得越多,离它越远(封顶 +3 格)。战斗 Goal 直接用。 */
    public double creeperFear() {
        return Math.min(3, creeperBlasts);
    }

    // 取数口(自动测试与调试面板用)
    public int getRaidersKilled() { return raidersKilled; }
    public int getLightningHits() { return lightningHits; }
    public int getPetsKnown() { return petsKnown; }
    public int getJukeboxHeard() { return jukeboxHeard; }

    // ===================== 表达 =====================

    /** 问"你都见过什么/见识"时的口头总结。 */
    public String summaryLine() {
        if (biomes.isEmpty() && kills.isEmpty() && mined.isEmpty()) {
            return "我还是张白纸呢……带我多走走,见识都是攒出来的。";
        }
        StringBuilder sb = new StringBuilder("说说我的见识:");
        if (!biomes.isEmpty()) sb.append("跟你走过 ").append(biomes.size()).append(" 种地方;");
        var topK = top(kills);
        if (topK != null) sb.append("打得最多的是").append(topK.getKey()).append("(").append(topK.getValue()).append(" 只);");
        var topM = top(mined);
        if (topM != null) sb.append("挖得最多的是 ").append(shortId(topM.getKey())).append("(").append(topM.getValue()).append(" 块);");
        if (creeperBlasts > 0) sb.append("被苦力怕炸过 ").append(creeperBlasts).append(" 回——现在见它我都躲着走;");
        if (raidersKilled > 0) sb.append("跟抢村子的强盗干过 ").append(raidersKilled).append(" 架;");
        if (firsts.contains("dragon_kill")) sb.append("末影龙是咱们一起打倒的;");
        else if (firsts.contains("wither_kill")) sb.append("咱们连凋灵都放倒过;");
        if (petsKnown > 0) sb.append("认得你家 ").append(petsKnown).append(" 只小家伙;");
        if (lightningHits > 0) sb.append("还被雷劈过 ").append(lightningHits).append(" 回——大难不死;");
        if (cropsHarvested >= 10) sb.append("收过 ").append(cropsHarvested).append(" 茬庄稼;");
        if (fishCaught >= 5) sb.append("钓上来 ").append(fishCaught).append(" 条(手感练出来了);");
        if (myDeaths > 0) sb.append("死过 ").append(myDeaths).append(" 回,魂还在,不怕。");
        return sb.toString();
    }

    /** 闲聊偶尔来一句见识(随机挑一个话头);没啥可说返回 null。 */
    public String randomInsight(Random random) {
        int pick = random.nextInt(9);
        var topK = top(kills);
        if (pick == 0 && topK != null && topK.getValue() >= 10) {
            return "你知道吗,咱俩到现在打了 " + topK.getValue() + " 只" + topK.getKey() + "了……都快打出感情了。";
        }
        if (pick == 1 && biomes.size() >= 3) {
            return "算下来我跟你见过 " + biomes.size() + " 种地方了。下回想去哪?我都行。";
        }
        var topM = top(mined);
        if (pick == 2 && topM != null && topM.getValue() >= 50) {
            return shortId(topM.getKey()) + "我都挖了 " + topM.getValue() + " 块了,闭着眼都认得。";
        }
        if (pick == 3 && creeperBlasts >= 2) {
            return "跟你说,苦力怕那玩意儿——炸过我 " + creeperBlasts + " 回,现在我隔老远就绕。";
        }
        if (pick == 4 && fishCaught >= 8) {
            return "我都钓上来 " + fishCaught + " 条了……哪天咱俩比比,看谁先空军。";
        }
        if (pick == 5 && cropsHarvested >= 20) {
            return "地里的活我熟——" + cropsHarvested + " 茬庄稼是我收的,哪根熟哪根青我一眼就看出来。";
        }
        if (pick == 6 && raidersKilled >= 3) {
            return "那帮抢村子的强盗——遇上咱俩算他们倒霉,都干翻 " + raidersKilled + " 个了。";
        }
        if (pick == 7 && lightningHits > 0) {
            return "跟你说个事:我可是被雷劈过还活蹦乱跳的——一般人没这待遇。";
        }
        if (pick == 8 && firsts.contains("dragon_kill")) {
            return "有时候想想还跟做梦似的……咱们真把末影龙打倒了。";
        }
        return null;
    }

    /** 给 LLM 人设的知识摘要(短)。 */
    public String llmBrief() {
        if (biomes.isEmpty() && kills.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("你的见识:去过 ").append(biomes.size()).append(" 种生物群系");
        var topK = top(kills);
        if (topK != null) sb.append(",打得最多的怪是").append(topK.getKey()).append("(").append(topK.getValue()).append(")");
        if (creeperBlasts > 0) sb.append(",被苦力怕炸过 ").append(creeperBlasts).append(" 次(你很怕它)");
        if (firsts.contains("dragon_kill")) sb.append(",你们一起打倒过末影龙(共同的高光时刻,值得骄傲)");
        else if (firsts.contains("wither_kill")) sb.append(",你们一起打倒过凋灵(共同的高光时刻)");
        if (lightningHits > 0) sb.append(",你被雷劈过还活着(你引以为豪)");
        sb.append("。");
        return sb.toString();
    }

    // ===================== NBT =====================

    public NbtCompound toNbt() {
        NbtCompound tag = new NbtCompound();
        tag.put("Kills", mapToNbt(kills));
        tag.put("Mined", mapToNbt(mined));
        tag.put("HurtBy", mapToNbt(hurtBy));
        NbtList b = new NbtList();
        for (String s : biomes) b.add(NbtString.of(s));
        tag.put("Biomes", b);
        NbtList f = new NbtList();
        for (String s : firsts) f.add(NbtString.of(s));
        tag.put("Firsts", f);
        tag.putInt("CreeperBlasts", creeperBlasts);
        tag.putInt("MyDeaths", myDeaths);
        tag.putInt("FishCaught", fishCaught);
        tag.putInt("CropsHarvested", cropsHarvested);
        tag.putInt("RaidersKilled", raidersKilled);
        tag.putInt("LightningHits", lightningHits);
        tag.putInt("JukeboxHeard", jukeboxHeard);
        tag.putInt("PetsKnown", petsKnown);
        NbtList p = new NbtList();
        for (String s : petIds) p.add(NbtString.of(s));
        tag.put("PetIds", p);
        return tag;
    }

    public void fromNbt(NbtCompound tag) {
        nbtToMap(tag.getCompound("Kills"), kills);
        nbtToMap(tag.getCompound("Mined"), mined);
        nbtToMap(tag.getCompound("HurtBy"), hurtBy);
        biomes.clear();
        NbtList b = tag.getList("Biomes", NbtElement.STRING_TYPE);
        for (int i = 0; i < b.size(); i++) biomes.add(b.getString(i));
        firsts.clear();
        NbtList f = tag.getList("Firsts", NbtElement.STRING_TYPE);
        for (int i = 0; i < f.size(); i++) firsts.add(f.getString(i));
        creeperBlasts = tag.getInt("CreeperBlasts");
        myDeaths = tag.getInt("MyDeaths");
        fishCaught = tag.getInt("FishCaught");
        cropsHarvested = tag.getInt("CropsHarvested");
        raidersKilled = tag.getInt("RaidersKilled");
        lightningHits = tag.getInt("LightningHits");
        jukeboxHeard = tag.getInt("JukeboxHeard");
        petsKnown = tag.getInt("PetsKnown");
        petIds.clear();
        NbtList p = tag.getList("PetIds", NbtElement.STRING_TYPE);
        for (int i = 0; i < p.size(); i++) petIds.add(p.getString(i));
    }

    // ===================== 小工具 =====================

    private static void bump(LinkedHashMap<String, Integer> map, String key, int cap) {
        map.merge(key, 1, Integer::sum);
        while (map.size() > cap) map.remove(map.keySet().iterator().next()); // 忘最旧的
    }

    private static Map.Entry<String, Integer> top(LinkedHashMap<String, Integer> map) {
        Map.Entry<String, Integer> best = null;
        for (var e : map.entrySet()) if (best == null || e.getValue() > best.getValue()) best = e;
        return best;
    }

    /** "minecraft:deepslate_coal_ore" → "deepslate_coal_ore"(口头够用)。 */
    private static String shortId(String id) {
        int i = id.indexOf(':');
        return i >= 0 ? id.substring(i + 1) : id;
    }

    /** 常见群系的中文味(不全,兜底走通用句)。 */
    private static String biomeCn(String id) {
        if (id.contains("desert")) return "沙漠";
        if (id.contains("badlands")) return "恶地";
        if (id.contains("jungle")) return "丛林";
        if (id.contains("mushroom")) return "蘑菇岛";
        if (id.contains("cherry")) return "樱花林";
        if (id.contains("deep_dark")) return "深暗之域";
        if (id.contains("swamp")) return "沼泽";
        if (id.contains("snowy") || id.contains("frozen") || id.contains("ice")) return "冰天雪地";
        if (id.contains("ocean")) return "大海";
        if (id.contains("nether_wastes")) return "下界荒地";
        if (id.contains("basalt")) return "玄武岩三角洲";
        if (id.contains("soul_sand")) return "灵魂沙峡谷";
        if (id.contains("the_end") || id.contains("end_")) return "末地";
        return null;
    }

    private static NbtCompound mapToNbt(LinkedHashMap<String, Integer> map) {
        NbtCompound t = new NbtCompound();
        for (var e : map.entrySet()) t.putInt(e.getKey(), e.getValue());
        return t;
    }

    private static void nbtToMap(NbtCompound t, LinkedHashMap<String, Integer> map) {
        map.clear();
        for (String k : t.getKeys()) map.put(k, t.getInt(k));
    }
}
