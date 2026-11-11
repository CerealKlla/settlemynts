package com.github.cerealklla.settlemynts.tunnel;

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
 * at the time, now built on explicit follow-up request). Unlike {@code roadway.RoadwayStakeEntity}'s
 * open, unbounded connection graph, a Tunnel Stake can only ever have ONE partner -- a Tunnel is
 * always exactly a pair of real-world doors, never a hub.
 *
 * <p><b>Right-click convention deliberately mirrors {@code zone.GhostPlotStakeEntity}'s, not {@code
 * roadway.RoadwayStakeEntity}'s</b> (explicit user request) -- holding a bound Tunnel Stake item and
 * right-clicking an existing, still-unpaired stake of the same settlement unconditionally adopts it
 * as the player's current anchor ("go back to a Fence Post which does not have 2 ropes on it," Plot
 * Stakes' own quote -- here, "does not have 2 ropes" maps to "isn't paired yet"), and right-clicking
 * WITHOUT the item opens a management screen (Finalize/Remove) rather than acting immediately the
 * way a bare Roadway Stake right-click does.
 *
 * <p>No real vanilla {@code Leashable} rope rendering -- unlike Plot/Perimeter Stakes (a small,
 * single-settlement-sized boundary), a Tunnel's two ends are explicitly meant to be far apart
 * ("shortcuts across town"), the same long-range problem Roadway/Highway Stakes already solved by
 * abandoning Leashable for a ghost-marker trail instead of trying to stretch a rope indefinitely --
 * {@link TunnelConnectionMarkerEntity} is this mechanic's own deliberately lightweight version of
 * that same fix (no Pingable/traveler-buff machinery, since a Tunnel isn't a road).
 */
public class TunnelStakeEntity extends Display.BlockDisplay {

    private static final double SEARCH_RADIUS_BLOCKS = 500.0;

    private static final EntityDataAccessor<String> DATA_PARTNER_ID =
            SynchedEntityData.defineId(TunnelStakeEntity.class, EntityDataSerializers.STRING);

    private UUID ownerCoreId;
    // Assigned fresh the moment a stake is placed, NOT at pairing time -- explicit user spec,
    // 2026-11-11: "First stake placed gets a settlement UUID + tunnel UUID, that's the name of the
    // recipe. That way two tunnels of the same length in the same settlement can be different." See
    // TunnelFinalizer -- the recipe name is {@code ownerCoreId + "_" + tunnelId}, so every tunnel
    // gets its own independent recipe regardless of room count, rather than two differently-placed
    // but equally-long tunnels silently sharing (and fighting over) one recipe. Whichever stake is
    // placed/anchored FIRST in a pairing keeps its own id; the one it pairs WITH adopts it (see
    // {@link #pair}) -- a stake placed solo and never paired just keeps its own, unused id.
    private UUID tunnelId;

    public TunnelStakeEntity(EntityType<? extends TunnelStakeEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_PARTNER_ID, "");
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

    public UUID getPartnerId() {
        String value = entityData.get(DATA_PARTNER_ID);
        return value.isEmpty() ? null : UUID.fromString(value);
    }

    public boolean hasPartner() {
        return getPartnerId() != null;
    }

    public UUID getTunnelId() {
        return tunnelId;
    }

    /** Pairs two still-unpaired stakes together and spawns their connecting marker trail. {@code a} is treated as whichever one was placed/anchored first -- its own {@link #tunnelId} becomes the pairing's shared one, overwriting {@code b}'s. Callers must check {@link #hasPartner()} on both first. */
    public static void pair(TunnelStakeEntity a, TunnelStakeEntity b) {
        b.tunnelId = a.tunnelId;
        a.entityData.set(DATA_PARTNER_ID, b.getUUID().toString());
        b.entityData.set(DATA_PARTNER_ID, a.getUUID().toString());
        if (a.level() instanceof ServerLevel serverLevel) {
            TunnelConnectionMarkerEntity.spawnTrail(serverLevel, a, b, a.tunnelId);
        }
    }

    /** Breaks this stake's own pairing (if any), discarding the marker trail and clearing the partner's own pointer -- used by Remove before the stake itself is discarded. Both stakes get a fresh, independent {@link #tunnelId} afterward, so a later re-pairing never silently inherits a now-broken pairing's old identity. */
    private void unpair() {
        UUID partnerId = getPartnerId();
        if (partnerId != null && level() instanceof ServerLevel serverLevel) {
            TunnelConnectionMarkerEntity.removeTrail(serverLevel, tunnelId);
            if (serverLevel.getEntity(partnerId) instanceof TunnelStakeEntity partner) {
                partner.entityData.set(DATA_PARTNER_ID, "");
                partner.tunnelId = UUID.randomUUID();
            }
        }
        entityData.set(DATA_PARTNER_ID, "");
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
            if (hasPartner()) {
                player.sendSystemMessage(Component.literal("This Tunnel Stake is already paired."));
                return InteractionResult.SUCCESS;
            }
            TunnelStakeEntity anchor = TunnelStakeLeash.resolveGhostAnchor(serverLevel, player.getUUID());
            if (anchor == null || anchor == this) {
                TunnelStakeLeash.setGhostAnchor(serverPlayer, this);
                player.sendSystemMessage(Component.literal(
                        "Anchored -- place or right-click a second Tunnel Stake to pair it with this one."));
                return InteractionResult.SUCCESS;
            }
            pair(anchor, this);
            // Unlike Roadway Stakes' open chain, a pair is always exactly 2 -- nothing left to extend,
            // so the anchor is cleared immediately instead of moving to this stake.
            TunnelStakeLeash.clearGhostAnchor(player.getUUID());
            player.sendSystemMessage(Component.literal(
                    "Paired -- right-click either stake (without a Tunnel Stake in hand) to finalize it into a real Tunnel, or to remove it."));
            return InteractionResult.SUCCESS;
        }

        // Not holding a Tunnel Stake -- same convention as GhostPlotStakeEntity's own fallback:
        // opens a management screen rather than acting immediately.
        GhostTownHallCoreEntity core = findOwnerCore();
        if (core == null || !core.isTownPlanner(player.getUUID())) {
            player.sendSystemMessage(Component.literal("You're not a Town Planner of this settlement."));
            return InteractionResult.SUCCESS;
        }
        PacketDistributor.sendToPlayer(serverPlayer, new OpenTunnelStakeScreenPayload(getId(), hasPartner()));
        return InteractionResult.SUCCESS;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Tunnel Stake");
    }

    /** Snaps this stake's own pairing (if any) and discards it -- no item handed back, same reasoning as every other ghost stake in this mod. */
    public void remove() {
        unpair();
        discard();
    }

    /** Discards THIS stake only, after its own pairing has already been handled by the caller (see {@code TunnelFinalizer}) -- used so Finalize can clear both stakes' pairing/markers exactly once before discarding each. */
    void discardAfterFinalize() {
        entityData.set(DATA_PARTNER_ID, "");
        discard();
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        ownerCoreId = input.read("OwnerCoreId", UUIDUtil.CODEC).orElse(null);
        entityData.set(DATA_PARTNER_ID, input.getStringOr("PartnerId", ""));
        tunnelId = input.read("TunnelId", UUIDUtil.CODEC).orElse(UUID.randomUUID());
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.storeNullable("OwnerCoreId", UUIDUtil.CODEC, ownerCoreId);
        output.putString("PartnerId", entityData.get(DATA_PARTNER_ID));
        output.storeNullable("TunnelId", UUIDUtil.CODEC, tunnelId);
    }
}
