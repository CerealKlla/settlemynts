package com.github.cerealklla.settlemynts.guardhouse;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.bridge.KytGuardhouseBridge;

import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.ChestMenu;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;

/**
 * Real-time replacement for {@code GuardSpawnTicker}'s old "top up already-alive guards" poll,
 * 2026-10-22 -- explicit user correction: "Guard equip rescan should not be a tick, There is an
 * event for gear being added to the box, use that." There's still no generic "a block container's
 * contents changed" event anywhere in this suite (confirmed before writing this -- see decisions.md
 * for the search), but this mod already has an established substitute for exactly this shape of
 * problem: {@code construction.FootprintSlabGuard} (Blueprynts), {@code currency.CoinPurseListener}
 * (Yconomics), and {@code merchant.MerchantListener} (Lyfe) all react to {@link
 * PlayerContainerEvent.Close} as "the player just finished interacting with a container" instead of
 * polling for a change. This is that same pattern, applied here for the first time.
 *
 * <p>Approximates "which plot" from the closing player's own position (the same approximation
 * {@code AutoDropper} already relies on for "deposit into the boxes of the plot you're standing on")
 * rather than resolving the exact chest's own position against each box's registered location --
 * a player who just closed a chest is standing right next to it, so this is accurate in every real
 * case, and avoids needing a new position-to-container-menu mapping that doesn't exist anywhere
 * else in this mod either. Restricted to {@link ChestMenu} (chest/barrel/shulker/ender chest -- the
 * real container types a plot's own boxes are, see {@code zone.ContainerDeposit}'s own doc) so
 * closing one of this mod's own custom screens (Shop Cart, Guardhouse Food Stores, a Kyt editor,
 * etc. -- none of which are "a plot box") never triggers a spurious rescan.
 */
public final class GuardEquipBoxChangeListener {

    @SubscribeEvent
    public void onContainerClose(PlayerContainerEvent.Close event) {
        if (!(event.getContainer() instanceof ChestMenu) || !KytGuardhouseBridge.isLoaded()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        Settlemynts.findPlotAt(level, player.blockPosition())
                .ifPresent(plot -> refreshGuardsOnPlot(level, plot.plotId(), plot.polygon()));
    }

    private void refreshGuardsOnPlot(ServerLevel level, UUID plotId, Geometry.Polygon polygon) {
        List<Container> plotBoxes = Settlemynts.resolveBoxesForPolygon(level, polygon);
        for (Map.Entry<UUID, GlobalPos> entry : GuardKitStandIndex.get(level.getServer()).all().entrySet()) {
            GlobalPos pos = entry.getValue();
            if (!pos.dimension().equals(level.dimension())
                    || !(level.getEntity(entry.getKey()) instanceof GuardKitStandEntity stand)
                    || !plotId.equals(stand.plotId())) {
                continue;
            }
            Optional<UUID> guardId = stand.assignedGuardId();
            if (guardId.isEmpty() || stand.boundKytName().isEmpty()
                    || !(level.getEntity(guardId.get()) instanceof GuardEntity guard)) {
                continue;
            }
            // Deliberately not a lambda/method-reference here -- see this class's own doc for why:
            // a lambda capturing a LoadoutRecord-typed value compiles to a synthetic method with
            // that type in its signature, which this file's own real incident proved gets eagerly
            // resolved (NoClassDefFoundError on a Kyt-less classpath) the moment this unconditionally
            // registered class's methods are reflected over -- the exact "client-only type" class of
            // bug the root project's own CLAUDE.md documents, just reached via a lambda instead of a
            // field. Plain imperative local-variable assignment, matching GuardSpawnTicker's own
            // established style exactly, has no such synthetic method and is safe.
            Optional<com.github.cerealklla.kyt.loadout.LoadoutRecord> record = KytGuardhouseBridge.getKyt(stand.boundKytName().get());
            if (record.isPresent()) {
                guard.equipMissingFromLoadoutUsingBoxes(record.get(), plotBoxes);
            }
        }
    }
}
