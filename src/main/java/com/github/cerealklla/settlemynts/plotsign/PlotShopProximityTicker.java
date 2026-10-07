package com.github.cerealklla.settlemynts.plotsign;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * "Walk within 4 blocks of a Plot Sign" shop prompt (design doc Section 14a, added 2026-10-05,
 * explicit user request). Every {@value #CHECK_INTERVAL_TICKS} ticks, scans a 4-block-radius sphere
 * around each online player for a {@link PlotConfigSignBlockEntity} whose plot has a real (non-
 * Guardhouse) shop (see {@link PlotConfigSignBlock#hasShop}), and pushes a {@link
 * PlotShopPromptPayload} to the client -- but only when the nearest-found sign changes, not every
 * scan, so the client isn't flooded with redundant packets while standing still next to one.
 *
 * <p>Mirrors {@code zone.PlotValidityOverlay}/{@code lyfe.minimap.MinimapTracker}'s general
 * "server resolves eligibility, client just renders/reacts to the last snapshot" shape -- the
 * client has no way to know a sign's shop-eligibility on its own (that's derived from {@code
 * PlotRecord#zoneTypeId()} via the settlement core, entirely server-side state).
 */
public final class PlotShopProximityTicker {

    private static final int CHECK_INTERVAL_TICKS = 10; // 2x/sec
    private static final int RADIUS_BLOCKS = 4;

    private final Map<UUID, BlockPos> lastSentSignPos = new HashMap<>();

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (player.tickCount % CHECK_INTERVAL_TICKS != 0) {
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        BlockPos center = player.blockPosition();
        double radiusSq = (double) RADIUS_BLOCKS * RADIUS_BLOCKS;

        BlockPos nearestShopSign = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-RADIUS_BLOCKS, -RADIUS_BLOCKS, -RADIUS_BLOCKS),
                center.offset(RADIUS_BLOCKS, RADIUS_BLOCKS, RADIUS_BLOCKS))) {
            double distSq = pos.distSqr(center);
            if (distSq > radiusSq || distSq >= nearestDistSq) {
                continue;
            }
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (!(blockEntity instanceof PlotConfigSignBlockEntity sign) || !PlotConfigSignBlock.hasShop(level, sign)) {
                continue;
            }
            nearestDistSq = distSq;
            nearestShopSign = pos.immutable();
        }

        UUID playerId = player.getUUID();
        BlockPos previous = lastSentSignPos.get(playerId);
        if (java.util.Objects.equals(previous, nearestShopSign)) {
            return;
        }
        if (nearestShopSign != null) {
            lastSentSignPos.put(playerId, nearestShopSign);
            PacketDistributor.sendToPlayer(player, new PlotShopPromptPayload(true, nearestShopSign));
        } else {
            lastSentSignPos.remove(playerId);
            PacketDistributor.sendToPlayer(player, new PlotShopPromptPayload(false, BlockPos.ZERO));
        }
    }
}
