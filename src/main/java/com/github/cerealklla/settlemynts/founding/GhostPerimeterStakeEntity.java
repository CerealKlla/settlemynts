package com.github.cerealklla.settlemynts.founding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.github.cerealklla.settlemynts.registration.ModEntities;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A Planned Perimeter Stake, placed (design doc Section 6). Visible only to the owning
 * settlement's Town Planners -- looked up by {@link #ownerCoreId} each time, rather than storing
 * its own copy of the planner set, so a stake's visibility always reflects the core's current
 * permissions (e.g. a newly-granted Town Planner immediately sees every existing stake, not just
 * ones placed after they were granted).
 *
 * <p>"Absolute" (design doc Section 6) pins this stake's position against the perimeter auto-fit
 * step (Section 7a, not built yet -- that's Milestone 3) -- e.g. a Planner deliberately placed it
 * along a riverbank and doesn't want the fit algorithm shrinking or growing that edge away from or
 * into the river. Capped at {@link #MAX_ABSOLUTE_STAKES} per settlement, enforced in {@code
 * SettlemyntsMod}'s {@code SetStakeAbsolutePayload} handler, not here.
 */
public class GhostPerimeterStakeEntity extends Entity {

    public static final int MAX_ABSOLUTE_STAKES = 5;

    // Not part of the original design doc -- added 2026-09-26 alongside making the Planned
    // Perimeter Stake item reusable (no longer consumed on placement). Without some cap, multiple
    // Town Planners freely re-placing the same item could pile up an unbounded number of stakes
    // for one settlement; enforced server-side in PlannedPerimeterStakeItem regardless of how many
    // stake items exist.
    public static final int MAX_STAKES_PER_SETTLEMENT = 20;

    // Design doc Section 6: "within a 500 foot radius from the Town Hall Core," converted to
    // blocks at the suite's 1 block ~= 1 meter ~= 3.28 ft rate (see SettlementFounding).
    public static final double MAX_PLACEMENT_RADIUS_FEET = 500.0;
    public static final double MAX_PLACEMENT_RADIUS_BLOCKS = MAX_PLACEMENT_RADIUS_FEET * 0.3048;

    private UUID ownerCoreId;
    private boolean absolute;
    private int placementIndex;

    public GhostPerimeterStakeEntity(EntityType<? extends GhostPerimeterStakeEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /**
     * {@code placementIndex} is this stake's position in the actual sequence it was placed in --
     * always {@code findByOwnerCore(...).size()} at the moment of creation (see {@code
     * PlannedPerimeterStakeItem}). Building the settlement's polygon from stakes sorted by this index
     * (not angularly around the core) is what makes non-star-shaped perimeters -- an L, a C, anything
     * -- work at all (2026-09-27, see decisions.md same date). Removal is restricted to the highest
     * currently-alive index (enforced in {@code SettlemyntsMod}'s {@code RemoveStakePayload} handler),
     * which is what keeps this scheme gap-free without needing a separately persisted
     * ever-incrementing counter.
     */
    public static GhostPerimeterStakeEntity create(ServerLevel level, double x, double y, double z, UUID ownerCoreId, int placementIndex) {
        GhostPerimeterStakeEntity stake = new GhostPerimeterStakeEntity(ModEntities.GHOST_PERIMETER_STAKE.get(), level);
        stake.setPos(x, y, z);
        stake.ownerCoreId = ownerCoreId;
        stake.placementIndex = placementIndex;
        level.addFreshEntity(stake);
        return stake;
    }

    public UUID getOwnerCoreId() {
        return ownerCoreId;
    }

    public boolean isAbsolute() {
        return absolute;
    }

    public void setAbsolute(boolean absolute) {
        this.absolute = absolute;
    }

    public int getPlacementIndex() {
        return placementIndex;
    }

    /**
     * Every currently-loaded stake belonging to {@code coreId} in {@code level} -- used to count
     * absolute stakes and (later, Milestone 3) to run the perimeter fit. Searches a box centered
     * on the owning core, generously larger than the 500 ft (~152 block) placement radius (design
     * doc Section 6) rather than the whole world, since a stake can never legitimately be placed
     * further out than that. Empty if the core itself can't be found (e.g. already finalized/
     * removed by the time this is called).
     */
    public static List<GhostPerimeterStakeEntity> findByOwnerCore(ServerLevel level, UUID coreId) {
        Entity core = level.getEntity(coreId);
        if (core == null) {
            return List.of();
        }
        double radius = MAX_PLACEMENT_RADIUS_BLOCKS + 16; // small margin, see the constant's own doc
        AABB searchBox = new AABB(
                core.getX() - radius, level.getMinY(), core.getZ() - radius,
                core.getX() + radius, level.getMaxY(), core.getZ() + radius);
        return level.getEntities(ModEntities.GHOST_PERIMETER_STAKE.get(), searchBox, stake -> coreId.equals(stake.getOwnerCoreId()));
    }

    /** {@link #findByOwnerCore} sorted by {@link #getPlacementIndex()} -- the actual polygon vertex order. */
    public static List<GhostPerimeterStakeEntity> findByOwnerCoreInPlacementOrder(ServerLevel level, UUID coreId) {
        List<GhostPerimeterStakeEntity> stakes = new ArrayList<>(findByOwnerCore(level, coreId));
        stakes.sort(Comparator.comparingInt(GhostPerimeterStakeEntity::getPlacementIndex));
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
        List<GhostPerimeterStakeEntity> siblings = ownerCoreId != null ? findByOwnerCore(serverLevel, ownerCoreId) : List.of();
        int absoluteCount = (int) siblings.stream().filter(GhostPerimeterStakeEntity::isAbsolute).count();
        PacketDistributor.sendToPlayer(serverPlayer, new OpenStakeScreenPayload(getId(), absolute, absoluteCount, MAX_ABSOLUTE_STAKES));
        return InteractionResult.SUCCESS;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Planned Perimeter Stake");
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    /**
     * Discards this entity (design doc Section 6: "remove the stake if they need to move it").
     * Doesn't hand an item back -- the stake item is reusable/infinite (see {@code
     * founding.PlannedPerimeterStakeItem}, "Get Perimeter Stake" is always available again), so
     * returning one on removal would just duplicate it (fixed 2026-09-26, a real playtest bug --
     * see decisions.md).
     */
    public void remove(Player player) {
        discard();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        // No synced data needed -- visibility is entirely server-side (broadcastToPlayer), and the
        // per-interaction OpenStakeScreenPayload snapshot covers everything the client needs to
        // render its own UI.
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        ownerCoreId = input.read("OwnerCoreId", UUIDUtil.CODEC).orElse(null);
        absolute = input.getBooleanOr("Absolute", false);
        placementIndex = input.getIntOr("PlacementIndex", 0);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.storeNullable("OwnerCoreId", UUIDUtil.CODEC, ownerCoreId);
        output.putBoolean("Absolute", absolute);
        output.putInt("PlacementIndex", placementIndex);
    }
}
