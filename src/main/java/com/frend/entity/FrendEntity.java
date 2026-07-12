package com.frend.entity;

import com.frend.FrendConfig;
import com.frend.system.FrendScheduler;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.UUID;

/**
 * frend 本体:类玩家陪伴 NPC。
 *
 * <p>v0.1 能力:
 * <ul>
 *   <li>三种模式:FOLLOW 跟随 / STAY 停留 / GO_HOME 回家(到家自动转 STAY)</li>
 *   <li>主人绑定(UUID,NBT 持久化);家坐标(含维度,NBT 持久化)</li>
 *   <li>27 格背包:主人右键打开(原版 9×3 箱子界面,零自定义 screen);死亡/解散全掉落</li>
 *   <li>"像人"细节:低血提醒主人、偶尔闲聊、被动缓慢回血、死前留遗言,说话统一走 {@link #say}
 *       并按聊天半径广播,不刷屏(全部带冷却)</li>
 * </ul>
 *
 * <p>v0.2 新增:干活任务(砍树/挖石/挖煤铁/回家存箱子,见 entity/task/)、干活时捡拾掉落物、
 * 工具耐久判断(留口气不用报废)、自动进食(食物饱食度换算回血)。
 *
 * <p>刻意不做的:不打怪(v0.3)、平时不捡物(只在干活时捡,不抢主人东西)、不自然生成(只能 /frend summon)。
 * 模式只存服务端 + NBT,客户端渲染不依赖它,所以不需要 DataTracker。任务对象不落盘,重载后 WORK 退回 STAY。
 */
public class FrendEntity extends PathAwareEntity {

    /** 行为模式。WORK = 正在执行任务(任务本身不落盘,重载后退回 STAY)。 */
    public enum Mode { FOLLOW, STAY, GO_HOME, WORK }

    private Mode mode = Mode.FOLLOW;
    private UUID ownerUuid = null;
    /** v0.27 分魂:灵魂档里的槽位号(1 起;0=老档未分魂,读写时视为 1)。随实体落盘,死亡换档不丢身份。 */
    private int soulId = 0;

    /** 当前任务(仅 WORK 模式;不落盘)。 */
    private com.frend.entity.task.FrendTask currentTask = null;
    /** v0.27 收工后余韵拾取(tick):任务刚结束时最后一块的掉落物还没落稳,多捡 3 秒。 */
    private int postTaskPickupTicks = 0;
    private int eatCooldown = 0;

    /** 27 格随身背包。 */
    private final SimpleInventory inventory = new SimpleInventory(27);

    /** v0.15 遗物袋:替你捡回的死亡掉落,独立于自己背包(存箱子不会把你的遗物存走),见面全数奉还。 */
    private final SimpleInventory salvage = new SimpleInventory(45);

    /** 家(可空);维度记 Identifier 字符串,如 minecraft:overworld。 */
    private BlockPos homePos = null;
    private String homeDimension = null;

    // ===== 说话冷却(tick,只在服务端 mobTick 递减) =====
    private int lowHealthWarnCooldown = 0;
    private int ambientCooldown = 20 * 60; // 出生一分钟内不闲聊
    private int hurtTalkCooldown = 0;

    // ===== v0.6 插火把冷却:放置间隔 + 说话独立冷却(插火把常有,念叨不能常有) =====
    private int torchCooldown = 0;
    private int torchTalkCooldown = 0;

    /** v0.7 穿装备道谢的独立冷却。 */
    private int equipTalkCooldown = 0;

    // ===== v0.9 下界适应 =====
    /** 跨维度追随的宽限计数:主人换维度后连续 N 次检查(每次 2s)都不在才追——防止主人进传送门马上回来时 frend 白跑。 */
    private int ownerAwayChecks = 0;
    /** 上次所在维度(Identifier 字符串),换维度说风味话用;随 NBT 走,传送复制实体后不丢。 */
    private String lastDimension = null;
    private int dimensionTalkCooldown = 0;
    private int fireTalkCooldown = 0;
    /** v0.10 你救我道谢的独立冷却(记忆永远记账,嘴上不刷屏)。 */
    private int saveThanksCooldown = 0;
    /** v0.11 "你上回在这栽过"提醒的冷却。 */
    private int deathSpotWarnCooldown = 0;

    // ===== v0.12 卡死自救 =====
    /** 上次卡死检查时的位置(每 2s 对比一次)。 */
    private net.minecraft.util.math.Vec3d lastStuckCheckPos = net.minecraft.util.math.Vec3d.ZERO;
    /** 连续"在导航却没挪窝"的次数:1 跳一下 → 2 停表让 Goal 重规划+说一句 → 3 归零重来。 */
    private int stuckCount = 0;
    private int stuckTalkCooldown = 0;

    // ===== 聊天记忆(不落盘):LLM 上下文 + "对话延续窗口" + 请求节流 =====
    private final java.util.ArrayDeque<String[]> chatHistory = new java.util.ArrayDeque<>();
    private long lastTalkMillis = 0;          // frend 上次开口时间
    private long lastLlmMillis = 0;           // 上次发起 LLM 请求时间
    private volatile boolean llmBusy = false; // 有请求在飞就不再发

    private static final String[] AMBIENT_LINES = {
            "这边风景不错,值得盖个观景台。",
            "你说……末影人整天搬方块图什么呢?",
            "等我学会挖矿了,钻石都归你,我就要一把铁镐。",
            "走这么久了,记得吃东西啊。",
            "我记得咱家的方向,迷路了就喊我带路。",
            "晚上怪多,咱俩别走散了。"
    };

    private static final String[] HURT_LINES = {
            "嘶——疼疼疼!",
            "谁打我?!",
            "没事,小伤,继续走。"
    };

    /** v0.3 战斗:持引用以便 onDamaged 通知支援。 */
    private FrendCombatGoal combatGoal;

    /** v0.4 长期记忆:相识天数/击杀/救主/干活量/大事记,随 NBT 持久化。 */
    private final FrendMemory memory = new FrendMemory();

    /** v0.19 知识库:见识与教训,随灵魂终身学习。 */
    private final FrendKnowledge knowledge = new FrendKnowledge();

    /** v0.5 自主行动:待命时自己找活、包满自己去存、环境闲话。 */
    private final FrendAutonomy autonomy = new FrendAutonomy(this);

    public FrendEntity(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world);
        this.setPersistent(); // 不因玩家远离而消失
        this.setCanPickUpLoot(false);
        // ===== v0.6 寻路避险:岩浆/火焰视为禁区,寻路永远绕开(生存本能第一层) =====
        // 【待编译验证】PathNodeType 枚举名(Yarn 1.21.1:LAVA/DANGER_FIRE/DAMAGE_FIRE)
        this.setPathfindingPenalty(net.minecraft.entity.ai.pathing.PathNodeType.LAVA, -1.0f);
        this.setPathfindingPenalty(net.minecraft.entity.ai.pathing.PathNodeType.DAMAGE_FIRE, -1.0f);
        this.setPathfindingPenalty(net.minecraft.entity.ai.pathing.PathNodeType.DANGER_FIRE, 16.0f);
        // ===== v0.12 路径规划:像人一样走路 =====
        // 水不再是障碍(默认惩罚 8 会绕着河走;0 = 该游就游,SwimGoal 保证浮起来不淹死)
        this.setPathfindingPenalty(net.minecraft.entity.ai.pathing.PathNodeType.WATER, 0.0f);
        this.getNavigation().setCanSwim(true);
        // 关着的木门算路(村民同款);真正伸手开门靠 initGoals 里的开门 Goal
        if (this.getNavigation() instanceof net.minecraft.entity.ai.pathing.MobNavigation nav) {
            nav.setCanPathThroughDoors(true);
        }
        this.getNavigation().getNodeMaker().setCanOpenDoors(true);
        // ===== v0.7 装备必掉:身上穿的拿的都是主人给的,死了一件不昧(>1 = 必掉且不折耐久) =====
        // 【待编译验证】MobEntity#setEquipmentDropChance;只设六个经典槽位,不碰 1.20.5+ 新增的 BODY
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            this.setEquipmentDropChance(slot, 2.0f);
        }
    }

    /** 属性:数值走配置(配置在实体注册之前加载)。 */
    public static DefaultAttributeContainer.Builder createFrendAttributes() {
        FrendConfig c = FrendConfig.get();
        return PathAwareEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, c.frendMaxHealth)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, c.frendMoveSpeed)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, c.frendAttackDamage)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 48.0);
    }

    @Override
    protected void initGoals() {
        combatGoal = new FrendCombatGoal(this);
        FrendRetreatGoal retreatGoal = new FrendRetreatGoal(this, combatGoal);
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(1, retreatGoal);          // 撤退优先于战斗
        this.goalSelector.add(2, combatGoal);           // 战斗优先于跟随
        this.goalSelector.add(3, new FrendFollowOwnerGoal(this));
        this.goalSelector.add(4, new FrendGoHomeGoal(this));
        // v0.12 开门关门:路过木门自己开,走过去随手带上(第二参 true = 延迟关门)。
        // 【待编译验证】Yarn 类名 LongDoorInteractGoal(卫道士突袭时用的就是它);报错找 DoorInteractGoal 子类/OpenDoorGoal
        if (FrendConfig.get().openDoors) {
            this.goalSelector.add(5, new net.minecraft.entity.ai.goal.LongDoorInteractGoal(this, true));
        }
        this.goalSelector.add(6, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
        this.goalSelector.add(7, new LookAroundGoal(this));
    }

    // ===================== 主人 / 模式 / 家 =====================

    public void setOwner(PlayerEntity player) {
        this.ownerUuid = player.getUuid();
        memory.initFirstMet(this.getWorld().getTime()); // 相识时刻只记第一次
        // v0.18 灵魂:有档就接——只灌进"白纸"(新召的),不覆盖已经活过的
        if (FrendConfig.get().soulEnabled && memory.isFresh()) {
            net.minecraft.nbt.NbtCompound soul = com.frend.system.FrendSoul.loadSlot(player.getUuid(), soulId); // v0.27 按槽
            if (soul != null && soul.contains("Memory")) {
                memory.fromNbt(soul.getCompound("Memory"));
                memory.rebaseTo(this.getWorld().getTime(), soul.getLong("DaysSnapshot"));
                if (soul.contains("Knowledge")) knowledge.fromNbt(soul.getCompound("Knowledge")); // v0.19 见识随魂走
                if (soul.contains("Name")) {
                    this.setCustomName(Text.literal(soul.getString("Name")));
                    this.setCustomNameVisible(true);
                }
                sayDelayed("……是你!哈,真的是你。换了个天地也没关系——咱们的事,我一件都没忘。");
            }
        }
    }

    public FrendMemory getMemory() { return memory; }

    public FrendKnowledge getKnowledge() { return knowledge; }

    public UUID getOwnerUuid() { return ownerUuid; }

    public int getSoulId() { return soulId; }

    public void setSoulId(int id) { this.soulId = id; }

    public boolean isOwner(PlayerEntity player) {
        return ownerUuid != null && ownerUuid.equals(player.getUuid());
    }

    /** 主人(仅同维度、在线时非空)。 */
    public PlayerEntity getOwnerPlayer() {
        if (ownerUuid == null) return null;
        return this.getWorld().getPlayerByUuid(ownerUuid);
    }

    /**
     * v0.9 全服找主人(不限维度)。getOwnerPlayer 走 world.getPlayerByUuid,
     * 主人进下界后本维度查不到会返回 null——跨维度追随需要这个。只在服务端有意义。
     */
    public net.minecraft.server.network.ServerPlayerEntity getOwnerPlayerAnywhere() {
        if (ownerUuid == null || this.getWorld().isClient) return null;
        net.minecraft.server.MinecraftServer server = this.getServer();
        if (server == null) return null;
        return server.getPlayerManager().getPlayer(ownerUuid);
    }

    public Mode getMode() { return mode; }

    public void setMode(Mode mode) {
        this.mode = mode;
        if (mode == Mode.STAY) this.guardAnchor = this.getBlockPos().toImmutable(); // v0.20 记岗位
        this.getNavigation().stop();
    }

    /** v0.20 看家锚点:进入 STAY 时的站位。看家清怪绕它扫、打完回它站岗。
     *  不落盘:重载后没锚就 lazy 用当前脚下——反正 STAY 的它就站在岗上。 */
    private BlockPos guardAnchor = null;

    public BlockPos getGuardAnchor() {
        return guardAnchor != null ? guardAnchor : this.getBlockPos();
    }

    public BlockPos getHomePos() { return homePos; }

    public String getHomeDimension() { return homeDimension; }

    public boolean hasHome() { return homePos != null && homeDimension != null; }

    /** 家是否在当前所处维度。 */
    public boolean isHomeInThisDimension() {
        return hasHome() && this.getWorld().getRegistryKey().getValue().toString().equals(homeDimension);
    }

    public void setHome(BlockPos pos, String dimension) {
        this.homePos = pos.toImmutable();
        this.homeDimension = dimension;
    }

    public SimpleInventory getInventory() { return inventory; }

    /** v0.15 收进遗物袋,返回装不下的余量。 */
    public ItemStack addSalvage(ItemStack stack) { return salvage.addStack(stack); }

    public boolean hasSalvage() {
        for (int i = 0; i < salvage.size(); i++) if (!salvage.getStack(i).isEmpty()) return true;
        return false;
    }

    /** v0.15 遗物全数奉还:直接塞进你的背包,塞不下的落你脚边。 */
    public void giveSalvageBack(PlayerEntity owner) {
        int given = 0;
        for (int i = 0; i < salvage.size(); i++) {
            ItemStack s = salvage.getStack(i);
            if (s.isEmpty()) continue;
            ItemStack copy = s.copy();
            if (!owner.getInventory().insertStack(copy)) {
                if (!copy.isEmpty()) owner.dropStack(copy); // 你包满了,剩的放你脚边
            }
            salvage.setStack(i, ItemStack.EMPTY);
            given++;
        }
        if (given > 0) {
            sayDelayed("都在这儿——一样没少,点点?");
        }
    }

    // ===================== 任务(v0.2 干活) =====================

    /** 开始一个任务(自动切 WORK 模式,顶掉旧任务)。 */
    /** v0.26 测试用:当前有没有在跑任务(GameTest 断言"够不着要认账收工"靠它)。 */
    public boolean hasActiveTask() { return currentTask != null; }

    public void startTask(com.frend.entity.task.FrendTask task, String announce) {
        if (currentTask != null) currentTask.onStop();
        currentTask = task;
        // v0.27 协作搭话:旁边有伙伴在干同一种活,搭一句(纯风味;真正的分工靠 FrendCrew 认领制)
        if (FrendConfig.get().crewChatter && this.random.nextFloat() < 0.5f
                && com.frend.system.FrendCrew.crewmateNearbyDoing(this, task.name(), 12.0)) {
            sayDelayed("分头干——那片归你,这片归我!");
        }
        setMode(Mode.WORK);
        if (announce != null) sayDelayed(announce);
    }

    /** 收工(announce 可空 = 不说话)。 */
    public void stopTask(String announce) {
        if (currentTask != null) {
            currentTask.onStop();
            currentTask = null;
            com.frend.system.FrendCrew.releaseAll(this); // v0.27 认领统一清账
            postTaskPickupTicks = 60; // v0.27 收工余韵拾取
        }
        if (mode == Mode.WORK) setMode(Mode.STAY);
        if (announce != null) sayDelayed(announce);
    }

    public boolean isWorking() { return currentTask != null; }

    public String currentTaskName() { return currentTask != null ? currentTask.name() : null; }

    /** 找一把还能用的工具(耐久留量走配置);没有返回 EMPTY。 */
    public ItemStack findUsableTool(net.minecraft.registry.tag.TagKey<net.minecraft.item.Item> tag) {
        int reserve = FrendConfig.get().toolReserveDurability;
        // v0.27 修真虫(自动测试首捕):自动换装会把镐/斧当武器装进主手(对调出背包),
        // 只扫背包会当着一手好镐说"没镐子"——工具攥在手里,当然算能用。
        ItemStack hand = this.getMainHandStack();
        if (!hand.isEmpty() && hand.isIn(tag)
                && !(hand.isDamageable() && hand.getMaxDamage() - hand.getDamage() <= reserve)) {
            return hand;
        }
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.getStack(i);
            if (s.isEmpty() || !s.isIn(tag)) continue;
            if (s.isDamageable() && s.getMaxDamage() - s.getDamage() <= reserve) continue; // 留口气
            return s;
        }
        return ItemStack.EMPTY;
    }

    /**
     * 工具磨一点耐久。不走 ItemStack#damage(1.21 签名多变,风险高),
     * 手动 setDamage;真磨没了从背包移除并喊一声。
     */
    public void damageTool(ItemStack tool) {
        if (tool.isEmpty() || !tool.isDamageable()) return;
        tool.setDamage(tool.getDamage() + 1);
        if (tool.getDamage() >= tool.getMaxDamage()) {
            for (int i = 0; i < inventory.size(); i++) {
                if (inventory.getStack(i) == tool) {
                    inventory.setStack(i, ItemStack.EMPTY);
                    break;
                }
            }
            sayDelayed("啊,工具报废了……回头给我补一把?");
        }
    }

    // ===================== v0.3 战斗工具 =====================

    /**
     * 从背包里找最好的武器(剑 > 斧)装到主手。
     * 主手已有武器时不替换(避免干活时把工具换走)。
     * 已验证:ItemTags 在 net.minecraft.registry.tag 包(不是 item 包)。
     */
    public void autoEquipBestWeapon() {
        // v0.20 钓鱼中别抢竿:不然每 40 tick 一次的换剑会把手里的鱼竿换回包里
        if (currentTask instanceof com.frend.entity.task.FishTask) return;
        ItemStack main = this.getMainHandStack();
        // 主手已有剑/斧/弓,不动(弓是 v0.8 CombatGoal 按距离换上去的,别抢)
        if (!main.isEmpty()
                && (main.isIn(net.minecraft.registry.tag.ItemTags.SWORDS)
                    || main.isIn(net.minecraft.registry.tag.ItemTags.AXES)
                    || main.getItem() == net.minecraft.item.Items.BOW)) return;

        // 优先找剑
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.getStack(i);
            if (s.isEmpty()) continue;
            if (s.isIn(net.minecraft.registry.tag.ItemTags.SWORDS)) {
                ItemStack old = this.getMainHandStack();
                this.setStackInHand(Hand.MAIN_HAND, s.copy());
                inventory.setStack(i, old.isEmpty() ? ItemStack.EMPTY : old.copy()); // 对调不覆盖,原主手物(火把/镐等)不消失
                return;
            }
        }
        // 次选斧
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.getStack(i);
            if (s.isEmpty()) continue;
            if (s.isIn(net.minecraft.registry.tag.ItemTags.AXES)) {
                ItemStack old = this.getMainHandStack();
                this.setStackInHand(Hand.MAIN_HAND, s.copy());
                inventory.setStack(i, old.isEmpty() ? ItemStack.EMPTY : old.copy());
                return;
            }
        }
        // 三选弓(v0.8):只有弓没近战也拿着——战斗里按距离换械由 CombatGoal 负责
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.getStack(i);
            if (s.isEmpty()) continue;
            if (s.getItem() == net.minecraft.item.Items.BOW) {
                ItemStack old = this.getMainHandStack();
                this.setStackInHand(Hand.MAIN_HAND, s.copy());
                inventory.setStack(i, old.isEmpty() ? ItemStack.EMPTY : old.copy());
                return;
            }
        }
    }

    /**
     * v0.7 自动穿甲/拿盾:扫背包——
     * 盾:副手空着就拿(不换,盾没有优劣);
     * 甲:对应槽位空着就穿;已穿则比护甲值,新的更硬才换,换下来的放回背包。
     * 穿上任何东西都道个谢(60 秒冷却,不刷屏)。
     */
    public void autoEquipArmorAndShield() {
        boolean equippedSomething = false;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.getStack(i);
            if (s.isEmpty()) continue;

            // 盾 → 副手
            if (s.getItem() == net.minecraft.item.Items.SHIELD) {
                if (this.getEquippedStack(EquipmentSlot.OFFHAND).isEmpty()) {
                    this.equipStack(EquipmentSlot.OFFHAND, s.copy());
                    inventory.setStack(i, ItemStack.EMPTY);
                    equippedSomething = true;
                }
                continue;
            }

            if (!(s.getItem() instanceof ArmorItem armor)) continue;
            // 【待编译验证】ArmorItem#getSlotType();若无此方法,备选 armor.getType().getEquipmentSlot()
            EquipmentSlot slot = armor.getSlotType();
            if (slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND) continue; // 保险
            ItemStack current = this.getEquippedStack(slot);
            // 【待编译验证】ArmorItem#getProtection()(护甲点数,钻甲胸=8 铁胸=6)
            int newProt = armor.getProtection();
            int curProt = current.getItem() instanceof ArmorItem cur ? cur.getProtection() : -1;
            if (current.isEmpty() || newProt > curProt) {
                this.equipStack(slot, s.copy());
                inventory.setStack(i, ItemStack.EMPTY);
                if (!current.isEmpty()) {
                    ItemStack rest = inventory.addStack(current); // 换下来的放回包
                    if (!rest.isEmpty()) this.dropStack(rest);    // 包满就落地(理论上不会:刚空出一格)
                }
                equippedSomething = true;
            }
        }
        if (equippedSomething && equipTalkCooldown <= 0) {
            equipTalkCooldown = 20 * 60;
            getMemory().recordGift(); // v0.10 朋友记账:你给我的,我记得
            sayDelayed("有装备当然要穿上——谢啦,感觉稳多了!");
        }
    }

    // ===================== 交互 =====================

    @Override
    protected ActionResult interactMob(PlayerEntity player, Hand hand) {
        if (this.getWorld().isClient) return ActionResult.SUCCESS;
        if (hand != Hand.MAIN_HAND) return ActionResult.PASS;

        if (ownerUuid == null || isOwner(player)) {
            if (ownerUuid == null) setOwner(player); // 无主时第一个右键的人认作主人(兜底,正常走 summon)
            player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                    (syncId, playerInv, p) -> GenericContainerScreenHandler.createGeneric9x3(syncId, playerInv, this.inventory),
                    Text.literal(this.getDisplayName().getString() + " 的背包")));
        } else {
            sayDelayed("我是 " + ownerName() + " 的朋友,你好呀。");
        }
        return ActionResult.CONSUME;
    }

    private String ownerName() {
        PlayerEntity owner = getOwnerPlayer();
        return owner != null ? owner.getName().getString() : "我朋友";
    }

    /**
     * 从背包里找一份食物吃掉:回血量 = 食物饱食度(实体没有饥饿系统,用这个当口粮换算)。
     * 【待编译验证】1.21 数据组件:DataComponentTypes.FOOD / FoodComponent#nutrition()。
     */
    private boolean tryEat() {
        int slot = findFoodSlot();
        if (slot < 0) return false;
        ItemStack s = inventory.getStack(slot);
        net.minecraft.component.type.FoodComponent food = s.get(net.minecraft.component.DataComponentTypes.FOOD);
        if (food == null) return false;
        s.decrement(1);
        this.heal(Math.max(1.0f, food.nutrition()));
        this.playSound(net.minecraft.sound.SoundEvents.ENTITY_GENERIC_EAT, 1.0f, 1.0f); // 已验证:1.21.1 里是纯 SoundEvent,不带 .value()
        if (this.random.nextFloat() < 0.3f) sayDelayed("先垫两口,不耽误事。");
        return true;
    }

    /** 背包里第一格食物的槽位,没有返回 -1(tryEat 和扔食共用)。 */
    private int findFoodSlot() {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.getStack(i);
            if (s.isEmpty()) continue;
            if (s.get(net.minecraft.component.DataComponentTypes.FOOD) != null) return i;
        }
        return -1;
    }

    /**
     * v0.11 有来有往:朝你脚边扔一份吃的(一份,不是一组——它自己也要活)。
     * 扔不出去(没吃的)返回 false,调用方退回口头提醒。
     */
    private boolean tossFoodTo(PlayerEntity owner) {
        int slot = findFoodSlot();
        if (slot < 0) return false;
        ItemStack s = inventory.getStack(slot);
        ItemStack one = s.copyWithCount(1);
        s.decrement(1);
        net.minecraft.entity.ItemEntity item = new net.minecraft.entity.ItemEntity(
                this.getWorld(), this.getX(), this.getEyeY() - 0.3, this.getZ(), one);
        net.minecraft.util.math.Vec3d dir = owner.getPos().add(0, 0.5, 0)
                .subtract(this.getPos()).normalize();
        item.setVelocity(dir.multiply(0.4).add(0, 0.2, 0));
        this.getWorld().spawnEntity(item);
        return true;
    }

    // ===================== tick(仅服务端) =====================

    @Override
    protected void mobTick() {
        super.mobTick();
        FrendConfig c = FrendConfig.get();

        // 撤退计时递减(CombatGoal 不激活时不 tick,不在这儿减就是"撤退一次终身和平"bug)
        if (combatGoal != null) combatGoal.tickRetreatCooldown();

        if (lowHealthWarnCooldown > 0) lowHealthWarnCooldown--;
        if (ambientCooldown > 0) ambientCooldown--;
        if (hurtTalkCooldown > 0) hurtTalkCooldown--;
        if (eatCooldown > 0) eatCooldown--;
        if (torchCooldown > 0) torchCooldown--;
        if (torchTalkCooldown > 0) torchTalkCooldown--;
        if (equipTalkCooldown > 0) equipTalkCooldown--;
        if (dimensionTalkCooldown > 0) dimensionTalkCooldown--;
        if (fireTalkCooldown > 0) fireTalkCooldown--;
        if (saveThanksCooldown > 0) saveThanksCooldown--;
        if (deathSpotWarnCooldown > 0) deathSpotWarnCooldown--;
        if (stuckTalkCooldown > 0) stuckTalkCooldown--;

        // ===== v0.12 卡死自救:导航进行中但 2s 没挪窝 → 跳 → 停表重算 =====
        // 拉弓站桩/干活敲方块时导航是停的(isIdle),不会误判。最终兜底仍是跟随的 48 格传送保险丝。
        if (c.stuckRescue && this.age % 40 == 0) {
            if (!this.getNavigation().isIdle()) {
                if (this.getPos().squaredDistanceTo(lastStuckCheckPos) < 0.25) {
                    stuckCount++;
                    if (stuckCount == 1) {
                        this.getJumpControl().setActive(); // 多半是一格台阶/栅栏,跳一下就过
                    } else if (stuckCount >= 2) {
                        this.getNavigation().stop(); // 停表,Goal 下 tick 换条路重新规划
                        stuckCount = 0;
                        if (stuckTalkCooldown <= 0) {
                            stuckTalkCooldown = 20 * 60;
                            sayDelayed("这路不好走……我绕绕。");
                        }
                    }
                } else {
                    stuckCount = 0;
                }
            } else {
                stuckCount = 0;
            }
            lastStuckCheckPos = this.getPos();
        }

        // ===== v0.9 下界适应:跨维度追随 + 换维度风味话 + 着火喊话 =====
        if (this.age % 20 == 0) {
            tickDimensionAwareness();
        }
        if (FrendConfig.get().crossDimensionFollow && mode == Mode.FOLLOW && this.age % 40 == 0) {
            tryFollowAcrossDimension();
        }

        // ===== v0.6 自动插火把:在洞里(方块光和天空光都低)+ 背包有火把 → 脚下插一根 =====
        // 天空光条件挡住"白天野外方块光本来就是 0"的误判——地表 sky=15,永远不满足;只有洞里才插。
        if (c.autoTorch && torchCooldown <= 0 && this.age % 30 == 0) {
            tryPlaceTorch(c);
        }

        // ===== 自动装备武器(v0.3):每 40 tick 扫一次背包,有剑/斧且主手空着就装上 =====
        if (c.combatEnabled && c.autoEquipWeapon && this.age % 40 == 0) {
            autoEquipBestWeapon();
        }

        // ===== v0.7 自动穿甲/拿盾:和武器错开 20 tick,摊薄扫包开销 =====
        if (c.autoEquipArmor && this.age % 40 == 20) {
            autoEquipArmorAndShield();
        }

        // ===== 任务驱动(WORK 模式) =====
        if (currentTask != null) {
            if (mode != Mode.WORK) {
                // 被"跟我来"等切走模式 → 任务自然作废
                currentTask.onStop();
                currentTask = null;
                com.frend.system.FrendCrew.releaseAll(this); // v0.27 认领统一清账
            } else if (!currentTask.tick()) {
                currentTask = null;
                com.frend.system.FrendCrew.releaseAll(this); // v0.27 认领统一清账
                postTaskPickupTicks = 60; // v0.27 修真虫:最后一块的掉落物还在天上飞,拾取多留 3 秒
                if (mode == Mode.WORK) setMode(Mode.STAY); // v0.15:任务收尾自己换了模式(捡尸后转跟随)就尊重它
            }
        }

        // 干活时顺手捡脚边掉落物(只在任务中捡,平时不跟主人抢地上的东西);
        // v0.27:节奏 10→5、范围 2.5→3.5、收工后余韵 60 tick——"都在我包里"必须是真话
        if (postTaskPickupTicks > 0) postTaskPickupTicks--;
        if ((currentTask != null || postTaskPickupTicks > 0) && this.age % 5 == 0) {
            for (net.minecraft.entity.ItemEntity item : this.getWorld().getEntitiesByClass(
                    net.minecraft.entity.ItemEntity.class, this.getBoundingBox().expand(3.5),
                    e -> e.isAlive() && e.isOnGround())) {
                ItemStack rest = inventory.addStack(item.getStack());
                if (rest.isEmpty()) item.discard();
                else item.setStack(rest);
            }
        }

        // ===== 自动进食:背包里有吃的、血不满就自己吃(优先于被动回血) =====
        if (c.autoEat && eatCooldown <= 0 && this.getHealth() < (float) c.autoEatBelowHealth) {
            if (tryEat()) eatCooldown = c.eatCooldownSeconds * 20;
        }

        // ===== v0.5 自主行动:待命时自己找活/包满自己存/环境闲话(全规则驱动) =====
        autonomy.tick(c);

        // 被动缓慢回血(没吃的时的保底)
        if (c.passiveRegen && this.age % Math.max(1, c.regenIntervalTicks) == 0
                && this.getHealth() < this.getMaxHealth()) {
            this.heal((float) c.regenAmount);
        }

        // v0.18 灵魂定期存盘(5 分钟一次;死亡/解散/主人下线另有即时存)
        if (c.soulEnabled && ownerUuid != null && this.age % 6000 == 5999) {
            com.frend.system.FrendSoul.save(this);
        }

        // 每秒检查一次主人状态
        if (this.age % 20 == 0) {
            PlayerEntity owner = getOwnerPlayer();
            if (owner != null && owner.isAlive()) {
                // v0.18 重逢:你上线后第一次走到它跟前,它按分别的天数问候(久别的那几句,慢慢听)
                if (c.soulEnabled && ownerUuid != null
                        && this.squaredDistanceTo(owner) < c.chatRadius * c.chatRadius) {
                    Long awayDays = com.frend.system.FrendSoul.popReunion(ownerUuid);
                    if (awayDays != null) {
                        sayDelayed(com.frend.system.FrendSoul.reunionLine(awayDays));
                        if (awayDays >= 7) { // 久别是大事,记进一生
                            getMemory().record(this.getWorld().getTime(),
                                    "你离开了 " + awayDays + " 天,我一直在等");
                        }
                    }
                    // 相识纪念日(10/100/365,一生各一次)
                    String ann = getMemory().anniversaryLine(this.getWorld().getTime());
                    if (ann != null) sayDelayed(ann);
                }
                // v0.15 捡回来的遗物,见面全数奉还(4 格内)
                if (hasSalvage() && this.squaredDistanceTo(owner) < 16.0) {
                    giveSalvageBack(owner);
                }
                // 主人低血:v0.11 朋友不光嘴上提醒——包里有吃的就扔一份过去
                if (c.ownerLowHealthWarn && owner.getHealth() <= c.lowHealthWarnThreshold
                        && lowHealthWarnCooldown <= 0
                        && this.squaredDistanceTo(owner) < c.chatRadius * c.chatRadius) {
                    lowHealthWarnCooldown = c.lowHealthWarnCooldownSeconds * 20;
                    if (c.shareFoodWhenOwnerLow && this.squaredDistanceTo(owner) < 64.0 && tossFoodTo(owner)) {
                        // 扔完再看:那是不是它最后的口粮
                        sayDelayed(findFoodSlot() < 0 ? "接着!最后一口了,你吃,我扛得住。"
                                                      : "接着!先垫口吃的,别硬扛。");
                    } else {
                        sayDelayed("你血量见底了!先别冲,吃点东西缓缓。");
                    }
                }
                // v0.11 路过你倒下过的地方 → 提醒小心(5min 冷却;同维度 16 格内才算)
                if (c.deathSpotWarn && deathSpotWarnCooldown <= 0
                        && this.squaredDistanceTo(owner) < c.chatRadius * c.chatRadius) {
                    net.minecraft.util.math.BlockPos spot = getMemory().nearestDeathSpot(
                            this.getWorld().getRegistryKey().getValue().toString(), owner.getBlockPos(), 16);
                    if (spot != null) {
                        deathSpotWarnCooldown = 20 * 300;
                        sayDelayed("小心点……你上回就是在这附近栽的。");
                    }
                }
                // 偶尔闲聊(主人在身边、白天概率高一点点就免了,保持简单:纯随机 + 长冷却)
                if (c.enableAmbientChat && ambientCooldown <= 0
                        && this.squaredDistanceTo(owner) < 64.0
                        && this.random.nextFloat() < 0.05f) { // 冷却结束后平均再等约 20 秒才开口
                    ambientCooldown = c.ambientChatCooldownSeconds * 20;
                    // v0.18 学话:两成概率蹦一句跟你学的口头禅——朋友待久了说话都像
                    String learned = c.phraseLearning && this.random.nextFloat() < 0.2f
                            ? memory.randomLearnedPhrase(this.random) : null;
                    // v0.19 三成概率谈见识(知识库有货才谈)
                    if (learned == null && c.knowledgeEnabled && this.random.nextFloat() < 0.3f) {
                        learned = knowledge.randomInsight(this.random);
                    }
                    sayDelayed(learned != null ? learned
                            : AMBIENT_LINES[this.random.nextInt(AMBIENT_LINES.length)]);
                }
            }
        }
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        boolean hurt = super.damage(source, amount);
        if (hurt && !this.getWorld().isClient && hurtTalkCooldown <= 0 && this.isAlive()) {
            hurtTalkCooldown = 20 * 8;
            sayDelayed(HURT_LINES[this.random.nextInt(HURT_LINES.length)]);
        }
        // v0.9 自卫反击:谁打的还谁(onSelfHurt 内部过滤玩家/同类,任何模式生效)
        if (hurt && !this.getWorld().isClient && this.isAlive() && combatGoal != null) {
            if (source.getAttacker() instanceof net.minecraft.entity.LivingEntity living) {
                combatGoal.onSelfHurt(living);
            }
        }
        // v0.19 知识:记住被谁伤过;苦力怕爆炸单独记教训(下次离它更远)
        if (hurt && !this.getWorld().isClient) {
            boolean creeperBlast = source.getSource() instanceof net.minecraft.entity.mob.CreeperEntity
                    || source.getAttacker() instanceof net.minecraft.entity.mob.CreeperEntity;
            String byName = source.getAttacker() instanceof net.minecraft.entity.LivingEntity la
                    ? la.getName().getString() : null;
            knowledge.recordHurtBy(byName, creeperBlast);
        }
        return hurt;
    }

    /**
     * 主人被攻击时由外部(Frend.java 事件监听)调用 → 通知 combatGoal 支援。
     * 只在服务端调用。
     */
    public void onOwnerHurt(net.minecraft.entity.LivingEntity attacker) {
        if (combatGoal != null && FrendConfig.get().supportOwner) {
            combatGoal.onOwnerHurt(attacker);
        }
    }

    /**
     * v0.10 朋友,不是仆人:攻击我的怪被你干掉了——你救了我。
     * 由 Frend.java 的 AFTER_DEATH 监听调用。记忆永远记账;道谢带冷却(60s)防刷屏,第一次必说。
     */
    public void onOwnerSavedMe(net.minecraft.entity.mob.MobEntity attacker) {
        if (this.getWorld().isClient || !this.isAlive()) return;
        String line = getMemory().recordOwnerSave(attacker.getName().getString(), this.getWorld().getTime());
        if (line != null && saveThanksCooldown <= 0) {
            saveThanksCooldown = 20 * 60;
            sayDelayed(line);
        }
    }

    /**
     * v0.11 你倒下了(Frend.java AFTER_DEATH 监听调用):记住地点,之后路过提醒。
     * 刚死完压一下提醒冷却——你复活跑尸时它没必要立刻在原地念叨。
     */
    public void onOwnerDied(net.minecraft.server.network.ServerPlayerEntity player) {
        if (this.getWorld().isClient || !this.isAlive()) return;
        getMemory().recordOwnerDeath(
                this.getWorld().getRegistryKey().getValue().toString(),
                player.getBlockPos(), this.getWorld().getTime());
        deathSpotWarnCooldown = Math.max(deathSpotWarnCooldown, 20 * 120);
        // v0.15 说到做到:"东西我帮你看着"不再是空话——赶过去把掉落全收进遗物袋,见面奉还
        if (FrendConfig.get().collectOwnerDrops) {
            startTask(new com.frend.entity.task.SalvageTask(this, player.getBlockPos()),
                    "不——!你先回来,东西我去收,一件都不会丢!");
        } else {
            sayDelayed("不——!你先回来,东西我帮你看着!");
        }
    }

    /**
     * v0.11 清 v0.8 欠账:射死的怪进战绩。箭是异步击杀,战斗 Goal 的白刃收尾检测不到,
     * 走 AFTER_DEATH 监听(凶器是箭 + 射手是我 → 白刃路径不会重复入账,零冲突)。
     * 顺带补救主判定:这怪死前正咬着我朋友。
     */
    public void onArrowKill(net.minecraft.entity.mob.MobEntity mob) {
        if (this.getWorld().isClient || !this.isAlive()) return;
        long now = this.getWorld().getTime();
        knowledge.recordKill(mob.getName().getString()); // v0.19 知识入账
        String line = getMemory().recordKill(mob.getName().getString(), now);
        if (line != null) sayDelayed(line);
        if (mob.getTarget() instanceof PlayerEntity p && isOwner(p)) {
            String r = getMemory().recordRescue(mob.getName().getString(), now);
            if (r != null) sayDelayed(r);
        }
    }

    /**
     * v0.10 起名字:朋友之间怎么能没名字。改 CustomName(原版机制,头顶显示、say 前缀自动跟着变、
     * NBT 白嫖持久化),记忆里记一笔大事。
     */
    public void renameBy(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > 16) {
            sayDelayed("这名字……要不换个短点的?16 个字以内。");
            return;
        }
        this.setCustomName(Text.literal(clean));
        this.setCustomNameVisible(true);
        getMemory().record(this.getWorld().getTime(), "你给我起了名字:" + clean);
        sayDelayed("「" + clean + "」……好名字!从今天起我就叫这个了。");
    }

    /**
     * v0.9 跨维度追随:主人进了别的维度(下界/末地/回主世界),跟随中的 frend 追过去。
     * 每 2s 检查一次,连续两次(≈4s)主人都不在本维度才追——主人进门马上折返时不白跑。
     *
     * 【关键坑】非玩家实体换维度在 MC 里是"复制实体":旧实体销毁,目标维度里造一个新的
     * (走完整 NBT 读写,所以背包/记忆/装备都会原样带过去)。teleportTo 返回的才是"活着的那个",
     * 传送之后不能再碰 this——说话必须用返回值说。
     */
    private void tryFollowAcrossDimension() {
        if (getOwnerPlayer() != null) { ownerAwayChecks = 0; return; } // 主人就在本维度,没事
        net.minecraft.server.network.ServerPlayerEntity owner = getOwnerPlayerAnywhere();
        if (owner == null || !owner.isAlive() || owner.getWorld() == this.getWorld()) {
            ownerAwayChecks = 0; // 下线了/死了/其实同维度(理论上上面已捕获) → 不追
            return;
        }
        if (++ownerAwayChecks < 2) return; // 宽限一轮
        ownerAwayChecks = 0;
        this.getNavigation().stop();
        // 编译实证(清账#2):这套 1.21.1 映射里 TeleportTarget 是新式签名
        // TeleportTarget(ServerWorld, Vec3d pos, Vec3d velocity, float yaw, float pitch, PostDimensionTransition),
        // 且 fabric-dimensions-v1 模块在 fabric-api 0.105 里已移除 → 走原版 Entity#teleportTo。
        // PostDimensionTransition 是函数式接口,传空 lambda 零副作用。
        // 【待编译验证】Entity#teleportTo(TeleportTarget) 方法名——若报错找 moveToWorld/changeDimension 系。
        net.minecraft.entity.Entity movedRaw = this.teleportTo(
                new net.minecraft.world.TeleportTarget(
                        (net.minecraft.server.world.ServerWorld) owner.getWorld(),
                        owner.getPos(), net.minecraft.util.math.Vec3d.ZERO,
                        this.getYaw(), this.getPitch(),
                        entity -> { }));
        if (movedRaw instanceof FrendEntity moved) {
            moved.sayDelayed("等等我,这就来!");
        }
    }

    /**
     * v0.9 维度感知:换维度说一句风味话(每维度一句,60s 共享冷却);着火了喊一声(30s 冷却)。
     * lastDimension 随 NBT 持久化——跨维度传送是复制实体,不存的话每次过门都丢状态。
     */
    private void tickDimensionAwareness() {
        // v0.19 探索知识:头一回到的生物群系记一笔,顺嘴感慨(recordBiome 自带去重)
        if (FrendConfig.get().knowledgeEnabled && this.age % 100 == 0) {
            String biomeId = this.getWorld().getBiome(this.getBlockPos()).getKey()
                    .map(k -> k.getValue().toString()).orElse(null); // 【待编译验证】RegistryEntry#getKey
            String line = knowledge.recordBiome(biomeId);
            if (line != null && ambientCooldown <= 0) { // 借闲聊冷却,不刷屏
                ambientCooldown = 20 * 60;
                sayDelayed(line);
            }
        }
        String dim = this.getWorld().getRegistryKey().getValue().toString();
        if (!dim.equals(lastDimension)) {
            boolean known = lastDimension != null; // 刚生成/刚加载时 lastDimension 为空,静默记录不喊话
            lastDimension = dim;
            if (known && dimensionTalkCooldown <= 0) {
                dimensionTalkCooldown = 20 * 60;
                switch (dim) {
                    case "minecraft:the_nether" -> sayDelayed("下界……跟紧点,这地方不讲道理。");
                    case "minecraft:the_end" -> sayDelayed("末地?!你可真敢带我来。");
                    case "minecraft:overworld" -> sayDelayed("呼,回来了。还是这边看着舒坦。");
                    default -> { }
                }
            }
        }
        if (this.isOnFire() && fireTalkCooldown <= 0 && this.isAlive()) {
            fireTalkCooldown = 20 * 30;
            sayDelayed("烫烫烫!没事,打完再说!");
        }
    }

    @Override
    public void onDeath(DamageSource source) {
        if (!this.getWorld().isClient) {
            // v0.18 灵魂让这句话成真了:死亡带不走记忆,再召出来的还是它
            knowledge.recordMyDeath(); // v0.19 灵魂记得每一世
            if (FrendConfig.get().soulEnabled && ownerUuid != null) {
                com.frend.system.FrendSoul.save(this);
                say("别慌……我们还会再见的。你的事我都记在魂里了——东西先留给你,回头见。");
            } else {
                say("对不起……我先走一步,东西都留给你。");
            }
        }
        super.onDeath(source);
    }

    /** 死亡时把随身背包全部掉出来。 */
    @Override
    protected void dropInventory() {
        super.dropInventory();
        dropAllItems();
    }

    /** 把背包内容散落在脚下(死亡 / dismiss 共用)。 */
    public void dropAllItems() {
        if (this.getWorld().isClient) return;
        ItemScatterer.spawn(this.getWorld(), this.getBlockPos(), this.inventory);
        for (int i = 0; i < inventory.size(); i++) inventory.setStack(i, ItemStack.EMPTY);
        // v0.15 遗物袋一并散落——它没了,你的东西也一件不昧
        ItemScatterer.spawn(this.getWorld(), this.getBlockPos(), this.salvage);
        for (int i = 0; i < salvage.size(); i++) salvage.setStack(i, ItemStack.EMPTY);
        // v0.7:身上穿的拿的也一并还给主人(解散走这里;死亡由 setEquipmentDropChance 兜底)
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack s = this.getEquippedStack(slot);
            if (!s.isEmpty()) {
                this.dropStack(s.copy()); // 【待编译验证】Entity#dropStack(ItemStack)
                this.equipStack(slot, ItemStack.EMPTY);
            }
        }
    }

    // ===================== 说话 =====================

    /**
     * v0.12 长途分段寻路:原版寻路的搜索范围被 FOLLOW_RANGE(48)钉死,回家/存箱子几百格远时
     * 一次算不出完整路径就直接摆烂。这里先试直达;不行就朝目标方向取 24 格中间点走一段,
     * 调用方(回家 Goal 每秒重发 / 任务 moveTo)反复调用,一段一段蹭过去。
     * 近距离(≤24 格)找不到路不硬分段——那是地形问题,交给卡死自救和传送保险丝。
     */
    public void navigateSmart(double x, double y, double z, double speed) {
        if (this.getNavigation().startMovingTo(x, y, z, speed) && !this.getNavigation().isIdle()) return;
        double dx = x - this.getX(), dz = z - this.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist <= 24) return;
        double mx = this.getX() + dx / dist * 24;
        double mz = this.getZ() + dz / dist * 24;
        this.getNavigation().startMovingTo(mx, this.getY(), mz, speed);
    }

    /** 立即向聊天半径内玩家广播一句话,格式 &lt;frend&gt; xxx。 */
    /** v0.27 黑匣子:最后说的一句(自动测试断言/报障快照用,一个字段大用处)。 */
    private String lastSaid = null;

    public String getLastSaid() { return lastSaid; }

    public void say(String msg) {
        if (this.getWorld().isClient) return;
        this.lastSaid = msg;
        this.lastTalkMillis = System.currentTimeMillis(); // 对话延续窗口从这一刻起算
        rememberChat("assistant", msg);
        double r = FrendConfig.get().chatRadius;
        Text text = Text.literal("<" + this.getDisplayName().getString() + "> ").formatted(Formatting.AQUA)
                .append(Text.literal(msg).formatted(Formatting.WHITE));
        for (PlayerEntity p : this.getWorld().getPlayers()) {
            if (p.squaredDistanceTo(this) <= r * r) {
                p.sendMessage(text, false);
            }
        }
    }

    /** 带随机延迟说话(像人:不秒回)。 */
    public void sayDelayed(String msg) {
        this.lastSaid = msg;
        FrendConfig c = FrendConfig.get();
        int span = Math.max(1, c.replyDelayMaxTicks - c.replyDelayMinTicks);
        int delay = c.replyDelayMinTicks + this.random.nextInt(span);
        FrendScheduler.schedule(delay, () -> { if (this.isAlive()) say(msg); });
    }

    // ===================== 聊天记忆 / 对话窗口 / LLM 节流 =====================

    /** 记一条对话(role: user/assistant)。只留最近 llmHistoryEntries 条,不落盘。 */
    public void rememberChat(String role, String content) {
        chatHistory.addLast(new String[]{role, content});
        int max = Math.max(2, FrendConfig.get().llmHistoryEntries);
        while (chatHistory.size() > max) chatHistory.pollFirst();
    }

    /** 最近对话快照(时间先后顺序)。 */
    public java.util.List<String[]> chatHistorySnapshot() {
        return new java.util.ArrayList<>(chatHistory);
    }

    /** frend 刚说完话的一小段时间内,主人不喊名字也算在跟它聊。 */
    public boolean inConversationWindow() {
        return System.currentTimeMillis() - lastTalkMillis
                < FrendConfig.get().conversationWindowSeconds * 1000L;
    }

    /** 尝试占用一次 LLM 请求名额(有请求在飞或间隔太短则拒绝)。成功后必须调 {@link #finishLlm()}。 */
    public boolean tryStartLlm() {
        long now = System.currentTimeMillis();
        if (llmBusy || now - lastLlmMillis < FrendConfig.get().llmMinIntervalSeconds * 1000L) return false;
        llmBusy = true;
        lastLlmMillis = now;
        return true;
    }

    public void finishLlm() {
        llmBusy = false;
    }

    // ===================== v0.6 自动插火把 =====================

    /**
     * 在洞里太黑就往脚下插火把(矿洞安全本能,跟自动吃饭同级,不属于"自主找活")。
     * 条件全满足才插:方块光 < 阈值(防刷怪)、天空光 < 阈值(证明在洞里,不是夜晚地表)、
     * 脚下这格是空气且火把能站住、背包里真有火把。
     * 插完靠光照自然拉开间距(照亮后周围不再"太黑"),外加 2 秒硬冷却兜底。
     */
    private void tryPlaceTorch(FrendConfig c) {
        World world = this.getWorld();
        BlockPos pos = this.getBlockPos();
        // 【待编译验证】World#getLightLevel(LightType, BlockPos)
        if (world.getLightLevel(net.minecraft.world.LightType.BLOCK, pos) >= c.torchLightThreshold) return;
        if (world.getLightLevel(net.minecraft.world.LightType.SKY, pos) >= c.torchLightThreshold) return;
        if (!world.getBlockState(pos).isAir()) return;
        // 【待编译验证】BlockState#canPlaceAt(火把支撑面检查,原版标准 API)
        if (!net.minecraft.block.Blocks.TORCH.getDefaultState().canPlaceAt(world, pos)) return;

        // 背包里找火把
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.getStack(i);
            if (s.getItem() == net.minecraft.item.Items.TORCH) {
                world.setBlockState(pos, net.minecraft.block.Blocks.TORCH.getDefaultState());
                s.decrement(1);
                this.swingHand(Hand.MAIN_HAND, true);
                torchCooldown = 40; // 2 秒硬冷却
                if (torchTalkCooldown <= 0) {
                    torchTalkCooldown = 20 * 300; // 5 分钟才念叨一次
                    sayDelayed("这儿太黑了,我插个火把。");
                }
                return;
            }
        }
        // 没火把:太黑但无能为力——不念叨(念了主人也未必在),交给状态汇报
        torchCooldown = 20 * 30; // 30 秒内别再白扫
    }

    // ===================== NBT 持久化 =====================

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        if (ownerUuid != null) nbt.putUuid("FrendOwner", ownerUuid);
        nbt.putString("FrendMode", mode.name());
        if (soulId > 0) nbt.putInt("SoulId", soulId); // v0.27 分魂槽位
        if (hasHome()) {
            nbt.putInt("HomeX", homePos.getX());
            nbt.putInt("HomeY", homePos.getY());
            nbt.putInt("HomeZ", homePos.getZ());
            nbt.putString("HomeDim", homeDimension);
        }
        if (lastDimension != null) nbt.putString("LastDim", lastDimension); // v0.9 跨维度复制实体后风味话状态不丢
        // 背包:拷贝到 DefaultedList 再走原版 Inventories(与 yongye/AccessoryStorage 同款写法)
        DefaultedList<ItemStack> list = DefaultedList.ofSize(inventory.size(), ItemStack.EMPTY);
        for (int i = 0; i < inventory.size(); i++) list.set(i, inventory.getStack(i));
        NbtCompound invTag = new NbtCompound();
        Inventories.writeNbt(invTag, list, this.getWorld().getRegistryManager());
        nbt.put("FrendInventory", invTag);
        // v0.15 遗物袋(同款写法)
        DefaultedList<ItemStack> sList = DefaultedList.ofSize(salvage.size(), ItemStack.EMPTY);
        for (int i = 0; i < salvage.size(); i++) sList.set(i, salvage.getStack(i));
        NbtCompound sTag = new NbtCompound();
        Inventories.writeNbt(sTag, sList, this.getWorld().getRegistryManager());
        nbt.put("FrendSalvage", sTag);
        // v0.4 长期记忆
        nbt.put("FrendMemory", memory.toNbt());
        // v0.19 知识库
        nbt.put("FrendKnowledge", knowledge.toNbt());
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.containsUuid("FrendOwner")) ownerUuid = nbt.getUuid("FrendOwner");
        try {
            mode = Mode.valueOf(nbt.getString("FrendMode"));
        } catch (IllegalArgumentException e) {
            mode = Mode.FOLLOW;
        }
        if (mode == Mode.WORK) mode = Mode.STAY; // 任务不落盘,重载后回待命
        if (nbt.contains("SoulId")) soulId = nbt.getInt("SoulId"); // v0.27
        if (nbt.contains("HomeDim")) {
            homePos = new BlockPos(nbt.getInt("HomeX"), nbt.getInt("HomeY"), nbt.getInt("HomeZ"));
            homeDimension = nbt.getString("HomeDim");
        }
        if (nbt.contains("LastDim")) lastDimension = nbt.getString("LastDim");
        if (nbt.contains("FrendInventory")) {
            DefaultedList<ItemStack> list = DefaultedList.ofSize(inventory.size(), ItemStack.EMPTY);
            Inventories.readNbt(nbt.getCompound("FrendInventory"), list, this.getWorld().getRegistryManager());
            for (int i = 0; i < inventory.size(); i++) inventory.setStack(i, list.get(i));
        }
        if (nbt.contains("FrendSalvage")) { // v0.15 遗物袋
            DefaultedList<ItemStack> sList = DefaultedList.ofSize(salvage.size(), ItemStack.EMPTY);
            Inventories.readNbt(nbt.getCompound("FrendSalvage"), sList, this.getWorld().getRegistryManager());
            for (int i = 0; i < salvage.size(); i++) salvage.setStack(i, sList.get(i));
        }
        if (nbt.contains("FrendKnowledge")) knowledge.fromNbt(nbt.getCompound("FrendKnowledge")); // v0.19
        if (nbt.contains("FrendMemory")) {
            memory.fromNbt(nbt.getCompound("FrendMemory"));
        }
    }
}
