package com.frend.registry;

import com.frend.Frend;
import com.frend.entity.FrendEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

/**
 * 自定义实体注册。写法照 yongye/ModEntities(1.21.1 已编译验证的同款模式)。
 * frend 本体:类玩家 NPC,玩家体型 0.6×1.8,CREATURE 组 + setPersistent 防消失。
 */
public final class ModEntities {
    private ModEntities() {}

    public static final RegistryKey<EntityType<?>> FREND_KEY =
            RegistryKey.of(RegistryKeys.ENTITY_TYPE, Identifier.of(Frend.MOD_ID, "frend"));

    public static final EntityType<FrendEntity> FREND =
            Registry.register(Registries.ENTITY_TYPE, FREND_KEY.getValue(),
                    EntityType.Builder.create(FrendEntity::new, SpawnGroup.CREATURE)
                            // 玩家同款体型
                            .dimensions(0.6f, 1.8f)
                            .build("frend"));

    public static void init() {
        FabricDefaultAttributeRegistry.register(FREND, FrendEntity.createFrendAttributes());
        Frend.LOGGER.info("[frend] 实体已注册:frend");
    }
}
