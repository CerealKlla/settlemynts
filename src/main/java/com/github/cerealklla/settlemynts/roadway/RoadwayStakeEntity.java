package com.github.cerealklla.settlemynts.roadway;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.EntityId;
import com.github.cerealklla.settlemynts.founding.GhostBlockDisplays;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModBlocks;
import com.github.cerealklla.settlemynts.registration.ModEntities;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A Roadway Stake (Roadways Milestone 1, added 2026-10-06) -- same ghost-entity shape as {@code
 * zone.GhostPlotStakeEntity} (Town-Planner-only {@link #broadcastToPlayer}, same rename-the-
 * block-model-to-a-fence-post display trick), but models a plain, symmetric <b>graph</b> of
 * connections, not a chain or tree.
 *
 * <p><b>Reworked 2026-10-06, explicit user request</b> -- this used to ride on vanilla's {@link
 * net.minecraft.world.entity.Leashable} (a real rope rendered between two entities), which forced
 * a "parent/child" shape onto the data model purely because Leashable only allows one holder per
 * leashee. Now that every connection is instead shown with a string of ghost torches ({@link
 * RoadwayConnectionMarkerEntity}), that constraint serves no purpose -- "I don't need the ropes if
 * there are torches visually connecting the stakes. I'd rather not carry around an unnecessary and
 * misleading parent/child concept." Each stake now just stores a flat, unordered list of the UUIDs
 * of every stake it's directly connected to ({@link #getConnectionIds()}), with no direction and no
 * degree limit in either direction -- a stake can have any number of connections, and connecting
 * two existing stakes (even two already-busy hubs) never requires "choosing a parent." Loops/rings
 * are simply ordinary graphs now, nothing special to allow or reject.
 *
 * <p><b>Never discarded once placed</b> -- unlike a Plot Stake (a transient, session-only marker
 * replaced by a registered Cartographyr polygon at Finalize), a Roadway Stake IS the road graph's
 * permanent record. The Town Hall's "Show Roadway Stakes" toggle only changes {@link
 * #broadcastToPlayer} visibility, not existence -- see {@code GhostTownHallCoreEntity#isShowRoadwayStakes}.
 *
 * <p><b>Removal</b>: right-clicking any stake removes it and snaps every one of its connections,
 * restoring each snapped edge's exact original terrain. The OTHER stake on each snapped connection
 * is never deleted -- it just loses that one connection and keeps all its others.
 */
public class RoadwayStakeEntity extends Display.BlockDisplay {

    private static final double SEARCH_RADIUS_BLOCKS = 500.0;

    // Flat, unordered, unbounded list of directly-connected stake UUIDs -- same "dodge the custom-
    // EntityDataSerializer registration trap" trick GhostPlotStakeEntity's own link fields use (see
    // that class's own doc for the full why). Every connection is recorded on BOTH stakes' own list.
    private static final EntityDataAccessor<String> DATA_CONNECTIONS =
            SynchedEntityData.defineId(RoadwayStakeEntity.class, EntityDataSerializers.STRING);

    // Roadway Stakes get their own, much longer max connection length than every other rope
    // mechanic in this mod (per the design doc's own "sign posts no more than 1 every 100 blocks"
    // spec for a finished road -- a connection needs to be able to span that same real distance,
    // unlike Plot/Perimeter Stakes marking a single settlement's small boundary). No longer tied to
    // a leash snap distance at all (see class doc) -- this is now just a plain distance constant
    // consulted at placement/connection time.
    public static final double MAX_CONNECTION_LENGTH_BLOCKS = 100.0;

    private UUID ownerCoreId;
    // One restore snapshot per connection, keyed by the OTHER stake's UUID, stored identically on
    // both ends of that connection (added 2026-10-06 as part of dropping the leash-based
    // parent/child model) -- means removal can restore an edge from whichever side initiates it,
    // with no "only the child owns this" asymmetry left over from the old model.
    private List<ConnectionSnapshot> connectionSnapshots = List.of();

    public record ConnectionSnapshot(UUID otherStakeId, List<RoadwayPaver.SnapshotEntry> snapshot, EntityId roadEntityId,
                                      List<net.minecraft.core.BlockPos> roadCells) {
        // roadCells defaults to empty for any connection saved before 2026-10-09 (the Tier-visual
        // feature) -- those simply won't be retroactively re-tiered by RoadwayTierTicker until the
        // edge is re-paved, a deliberate, accepted gap (see decisions.md) rather than a migration.
        public static final Codec<ConnectionSnapshot> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("other_stake_id").forGetter(ConnectionSnapshot::otherStakeId),
                Codec.list(RoadwayPaver.SnapshotEntry.CODEC).fieldOf("snapshot").forGetter(ConnectionSnapshot::snapshot),
                EntityId.CODEC.fieldOf("road_entity_id").forGetter(ConnectionSnapshot::roadEntityId),
                Codec.list(net.minecraft.core.BlockPos.CODEC).optionalFieldOf("road_cells", List.of()).forGetter(ConnectionSnapshot::roadCells)
        ).apply(i, ConnectionSnapshot::new));
    }

    public RoadwayStakeEntity(EntityType<? extends RoadwayStakeEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_CONNECTIONS, "");
    }

    public static RoadwayStakeEntity create(ServerLevel level, int x, int y, int z, UUID ownerCoreId) {
        RoadwayStakeEntity stake = new RoadwayStakeEntity(ModEntities.ROADWAY_STAKE.get(), level);
        // Same +0.5/-0.5 position-vs-render-translation split as GhostPlotStakeEntity -- see
        // GhostBlockDisplays#setTranslation's own doc for why (keeps the interaction hitbox centered
        // on the visual instead of covering only its near quarter).
        stake.setPos(x + 0.5, y, z + 0.5);
        stake.ownerCoreId = ownerCoreId;
        GhostBlockDisplays.setBlockState(stake, ModBlocks.ROPE_FENCE_POST.get().defaultBlockState());
        GhostBlockDisplays.setTranslation(stake, -0.5f, 0f, -0.5f);
        level.addFreshEntity(stake);
        return stake;
    }

    public UUID getOwnerCoreId() {
        return ownerCoreId;
    }

    public List<UUID> getConnectionIds() {
        String raw = entityData.get(DATA_CONNECTIONS);
        if (raw.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            ids.add(UUID.fromString(part));
        }
        return ids;
    }

    private void addConnectionId(UUID otherId) {
        List<UUID> current = new ArrayList<>(getConnectionIds());
        current.add(otherId);
        entityData.set(DATA_CONNECTIONS, joinIds(current));
    }

    private void removeConnectionId(UUID otherId) {
        List<UUID> current = new ArrayList<>(getConnectionIds());
        current.remove(otherId);
        entityData.set(DATA_CONNECTIONS, joinIds(current));
    }

    /** Records a new, mutual, undirected connection between two stakes -- no parent/child, no direction. */
    public static void connect(RoadwayStakeEntity a, RoadwayStakeEntity b) {
        a.addConnectionId(b.getUUID());
        b.addConnectionId(a.getUUID());
    }

    /** Every {@link ConnectionSnapshot} this stake currently holds -- used by {@link RoadwayTierTicker} to re-tier every road cell it owns. */
    public List<ConnectionSnapshot> getConnectionSnapshots() {
        return connectionSnapshots;
    }

    /** The {@link ConnectionSnapshot} record (terrain snapshot + the connection's own registered Cartographyr road entity) for one specific connection, or {@code null} if none is recorded. */
    public ConnectionSnapshot getConnectionSnapshot(UUID otherStakeId) {
        for (ConnectionSnapshot entry : connectionSnapshots) {
            if (entry.otherStakeId().equals(otherStakeId)) {
                return entry;
            }
        }
        return null;
    }

    /** Stores (or replaces) this stake's own copy of one connection's restore snapshot, its Cartographyr road entity id, and (2026-10-09) its exact road-surface cell positions for {@link RoadwayTierTicker} -- see {@link RoadwayPaver#paveEdge}, which calls this on both ends. */
    public void setSnapshotFor(UUID otherStakeId, List<RoadwayPaver.SnapshotEntry> snapshot, EntityId roadEntityId, List<net.minecraft.core.BlockPos> roadCells) {
        List<ConnectionSnapshot> current = new ArrayList<>();
        for (ConnectionSnapshot entry : connectionSnapshots) {
            if (!entry.otherStakeId().equals(otherStakeId)) {
                current.add(entry);
            }
        }
        current.add(new ConnectionSnapshot(otherStakeId, snapshot, roadEntityId, roadCells));
        connectionSnapshots = current;
    }

    private void removeSnapshotFor(UUID otherStakeId) {
        List<ConnectionSnapshot> current = new ArrayList<>();
        for (ConnectionSnapshot entry : connectionSnapshots) {
            if (!entry.otherStakeId().equals(otherStakeId)) {
                current.add(entry);
            }
        }
        connectionSnapshots = current;
    }

    private static String joinIds(List<UUID> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids.get(i));
        }
        return sb.toString();
    }

    public static List<RoadwayStakeEntity> findByOwnerCore(ServerLevel level, UUID coreId) {
        Entity core = level.getEntity(coreId);
        if (core == null) {
            return List.of();
        }
        AABB searchBox = new AABB(
                core.getX() - SEARCH_RADIUS_BLOCKS, level.getMinY(), core.getZ() - SEARCH_RADIUS_BLOCKS,
                core.getX() + SEARCH_RADIUS_BLOCKS, level.getMaxY(), core.getZ() + SEARCH_RADIUS_BLOCKS);
        return level.getEntities(ModEntities.ROADWAY_STAKE.get(), searchBox, stake -> coreId.equals(stake.getOwnerCoreId()));
    }

    private GhostTownHallCoreEntity findOwnerCore() {
        if (!(level() instanceof ServerLevel serverLevel) || ownerCoreId == null) {
            return null;
        }
        return serverLevel.getEntity(ownerCoreId) instanceof GhostTownHallCoreEntity core ? core : null;
    }

    /**
     * Visible only to the owning settlement's current Town Planners, and only while either the
     * "Show Roadway Stakes" toggle is on, OR the viewing player is actively holding a Roadway Stake
     * item bound to this same settlement.
     *
     * <p><b>Real bug fixed 2026-10-06</b> -- the first version gated visibility on the toggle alone
     * (default off), which meant a Town Planner placing brand-new stakes couldn't see their own
     * stakes at all while doing it, only the real, always-visible paved road blocks appearing after
     * each connection -- reported live as "instead of placing a stake you are placing a solid road
     * block." The toggle is meant for revisiting an already-built road later without holding the
     * item, not for hiding the stakes from the very player actively placing them.
     */
    @Override
    public boolean broadcastToPlayer(ServerPlayer player) {
        GhostTownHallCoreEntity core = findOwnerCore();
        if (core == null || !core.isTownPlanner(player.getUUID())) {
            return false;
        }
        if (core.isShowRoadwayStakes()) {
            return true;
        }
        var held = player.getMainHandItem();
        return held.is(com.github.cerealklla.settlemynts.registration.ModItems.ROADWAY_STAKE.get())
                && ownerCoreId.equals(held.get(com.github.cerealklla.settlemynts.registration.ModItems.ROADWAY_STAKE_OWNER_CORE_ID));
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (level().isClientSide() || !(player instanceof ServerPlayer serverPlayer) || !(level() instanceof ServerLevel serverLevel)) {
            return InteractionResult.SUCCESS;
        }
        // Right-clicking an existing post while holding a Roadway Stake item (for the owning
        // settlement) makes this the player's new anchor -- same "resume/extend from any post"
        // mechanic as GhostPlotStakeEntity#interact. If the player ALREADY had a different stake
        // anchored, this instead connects that anchor directly to THIS stake -- no new stake placed
        // in between -- then moves the anchor here so the player can keep extending the chain.
        var held = player.getItemInHand(hand);
        if (held.is(com.github.cerealklla.settlemynts.registration.ModItems.ROADWAY_STAKE.get())) {
            UUID heldCoreId = held.get(com.github.cerealklla.settlemynts.registration.ModItems.ROADWAY_STAKE_OWNER_CORE_ID);
            if (heldCoreId == null || !heldCoreId.equals(ownerCoreId)) {
                player.sendSystemMessage(Component.literal("This Roadway Stake belongs to a different settlement."));
                return InteractionResult.SUCCESS;
            }
            RoadwayStakeEntity anchor = RoadwayStakeLeash.resolveGhostAnchor(serverLevel, player.getUUID());
            if (anchor == null || anchor == this) {
                RoadwayStakeLeash.setGhostAnchor(serverPlayer, this);
                player.sendSystemMessage(Component.literal(
                        "Anchored -- continue placing Roadway Stakes to extend the road, or right-click another existing stake to connect straight to it."));
                return InteractionResult.SUCCESS;
            }
            if (getConnectionIds().contains(anchor.getUUID())) {
                player.sendSystemMessage(Component.literal("Those two stakes are already connected directly."));
                return InteractionResult.SUCCESS;
            }
            GhostTownHallCoreEntity core = findOwnerCore();
            if (core == null) {
                player.sendSystemMessage(Component.literal("This stake's settlement is gone."));
                return InteractionResult.SUCCESS;
            }
            int ax = Mth.floor(anchor.getX());
            int az = Mth.floor(anchor.getZ());
            int tx = Mth.floor(getX());
            int tz = Mth.floor(getZ());
            if (RoadwayPaver.crossesPlot(serverLevel, core, ax, az, tx, tz)) {
                player.sendSystemMessage(Component.literal(
                        "A road can't cross directly through a plot -- route around it, or pick a different connection."));
                return InteractionResult.SUCCESS;
            }
            int anchorGroundY = Mth.floor(anchor.getY()) - 1;
            int targetGroundY = Mth.floor(getY()) - 1;
            if (RoadwayPaver.exceedsMaxSlope(ax, az, anchorGroundY, tx, tz, targetGroundY)) {
                player.sendSystemMessage(Component.literal("That's too steep for a road -- pick a different connection."));
                return InteractionResult.SUCCESS;
            }
            connect(anchor, this);
            RoadwayPaver.paveEdge(serverLevel, core, anchor, this);
            RoadwayStakeLeash.setGhostAnchor(serverPlayer, this);
            player.sendSystemMessage(Component.literal("Connected -- anchor now here, continue extending the road."));
            return InteractionResult.SUCCESS;
        }

        // Not holding a Roadway Stake item -- right-clicking a visible stake (only ever visible to a
        // Town Planner with the toggle on, see broadcastToPlayer) is a removal request: this stake
        // is removed outright, and every connection touching it is snapped -- see #remove.
        GhostTownHallCoreEntity core = findOwnerCore();
        if (core == null || !core.isTownPlanner(player.getUUID())) {
            player.sendSystemMessage(Component.literal("You're not a Town Planner of this settlement."));
            return InteractionResult.SUCCESS;
        }
        int connectionCount = getConnectionIds().size();
        RoadwayStakeLeash.clearGhostAnchor(player.getUUID());
        if (connectionCount == 0) {
            // Real live request, 2026-10-06: an already-empty stake right-clicked again is the only
            // case that actually deletes it -- a connected stake is only ever snapped, never deleted,
            // so a Planner can deliberately empty one out first, then remove it once it's truly done.
            remove();
            player.sendSystemMessage(Component.literal("Roadway Stake removed."));
        } else {
            snapAllConnections();
            player.sendSystemMessage(Component.literal(
                    "Snapped " + connectionCount + " connection" + (connectionCount == 1 ? "" : "s")
                            + " -- stake left in place. Right-click it again to remove it, or reconnect it to whichever stakes you want."));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Roadway Stake");
    }

    /**
     * Snaps every one of this stake's connections, restoring each snapped edge to its exact original
     * terrain (original request: "removing roadway stakes needs to restore where the road was placed
     * to whatever the server originally generated"), but leaves THIS stake itself in place -- added
     * 2026-10-06, explicit user request: "when you snap the connections i want the actual stake to
     * remain there, so I can then go reattach only the ones that I want reattached." The stake on the
     * other end of each snapped connection is likewise never discarded, just disconnected.
     */
    public void snapAllConnections() {
        if (level() instanceof ServerLevel serverLevel) {
            for (UUID otherId : List.copyOf(getConnectionIds())) {
                ConnectionSnapshot entry = getConnectionSnapshot(otherId);
                if (entry != null) {
                    RoadwayPaver.restoreEdge(serverLevel, entry.snapshot());
                    // Real live bug, 2026-10-06: "picking up stakes isn't removing their existence
                    // from Cartographyr" -- restoring the terrain alone left the edge's registered
                    // Cartographyr ROAD entity (see RoadwayPaver#paveEdge) behind as a stale ghost
                    // record pointing at a path that no longer physically exists.
                    Cartography.retireEntity(serverLevel, entry.roadEntityId());
                }
                if (serverLevel.getEntity(otherId) instanceof RoadwayStakeEntity other) {
                    other.removeConnectionId(getUUID());
                    other.removeSnapshotFor(getUUID());
                }
            }
        }
        entityData.set(DATA_CONNECTIONS, "");
        connectionSnapshots = List.of();
        if (level() instanceof ServerLevel serverLevel && ownerCoreId != null) {
            RoadwayConnectionMarkerEntity.regenerateAll(serverLevel, ownerCoreId);
        }
    }

    /** Discards this entity outright -- only called once it already has no connections left, see {@link #interact}. No item handed back (same reasoning as every other ghost stake in this mod). */
    public void remove() {
        discard();
        if (level() instanceof ServerLevel serverLevel && ownerCoreId != null) {
            RoadwayConnectionMarkerEntity.regenerateAll(serverLevel, ownerCoreId);
        }
    }

    @Override
    protected void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input) {
        super.readAdditionalSaveData(input);
        ownerCoreId = input.read("OwnerCoreId", UUIDUtil.CODEC).orElse(null);
        entityData.set(DATA_CONNECTIONS, input.getStringOr("Connections", ""));
        connectionSnapshots = input.read("ConnectionSnapshots", Codec.list(ConnectionSnapshot.CODEC)).orElse(List.of());
    }

    @Override
    protected void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.storeNullable("OwnerCoreId", UUIDUtil.CODEC, ownerCoreId);
        output.putString("Connections", entityData.get(DATA_CONNECTIONS));
        output.store("ConnectionSnapshots", Codec.list(ConnectionSnapshot.CODEC), connectionSnapshots);
    }
}
