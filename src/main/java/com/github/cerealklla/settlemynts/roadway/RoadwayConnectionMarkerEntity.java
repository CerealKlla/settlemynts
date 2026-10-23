package com.github.cerealklla.settlemynts.roadway;

import java.util.List;
import java.util.UUID;

import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.nuclyus.ping.PingCooldown;
import com.github.cerealklla.nuclyus.ping.PingPacket;
import com.github.cerealklla.nuclyus.ping.Pingable;
import com.github.cerealklla.settlemynts.founding.GhostBlockDisplays;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModEntities;
import com.github.cerealklla.settlemynts.registration.ModItems;
import com.github.cerealklla.settlemynts.registration.ModMobEffects;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;

/**
 * A permanent, indestructible marker along a Roadway Stake edge's own centerline (added 2026-10-06,
 * reworked 2026-10-24 into a {@link Pingable} -- see design doc Section 16 for the full
 * conversation this came out of). Spawned in a string along every edge at {@link
 * #MARKER_SPACING_BLOCKS} intervals.
 *
 * <p><b>Visibility reworked 2026-10-24</b> -- always broadcast to every player now ({@link
 * #broadcastToPlayer} unconditionally {@code true}), since {@link Pingable}'s client-side scanner
 * can only ever find entities the client already has broadcast to it; the old per-viewer hidden/
 * shown split moved into the *displayed block state* instead. Default is {@link Blocks#BARRIER} --
 * genuinely imperceptible in normal survival rendering, unlike a pale carpet which would still show
 * as a visible thin slab. A player holding the matching {@code ModItems#ROADWAY_STAKE} tool who
 * pings within range of a marker lights *that* marker up ({@link Blocks#RED_WOOL}) for **everyone**
 * nearby, not just the holder (a real behavior change from the old "entire network glows for
 * whoever's holding the tool, regardless of distance" design) -- reverts back to hidden a short
 * grace period after the last such ping, checked opportunistically in this entity's own {@link
 * #tick()} (a per-entity decay check, not a world-scanning periodic ticker -- the kind of
 * self-expiring-state tick this suite already uses elsewhere, e.g. a Plot Fence Post's own claim
 * TTL). The settlement-wide "Show Roadway Stakes" toggle still works too, now as an unconditional
 * "stay lit for everyone" override instead of a per-Town-Planner-only one -- an explicitly accepted
 * consequence of visibility becoming a shared field instead of a per-viewer decision, not
 * overlooked.
 *
 * <p>Any player who pings within {@link #PING_RANGE_BLOCKS} also gets/refreshes the "Traveler"
 * movement-speed buff, amplifier = (this marker's own road tier - 1), read directly off the real
 * {@link TieredRoadwayBlock} sitting just beneath the marker.
 *
 * <p><b>Permanent, not regenerated-and-discarded like before</b> -- {@code setInvulnerable(true)}
 * at creation, real persistence (not {@code .noSave()}), and the same "only automatically removed
 * when its own connection is actually disconnected" lifecycle {@link #regenerateAll} already gave
 * it (discard-and-rebuild on any graph change is unchanged; this rework only touches visibility and
 * adds the ping behavior).
 */
public class RoadwayConnectionMarkerEntity extends Display.BlockDisplay implements Pingable {

    private static final double SEARCH_RADIUS_BLOCKS = 500.0;
    private static final int MARKER_SPACING_BLOCKS = 4;
    private static final int PING_RANGE_BLOCKS = 15;
    private static final long TRAVELER_PING_COOLDOWN_TICKS = 50L; // 2.5 seconds.
    private static final int TRAVELER_BUFF_DURATION_TICKS = 20; // 1 second, explicit spec.
    private static final long LIT_GRACE_TICKS = 40L; // 2 seconds after the last tool-holder ping.

    private UUID ownerCoreId;
    private final PingCooldown travelerCooldown = new PingCooldown();
    private boolean lit;
    private long litUntilGameTime;

    public RoadwayConnectionMarkerEntity(EntityType<? extends RoadwayConnectionMarkerEntity> type, Level level) {
        super(type, level);
    }

    private static RoadwayConnectionMarkerEntity create(ServerLevel level, int x, int y, int z, UUID ownerCoreId) {
        RoadwayConnectionMarkerEntity marker = new RoadwayConnectionMarkerEntity(ModEntities.ROADWAY_CONNECTION_MARKER.get(), level);
        // Plain block-corner position, no +0.5 center offset -- unlike RoadwayStakeEntity (which
        // needs that offset PLUS a render-translation compensation to keep its INTERACTION hitbox
        // centered), this marker is never clicked (isPickable() false), so it should just follow
        // GhostRoadAccessPreviewEntity's own simpler convention. Real live bug, 2026-10-06: copying
        // the stake's position offset here without its matching translation compensation left the
        // torch model rendered visibly off-center within its own block.
        marker.setPos(x, y, z);
        marker.ownerCoreId = ownerCoreId;
        marker.setInvulnerable(true);
        GhostBlockDisplays.setBlockState(marker, Blocks.BARRIER.defaultBlockState());
        level.addFreshEntity(marker);
        return marker;
    }

    private static List<RoadwayConnectionMarkerEntity> findByOwnerCore(ServerLevel level, UUID coreId) {
        Entity core = level.getEntity(coreId);
        if (core == null) {
            return List.of();
        }
        AABB searchBox = new AABB(
                core.getX() - SEARCH_RADIUS_BLOCKS, level.getMinY(), core.getZ() - SEARCH_RADIUS_BLOCKS,
                core.getX() + SEARCH_RADIUS_BLOCKS, level.getMaxY(), core.getZ() + SEARCH_RADIUS_BLOCKS);
        return level.getEntities(ModEntities.ROADWAY_CONNECTION_MARKER.get(), searchBox, marker -> coreId.equals(marker.ownerCoreId));
    }

    /**
     * Discards every existing marker for this settlement, then rebuilds one string per currently-
     * existing connection -- called after any placement, direct connection, or removal changes the
     * graph (unchanged lifecycle from before this rework -- this is still how a disconnected edge's
     * markers get automatically removed, confirmed intentional, no new work needed for that). Since
     * connections are now a plain symmetric graph (no parent/child, see {@link RoadwayStakeEntity}'s
     * own doc), each stake's own connection list would otherwise walk every edge twice (once from
     * each end) -- a connection is only ever drawn once here, from whichever of its two stakes has
     * the lexicographically smaller UUID, an arbitrary but stable tie-break that needs no extra
     * bookkeeping. Cheap even for a sizeable road network: the search itself is bounded by {@link
     * RoadwayStakeEntity#findByOwnerCore}'s own radius, and marker count per edge is capped by the
     * same {@link RoadwayStakeEntity#MAX_CONNECTION_LENGTH_BLOCKS} that already bounds edge length.
     */
    public static void regenerateAll(ServerLevel level, UUID ownerCoreId) {
        for (RoadwayConnectionMarkerEntity existing : findByOwnerCore(level, ownerCoreId)) {
            existing.discard();
        }
        for (RoadwayStakeEntity stake : RoadwayStakeEntity.findByOwnerCore(level, ownerCoreId)) {
            for (UUID otherId : stake.getConnectionIds()) {
                if (stake.getUUID().compareTo(otherId) >= 0) {
                    continue; // Drawn once from the other end instead.
                }
                if (!(level.getEntity(otherId) instanceof RoadwayStakeEntity other)) {
                    continue;
                }
                int x1 = Mth.floor(stake.getX());
                int z1 = Mth.floor(stake.getZ());
                int x2 = Mth.floor(other.getX());
                int z2 = Mth.floor(other.getZ());
                List<Geometry.Polygon.Vertex> centerline = Geometry.Polygon.supercoverLine(
                        new Geometry.Polygon.Vertex(x1, z1), new Geometry.Polygon.Vertex(x2, z2));
                for (int i = 0; i < centerline.size(); i += MARKER_SPACING_BLOCKS) {
                    Geometry.Polygon.Vertex cell = centerline.get(i);
                    int markerY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cell.x(), cell.z());
                    create(level, cell.x(), markerY, cell.z(), ownerCoreId);
                }
            }
        }
    }

    @Override
    public void onPing(PingPacket ping) {
        if (!(level() instanceof ServerLevel level) || ownerCoreId == null) {
            return;
        }
        ServerPlayer player = ping.player();

        if (ping.distanceToPlayer() <= PING_RANGE_BLOCKS) {
            long now = level.getGameTime();
            if (travelerCooldown.isReady(player.getUUID(), now, TRAVELER_PING_COOLDOWN_TICKS)) {
                travelerCooldown.markHandled(player.getUUID(), now);
                int tier = resolveRoadTier(level);
                player.addEffect(new MobEffectInstance(ModMobEffects.TRAVELER, TRAVELER_BUFF_DURATION_TICKS, tier - 1, false, false, true));
            }
        }

        ItemStack held = player.getMainHandItem();
        boolean holdingMatchingTool = held.is(ModItems.ROADWAY_STAKE.get())
                && ownerCoreId.equals(held.get(ModItems.ROADWAY_STAKE_OWNER_CORE_ID));
        if (holdingMatchingTool && ping.distanceToPlayer() <= PING_RANGE_BLOCKS) {
            litUntilGameTime = level.getGameTime() + LIT_GRACE_TICKS;
        }
    }

    /** Reads the real paved road tier directly off the block this marker sits just above. Falls back to Tier 1 if, for any reason, it's not actually sitting on a real {@link TieredRoadwayBlock}. */
    private int resolveRoadTier(ServerLevel level) {
        BlockPos below = blockPosition().below();
        var state = level.getBlockState(below);
        if (state.getBlock() instanceof TieredRoadwayBlock) {
            return state.getValue(TieredRoadwayBlock.TIER);
        }
        return 1;
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel level) || ownerCoreId == null) {
            return;
        }
        boolean toggledOnForEveryone = level.getEntity(ownerCoreId) instanceof GhostTownHallCoreEntity core && core.isShowRoadwayStakes();
        boolean shouldBeLit = toggledOnForEveryone || level.getGameTime() <= litUntilGameTime;
        if (shouldBeLit == lit) {
            return;
        }
        lit = shouldBeLit;
        GhostBlockDisplays.setBlockState(this, lit ? Blocks.RED_WOOL.defaultBlockState() : Blocks.BARRIER.defaultBlockState());
    }

    /**
     * Always broadcasts now (2026-10-24) -- {@link Pingable}'s client-side scanner can only ever
     * find entities the client already has broadcast to it, so hiding this from a player entirely
     * (the old per-viewer behavior) would also hide it from pinging, defeating the whole point of
     * this rework. The old hidden/shown distinction lives in the displayed block state now instead
     * -- see this class's own doc.
     */
    @Override
    public boolean broadcastToPlayer(ServerPlayer player) {
        return true;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Roadway Connection Marker");
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        ownerCoreId = input.read("OwnerCoreId", UUIDUtil.CODEC).orElse(null);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.storeNullable("OwnerCoreId", UUIDUtil.CODEC, ownerCoreId);
    }
}
