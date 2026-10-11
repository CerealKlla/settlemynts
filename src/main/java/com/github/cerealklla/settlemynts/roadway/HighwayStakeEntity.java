package com.github.cerealklla.settlemynts.roadway;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.EntityId;
import com.github.cerealklla.settlemynts.founding.GhostBlockDisplays;
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
 * A "Highway Stake" -- a copy of {@link RoadwayStakeEntity}, deliberately NOT a subclass (same
 * "copy it, then change it" precedent this suite uses when two mechanics are similar but need
 * independently-evolvable rules), tweaked for inter-settlement roads (added 2026-10-11, explicit
 * user spec):
 * <ul>
 *   <li>Owned by a {@code linkedInstanceId} (one Myssions road-connection quest instance), not a
 *   settlement's {@code ownerCoreId} -- this is what lets two stakes near two DIFFERENT settlements
 *   connect at all, deliberately bypassing the same-core guard {@link RoadwayStakeEntity} still
 *   correctly enforces for its own, unrelated, intra-settlement use.</li>
 *   <li>Ghost-visible only to the one player who accepted the quest ({@code broadcastToPlayer}),
 *   not to a settlement's Town Planners -- "these will be 'ghost stakes', visible/interactive to
 *   the player with the quest only."</li>
 *   <li>Placed from a finite, consumable item (see {@link HighwayStakeItem}), not an infinite tool.</li>
 * </ul>
 * Paving is still real and immediate on connection, via {@link HighwayPaver} (a parallel copy of
 * {@link RoadwayPaver}, not the original -- see that class's own doc for why one couldn't just be
 * reused as-is here).
 */
public class HighwayStakeEntity extends Display.BlockDisplay implements StakeNode {

    private static final double SEARCH_RADIUS_BLOCKS = 2000.0; // Must cover the full inter-settlement span, unlike the 500-block intra-settlement radius.

    private static final EntityDataAccessor<String> DATA_CONNECTIONS =
            SynchedEntityData.defineId(HighwayStakeEntity.class, EntityDataSerializers.STRING);

    public static final double MAX_CONNECTION_LENGTH_BLOCKS = RoadwayStakeEntity.MAX_CONNECTION_LENGTH_BLOCKS;

    private UUID ownerPlayerId;
    private UUID linkedInstanceId;
    // Both nullable -- a natural (NPC-owned) settlement has no real GhostTownHallCoreEntity at all.
    // See HighwayPaver's own doc for how pavement/tier/plot-crossing degrade gracefully without one.
    private UUID settlementCoreIdA;
    private UUID settlementCoreIdB;
    private List<ConnectionSnapshot> connectionSnapshots = List.of();

    public record ConnectionSnapshot(UUID otherStakeId, List<HighwayPaver.SnapshotEntry> snapshot, EntityId roadEntityId) {
        public static final Codec<ConnectionSnapshot> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("other_stake_id").forGetter(ConnectionSnapshot::otherStakeId),
                Codec.list(HighwayPaver.SnapshotEntry.CODEC).fieldOf("snapshot").forGetter(ConnectionSnapshot::snapshot),
                EntityId.CODEC.fieldOf("road_entity_id").forGetter(ConnectionSnapshot::roadEntityId)
        ).apply(i, ConnectionSnapshot::new));
    }

    public HighwayStakeEntity(EntityType<? extends HighwayStakeEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_CONNECTIONS, "");
    }

    public static HighwayStakeEntity create(ServerLevel level, int x, int y, int z, UUID ownerPlayerId, UUID linkedInstanceId,
                                             Optional<UUID> settlementCoreIdA, Optional<UUID> settlementCoreIdB) {
        HighwayStakeEntity stake = new HighwayStakeEntity(ModEntities.HIGHWAY_STAKE.get(), level);
        stake.setPos(x + 0.5, y, z + 0.5);
        stake.ownerPlayerId = ownerPlayerId;
        stake.linkedInstanceId = linkedInstanceId;
        stake.settlementCoreIdA = settlementCoreIdA.orElse(null);
        stake.settlementCoreIdB = settlementCoreIdB.orElse(null);
        GhostBlockDisplays.setBlockState(stake, ModBlocks.ROPE_FENCE_POST.get().defaultBlockState());
        GhostBlockDisplays.setTranslation(stake, -0.5f, 0f, -0.5f);
        level.addFreshEntity(stake);
        return stake;
    }

    public UUID getLinkedInstanceId() {
        return linkedInstanceId;
    }

    public UUID getOwnerPlayerId() {
        return ownerPlayerId;
    }

    @Override
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

    public static void connect(HighwayStakeEntity a, HighwayStakeEntity b) {
        a.addConnectionId(b.getUUID());
        b.addConnectionId(a.getUUID());
    }

    public List<ConnectionSnapshot> getConnectionSnapshots() {
        return connectionSnapshots;
    }

    public void setSnapshotFor(UUID otherStakeId, List<HighwayPaver.SnapshotEntry> snapshot, EntityId roadEntityId) {
        List<ConnectionSnapshot> current = new ArrayList<>();
        for (ConnectionSnapshot entry : connectionSnapshots) {
            if (!entry.otherStakeId().equals(otherStakeId)) {
                current.add(entry);
            }
        }
        current.add(new ConnectionSnapshot(otherStakeId, snapshot, roadEntityId));
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

    public static List<HighwayStakeEntity> findByInstanceId(ServerLevel level, UUID instanceId, net.minecraft.core.BlockPos nearHint) {
        AABB searchBox = new AABB(
                nearHint.getX() - SEARCH_RADIUS_BLOCKS, level.getMinY(), nearHint.getZ() - SEARCH_RADIUS_BLOCKS,
                nearHint.getX() + SEARCH_RADIUS_BLOCKS, level.getMaxY(), nearHint.getZ() + SEARCH_RADIUS_BLOCKS);
        return level.getEntities(ModEntities.HIGHWAY_STAKE.get(), searchBox, stake -> instanceId.equals(stake.linkedInstanceId));
    }

    /** Resolves whichever of this stake's two candidate settlement cores actually still exists as a real {@code GhostTownHallCoreEntity} and is nearer -- see {@link HighwayPaver}'s own doc for why this can legitimately resolve to {@code null} (a natural settlement has none). */
    public com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity resolveNearestRealCore(ServerLevel level) {
        com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity a = resolveCore(level, settlementCoreIdA);
        com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity b = resolveCore(level, settlementCoreIdB);
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return distanceToSqr(a) <= distanceToSqr(b) ? a : b;
    }

    private com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity resolveCore(ServerLevel level, UUID coreId) {
        if (coreId == null) {
            return null;
        }
        return level.getEntity(coreId) instanceof com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity core ? core : null;
    }

    /** Ghost-visible only to the one player escorting this instance's road -- "visible/interactive to the player with the quest only." */
    @Override
    public boolean broadcastToPlayer(ServerPlayer player) {
        return ownerPlayerId != null && ownerPlayerId.equals(player.getUUID());
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

        var held = player.getItemInHand(hand);
        if (held.is(com.github.cerealklla.settlemynts.registration.ModItems.HIGHWAY_STAKE.get())) {
            UUID heldInstanceId = held.get(com.github.cerealklla.settlemynts.registration.ModItems.HIGHWAY_STAKE_INSTANCE_ID);
            if (heldInstanceId == null || !heldInstanceId.equals(linkedInstanceId)) {
                player.sendSystemMessage(Component.literal("This Highway Stake belongs to a different road."));
                return InteractionResult.SUCCESS;
            }
            HighwayStakeEntity anchor = HighwayStakeLeash.resolveGhostAnchor(serverLevel, player.getUUID());
            if (anchor == null || anchor == this) {
                HighwayStakeLeash.setGhostAnchor(serverPlayer, this);
                player.sendSystemMessage(Component.literal(
                        "Anchored -- continue placing Highway Stakes to extend the road, or right-click another existing stake to connect straight to it."));
                return InteractionResult.SUCCESS;
            }
            if (getConnectionIds().contains(anchor.getUUID())) {
                player.sendSystemMessage(Component.literal("Those two stakes are already connected directly."));
                return InteractionResult.SUCCESS;
            }
            int ax = Mth.floor(anchor.getX());
            int az = Mth.floor(anchor.getZ());
            int tx = Mth.floor(getX());
            int tz = Mth.floor(getZ());
            var core = resolveNearestRealCore(serverLevel);
            if (core != null && HighwayPaver.crossesPlot(serverLevel, core, ax, az, tx, tz)) {
                player.sendSystemMessage(Component.literal(
                        "A road can't cross directly through a plot -- route around it, or pick a different connection."));
                return InteractionResult.SUCCESS;
            }
            int anchorGroundY = Mth.floor(anchor.getY()) - 1;
            int targetGroundY = Mth.floor(getY()) - 1;
            if (HighwayPaver.exceedsMaxSlope(ax, az, anchorGroundY, tx, tz, targetGroundY)) {
                player.sendSystemMessage(Component.literal("That's too steep for a road -- pick a different connection."));
                return InteractionResult.SUCCESS;
            }
            connect(anchor, this);
            HighwayPaver.paveEdge(serverLevel, core, anchor, this);
            HighwayConnectionMarkerEntity.regenerateAll(serverLevel, ownerPlayerId, linkedInstanceId, blockPosition());
            HighwayStakeLeash.setGhostAnchor(serverPlayer, this);
            player.sendSystemMessage(Component.literal("Connected -- anchor now here, continue extending the road."));
            return InteractionResult.SUCCESS;
        }

        // Not holding a Highway Stake item for this instance -- right-clicking removes this stake
        // outright if it has no connections (same "only an empty stake deletes" rule as
        // RoadwayStakeEntity), no item handed back.
        if (!getConnectionIds().isEmpty()) {
            player.sendSystemMessage(Component.literal("Snap this stake's connections first by removing the stakes on either end."));
            return InteractionResult.SUCCESS;
        }
        HighwayStakeLeash.clearGhostAnchor(player.getUUID());
        discard();
        player.sendSystemMessage(Component.literal("Highway Stake removed."));
        return InteractionResult.SUCCESS;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Highway Stake");
    }

    /** Despawns every Highway Stake tied to {@code instanceId} -- called on quest fail/expire/cancel. Already-paved road blocks are deliberately left in place (accepted minor debris, see decisions.md). */
    public static void despawnAllForInstance(ServerLevel level, UUID instanceId, net.minecraft.core.BlockPos nearHint) {
        for (HighwayStakeEntity stake : findByInstanceId(level, instanceId, nearHint)) {
            stake.discard();
        }
    }

    @Override
    protected void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input) {
        super.readAdditionalSaveData(input);
        ownerPlayerId = input.read("OwnerPlayerId", UUIDUtil.CODEC).orElse(null);
        linkedInstanceId = input.read("LinkedInstanceId", UUIDUtil.CODEC).orElse(null);
        settlementCoreIdA = input.read("SettlementCoreIdA", UUIDUtil.CODEC).orElse(null);
        settlementCoreIdB = input.read("SettlementCoreIdB", UUIDUtil.CODEC).orElse(null);
        entityData.set(DATA_CONNECTIONS, input.getStringOr("Connections", ""));
        connectionSnapshots = input.read("ConnectionSnapshots", Codec.list(ConnectionSnapshot.CODEC)).orElse(List.of());
    }

    @Override
    protected void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.storeNullable("OwnerPlayerId", UUIDUtil.CODEC, ownerPlayerId);
        output.storeNullable("LinkedInstanceId", UUIDUtil.CODEC, linkedInstanceId);
        output.storeNullable("SettlementCoreIdA", UUIDUtil.CODEC, settlementCoreIdA);
        output.storeNullable("SettlementCoreIdB", UUIDUtil.CODEC, settlementCoreIdB);
        output.putString("Connections", entityData.get(DATA_CONNECTIONS));
        output.store("ConnectionSnapshots", Codec.list(ConnectionSnapshot.CODEC), connectionSnapshots);
    }
}
