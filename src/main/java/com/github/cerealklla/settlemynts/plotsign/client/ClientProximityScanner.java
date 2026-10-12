package com.github.cerealklla.settlemynts.plotsign.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.github.cerealklla.settlemynts.plotsign.PlotConfigSignBlock;
import com.github.cerealklla.settlemynts.plotsign.PlotShopProximityTicker;
import com.github.cerealklla.settlemynts.plotsign.ProximityScanPayload;
import com.github.cerealklla.settlemynts.plotsign.ShopAnchor;
import com.github.cerealklla.settlemynts.zone.PlotNpc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;

/**
 * Client-side half of the 2026-10-12 proximity rework (see {@code PlotShopProximityTicker}'s own
 * doc for the full "why") -- does the actual "what's near me" scanning that used to run server-side
 * every 10 ticks for every online player, regardless of whether anything was nearby or had changed.
 *
 * <p>This is genuinely cheap to run on the client: {@link ClientLevel#getEntitiesOfClass} and
 * {@link ClientLevel#getBlockState} only ever touch entities/chunks the client already has loaded
 * for rendering -- nothing here fetches anything new over the network. Only the *result* (a small
 * candidate list, sent only when it changes) crosses the wire, replacing a fixed-rate server-side
 * poll with an event-driven trigger from the one machine that was already doing equivalent work for
 * free.
 *
 * <p>Deliberately over-inclusive on both candidate lists -- this class has no way to know which
 * Villagers are actually plot-tagged, shop-eligible, or have a real quest for this specific player
 * (all server-only state, see {@code PlotShopProximityTicker}'s own doc on why that can't move
 * client-side); it reports every plausible candidate within range and lets the server's own
 * re-validation in {@code PlotShopProximityTicker#handleProximityScan} filter it down.
 *
 * <p>The heartbeat ({@link #HEARTBEAT_INTERVAL_TICKS}) exists so server-side state that depends on
 * "this candidate set didn't change" -- the public wishlist nameplate, the personal business cache
 * -- can't go stale forever while a player stands still near something whose real eligibility
 * changes for an unrelated reason (e.g. another player clears a board offer). Skipped entirely while
 * both candidate lists are empty, since there's no server-side state riding on an empty set that
 * could go stale -- the overwhelmingly common case (most players, most of the time, aren't near a
 * plot NPC or sign at all) costs nothing beyond the local scan itself.
 */
public final class ClientProximityScanner {

    // Client-side only -- cheap by construction (see class doc), so a short interval costs nothing
    // meaningful; this exists purely to avoid literally every single client tick doing the scan.
    private static final int SCAN_INTERVAL_TICKS = 5;
    private static final int HEARTBEAT_INTERVAL_TICKS = 100; // 5s.

    private static List<ShopAnchor> lastSentNear = List.of();
    private static List<Integer> lastSentFar = List.of();
    private static int tickCounter = 0;
    private static int ticksSinceLastSend = 0;

    private ClientProximityScanner() {
    }

    public static void tick() {
        tickCounter++;
        if (tickCounter % SCAN_INTERVAL_TICKS != 0) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.getConnection() == null) {
            return;
        }

        int nearRadius = PlotShopProximityTicker.RADIUS_BLOCKS;
        int farRadius = PlotShopProximityTicker.PERSONAL_MARKER_RADIUS_BLOCKS;
        double nearRadiusSq = (double) nearRadius * nearRadius;
        BlockPos center = player.blockPosition();

        List<ShopAnchor> near = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-nearRadius, -nearRadius, -nearRadius),
                center.offset(nearRadius, nearRadius, nearRadius))) {
            if (level.getBlockState(pos).getBlock() instanceof PlotConfigSignBlock) {
                near.add(new ShopAnchor.Sign(pos.immutable()));
            }
        }

        List<Integer> far = new ArrayList<>();
        AABB farSearch = new AABB(center).inflate(farRadius);
        for (Villager villager : level.getEntitiesOfClass(Villager.class, farSearch,
                v -> v.getClass() == Villager.class || v instanceof PlotNpc)) {
            far.add(villager.getId());
            if (villager.distanceToSqr(player) <= nearRadiusSq) {
                near.add(new ShopAnchor.Npc(villager.getId()));
            }
        }

        ticksSinceLastSend += SCAN_INTERVAL_TICKS;
        boolean changed = !sameElements(near, lastSentNear) || !sameElements(far, lastSentFar);
        boolean bothEmpty = near.isEmpty() && far.isEmpty();
        if (!changed && (bothEmpty || ticksSinceLastSend < HEARTBEAT_INTERVAL_TICKS)) {
            return;
        }

        lastSentNear = near;
        lastSentFar = far;
        ticksSinceLastSend = 0;
        mc.getConnection().send(new ServerboundCustomPayloadPacket(new ProximityScanPayload(near, far)));
    }

    private static boolean sameElements(List<?> a, List<?> b) {
        return new HashSet<>(a).equals(new HashSet<>(b));
    }
}
