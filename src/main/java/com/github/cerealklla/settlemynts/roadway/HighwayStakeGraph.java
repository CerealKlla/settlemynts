package com.github.cerealklla.settlemynts.roadway;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import com.github.cerealklla.cartographyr.geo.EntityId;
import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Save-wide, entity-liveness-independent record of every placed Highway Stake's position and
 * connections, keyed by the stake's own entity UUID -- added 2026-10-13 after a real live report
 * ("I placed every single stake, and they are all connected") that {@link HighwayQuestSupport}'s
 * progress check flatly contradicted. Root cause: {@code HighwayStakeEntity#findByInstanceId} scans
 * {@code Level#getEntities}, which only ever returns entities in currently LOADED chunks -- for a
 * road spanning more than a player's own render distance, the full placed-and-connected chain is
 * never simultaneously loaded, so a live-entity scan can structurally never confirm the whole path
 * exists at once, regardless of how anchor-slack is tuned (a dead end chased first, see
 * decisions.md). This index is written through at every real mutation ({@link
 * HighwayStakeEntity#create}, a paved connection, a snap, a pickup) so a completion check -- or a
 * debug listing -- can walk the real graph with no dependency on where anyone is currently standing.
 */
public final class HighwayStakeGraph extends SavedData {

    public static final SavedDataType<HighwayStakeGraph> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "highway_stake_graph"),
            HighwayStakeGraph::new,
            codec());

    public record Connection(UUID otherStakeId, EntityId roadEntityId) {
        static final Codec<Connection> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("other_stake_id").forGetter(Connection::otherStakeId),
                EntityId.CODEC.fieldOf("road_entity_id").forGetter(Connection::roadEntityId)
        ).apply(i, Connection::new));
    }

    public record StakeRecord(UUID stakeId, UUID instanceId, BlockPos pos, List<Connection> connections) {
        static final Codec<StakeRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("stake_id").forGetter(StakeRecord::stakeId),
                UUIDUtil.CODEC.fieldOf("instance_id").forGetter(StakeRecord::instanceId),
                BlockPos.CODEC.fieldOf("pos").forGetter(StakeRecord::pos),
                Connection.CODEC.listOf().fieldOf("connections").forGetter(StakeRecord::connections)
        ).apply(i, StakeRecord::new));

        StakeRecord withConnections(List<Connection> newConnections) {
            return new StakeRecord(stakeId, instanceId, pos, newConnections);
        }
    }

    private final Map<UUID, StakeRecord> stakes;
    // Instance ids whose quest ended (abandoned/expired/failed) while some of their stakes were in
    // unloaded chunks -- see #markPendingCleanup's own doc for why this has to be persisted rather
    // than handled synchronously at despawn time.
    private final Set<UUID> pendingCleanupInstanceIds;
    // Instance ids whose quest COMPLETED (the road connected) while some of their stakes were in
    // unloaded chunks -- deliberately a separate set from pendingCleanupInstanceIds, see
    // #markPendingHide's own doc for why reusing the abandon-path's terrain-reverting cleanup here
    // would be a real bug (it would delete a player's finished road).
    private final Set<UUID> pendingHideInstanceIds;

    HighwayStakeGraph() {
        this(new HashMap<>(), new HashSet<>(), new HashSet<>());
    }

    private HighwayStakeGraph(Map<UUID, StakeRecord> stakes, Set<UUID> pendingCleanupInstanceIds, Set<UUID> pendingHideInstanceIds) {
        this.stakes = stakes;
        this.pendingCleanupInstanceIds = pendingCleanupInstanceIds;
        this.pendingHideInstanceIds = pendingHideInstanceIds;
    }

    private record PersistedState(List<StakeRecord> stakes, List<UUID> pendingCleanupInstanceIds, List<UUID> pendingHideInstanceIds) {
        static final Codec<PersistedState> CODEC = RecordCodecBuilder.create(i -> i.group(
                StakeRecord.CODEC.listOf().fieldOf("stakes").forGetter(PersistedState::stakes),
                UUIDUtil.CODEC.listOf().optionalFieldOf("pending_cleanup_instance_ids", List.of())
                        .forGetter(PersistedState::pendingCleanupInstanceIds),
                UUIDUtil.CODEC.listOf().optionalFieldOf("pending_hide_instance_ids", List.of())
                        .forGetter(PersistedState::pendingHideInstanceIds)
        ).apply(i, PersistedState::new));
    }

    private static Codec<HighwayStakeGraph> codec() {
        return PersistedState.CODEC.xmap(
                state -> {
                    Map<UUID, StakeRecord> map = new HashMap<>();
                    for (StakeRecord entry : state.stakes()) {
                        map.put(entry.stakeId(), entry);
                    }
                    return new HighwayStakeGraph(map, new HashSet<>(state.pendingCleanupInstanceIds()), new HashSet<>(state.pendingHideInstanceIds()));
                },
                data -> new PersistedState(List.copyOf(data.stakes.values()), List.copyOf(data.pendingCleanupInstanceIds), List.copyOf(data.pendingHideInstanceIds)));
    }

    public static HighwayStakeGraph get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    /**
     * Inserts a brand-new stake, or (deliberately never clobbering already-known connections)
     * refreshes an existing one's position -- the same call is used both at real placement time and
     * as a self-healing backfill (see {@link HighwayStakeEntity#onAddedToLevel}) for a stake that was
     * placed before this graph existed at all, so it must never wipe connection data an older stake
     * might already have re-asserted.
     */
    public void putStake(UUID stakeId, UUID instanceId, BlockPos pos) {
        StakeRecord existing = stakes.get(stakeId);
        if (existing == null) {
            stakes.put(stakeId, new StakeRecord(stakeId, instanceId, pos, List.of()));
            setDirty();
        } else if (!existing.pos().equals(pos)) {
            stakes.put(stakeId, new StakeRecord(stakeId, instanceId, pos, existing.connections()));
            setDirty();
        }
    }

    public void setConnection(UUID stakeId, UUID otherStakeId, EntityId roadEntityId) {
        StakeRecord record = stakes.get(stakeId);
        if (record == null) {
            return;
        }
        List<Connection> current = new ArrayList<>();
        for (Connection existing : record.connections()) {
            if (!existing.otherStakeId().equals(otherStakeId)) {
                current.add(existing);
            }
        }
        current.add(new Connection(otherStakeId, roadEntityId));
        stakes.put(stakeId, record.withConnections(current));
        setDirty();
    }

    public void removeConnection(UUID stakeId, UUID otherStakeId) {
        StakeRecord record = stakes.get(stakeId);
        if (record == null) {
            return;
        }
        List<Connection> current = new ArrayList<>();
        for (Connection existing : record.connections()) {
            if (!existing.otherStakeId().equals(otherStakeId)) {
                current.add(existing);
            }
        }
        stakes.put(stakeId, record.withConnections(current));
        setDirty();
    }

    public void clearConnections(UUID stakeId) {
        StakeRecord record = stakes.get(stakeId);
        if (record == null) {
            return;
        }
        stakes.put(stakeId, record.withConnections(List.of()));
        setDirty();
    }

    public void removeStake(UUID stakeId) {
        if (stakes.remove(stakeId) != null) {
            setDirty();
        }
    }

    public void removeAllForInstance(UUID instanceId) {
        if (stakes.values().removeIf(r -> r.instanceId().equals(instanceId))) {
            setDirty();
        }
        if (pendingCleanupInstanceIds.remove(instanceId)) {
            setDirty();
        }
    }

    /**
     * Marks an instance whose quest just ended (abandoned/expired/failed) as still having stakes
     * this server couldn't find and revert because their chunks weren't loaded at the time -- real
     * live report, 2026-10-13: abandoning a quest with most of a long road's chunks unloaded left the
     * un-reverted terrain and orphaned ghost stakes behind permanently, since the old despawn path
     * only ever found entities in currently-loaded chunks, same root cause as the completion-check
     * bug above. Each straggling stake's own {@code tick()} checks this flag and self-cleans
     * (reverts its connections' terrain, discards itself) the moment its chunk loads again -- no
     * ticker polling every unloaded stake is needed, since loading is the only way it could become
     * reachable at all.
     */
    public void markPendingCleanup(UUID instanceId) {
        if (pendingCleanupInstanceIds.add(instanceId)) {
            setDirty();
        }
    }

    public boolean isPendingCleanup(UUID instanceId) {
        return pendingCleanupInstanceIds.contains(instanceId);
    }

    /** Called after a stake removes itself -- clears the pending flag once nothing's left to clean up. */
    public void clearPendingCleanupIfDone(UUID instanceId) {
        if (byInstance(instanceId).isEmpty() && pendingCleanupInstanceIds.remove(instanceId)) {
            setDirty();
        }
    }

    /**
     * Marks an instance whose quest just COMPLETED (the road connected) as still having stakes this
     * server couldn't find and discard because their chunks weren't loaded -- added 2026-10-13,
     * explicit user request ("all the ghost stakes and torches on the finished road should be
     * deleted/invisible again now that the road is done"). Deliberately a separate flag from {@link
     * #markPendingCleanup} -- that one's self-heal reverts each stake's paved terrain before
     * discarding it, which is exactly right for an abandoned road (nothing should be left behind) but
     * would be a real bug here: a COMPLETED road's terrain is the player's actual finished work and
     * must never be reverted, only the cosmetic ghost markers removed. Each straggler's own {@code
     * tick()} checks this flag and just discards itself (no {@code snapAllConnections} call) the
     * moment its chunk loads again.
     */
    public void markPendingHide(UUID instanceId) {
        if (pendingHideInstanceIds.add(instanceId)) {
            setDirty();
        }
    }

    public boolean isPendingHide(UUID instanceId) {
        return pendingHideInstanceIds.contains(instanceId);
    }

    /** Called after a stake removes itself -- clears the pending-hide flag once nothing's left. */
    public void clearPendingHideIfDone(UUID instanceId) {
        if (byInstance(instanceId).isEmpty() && pendingHideInstanceIds.remove(instanceId)) {
            setDirty();
        }
    }

    public List<StakeRecord> byInstance(UUID instanceId) {
        List<StakeRecord> result = new ArrayList<>();
        for (StakeRecord record : stakes.values()) {
            if (record.instanceId().equals(instanceId)) {
                result.add(record);
            }
        }
        return result;
    }

    /** BFS over this instance's own persisted connections -- entity-liveness-independent. */
    public Optional<List<UUID>> findPath(UUID instanceId, UUID startId, UUID endId) {
        if (startId.equals(endId)) {
            return Optional.of(List.of(startId));
        }
        Map<UUID, StakeRecord> byId = new HashMap<>();
        for (StakeRecord record : byInstance(instanceId)) {
            byId.put(record.stakeId(), record);
        }
        if (!byId.containsKey(startId) || !byId.containsKey(endId)) {
            return Optional.empty();
        }
        Map<UUID, UUID> cameFrom = new HashMap<>();
        Set<UUID> visited = new HashSet<>();
        Queue<UUID> queue = new ArrayDeque<>();
        queue.add(startId);
        visited.add(startId);
        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            if (current.equals(endId)) {
                List<UUID> path = new ArrayList<>();
                UUID step = endId;
                while (step != null) {
                    path.add(0, step);
                    step = cameFrom.get(step);
                }
                return Optional.of(path);
            }
            StakeRecord record = byId.get(current);
            if (record == null) {
                continue;
            }
            for (Connection connection : record.connections()) {
                if (visited.add(connection.otherStakeId())) {
                    cameFrom.put(connection.otherStakeId(), current);
                    queue.add(connection.otherStakeId());
                }
            }
        }
        return Optional.empty();
    }

    public Optional<EntityId> roadEntityIdBetween(UUID stakeId, UUID otherStakeId) {
        StakeRecord record = stakes.get(stakeId);
        if (record == null) {
            return Optional.empty();
        }
        for (Connection connection : record.connections()) {
            if (connection.otherStakeId().equals(otherStakeId)) {
                return Optional.of(connection.roadEntityId());
            }
        }
        return Optional.empty();
    }

    public Optional<BlockPos> posOf(UUID stakeId) {
        return Optional.ofNullable(stakes.get(stakeId)).map(StakeRecord::pos);
    }
}
