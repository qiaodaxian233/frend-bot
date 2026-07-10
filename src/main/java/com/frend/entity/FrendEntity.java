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

    /** 当前任务(仅 WORK 模式;不落盘)。 */
    private com.frend.entity.task.FrendTask currentTask = null;
    private int eatCooldown = 0;

    /** 27 格随身背包。 */
    private final SimpleInventory inventory = new SimpleInventory(27);

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
        this.goalSelector.add(6, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
        this.goalSelector.add(7, new LookAroundGoal(this));
    }

    // ===================== 主人 / 模式 / 家 =====================

    public void setOwner(PlayerEntity player) {
        this.ownerUuid = player.getUuid();
        memory.initFirstMet(this.getWorld().getTime()); // 相识时刻只记第一次
    }

    public FrendMemory getMemory() { return memory; }

    public UUID getOwnerUuid() { return ownerUuid; }

    public boolean isOwner(PlayerEntity player) {
        return ownerUuid != null && ownerUuid.equals(player.getUuid());
    }

    /** 主人(仅同维度、在线时非空)。 */
    public PlayerEntity getOwnerPlayer() {
        if (ownerUuid == null) return null;
        return this.getWorld().getPlayerByUuid(ownerUuid);
    }

    public Mode getMode() { return mode; }

    public void setMode(Mode mode) {
        this.mode = mode;
        this.getNavigation().stop();
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

    // ===================== 任务(v0.2 干活) =====================

    /** 开始一个任务(自动切 WORK 模式,顶掉旧任务)。 */
    public void startTask(com.frend.entity.task.FrendTask task, String announce) {
        if (currentTask != null) currentTask.onStop();
        currentTask = task;
        setMode(Mode.WORK);
        if (announce != null) sayDelayed(announce);
    }

    /** 收工(announce 可空 = 不说话)。 */
    public void stopTask(String announce) {
        if (currentTask != null) {
            currentTask.onStop();
            currentTask = null;
        }
        if (mode == Mode.WORK) setMode(Mode.STAY);
        if (announce != null) sayDelayed(announce);
    }

    public boolean isWorking() { return currentTask != null; }

    public String currentTaskName() { return currentTask != null ? currentTask.name() : null; }

    /** 找一把还能用的工具(耐久留量走配置);没有返回 EMPTY。 */
    public ItemStack findUsableTool(net.minecraft.registry.tag.TagKey<net.minecraft.item.Item> tag) {
        int reserve = FrendConfig.get().toolReserveDurability;
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
     * 【待编译验证】ItemTags.SWORDS / ItemTags.AXES — v0.1 DEVLOG 已列,同 MineTask。
     */
    public void autoEquipBestWeapon() {
        ItemStack main = this.getMainHandStack();
        // 主手已有剑/斧,不动
        if (!main.isEmpty()
                && (main.isIn(net.minecraft.item.ItemTags.SWORDS)
                    || main.isIn(net.minecraft.item.ItemTags.AXES))) return;

        // 优先找剑
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.getStack(i);
            if (s.isEmpty()) continue;
            if (s.isIn(net.minecraft.item.ItemTags.SWORDS)) {
                this.setStackInHand(Hand.MAIN_HAND, s.copy());
                inventory.setStack(i, ItemStack.EMPTY);
                return;
            }
        }
        // 次选斧
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.getStack(i);
            if (s.isEmpty()) continue;
            if (s.isIn(net.minecraft.item.ItemTags.AXES)) {
                this.setStackInHand(Hand.MAIN_HAND, s.copy());
                inventory.setStack(i, ItemStack.EMPTY);
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
        return owner != null ? owner.getName().getString() : "我的主人";
    }

    /**
     * 从背包里找一份食物吃掉:回血量 = 食物饱食度(实体没有饥饿系统,用这个当口粮换算)。
     * 【待编译验证】1.21 数据组件:DataComponentTypes.FOOD / FoodComponent#nutrition()。
     */
    private boolean tryEat() {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.getStack(i);
            if (s.isEmpty()) continue;
            net.minecraft.component.type.FoodComponent food = s.get(net.minecraft.component.DataComponentTypes.FOOD);
            if (food == null) continue;
            s.decrement(1);
            this.heal(Math.max(1.0f, food.nutrition()));
            this.playSound(net.minecraft.sound.SoundEvents.ENTITY_GENERIC_EAT.value(), 1.0f, 1.0f); // 【待编译验证】RegistryEntry 还是 SoundEvent,报错就去 .value()
            if (this.random.nextFloat() < 0.3f) sayDelayed("先垫两口,不耽误事。");
            return true;
        }
        return false;
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
            } else if (!currentTask.tick()) {
                currentTask = null;
                setMode(Mode.STAY);
            }
        }

        // 干活时顺手捡脚边掉落物(只在任务中捡,平时不跟主人抢地上的东西)
        if (currentTask != null && this.age % 10 == 0) {
            for (net.minecraft.entity.ItemEntity item : this.getWorld().getEntitiesByClass(
                    net.minecraft.entity.ItemEntity.class, this.getBoundingBox().expand(2.5),
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

        // 每秒检查一次主人状态
        if (this.age % 20 == 0) {
            PlayerEntity owner = getOwnerPlayer();
            if (owner != null && owner.isAlive()) {
                // 主人低血提醒
                if (c.ownerLowHealthWarn && owner.getHealth() <= c.lowHealthWarnThreshold
                        && lowHealthWarnCooldown <= 0
                        && this.squaredDistanceTo(owner) < c.chatRadius * c.chatRadius) {
                    lowHealthWarnCooldown = c.lowHealthWarnCooldownSeconds * 20;
                    sayDelayed("你血量见底了!先别冲,吃点东西缓缓。");
                }
                // 偶尔闲聊(主人在身边、白天概率高一点点就免了,保持简单:纯随机 + 长冷却)
                if (c.enableAmbientChat && ambientCooldown <= 0
                        && this.squaredDistanceTo(owner) < 64.0
                        && this.random.nextFloat() < 0.05f) { // 冷却结束后平均再等约 20 秒才开口
                    ambientCooldown = c.ambientChatCooldownSeconds * 20;
                    sayDelayed(AMBIENT_LINES[this.random.nextInt(AMBIENT_LINES.length)]);
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

    @Override
    public void onDeath(DamageSource source) {
        if (!this.getWorld().isClient) {
            say("对不起……我先走一步,东西都留给你。");
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

    /** 立即向聊天半径内玩家广播一句话,格式 &lt;frend&gt; xxx。 */
    public void say(String msg) {
        if (this.getWorld().isClient) return;
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
        if (hasHome()) {
            nbt.putInt("HomeX", homePos.getX());
            nbt.putInt("HomeY", homePos.getY());
            nbt.putInt("HomeZ", homePos.getZ());
            nbt.putString("HomeDim", homeDimension);
        }
        // 背包:拷贝到 DefaultedList 再走原版 Inventories(与 yongye/AccessoryStorage 同款写法)
        DefaultedList<ItemStack> list = DefaultedList.ofSize(inventory.size(), ItemStack.EMPTY);
        for (int i = 0; i < inventory.size(); i++) list.set(i, inventory.getStack(i));
        NbtCompound invTag = new NbtCompound();
        Inventories.writeNbt(invTag, list, this.getWorld().getRegistryManager());
        nbt.put("FrendInventory", invTag);
        // v0.4 长期记忆
        nbt.put("FrendMemory", memory.toNbt());
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
        if (nbt.contains("HomeDim")) {
            homePos = new BlockPos(nbt.getInt("HomeX"), nbt.getInt("HomeY"), nbt.getInt("HomeZ"));
            homeDimension = nbt.getString("HomeDim");
        }
        if (nbt.contains("FrendInventory")) {
            DefaultedList<ItemStack> list = DefaultedList.ofSize(inventory.size(), ItemStack.EMPTY);
            Inventories.readNbt(nbt.getCompound("FrendInventory"), list, this.getWorld().getRegistryManager());
            for (int i = 0; i < inventory.size(); i++) inventory.setStack(i, list.get(i));
        }
        if (nbt.contains("FrendMemory")) {
            memory.fromNbt(nbt.getCompound("FrendMemory"));
        }
    }
}
