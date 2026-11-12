package com.github.cerealklla.settlemynts.tunnel;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.github.cerealklla.settlemynts.founding.GhostBlockDisplays;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModBlocks;
import com.github.cerealklla.settlemynts.registration.ModEntities;
import com.github.cerealklla.settlemynts.registration.ModItems;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A Tunnel Stake -- the Mayor/Town-Planner-placed mechanism for a settlement's own in-town
 * shortcut (the 3rd use case from the Tunnel design conversation, explicitly scoped as future work
 * at the time, now built on explicit follow-up request). Originally capped at exactly ONE partner
 * ("a Tunnel is always exactly a pair of real-world doors, never a hub") -- widened 2026-11-12 to
 * an open connection graph, mirroring {@code roadway.RoadwayStakeEntity}'s own shape, per explicit
 * user spec: "certain settlements could have multiple entrances to the same underground sewer
 * system... multiple entrances to, say, a mountain mineshaft themed tunnel." A stake's own {@link
 * #tunnelId} is still the group's shared identity (unchanged); {@link #getConnectionIds()} replaces
 * the old single {@code getPartnerId()}/{@code hasPartner()} pair.
 *
 * <p>Right-click convention deliberately mirrors {@code zone.GhostPlotStakeEntity}'s, not {@code
 * roadway.RoadwayStakeEntity}'s</b> (explicit user request) -- holding a bound Tunnel Stake item and
 * right-clicking an existing stake of the same settlement unconditionally adopts it as the player's
 * current anchor, joining it to whatever chain is currently being built; right-clicking WITHOUT the
 * item opens a management screen (Finalize/Remove) rather than acting immediately the way a bare
 * Roadway Stake right-click does. Unlike the original pair-only version, a stake already belonging
 * to a group can still accept further joiners -- there's no "already paired" ceiling anymore.
 *
 * <p>No real vanilla {@code Leashable} rope rendering -- unlike Plot/Perimeter Stakes (a small,
 * single-settlement-sized boundary), a Tunnel's stakes are explicitly meant to be far apart
 * ("shortcuts across town"), the same long-range problem Roadway/Highway Stakes already solved by
 * abandoning Leashable for a ghost-marker trail instead of trying to stretch a rope indefinitely --
 * {@link TunnelConnectionMarkerEntity} is this mechanic's own deliberately lightweight version of
 * that same fix (no Pingable/traveler-buff machinery, since a Tunnel isn't a road).
 */
public class TunnelStakeEntity extends Display.BlockDisplay {

    private static final double SEARCH_RADIUS_BLOCKS = 500.0;

    /** Comma-joined UUIDs of every OTHER stake this one has a direct edge to -- mirrors {@code
     *  roadway.RoadwayStakeEntity#DATA_CONNECTIONS}' own shape exactly. Replaces the old
     *  single-value {@code DATA_PARTNER_ID}. */
    private static final EntityDataAccessor<String> DATA_CONNECTIONS =
            SynchedEntityData.defineId(TunnelStakeEntity.class, EntityDataSerializers.STRING);

    private UUID ownerCoreId;
    // Assigned fresh the moment a stake is placed, NOT at joining time -- explicit user spec,
    // 2026-11-11: "First stake placed gets a settlement UUID + tunnel UUID, that's the name of the
    // recipe. That way two tunnels of the same length in the same settlement can be different." See
    // TunnelFinalizer -- the recipe name is {@code ownerCoreId + "_" + tunnelId}, so every tunnel
    // group gets its own independent recipe regardless of room count, rather than two differently-
    // placed but equally-long tunnels silently sharing (and fighting over) one recipe. Whichever
    // stake is placed/anchored FIRST in a chain keeps its own id; every stake that joins it (see
    // {@link #join}) adopts it -- a stake placed solo and never joined just keeps its own, unused id.
    private UUID tunnelId;

    public TunnelStakeEntity(EntityType<? extends TunnelStakeEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_CONNECTIONS, "");
    }

    public static TunnelStakeEntity create(ServerLevel level, int x, int y, int z, UUID ownerCoreId) {
        TunnelStakeEntity stake = new TunnelStakeEntity(ModEntities.TUNNEL_STAKE.get(), level);
        // Same +0.5/-0.5 position-vs-render-translation split as every other ghost stake in this mod
        // -- see GhostBlockDisplays#setTranslation's own doc for why.
        stake.setPos(x + 0.5, y, z + 0.5);
        stake.ownerCoreId = ownerCoreId;
        stake.tunnelId = UUID.randomUUID();
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
        if (!current.contains(otherId)) {
            current.add(otherId);
        }
        entityData.set(DATA_CONNECTIONS, joinIds(current));
    }

    private void removeConnectionId(UUID otherId) {
        List<UUID> current = new ArrayList<>(getConnectionIds());
        current.remove(otherId);
        entityData.set(DATA_CONNECTIONS, joinIds(current));
    }

    private static String joinIds(List<UUID> ids) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(ids.get(i));
        }
        return builder.toString();
    }

    public boolean isConnected() {
        return !getConnectionIds().isEmpty();
    }

    public UUID getTunnelId() {
        return tunnelId;
    }

    /** Joins {@code newcomer} into {@code existing}'s group (adopting {@code existing}'s own {@link
     *  #tunnelId}) and spawns the connecting marker trail for this one new edge. Unlike the
     *  original pair-only version, {@code existing} may already have any number of other
     *  connections -- joining never removes or replaces them. */
    public static void join(TunnelStakeEntity existing, TunnelStakeEntity newcomer) {
        newcomer.tunnelId = existing.tunnelId;
        existing.addConnectionId(newcomer.getUUID());
        newcomer.addConnectionId(existing.getUUID());
        if (existing.level() instanceof ServerLevel serverLevel) {
            TunnelConnectionMarkerEntity.spawnTrail(serverLevel, existing, newcomer, existing.tunnelId);
        }
    }

    /** Breaks every one of this stake's own edges, discarding each edge's marker trail and
     *  updating the other side's own connection list -- used by Remove before the stake itself is
     *  discarded. Any other stake left with zero connections afterward gets a fresh, independent
     *  {@link #tunnelId} too (it's now solo again); a stake that still has other connections keeps
     *  its current {@code tunnelId} unchanged -- the rest of the group's identity doesn't depend on
     *  this one stake. */
    private void unlinkAll() {
        if (!(level() instanceof ServerLevel serverLevel)) {
            entityData.set(DATA_CONNECTIONS, "");
            tunnelId = UUID.randomUUID();
            return;
        }
        for (UUID otherId : List.copyOf(getConnectionIds())) {
            TunnelConnectionMarkerEntity.removeTrail(serverLevel, blockPosition(), tunnelId, getUUID(), otherId);
            if (serverLevel.getEntity(otherId) instanceof TunnelStakeEntity other) {
                other.removeConnectionId(getUUID());
                if (!other.isConnected()) {
                    other.tunnelId = UUID.randomUUID();
                }
            }
        }
        entityData.set(DATA_CONNECTIONS, "");
        tunnelId = UUID.randomUUID();
    }

    public static List<TunnelStakeEntity> findByOwnerCore(ServerLevel level, UUID coreId) {
        Entity core = level.getEntity(coreId);
        if (core == null) {
            return List.of();
        }
        AABB searchBox = new AABB(
                core.getX() - SEARCH_RADIUS_BLOCKS, level.getMinY(), core.getZ() - SEARCH_RADIUS_BLOCKS,
                core.getX() + SEARCH_RADIUS_BLOCKS, level.getMaxY(), core.getZ() + SEARCH_RADIUS_BLOCKS);
        return level.getEntities(ModEntities.TUNNEL_STAKE.get(), searchBox, stake -> coreId.equals(stake.getOwnerCoreId()));
    }

    /** Every stake sharing this one's current {@link #tunnelId}, including itself -- i.e. this
     *  stake's whole Tunnel group, regardless of its connection graph's exact shape. */
    public List<TunnelStakeEntity> groupMembers() {
        if (!(level() instanceof ServerLevel serverLevel) || ownerCoreId == null) {
            return List.of(this);
        }
        return findByOwnerCore(serverLevel, ownerCoreId).stream().filter(s -> tunnelId.equals(s.getTunnelId())).toList();
    }

    private GhostTownHallCoreEntity findOwnerCore() {
        if (!(level() instanceof ServerLevel serverLevel) || ownerCoreId == null) {
            return null;
        }
        return serverLevel.getEntity(ownerCoreId) instanceof GhostTownHallCoreEntity core ? core : null;
    }

    /** Visible only to the owning settlement's current Town Planners -- same rule as every other ghost stake in this mod. */
    @Override
    public boolean broadcastToPlayer(ServerPlayer player) {
        GhostTownHallCoreEntity core = findOwnerCore();
        return core != null && core.isTownPlanner(player.getUUID());
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

        ItemStack held = player.getItemInHand(hand);
        if (held.is(ModItems.TUNNEL_STAKE.get())) {
            UUID heldCoreId = held.get(ModItems.TUNNEL_STAKE_OWNER_CORE_ID);
            if (heldCoreId == null || !heldCoreId.equals(ownerCoreId)) {
                player.sendSystemMessage(Component.literal("This Tunnel Stake belongs to a different settlement."));
                return InteractionResult.SUCCESS;
            }
            TunnelStakeEntity anchor = TunnelStakeLeash.resolveGhostAnchor(serverLevel, player.getUUID());
            if (anchor == null || anchor == this) {
                TunnelStakeLeash.setGhostAnchor(serverPlayer, this);
                player.sendSystemMessage(Component.literal(
                        "Anchored -- place or right-click another Tunnel Stake to join it to this one."));
                return InteractionResult.SUCCESS;
            }
            join(anchor, this);
            // Unlike a Plot Fence Post chain, the anchor advances to the stake just joined --
            // mirrors roadway.RoadwayStakeItem's own open-chain shape, so a Mayor can keep
            // right-clicking/placing further stakes to grow the same group past 2.
            TunnelStakeLeash.setGhostAnchor(serverPlayer, this);
            player.sendSystemMessage(Component.literal(
                    "Joined -- place or right-click another stake to add it to this group too, "
                            + "or right-click any stake in the group (without a Tunnel Stake in hand) to finalize it into a real Tunnel, or to remove it."));
            return InteractionResult.SUCCESS;
        }

        // Not holding a Tunnel Stake -- same convention as GhostPlotStakeEntity's own fallback:
        // opens a management screen rather than acting immediately.
        GhostTownHallCoreEntity core = findOwnerCore();
        if (core == null || !core.isTownPlanner(player.getUUID())) {
            player.sendSystemMessage(Component.literal("You're not a Town Planner of this settlement."));
            return InteractionResult.SUCCESS;
        }
        PacketDistributor.sendToPlayer(serverPlayer, new OpenTunnelStakeScreenPayload(getId(), isConnected()));
        return InteractionResult.SUCCESS;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Tunnel Stake");
    }

    /** Unlinks every edge this stake has and discards it -- no item handed back, same reasoning as every other ghost stake in this mod. */
    public void remove() {
        unlinkAll();
        discard();
    }

    /** Discards THIS stake only, after its own edges have already been handed by the caller (see {@code TunnelFinalizer}) -- used so Finalize can clear every stake's edges exactly once before discarding each. */
    void discardAfterFinalize() {
        entityData.set(DATA_CONNECTIONS, "");
        discard();
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        ownerCoreId = input.read("OwnerCoreId", UUIDUtil.CODEC).orElse(null);
        String connections = input.getStringOr("Connections", "");
        if (connections.isEmpty()) {
            // Pre-generalization save (a single PartnerId) -- migrate it into the new list field.
            String legacyPartner = input.getStringOr("PartnerId", "");
            connections = legacyPartner;
        }
        entityData.set(DATA_CONNECTIONS, connections);
        tunnelId = input.read("TunnelId", UUIDUtil.CODEC).orElse(UUID.randomUUID());
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.storeNullable("OwnerCoreId", UUIDUtil.CODEC, ownerCoreId);
        output.putString("Connections", entityData.get(DATA_CONNECTIONS));
        output.storeNullable("TunnelId", UUIDUtil.CODEC, tunnelId);
    }
}
