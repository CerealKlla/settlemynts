package com.github.cerealklla.settlemynts;

import com.github.cerealklla.settlemynts.founding.ClientFoundingRequests;
import com.github.cerealklla.settlemynts.founding.client.FoundingScreen;
import com.github.cerealklla.settlemynts.founding.client.GhostBlockDisplayRenderer;
import com.github.cerealklla.settlemynts.founding.client.GhostTownHallCoreRenderer;
import com.github.cerealklla.settlemynts.founding.client.StakeDistanceOverlay;
import com.github.cerealklla.settlemynts.founding.client.StakeScreen;
import com.github.cerealklla.settlemynts.registration.ModEntities;
import com.github.cerealklla.settlemynts.zone.client.PlotStakeScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = SettlemyntsMod.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = SettlemyntsMod.MODID, value = Dist.CLIENT)
public class SettlemyntsModClient {
    public SettlemyntsModClient(ModContainer container) {
    }

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.GHOST_TOWN_HALL_CORE.get(), GhostTownHallCoreRenderer::new);
        // Every ghost marker in this mod is a real Display.BlockDisplay entity now (2026-09-27, see
        // decisions.md -- stakes joined the wall/fence-post markers same day) -- one shared renderer
        // works for all of them.
        event.registerEntityRenderer(ModEntities.GHOST_PERIMETER_STAKE.get(), GhostBlockDisplayRenderer::new);
        event.registerEntityRenderer(ModEntities.GHOST_PLOT_STAKE.get(), GhostBlockDisplayRenderer::new);
        event.registerEntityRenderer(ModEntities.GHOST_BOUNDARY_WALL.get(), GhostBlockDisplayRenderer::new);
        event.registerEntityRenderer(ModEntities.GHOST_PLOT_WALL.get(), GhostBlockDisplayRenderer::new);
        event.registerEntityRenderer(ModEntities.GHOST_PERIMETER_FENCE_POST.get(), GhostBlockDisplayRenderer::new);
        event.registerEntityRenderer(ModEntities.GHOST_PLOT_FENCE_POST.get(), GhostBlockDisplayRenderer::new);
    }

    @SubscribeEvent
    static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "stake_distance_overlay"), new StakeDistanceOverlay());
    }

    // Polls the zero-server-refs bridge (see ClientFoundingRequests' own doc) for a pending
    // screen-open request every client tick -- same pattern as Lyfe's LyfeModClient/
    // ClientWritingRequest, which exists specifically so the common-loaded SettlemyntsMod class
    // (registerPayloads runs on both sides) never itself references Screen/Minecraft.
    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        ClientFoundingRequests.takePendingFoundingScreen().ifPresent(request -> {
            if (Minecraft.getInstance().screen == null) {
                Minecraft.getInstance().setScreen(new FoundingScreen(request));
            }
        });
        ClientFoundingRequests.takePendingStakeScreen().ifPresent(request -> {
            if (Minecraft.getInstance().screen == null) {
                Minecraft.getInstance().setScreen(new StakeScreen(request));
            }
        });
        ClientFoundingRequests.takePendingPlotStakeScreen().ifPresent(request -> {
            if (Minecraft.getInstance().screen == null) {
                Minecraft.getInstance().setScreen(new PlotStakeScreen(request));
            }
        });
    }
}
