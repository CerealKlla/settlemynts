package com.github.cerealklla.settlemynts.plotsign;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.zone.NaturalVillagePlotGenerator;
import com.github.cerealklla.settlemynts.zone.PlotNpc;

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
        // Temporary diagnostic (2026-10-10, see diagnostics.LagDiagnostics' own doc) -- remove once
        // the real lag cause is confirmed.
        long diagStart = System.nanoTime();
        try {
            onPlayerTickTimed(player);
        } finally {
            long diagElapsedMs = (System.nanoTime() - diagStart) / 1_000_000L;
            if (diagElapsedMs >= 5) {
                SettlemyntsMod.LOGGER.info("[LagDiagnostics] PlotShopProximityTicker.onPlayerTick took {}ms", diagElapsedMs);
            }
        }
    }

    private void onPlayerTickTimed(ServerPlayer player) {
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
            ShopAnchor.Npc candidate = new ShopAnchor.Npc(villager.getId());
            updateWishlistNameplate(level, villager, candidate);
            if (distSq > radiusSq || distSq >= nearestDistSq) {
                continue;
            }
            if (!SettlemyntsMod.npcHasOpenableShop(level, candidate)) {
                continue; // Tagged, but its plot has no Shop (or doesn't resolve) -- not worth prompting for.
            }
            nearestDistSq = distSq;
            nearest = candidate;
        }

        // Player settlements' own NPC-plot workers (2026-10-09, explicit request: "I'd like the NPCs
        // in player Settlements to grant players access to their shop the same way they do in
        // natural settlements") -- same shape as the natural-village Villager scan above, just keyed
        // off the shared PlotNpc marker (resident.ResidentVillagerEntity, farm.FarmerWorkerEntity,
        // lumberyard.LumberjackWorkerEntity) instead of a persistent-data tag.
        //
        // Real report, 2026-10-09: a plain ResidentVillagerEntity (the ambient decorative NPC on an
        // NPC-owned, shopless plot like Private Residence) has a real plotId, so it passed the old
        // "has a plot" check and still got offered a "Press G to open shop" prompt that dead-ended --
        // npcHasOpenableShop below is the real, narrower condition both scans need.
        for (Villager worker : level.getEntitiesOfClass(Villager.class, villagerSearch,
                v -> v instanceof PlotNpc npc && npc.plotId() != null)) {
            double distSq = worker.distanceToSqr(player);
            ShopAnchor.Npc candidate = new ShopAnchor.Npc(worker.getId());
            updateWishlistNameplate(level, worker, candidate);
            if (distSq > radiusSq || distSq >= nearestDistSq) {
                continue;
            }
            if (!SettlemyntsMod.npcHasOpenableShop(level, candidate)) {
                continue;
            }
            nearestDistSq = distSq;
            nearest = candidate;
        }

        UUID playerId = player.getUUID();
        ShopAnchor previous = lastSent.get(playerId);
        if (Objects.equals(previous, nearest)) {
            return;
        }
        if (nearest != null) {
            lastSent.put(playerId, nearest);
            boolean hasWishlist = SettlemyntsMod.anchorHasWishlist(level, nearest);
            PacketDistributor.sendToPlayer(player, new PlotShopPromptPayload(true, nearest, hasWishlist));
        } else {
            lastSent.remove(playerId);
            PacketDistributor.sendToPlayer(player, new PlotShopPromptPayload(false, new ShopAnchor.Sign(BlockPos.ZERO), false));
        }
    }

    private static final net.minecraft.network.chat.Component WISHLIST_MARKER =
            net.minecraft.network.chat.Component.literal("!").withStyle(net.minecraft.ChatFormatting.YELLOW, net.minecraft.ChatFormatting.BOLD);

    /**
     * Sets/clears a plain vanilla nameplate ("!") above an NPC whose plot currently has an unmet
     * Planned Inventory deficit (2026-10-10, "if they have needs they'd have a ! floating over their
     * heads") -- reuses vanilla's own nametag rendering entirely, no new client rendering code
     * needed. Public (visible to every player, not just whoever's asking), server-authoritative,
     * computed only for NPCs this method is already iterating for proximity detection -- no
     * additional world scanning beyond what this ticker already does every interval.
     */
    private static void updateWishlistNameplate(ServerLevel level, Villager npc, ShopAnchor.Npc anchor) {
        boolean hasWishlist = SettlemyntsMod.anchorHasWishlist(level, anchor);
        if (hasWishlist) {
            if (!WISHLIST_MARKER.equals(npc.getCustomName())) {
                npc.setCustomName(WISHLIST_MARKER);
                npc.setCustomNameVisible(true);
            }
        } else if (npc.getCustomName() != null) {
            npc.setCustomName(null);
            npc.setCustomNameVisible(false);
        }
    }
}
