package com.github.cerealklla.settlemynts;

import com.mojang.blaze3d.platform.InputConstants;

import com.github.cerealklla.settlemynts.founding.ClientFoundingRequests;
import com.github.cerealklla.settlemynts.founding.client.FoundingScreen;
import com.github.cerealklla.settlemynts.founding.client.GhostBlockDisplayRenderer;
import com.github.cerealklla.settlemynts.founding.client.GhostTownHallCoreRenderer;
import com.github.cerealklla.settlemynts.founding.client.StakeDistanceOverlay;
import com.github.cerealklla.settlemynts.founding.client.StakeScreen;
import com.github.cerealklla.settlemynts.guardhouse.ClientGuardhouseRequests;
import com.github.cerealklla.settlemynts.guardhouse.client.GuardKitStandScreen;
import com.github.cerealklla.settlemynts.guardhouse.client.GuardRenderer;
import com.github.cerealklla.settlemynts.guardhouse.client.GuardhouseFoodScreen;
import com.github.cerealklla.settlemynts.guardhouse.client.SelectKytScreen;
import com.github.cerealklla.settlemynts.plotsign.ClientPlotSignRequests;
import com.github.cerealklla.settlemynts.plotsign.client.ClientProximityScanner;
import com.github.cerealklla.settlemynts.plotsign.client.ClientShopPromptState;
import com.github.cerealklla.settlemynts.plotsign.client.PlotConfigSignMenuScreen;
import com.github.cerealklla.settlemynts.plotsign.client.PlotDetailsScreen;
import com.github.cerealklla.settlemynts.plotsign.client.NpcMenuScreen;
import com.github.cerealklla.settlemynts.plotsign.client.PlotManagementScreen;
import com.github.cerealklla.settlemynts.plotsign.client.ShopPromptOverlay;
import com.github.cerealklla.settlemynts.plotsign.client.ShopScreen;
import com.github.cerealklla.settlemynts.plotsign.client.WishlistScreen;
import com.github.cerealklla.settlemynts.registration.ModEntities;
import com.github.cerealklla.settlemynts.registration.ModMenus;
import com.github.cerealklla.settlemynts.zone.client.PlotStakeScreen;
import com.github.cerealklla.settlemynts.zone.client.PlotValidityOverlay;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import org.lwjgl.glfw.GLFW;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = SettlemyntsMod.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = SettlemyntsMod.MODID, value = Dist.CLIENT)
public class SettlemyntsModClient {
    public SettlemyntsModClient(ModContainer container) {
        // RenderNameTagEvent fires on NeoForge.EVENT_BUS (the game bus), not the mod bus the
        // @EventBusSubscriber annotation above wires everything else in this class to -- registered
        // here, in a client-only constructor, same reasoning as PersonalQuestMarkerRenderer's own doc.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(
                new com.github.cerealklla.settlemynts.plotsign.client.PersonalQuestMarkerRenderer());
    }

    // "Press G to talk" (design doc Section 14a, originally 2026-10-05; reworked 2026-10-16, NPC
    // Menu overhaul, explicit user spec: "Remove 'Press H to talk' / Change G to 'Press G to
    // talk'") -- "G" is free across the whole suite (Lyfe uses "[", "]", "O", "K"), flagged as an
    // easy-to-rebind placeholder like every other keybind in this project. The old separate "H"
    // keybind is gone entirely -- both G and a plain right-click now open the same unified NPC
    // Menu (see client.NpcMenuScreen), which is where "chat" (Talk) lives now.
    public static final KeyMapping OPEN_NPC_MENU = new KeyMapping(
            "key.settlemynts.open_npc_menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, KeyMapping.Category.MISC);

    // "Auto-Drop" (2026-10-17) -- sweeps main-inventory items matching a plot's own existing box
    // stock into those boxes. "V" is free across the whole suite as of this writing (G/open_npc_menu
    // here, N/Coryspondance mailbox, J/Myssions quest log, K/M/O/Lyfe) -- same easy-to-rebind
    // placeholder as every other keybind in this project.
    public static final KeyMapping AUTO_DROP = new KeyMapping(
            "key.settlemynts.auto_drop", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, KeyMapping.Category.MISC);

    @SubscribeEvent
    static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_NPC_MENU);
        event.register(AUTO_DROP);
    }

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // The Core itself no longer renders a floating icon (2026-10-05, see its renderer's own
        // doc) -- GhostTownHallCoreRenderer is now a deliberate no-op; the visible marker is its
        // spawned GHOST_TOWN_HALL_BELL child (a real Bell block), registered on
        // GhostBlockDisplayRenderer like every other block-ghost-marker in this file.
        event.registerEntityRenderer(ModEntities.GHOST_TOWN_HALL_CORE.get(), GhostTownHallCoreRenderer::new);
        event.registerEntityRenderer(ModEntities.GHOST_TOWN_HALL_BELL.get(), GhostBlockDisplayRenderer::new);
        // Every ghost marker in this mod is a real Display.BlockDisplay entity now (2026-09-27, see
        // decisions.md -- stakes joined the wall/fence-post markers same day) -- one shared renderer
        // works for all of them.
        event.registerEntityRenderer(ModEntities.GHOST_PERIMETER_STAKE.get(), GhostBlockDisplayRenderer::new);
        event.registerEntityRenderer(ModEntities.GHOST_PLOT_STAKE.get(), GhostBlockDisplayRenderer::new);
        event.registerEntityRenderer(ModEntities.GHOST_BOUNDARY_WALL.get(), GhostBlockDisplayRenderer::new);
        event.registerEntityRenderer(ModEntities.GHOST_PLOT_WALL.get(), GhostBlockDisplayRenderer::new);
        event.registerEntityRenderer(ModEntities.GHOST_PERIMETER_FENCE_POST.get(), GhostBlockDisplayRenderer::new);
        event.registerEntityRenderer(ModEntities.GHOST_PLOT_FENCE_POST.get(), GhostBlockDisplayRenderer::new);
        event.registerEntityRenderer(ModEntities.GHOST_ROAD_ACCESS_FLAG.get(), GhostBlockDisplayRenderer::new);
        event.registerEntityRenderer(ModEntities.GHOST_ROAD_ACCESS_PREVIEW.get(), GhostBlockDisplayRenderer::new);
        // Roadways Milestone 1 (added 2026-10-06) -- same shared Display.BlockDisplay renderer as
        // every other ghost marker above. Missing this registration crashed the client outright the
        // instant a RoadwayStakeEntity was spawned/rendered (NPE: EntityRenderDispatcher.shouldRender
        // -- "renderer is null"), confirmed via the actual crash report, not assumed.
        event.registerEntityRenderer(ModEntities.ROADWAY_STAKE.get(), GhostBlockDisplayRenderer::new);
        // Connection markers (added 2026-10-06, "make it obvious which stakes are connected") --
        // same shared renderer, same ghost-torch block state as GHOST_ROAD_ACCESS_PREVIEW below.
        event.registerEntityRenderer(ModEntities.ROADWAY_CONNECTION_MARKER.get(), GhostBlockDisplayRenderer::new);
        event.registerEntityRenderer(ModEntities.HIGHWAY_STAKE.get(), GhostBlockDisplayRenderer::new);
        event.registerEntityRenderer(ModEntities.HIGHWAY_CONNECTION_MARKER.get(), GhostBlockDisplayRenderer::new);
        // RopeAnchorEntity never has a block state (see its own doc) -- reused purely so it has SOME
        // renderer registered; its only real visual contribution is the generic vanilla leash line,
        // which renders independently of this.
        event.registerEntityRenderer(ModEntities.ROPE_ANCHOR.get(), GhostBlockDisplayRenderer::new);
        event.registerEntityRenderer(ModEntities.GUARD.get(), GuardRenderer::new);
        // Reuses vanilla's own VillagerRenderer directly -- a real Villager subclass needs no
        // bespoke rendering (unlike GuardEntity, which isn't a Villager at all).
        event.registerEntityRenderer(ModEntities.RESIDENT_VILLAGER.get(), net.minecraft.client.renderer.entity.VillagerRenderer::new);
        // Same reasoning -- Lumberjack/Farmer worker NPCs (2026-10-05) are also plain Villager
        // subclasses, reusing vanilla's own renderer.
        event.registerEntityRenderer(ModEntities.LUMBERJACK_WORKER.get(), net.minecraft.client.renderer.entity.VillagerRenderer::new);
        event.registerEntityRenderer(ModEntities.FARMER_WORKER.get(), net.minecraft.client.renderer.entity.VillagerRenderer::new);
        event.registerEntityRenderer(ModEntities.MINER_WORKER.get(), net.minecraft.client.renderer.entity.VillagerRenderer::new);
        // Guard Kit Stand overhaul (added 2026-10-16) -- GuardKitStandEntity extends vanilla's own
        // ArmorStand directly (see its own doc), so reusing vanilla's own ArmorStandRenderer gives
        // genuinely identical visuals with zero new render code, same reasoning as the Villager
        // subclasses above.
        event.registerEntityRenderer(ModEntities.GUARD_KIT_STAND.get(), net.minecraft.client.renderer.entity.ArmorStandRenderer::new);
    }

    @SubscribeEvent
    static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.GUARDHOUSE_FOOD.get(), GuardhouseFoodScreen::new);
    }

    @SubscribeEvent
    static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "stake_distance_overlay"), new StakeDistanceOverlay());
        event.registerAboveAll(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "plot_validity_overlay"), new PlotValidityOverlay());
        event.registerAboveAll(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "shop_prompt_overlay"), new ShopPromptOverlay());
    }

    // Polls the zero-server-refs bridge (see ClientFoundingRequests' own doc) for a pending
    // screen-open request every client tick -- same pattern as Lyfe's LyfeModClient/
    // ClientWritingRequest, which exists specifically so the common-loaded SettlemyntsMod class
    // (registerPayloads runs on both sides) never itself references Screen/Minecraft.
    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        // Client-side proximity scan (2026-10-12) -- see ClientProximityScanner's own doc. Replaces
        // the old server-side PlotShopProximityTicker poll with a client-triggered one.
        ClientProximityScanner.tick();

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
        ClientPlotSignRequests.takePendingMenu().ifPresent(request -> {
            if (Minecraft.getInstance().screen == null) {
                Minecraft.getInstance().setScreen(new PlotConfigSignMenuScreen(request));
            }
        });
        ClientPlotSignRequests.takePendingPlotDetails().ifPresent(request -> {
            if (Minecraft.getInstance().screen == null) {
                Minecraft.getInstance().setScreen(new PlotDetailsScreen(request));
            }
        });
        ClientPlotSignRequests.takePendingPlotManagement().ifPresent(request -> {
            if (Minecraft.getInstance().screen == null) {
                Minecraft.getInstance().setScreen(new PlotManagementScreen(request));
            }
        });
        ClientFoundingRequests.takePendingDefenseSpendingScreen().ifPresent(request -> {
            if (Minecraft.getInstance().screen == null) {
                Minecraft.getInstance().setScreen(new com.github.cerealklla.settlemynts.founding.client.DefenseSpendingScreen(request));
            }
        });
        ClientGuardhouseRequests.takePendingMenu().ifPresent(request -> {
            if (Minecraft.getInstance().screen == null) {
                Minecraft.getInstance().setScreen(new GuardKitStandScreen(request));
            }
        });
        // "Select Kyt" button clicked on GuardKitStandScreen -- that same screen is still open
        // (waiting on this server round trip), so it's the parent SelectKytScreen returns to.
        ClientGuardhouseRequests.takePendingKytPicker().ifPresent(request -> {
            if (Minecraft.getInstance().screen instanceof GuardKitStandScreen current) {
                Minecraft.getInstance().setScreen(new SelectKytScreen(request, current));
            }
        });

        // "Press G to talk" (reworked 2026-10-16, NPC Menu overhaul) -- opens the unified NPC Menu
        // instead of jumping straight to the Shop; same "server resolves eligibility, client just
        // reacts to the last snapshot" shape the old prompt already used. ClientShopPromptState is
        // NPC-only now (see its own doc), so this cast is always safe.
        while (OPEN_NPC_MENU.consumeClick()) {
            if (Minecraft.getInstance().screen == null) {
                ClientShopPromptState.get().ifPresent(anchor -> {
                    if (anchor instanceof com.github.cerealklla.settlemynts.plotsign.ShopAnchor.Npc npcAnchor) {
                        send(new com.github.cerealklla.settlemynts.plotsign.RequestNpcMenuPayload(npcAnchor));
                    }
                });
            }
        }

        // "Auto-Drop" (2026-10-17) -- no screen to open, just fires the request straight to the server.
        while (AUTO_DROP.consumeClick()) {
            if (Minecraft.getInstance().screen == null) {
                send(new com.github.cerealklla.settlemynts.zone.RequestAutoDropPayload());
            }
        }

        ClientPlotSignRequests.takePendingUpgradePlotPreview().ifPresent(request -> {
            if (Minecraft.getInstance().screen == null) {
                Minecraft.getInstance().setScreen(new com.github.cerealklla.settlemynts.plotsign.client.UpgradePlotScreen(request));
            }
        });

        ClientPlotSignRequests.takePendingEstablishHighwayMap().ifPresent(request -> {
            if (Minecraft.getInstance().screen == null) {
                Minecraft.getInstance().setScreen(new com.github.cerealklla.settlemynts.plotsign.client.EstablishHighwayMapScreen(request));
            }
        });

        ClientPlotSignRequests.takePendingShop().ifPresent(request -> {
            if (Minecraft.getInstance().screen == null) {
                Minecraft.getInstance().setScreen(request.manage()
                        ? new com.github.cerealklla.settlemynts.plotsign.client.ManageShopScreen(request)
                        : new ShopScreen(request));
            }
        });

        ClientPlotSignRequests.takePendingPlannedInventory().ifPresent(request -> {
            if (Minecraft.getInstance().screen == null) {
                Minecraft.getInstance().setScreen(new com.github.cerealklla.settlemynts.plotsign.client.PlannedInventoryScreen(request));
            }
        });

        // Patches the currently-open Shop screen's gold totals in place after a Buy/Sell (real
        // report, 2026-10-10) -- deliberately NOT routed through the "open a screen" pattern above,
        // since every one of those is a no-op while a screen is already open, which is exactly the
        // case here (the Shop screen itself is what's open when this fires).
        ClientPlotSignRequests.takePendingGoldUpdate().ifPresent(update -> {
            if (Minecraft.getInstance().screen instanceof ShopScreen shopScreen) {
                shopScreen.updateGold(update.shopGoldNuggets(), update.playerGoldNuggets());
            }
        });

        // Same pattern as the gold-update patch above, for the "Shop Stock" column (real report,
        // 2026-10-10: "When buying/selling from a store the shop stock number is not changing") --
        // see ShopStockUpdatePayload's own doc.
        // Opens the WishlistScreen on "Press H to chat," or refreshes it in place if a "Sell" click
        // on that same screen just re-sent fresh data (same dual-purpose shape as the gold/stock
        // patches above vs. the "open a new screen" pattern everything else here uses).
        ClientPlotSignRequests.takePendingWishlist().ifPresent(update -> {
            if (Minecraft.getInstance().screen instanceof WishlistScreen wishlistScreen) {
                wishlistScreen.refresh(update);
            } else if (Minecraft.getInstance().screen instanceof com.github.cerealklla.settlemynts.plotsign.client.ChatMenuScreen chatMenuScreen) {
                chatMenuScreen.refresh(update);
            } else {
                // First "Press H" open, or just backed off any of the quest sub-screens
                // (QuestListScreen/QuestCompleteScreen/QuestAcceptScreen) after an Accept/Complete --
                // land back on the chat menu either way, see ChatMenuScreen's own doc.
                Minecraft.getInstance().setScreen(new com.github.cerealklla.settlemynts.plotsign.client.ChatMenuScreen(update));
            }
        });

        ClientPlotSignRequests.takePendingStockUpdate().ifPresent(update -> {
            if (Minecraft.getInstance().screen instanceof ShopScreen shopScreen) {
                shopScreen.updateListings(update.listings());
            }
        });

        // NPC Menu overhaul (2026-10-16) -- "G" (above) and a plain right-click both land here.
        ClientPlotSignRequests.takePendingNpcMenu().ifPresent(request -> {
            if (Minecraft.getInstance().screen == null) {
                Minecraft.getInstance().setScreen(new NpcMenuScreen(request));
            }
        });
    }

    private static void send(CustomPacketPayload payload) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
    }
}
