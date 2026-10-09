package com.github.cerealklla.settlemynts.plotsign;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.github.cerealklla.settlemynts.zone.NaturalVillagePlotGenerator;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * "Walk within range of a shop-eligible Plot Config Sign, or a natural village's own trading
 * Villager" prompt (design doc Section 14a, added 2026-10-05; Villager case added 2026-10-08, Part C
 * of the "Natural settlements..." plan). Every {@value #CHECK_INTERVAL_TICKS} ticks, scans a small
 * radius around each online player for whichever's nearest, and pushes a {@link
 * PlotShopPromptPayload} to the client -- but only when the nearest-found anchor changes, not every
 * scan, so the client isn't flooded with redundant packets while standing still next to one.
 *
 * <p>Mirrors {@code zone.PlotValidityOverlay}/{@code lyfe.minimap.MinimapTracker}'s general
 * "server resolves eligibility, client just renders/reacts to the last snapshot" shape -- the
 * client has no way to know a sign's (or villager's) shop-eligibility on its own (that's derived
 * from {@code PlotRecord#zoneTypeId()}/{@code shopId()}, entirely server-side state).
 */
public final class PlotShopProximityTicker {

    private static final int CHECK_INTERVAL_TICKS = 10; // 2x/sec
    private static final int RADIUS_BLOCKS = 4;

    private final Map<UUID, ShopAnchor> lastSent = new HashMap<>();

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

        ShopAnchor nearest = null;
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
            nearest = new ShopAnchor.Sign(pos.immutable());
        }

        AABB villagerSearch = new AABB(center).inflate(RADIUS_BLOCKS);
        for (Villager villager : level.getEntitiesOfClass(Villager.class, villagerSearch,
                v -> v.getClass() == Villager.class && v.getPersistentData().contains(NaturalVillagePlotGenerator.PLOT_ID_TAG))) {
            double distSq = villager.distanceToSqr(player);
            if (distSq > radiusSq || distSq >= nearestDistSq) {
                continue;
            }
            nearestDistSq = distSq;
            nearest = new ShopAnchor.Npc(villager.getId());
        }

        UUID playerId = player.getUUID();
        ShopAnchor previous = lastSent.get(playerId);
        if (Objects.equals(previous, nearest)) {
            return;
        }
        if (nearest != null) {
            lastSent.put(playerId, nearest);
            PacketDistributor.sendToPlayer(player, new PlotShopPromptPayload(true, nearest));
        } else {
            lastSent.remove(playerId);
            PacketDistributor.sendToPlayer(player, new PlotShopPromptPayload(false, new ShopAnchor.Sign(BlockPos.ZERO)));
        }
    }
}
