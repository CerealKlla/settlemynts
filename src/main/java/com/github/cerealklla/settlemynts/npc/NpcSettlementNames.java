package com.github.cerealklla.settlemynts.npc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.guardhouse.GuardKitStandEntity;
import com.github.cerealklla.settlemynts.guardhouse.GuardKitStandIndex;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Real, live resolution of "every NPC name already used in this settlement" -- replaces the old
 * Audyble-owned {@code namesInSettlement}, which filtered a cached {@code settlementPlaceId} field
 * that (confirmed, 2026-11-05) the real live conversation call site never actually populated, so the
 * uniqueness check it fed never worked at all. Resolved live instead, straight from this settlement's
 * own authoritative data -- {@code core.getPlots()} is already an in-memory list on the live core
 * entity (no chunk/world scan needed) for the 4 worker roles; {@link NpcRole#GUARD} needs a small
 * best-effort scan of every known Guard Kit Stand (server-wide, but realistically small -- one entry
 * per garrison slot) since nothing indexes "which stands belong to which plot" directly, matching the
 * same chunk-load-dependent-best-effort standard every other Guard Kit Stand scan in this mod already
 * has (e.g. {@code guardhouse.GuardSpawnTicker}).
 */
public final class NpcSettlementNames {

    private NpcSettlementNames() {
    }

    /** Every real generated name already used in {@code core}'s settlement, living or dead -- used at birth time so a new NPC's name doesn't repeat one already in the same town. Deliberately includes deceased identities too; reusing a dead character's name is just as much a repeat. */
    public static List<String> inSettlement(ServerLevel level, GhostTownHallCoreEntity core) {
        return identitiesInSettlement(level, core).stream().map(NpcIdentity::name).toList();
    }

    /**
     * Same resolution as {@link #inSettlement}, but returning the full {@link NpcIdentity} records
     * (not just names) -- added 2026-11-05 for {@code debug.DebugCommands#dedupNpcNames}, a one-time
     * admin fix for a real report ("there are a lot of duplicated NPC names in starterville"): data
     * born under the pre-2026-11-05 Audyble-owned identity system never actually had a working
     * uniqueness check (see {@link NpcIdentityStore}'s own class doc), so several already-persisted
     * names collide within the same settlement -- this live resolution is reused here to find exactly
     * which ones, the same way it already finds them at birth time to avoid a *new* collision.
     */
    public static List<NpcIdentity> identitiesInSettlement(ServerLevel level, GhostTownHallCoreEntity core) {
        List<NpcIdentity> identities = new ArrayList<>();
        for (PlotRecord plot : core.getPlots()) {
            for (NpcRole role : List.of(NpcRole.FARMER, NpcRole.LUMBERJACK, NpcRole.MINER, NpcRole.RESIDENT)) {
                NpcIdentityStore.find(new NpcIdentityKey(plot.plotId(), role)).ifPresent(identities::add);
            }
        }
        Set<java.util.UUID> plotIds = core.getPlots().stream().map(PlotRecord::plotId).collect(Collectors.toSet());
        for (Map.Entry<java.util.UUID, GlobalPos> entry : GuardKitStandIndex.get(level.getServer()).all().entrySet()) {
            GlobalPos pos = entry.getValue();
            ServerLevel standLevel = level.getServer().getLevel(pos.dimension());
            if (standLevel == null || !standLevel.isLoaded(pos.pos())) {
                continue; // Best-effort -- an unloaded stand's own plot can't be checked right now, same limitation every other Guard Kit Stand scan in this mod already has.
            }
            if (standLevel.getEntity(entry.getKey()) instanceof GuardKitStandEntity stand && plotIds.contains(stand.plotId())) {
                NpcIdentityStore.find(new NpcIdentityKey(entry.getKey(), NpcRole.GUARD)).ifPresent(identities::add);
            }
        }
        return identities;
    }
}
