package com.frend.client;

import com.frend.client.render.FrendRenderer;
import com.frend.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

/**
 * 客户端入口:注册 frend 的类玩家渲染器(玩家模型 + 原版 Steve 皮肤)。
 */
@Environment(EnvType.CLIENT)
public class FrendClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(ModEntities.FREND, FrendRenderer::new);
    }
}
