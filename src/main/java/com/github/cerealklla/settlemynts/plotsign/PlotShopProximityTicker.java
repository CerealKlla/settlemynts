package com.github.cerealklla.settlemynts.plotsign;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * "Walk within range of a shop-eligible Plot Config Sign, or a shop-eligible NPC" prompt (design
 * doc Section 14a, added 2026-10-05; Villager case added 2026-10-08, Part C of the "Natural
 * settlements..." plan). Every {@value #CHECK_INTERVAL_TICKS} ticks, scans a small radius around
 * each online player for whichever's nearest, and pushes a {@link PlotShopPromptPayload} to the
 * client -- but only when the nearest-found anchor changes, not every scan, so the client isn't
 * flooded with redundant packets while standing still next to one.
 *
 * <p><b>"Press G to open shop" works for both signs and NPCs; "Press H to chat" is NPC-only</b>
 * (corrected 2026-10-10 twice the same day -- first an overcorrection removed sign scanning
 * entirely, which silently broke "Press G" near a sign too; restored here, with the chat-specific
 * half of {@link PlotShopPromptPayload} gated to {@link ShopAnchor.Npc} instead). Explicit user
 * correction behind the NPC-only half: "the 'Chat' menu... is only supposed to be accessible from
 * the NPC itself." A sign's own right-click Main Menu (Enter Shop/Manage Shop) is a separate
 * interaction, untouched by any of this.
 *
 * <p>Mirrors {@code zone.PlotValidityOverlay}/{@code lyfe.minimap.MinimapTracker}'s general
 * "server resolves eligibility, client just renders/reacts to the last snapshot" shape -- the
 * client has no way to know a sign's/NPC's shop-eligibility on its own (that's derived from {@code
 * PlotRecord#zoneTypeId()}/{@code shopId()}, entirely server-side state).
 */
public final class PlotShopProximityTicker {

    private static final int CHECK_INTERVAL_TICKS = 10; // 2x/sec
    private static final int RADIUS_BLOCKS = 4;

    // Wider than RADIUS_BLOCKS on purpose -- the personal "!" marker (2026-10-11) is meant to be
    // spotted from across a plot/settlement, same rough visibility as a normal nameplate, not just
    // once a player is already standing in interaction range. Not tied to RADIUS_BLOCKS at all; the
    // "Press G/H" proximity trigger and this marker's own visibility are two independent concerns.
    private static final int PERSONAL_MARKER_RADIUS_BLOCKS = 48;

    private final Map<UUID, ShopAnchor> lastSent = new HashMap<>();
    private final Map<UUID, Set<Integer>> lastSentMarkers = new HashMap<>();

    // Real lag source found 2026-10-11 via [LagDiagnostics] ("PlotShopProximityTicker.onPlayerTick
    // took 109-121ms"): scanPersonalMarkers' PERSONAL_MARKER_RADIUS_BLOCKS (48, far wider than the
    // 4-block main scan) could sweep many NPCs at once, and anchorHasUrgentPersonalBusiness is a real
    // expensive call (walks into Myssions' getQuestGiverMenu, which iterates a player's entire quest
    // history and re-checks milestone completion, including real inventory scans for deliver-style
    // quests) -- run uncached, per NPC, per player, every CHECK_INTERVAL_TICKS (twice a second). This
    // is purely a cosmetic head-marker, which doesn't need sub-second freshness, so each (player, NPC)
    // pair's result is now cached for PERSONAL_BUSINESS_CACHE_TTL_TICKS instead of recomputed every
    // scan -- same "don't need perfect freshness, just don't recompute every tick" reasoning
    // ShopWishlistCache already applies to the plot-wishlist side of this same ticker.
    private static final long PERSONAL_BUSINESS_CACHE_TTL_TICKS = 60; // 3 seconds.
    private record BusinessKey(UUID playerId, int entityId) {
    }
    private record CachedBusiness(boolean hasUrgentBusiness, long expiresAtGameTime) {
    }
    private final Map<BusinessKey, CachedBusiness> personalBusinessCache = new HashMap<>();

    private boolean hasUrgentPersonalBusinessCached(ServerPlayer player, ServerLevel level, ShopAnchor.Npc anchor) {
        BusinessKey key = new BusinessKey(player.getUUID(), anchor.entityId());
        long now = level.getGameTime();
        CachedBusiness cached = personalBusinessCache.get(key);
        if (cached != null && cached.expiresAtGameTime() > now) {
            return cached.hasUrgentBusiness();
        }
        boolean result = SettlemyntsMod.anchorHasUrgentPersonalBusiness(player, level, anchor);
        personalBusinessCache.put(key, new CachedBusiness(result, now + PERSONAL_BUSINESS_CACHE_TTL_TICKS));
        return result;
    }

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
            // "Press H to chat" is NPC-only -- see this class's own doc. A sign anchor always
            // reports false here even if its own shop has a real wishlist deficit; "Press G to open
            // shop" (gated only on `nearest` being present at all, not on this flag) still works.
            boolean canChat = nearest instanceof ShopAnchor.Npc
                    && (SettlemyntsMod.anchorHasWishlist(level, nearest) || SettlemyntsMod.anchorHasPersonalQuestBusiness(player, level, nearest));
            PacketDistributor.sendToPlayer(player, new PlotShopPromptPayload(true, nearest, canChat));
        } else {
            lastSent.remove(playerId);
            PacketDistributor.sendToPlayer(player, new PlotShopPromptPayload(false, new ShopAnchor.Sign(BlockPos.ZERO), false));
        }

        scanPersonalMarkers(player, level, center);
    }

    /**
     * Separate, wider scan for the personal "!" head marker (2026-10-11) -- unlike everything else
     * in this ticker, not about "what's the single nearest interactable thing," but "every NPC within
     * normal nameplate-visibility range that this specific player personally has urgent business
     * with." Always sends the FULL current set on change (not a diff), matching {@link
     * PersonalQuestMarkerPayload}'s own doc -- simplest correct option, and this set is small (a
     * handful of entities per player at most).
     */
    private void scanPersonalMarkers(ServerPlayer player, ServerLevel level, BlockPos center) {
        AABB search = new AABB(center).inflate(PERSONAL_MARKER_RADIUS_BLOCKS);
        List<Villager> candidates = level.getEntitiesOfClass(Villager.class, search,
                v -> (v.getClass() == Villager.class && v.getPersistentData().contains(NaturalVillagePlotGenerator.PLOT_ID_TAG))
                        || (v instanceof PlotNpc npc && npc.plotId() != null));

        Set<Integer> marked = new java.util.HashSet<>();
        for (Villager npc : candidates) {
            ShopAnchor.Npc anchor = new ShopAnchor.Npc(npc.getId());
            if (hasUrgentPersonalBusinessCached(player, level, anchor)) {
                marked.add(npc.getId());
            }
        }

        UUID playerId = player.getUUID();
        Set<Integer> previous = lastSentMarkers.get(playerId);
        if (Objects.equals(previous, marked)) {
            return;
        }
        lastSentMarkers.put(playerId, marked);
        PacketDistributor.sendToPlayer(player, new PersonalQuestMarkerPayload(new ArrayList<>(marked)));
    }

    private static final net.minecraft.network.chat.Component WISHLIST_MARKER =
            net.minecraft.network.chat.Component.literal("?").withStyle(net.minecraft.ChatFormatting.GREEN, net.minecraft.ChatFormatting.BOLD);

    /**
     * Sets/clears a plain vanilla nameplate ("?") above an NPC whose plot currently has an unmet
     * Planned Inventory deficit OR an unclaimed quest board offer (2026-10-10, "if they have needs
     * they'd have a ! floating over their heads"; recolored/reglyphed green "?" 2026-10-11 -- the
     * user's own correction, freeing up "!" for the new personal marker below: "Currently that
     * results in a Golden ! and I now want a Green ?") -- reuses vanilla's own nametag rendering
     * entirely, no new client rendering code needed. Public (visible to every player, not just
     * whoever's asking), server-authoritative, computed only for NPCs this method is already
     * iterating for proximity detection -- no additional world scanning beyond what this ticker
     * already does every interval.
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
