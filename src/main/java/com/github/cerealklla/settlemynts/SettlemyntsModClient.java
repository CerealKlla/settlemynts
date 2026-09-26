package com.github.cerealklla.settlemynts;

import com.github.cerealklla.settlemynts.founding.client.GhostTownHallCoreRenderer;
import com.github.cerealklla.settlemynts.registration.ModEntities;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = SettlemyntsMod.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = SettlemyntsMod.MODID, value = Dist.CLIENT)
public class SettlemyntsModClient {
    public SettlemyntsModClient(ModContainer container) {
    }

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.GHOST_TOWN_HALL_CORE.get(), GhostTownHallCoreRenderer::new);
    }
}
