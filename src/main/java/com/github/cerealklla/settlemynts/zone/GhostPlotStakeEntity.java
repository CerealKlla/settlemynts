package com.github.cerealklla.settlemynts.zone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.github.cerealklla.settlemynts.founding.GhostBlockDisplays;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModEntities;
import com.github.cerealklla.settlemynts.registration.ModItems;
import com.github.cerealklla.settlemynts.registration.ModBlocks;
import com.github.cerealklla.settlemynts.rope.RopeFenceLeash;

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
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A Plot Placement Stake, placed while drawing out a new plot (design doc Section 11a). Same
 * shape as {@code founding.GhostPerimeterStakeEntity} -- visible only to the owning settlement's
 * Town Planners, looked up live from the core each time -- but grouped by {@link #plotSessionId}
 * instead of belonging to the settlement as a whole, since multiple Town Planners can each be
 * drawing out their own separate plot at the same time.
 *
 * <p>A real block-model {@link Display.BlockDisplay} (switched from a floating held-item icon
 * 2026-09-27, see {@code founding.GhostPerimeterStakeEntity}'s own doc for the full reasoning,
 * shared verbatim -- a placed {@code Blocks.LANTERN}, positioned one block above its own
 * placement point so it stands visibly on top of the live fence-post preview instead of buried
 * inside it, with the entity's *actual* position moved (not just a render offset) so the
 * click/interact hitbox follows the visual).
 *
 * <p><b>{@code plotSessionId}/{@code placementIndex} are synced to clients via {@link
 * SynchedEntityData} (fixed 2026-09-29)</b> -- both were originally plain fields, populated only via
 * {@link #readAdditionalSaveData}/set directly in {@link #create}, which persists them to the world
 * save but does **not** transmit them over the network at all (NBT read/write is disk persistence
 * only; only tracked/synched data reaches a client on spawn). This was invisible until a client-side
 * consumer first needed these fields ({@code zone.client.PlotValidityOverlay}'s live validity
 * check) -- every stake read back {@code null}/{@code 0} client-side, so the overlay always saw zero
 * matching stakes and never showed "valid" no matter how the plot was actually shaped. {@code
 * ownerCoreId} stays a plain, unsynced field -- nothing client-side needs it yet.
 *
 * <p><b>{@code plotSessionId} syncs as a plain {@code String}, not a custom {@code
 * Optional<UUID>} serializer (corrected same day)</b> -- a first attempt built a custom {@code
 * EntityDataSerializer}, which NeoForge flatly rejects unless registered through its own {@code
 * NeoForgeRegistries.Keys.ENTITY_DATA_SERIALIZERS} (a real crash: "Modded EntityDataSerializers
 * must be registered..."), and doing that safely turned out to need the registry entry bound
 * *before* this class's own static fields are evaluated -- `ModEntities`'s registration of {@code
 * GhostPlotStakeEntity::new} triggers this class's `<clinit>` synchronously during mod
 * construction, well before NeoForge's `RegisterEvent` for custom registries ever fires, so a
 * `DeferredHolder#get()` at that point would just fail a different way. Encoding the UUID as its
 * string form (empty string = none) sidesteps the whole problem -- {@code
 * EntityDataSerializers.STRING} is a built-in, always-safe vanilla serializer.
 */
public class GhostPlotStakeEntity extends Display.BlockDisplay implements Leashable {

    // Generous search radius around the owning core -- a plot is expected to sit well within a
    // settlement's own perimeter, so this doesn't need PerimeterFit's much larger placement-radius
    // margin the way GhostPerimeterStakeEntity's own search does.
    private static final double SEARCH_RADIUS_BLOCKS = 500.0;

    private static final EntityDataAccessor<String> DATA_PLOT_SESSION_ID =
            SynchedEntityData.defineId(GhostPlotStakeEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> DATA_PLACEMENT_INDEX =
            SynchedEntityData.defineId(GhostPlotStakeEntity.class, EntityDataSerializers.INT);
    // Rope Fence rework (see decisions.md) -- at most two rope links to sibling posts in this same
    // session, each a stringified UUID (empty = none), same proven technique as DATA_PLOT_SESSION_ID
    // above. "Open" (either slot empty) is what lets a player resume/extend construction from this
    // post later by right-clicking it while holding a Plot Placement Stake for this session.
    private static final EntityDataAccessor<String> DATA_LINK_A =
            SynchedEntityData.defineId(GhostPlotStakeEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_LINK_B =
            SynchedEntityData.defineId(GhostPlotStakeEntity.class, EntityDataSerializers.STRING);

    private UUID ownerCoreId;
    private LeashData leashData;

    public GhostPlotStakeEntity(EntityType<? extends GhostPlotStakeEntity> type, Level level) {
        super(type, level);
    }

    // Rope Fence rework, second pass (see decisions.md) -- this post is itself the leash
    // attachment point for its own real vanilla rope, in either direction (leashee of its
    // predecessor, or holder for whichever post attaches to it next). No separate anchor entity is
    // needed the way the real block mechanic needs RopeAnchorEntity, since this class is already a
    // dedicated per-post entity.
    @Override
    public LeashData getLeashData() {
        return leashData;
    }

    @Override
    public void setLeashData(LeashData leashData) {
        this.leashData = leashData;
    }

    @Override
    public void tick() {
        super.tick();
        if (level() instanceof ServerLevel serverLevel) {
            Leashable.tickLeash(serverLevel, this);
        }
    }

    // Vanilla's own default (12.0) would auto-break the leash before this mod's own pull-back
    // mechanic (SettlemyntsMod's tick handler) ever gets a chance to act, now that
    // RopeConnections.MAX_ROPE_LENGTH_BLOCKS exceeds it -- see that constant's own doc.
    @Override
    public double leashSnapDistance() {
        return com.github.cerealklla.settlemynts.rope.RopeConnections.LEASH_SNAP_DISTANCE_OVERRIDE;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_PLOT_SESSION_ID, "");
        builder.define(DATA_PLACEMENT_INDEX, 0);
        builder.define(DATA_LINK_A, "");
        builder.define(DATA_LINK_B, "");
    }

    /**
     * {@code placementIndex} is this stake's position in the actual sequence it was placed in --
     * always {@code findBySession(...).size()} at the moment of creation (see {@code
     * PlotPlacementStakeItem}). Building the plot's polygon from stakes sorted by this index (not
     * angularly around a centroid) is what makes non-star-shaped plots -- an L, a C, anything -- work
     * at all (2026-09-27, see decisions.md same date). Removal is restricted to the highest currently-
     * alive index (enforced in {@code SettlemyntsMod}'s {@code RemovePlotStakePayload} handler), which
     * is what keeps this scheme gap-free without needing a separately persisted ever-incrementing
     * counter.
     */
    public static GhostPlotStakeEntity create(ServerLevel level, int x, int y, int z, UUID ownerCoreId, UUID plotSessionId, int placementIndex) {
        GhostPlotStakeEntity stake = new GhostPlotStakeEntity(ModEntities.GHOST_PLOT_STAKE.get(), level);
        // Position offset +0.5 on X/Z, compensated by a matching -0.5 render translation below -- see
        // GhostBlockDisplays#setTranslation's own doc for why (2026-09-29, live report: "very
        // difficult to right click"). The visual still lands exactly at block (x, y, z) as before;
        // only the entity's own (always X/Z-centered) interaction hitbox is now correctly aligned to
        // it instead of covering just its near quarter.
        stake.setPos(x + 0.5, y, z + 0.5);
        stake.ownerCoreId = ownerCoreId;
        stake.entityData.set(DATA_PLOT_SESSION_ID, plotSessionId == null ? "" : plotSessionId.toString());
        stake.entityData.set(DATA_PLACEMENT_INDEX, placementIndex);
        // Rope Fence Post's own default state, not Blocks.LANTERN (2026-09-29 rework) -- ghost plot
        // staking now marks its posts with the same block visual the real, craftable item uses.
        GhostBlockDisplays.setBlockState(stake, ModBlocks.ROPE_FENCE_POST.get().defaultBlockState());
        GhostBlockDisplays.setTranslation(stake, -0.5f, 0f, -0.5f);
        level.addFreshEntity(stake);
        return stake;
    }

    public UUID getOwnerCoreId() {
        return ownerCoreId;
    }

    public UUID getPlotSessionId() {
        String value = entityData.get(DATA_PLOT_SESSION_ID);
        return value.isEmpty() ? null : UUID.fromString(value);
    }

    public int getPlacementIndex() {
        return entityData.get(DATA_PLACEMENT_INDEX);
    }

    public boolean hasOpenSlot() {
        return entityData.get(DATA_LINK_A).isEmpty() || entityData.get(DATA_LINK_B).isEmpty();
    }

    /** No-op if both slots are already taken -- callers should check {@link #hasOpenSlot()} first. */
    public void addLink(UUID otherId) {
        if (entityData.get(DATA_LINK_A).isEmpty()) {
            entityData.set(DATA_LINK_A, otherId.toString());
        } else if (entityData.get(DATA_LINK_B).isEmpty()) {
            entityData.set(DATA_LINK_B, otherId.toString());
        }
    }

    /** Clears whichever slot currently points at {@code otherId}, if any -- used when the other post is removed, so this one's slot re-opens. */
    public void clearLink(UUID otherId) {
        if (otherId.toString().equals(entityData.get(DATA_LINK_A))) {
            entityData.set(DATA_LINK_A, "");
        } else if (otherId.toString().equals(entityData.get(DATA_LINK_B))) {
            entityData.set(DATA_LINK_B, "");
        }
    }

    /** Every sibling post UUID this one currently has a rope to (0-2 entries). */
    public List<UUID> getLinkIds() {
        List<UUID> ids = new ArrayList<>(2);
        String a = entityData.get(DATA_LINK_A);
        String b = entityData.get(DATA_LINK_B);
        if (!a.isEmpty()) {
            ids.add(UUID.fromString(a));
        }
        if (!b.isEmpty()) {
            ids.add(UUID.fromString(b));
        }
        return ids;
    }

    /** Every currently-loaded stake belonging to one specific in-progress plot. */
    public static List<GhostPlotStakeEntity> findBySession(ServerLevel level, UUID coreId, UUID plotSessionId) {
        Entity core = level.getEntity(coreId);
        if (core == null) {
            return List.of();
        }
        AABB searchBox = new AABB(
                core.getX() - SEARCH_RADIUS_BLOCKS, level.getMinY(), core.getZ() - SEARCH_RADIUS_BLOCKS,
                core.getX() + SEARCH_RADIUS_BLOCKS, level.getMaxY(), core.getZ() + SEARCH_RADIUS_BLOCKS);
        return level.getEntities(ModEntities.GHOST_PLOT_STAKE.get(), searchBox,
                stake -> plotSessionId.equals(stake.getPlotSessionId()));
    }

    /** {@link #findBySession} sorted by {@link #getPlacementIndex()} -- the actual polygon vertex order. */
    public static List<GhostPlotStakeEntity> findBySessionInPlacementOrder(ServerLevel level, UUID coreId, UUID plotSessionId) {
        List<GhostPlotStakeEntity> stakes = new ArrayList<>(findBySession(level, coreId, plotSessionId));
        stakes.sort(Comparator.comparingInt(GhostPlotStakeEntity::getPlacementIndex));
        return stakes;
    }

    private GhostTownHallCoreEntity findOwnerCore() {
        if (!(level() instanceof ServerLevel serverLevel) || ownerCoreId == null) {
            return null;
        }
        return serverLevel.getEntity(ownerCoreId) instanceof GhostTownHallCoreEntity core ? core : null;
    }

    /** Visible only to the owning settlement's current Town Planners -- see class doc. */
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

        // Plot Stakes rework (2026-09-30): right-clicking an open post while holding a bound Plot
        // Stakes item always adopts this post's own PlotID onto the item (CurrentPlotID), then
        // attaches/resumes the leash here instead of opening the Plot Stake screen -- "go back to a
        // Fence Post which does not have 2 ropes on it." This is unconditional now, not gated on the
        // item already matching this post's session -- the whole point of a reusable Plot Stakes item
        // is that right-clicking any of your own posts switches which in-progress plot it's currently
        // extending. Only a stake for a *different settlement* is rejected outright; any other held
        // item falls through to the existing screen-open behavior below, unchanged.
        ItemStack held = player.getItemInHand(hand);
        if (held.is(ModItems.PLOT_PLACEMENT_STAKE.get())) {
            PlotSessionData heldSession = held.get(ModItems.PLOT_SESSION_DATA);
            if (heldSession != null) {
                if (!heldSession.ownerCoreId().equals(getOwnerCoreId())) {
                    player.sendSystemMessage(Component.literal("This Fence Post belongs to a different settlement."));
                    return InteractionResult.SUCCESS;
                }
                if (!hasOpenSlot()) {
                    player.sendSystemMessage(Component.literal("This Fence Post already has 2 ropes attached."));
                    return InteractionResult.SUCCESS;
                }
                held.set(ModItems.PLOT_SESSION_DATA, new PlotSessionData(heldSession.ownerCoreId(), getPlotSessionId()));
                RopeFenceLeash.setGhostAnchor(serverPlayer, this);
                player.sendSystemMessage(Component.literal("Rope attached -- continue placing Fence Posts to extend the perimeter."));
                return InteractionResult.SUCCESS;
            }
        }

        // Road Access Flag rework (2026-09-30, same shape as the Plot Stakes rework above): the flag
        // item is now a reusable cursor too -- right-clicking a post while holding it (bound or not)
        // adopts this post's own PlotID, which is what makes its "valid placement spots" preview
        // (SettlemyntsMod#onServerTick) start showing for this plot. No rope/open-slot concept here,
        // unlike Plot Stakes -- just the binding.
        if (held.is(ModItems.ROAD_ACCESS_FLAG.get())) {
            PlotSessionData heldSession = held.get(ModItems.PLOT_SESSION_DATA);
            if (heldSession != null) {
                if (!heldSession.ownerCoreId().equals(getOwnerCoreId())) {
                    player.sendSystemMessage(Component.literal("This Fence Post belongs to a different settlement."));
                    return InteractionResult.SUCCESS;
                }
                held.set(ModItems.PLOT_SESSION_DATA, new PlotSessionData(heldSession.ownerCoreId(), getPlotSessionId()));
                player.sendSystemMessage(Component.literal("Road Access Flag bound to this plot -- valid placement spots are now shown along its perimeter."));
                return InteractionResult.SUCCESS;
            }
        }

        UUID plotSessionId = getPlotSessionId();
        List<GhostPlotStakeEntity> stakes = ownerCoreId != null && plotSessionId != null
                ? findBySessionInPlacementOrder(serverLevel, ownerCoreId, plotSessionId)
                : List.of();
        boolean valid = false;
        if (stakes.size() >= 3) {
            List<PlotGeometry.StakePoint> ordered = new ArrayList<>(stakes.size());
            for (GhostPlotStakeEntity s : stakes) {
                ordered.add(new PlotGeometry.StakePoint(s.getX(), s.getZ()));
            }
            valid = com.github.cerealklla.cartographyr.geo.PlotValidity.hasValidArea(PlotGeometry.polygonFromStakes(ordered));
        }
        boolean hasRoadAccessFlag = ownerCoreId != null && plotSessionId != null
                && GhostRoadAccessFlagEntity.findBySession(serverLevel, ownerCoreId, plotSessionId) != null;
        PacketDistributor.sendToPlayer(serverPlayer, new OpenPlotStakeScreenPayload(getId(), stakes.size(), valid, hasRoadAccessFlag));
        return InteractionResult.SUCCESS;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Plot Fence Post");
    }

    /** Discards this entity -- no item handed back (the stake item is reusable/infinite, same reasoning as {@code founding.GhostPerimeterStakeEntity#remove}). */
    public void remove(Player player) {
        discard();
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        ownerCoreId = input.read("OwnerCoreId", UUIDUtil.CODEC).orElse(null);
        // Pushed into synched data too (not just the plain fields above) -- a world reload doesn't
        // re-run create(), so without this a reloaded stake would sync as session-less/index-0 to any
        // freshly-connecting client even though its NBT is fine. See class doc for the full bug.
        UUID plotSessionId = input.read("PlotSessionId", UUIDUtil.CODEC).orElse(null);
        entityData.set(DATA_PLOT_SESSION_ID, plotSessionId == null ? "" : plotSessionId.toString());
        entityData.set(DATA_PLACEMENT_INDEX, input.getIntOr("PlacementIndex", 0));
        entityData.set(DATA_LINK_A, input.getStringOr("LinkA", ""));
        entityData.set(DATA_LINK_B, input.getStringOr("LinkB", ""));
        this.readLeashData(input);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.storeNullable("OwnerCoreId", UUIDUtil.CODEC, ownerCoreId);
        output.storeNullable("PlotSessionId", UUIDUtil.CODEC, getPlotSessionId());
        output.putInt("PlacementIndex", getPlacementIndex());
        output.putString("LinkA", entityData.get(DATA_LINK_A));
        output.putString("LinkB", entityData.get(DATA_LINK_B));
        if (leashData != null) {
            this.writeLeashData(output, leashData);
        }
    }
}
