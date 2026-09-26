package com.github.cerealklla.settlemynts;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import com.github.cerealklla.settlemynts.founding.ClientFoundingRequests;
import com.github.cerealklla.settlemynts.founding.GhostPerimeterStakeEntity;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.founding.GrantTownPlannerPayload;
import com.github.cerealklla.settlemynts.founding.OpenFoundingScreenPayload;
import com.github.cerealklla.settlemynts.founding.OpenStakeScreenPayload;
import com.github.cerealklla.settlemynts.founding.RemoveStakePayload;
import com.github.cerealklla.settlemynts.founding.RequestPerimeterStakePayload;
import com.github.cerealklla.settlemynts.founding.SetSettlementNamePayload;
import com.github.cerealklla.settlemynts.founding.SetStakeAbsolutePayload;
import com.github.cerealklla.settlemynts.registration.ModEntities;
import com.github.cerealklla.settlemynts.registration.ModItems;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

// The value here must match the modId entry in META-INF/neoforge.mods.toml (sourced from mod_id in gradle.properties)
@Mod(SettlemyntsMod.MODID)
public class SettlemyntsMod {
    public static final String MODID = "settlemynts";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SettlemyntsMod(IEventBus modEventBus, ModContainer modContainer) {
        ModItems.ITEMS.register(modEventBus);
        ModEntities.ENTITIES.register(modEventBus);

        NeoForge.EVENT_BUS.register(this);
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerPayloads);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Settlemynts common setup");
    }

    /** Payload registrations for the staking mechanic (design doc Section 6) -- see founding.* payload classes' own docs. */
    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");

        // Server-to-client: hand off to the client-only bridge, never touch Screen/Minecraft here
        // (this method runs on both sides -- see ClientFoundingRequests' own doc).
        registrar.playToClient(OpenFoundingScreenPayload.TYPE, OpenFoundingScreenPayload.STREAM_CODEC,
                (payload, context) -> ClientFoundingRequests.requestFoundingScreen(payload));
        registrar.playToClient(OpenStakeScreenPayload.TYPE, OpenStakeScreenPayload.STREAM_CODEC,
                (payload, context) -> ClientFoundingRequests.requestStakeScreen(payload));

        registrar.playToServer(SetSettlementNamePayload.TYPE, SetSettlementNamePayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player
                            && player.level().getEntity(payload.coreEntityId()) instanceof GhostTownHallCoreEntity core
                            && core.isTownPlanner(player.getUUID())) {
                        core.setSettlementName(payload.name());
                    }
                });

        registrar.playToServer(GrantTownPlannerPayload.TYPE, GrantTownPlannerPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (!(context.player() instanceof ServerPlayer player)
                            || !(player.level().getEntity(payload.coreEntityId()) instanceof GhostTownHallCoreEntity core)) {
                        return;
                    }
                    ServerPlayer target = player.level().getServer().getPlayerList().getPlayerByName(payload.targetPlayerName());
                    if (target == null) {
                        player.sendSystemMessage(Component.literal(
                                "No online player named \"" + payload.targetPlayerName() + "\" (granting an offline player isn't supported yet)."));
                        return;
                    }
                    if (!core.grantTownPlanner(player.getUUID(), target.getUUID())) {
                        player.sendSystemMessage(Component.literal(
                                "Only the settlement's founder can grant Town Planner permission."));
                    }
                });

        registrar.playToServer(RequestPerimeterStakePayload.TYPE, RequestPerimeterStakePayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player
                            && player.level().getEntity(payload.coreEntityId()) instanceof GhostTownHallCoreEntity core
                            && core.isTownPlanner(player.getUUID())) {
                        ItemStack stake = new ItemStack(ModItems.PLANNED_PERIMETER_STAKE.get());
                        if (!player.getInventory().add(stake)) {
                            player.drop(stake, false);
                        }
                    }
                });

        registrar.playToServer(SetStakeAbsolutePayload.TYPE, SetStakeAbsolutePayload.STREAM_CODEC,
                (payload, context) -> {
                    if (!(context.player() instanceof ServerPlayer player)
                            || !(player.level() instanceof ServerLevel serverLevel)
                            || !(serverLevel.getEntity(payload.stakeEntityId()) instanceof GhostPerimeterStakeEntity stake)) {
                        return;
                    }
                    GhostTownHallCoreEntity core = ownerCoreOf(serverLevel, stake);
                    if (core == null || !core.isTownPlanner(player.getUUID())) {
                        return;
                    }
                    if (payload.absolute()) {
                        long absoluteCount = GhostPerimeterStakeEntity.findByOwnerCore(serverLevel, stake.getOwnerCoreId()).stream()
                                .filter(GhostPerimeterStakeEntity::isAbsolute).count();
                        if (absoluteCount >= GhostPerimeterStakeEntity.MAX_ABSOLUTE_STAKES) {
                            player.sendSystemMessage(Component.literal(
                                    "Already at the maximum of " + GhostPerimeterStakeEntity.MAX_ABSOLUTE_STAKES + " absolute stakes."));
                            return;
                        }
                    }
                    stake.setAbsolute(payload.absolute());
                });

        registrar.playToServer(RemoveStakePayload.TYPE, RemoveStakePayload.STREAM_CODEC,
                (payload, context) -> {
                    if (!(context.player() instanceof ServerPlayer player)
                            || !(player.level() instanceof ServerLevel serverLevel)
                            || !(serverLevel.getEntity(payload.stakeEntityId()) instanceof GhostPerimeterStakeEntity stake)) {
                        return;
                    }
                    GhostTownHallCoreEntity core = ownerCoreOf(serverLevel, stake);
                    if (core != null && core.isTownPlanner(player.getUUID())) {
                        stake.removeAndReturnItem(player);
                    }
                });
    }

    private static GhostTownHallCoreEntity ownerCoreOf(ServerLevel level, GhostPerimeterStakeEntity stake) {
        if (stake.getOwnerCoreId() == null) {
            return null;
        }
        Entity entity = level.getEntity(stake.getOwnerCoreId());
        return entity instanceof GhostTownHallCoreEntity core ? core : null;
    }

    /**
     * DEBUG ONLY -- grants a testing flag on every login, no real crafting recipe exists yet
     * (design doc Section 5 calls for one; not built this milestone). Same pattern/caveats as
     * Lyfe's own debug item grant (LyfeMod#onPlayerLoggedIn) -- must be removed or gated behind a
     * real debug flag before any actual release.
     */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) {
            return;
        }
        player.addItem(new ItemStack(ModItems.SETTLEMENT_CLAIM_FLAG.get(), 4));
        LOGGER.info("Granted debug Settlement Claim Flag(s) to {}", player.getName().getString());
    }
}
