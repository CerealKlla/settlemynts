package com.github.cerealklla.settlemynts.founding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import com.github.cerealklla.settlemynts.registration.ModEntities;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
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
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The Ghost Town Hall Core (design doc Sections 3, 5) -- a "ghost" placeholder marking the center
 * of a not-yet-finalized settlement, visible only to permitted players (currently just the
 * founder; the Town Planner permission system is Section 6, not built yet). Becomes a solid,
 * everyone-visible Town Hall Core once the settlement is finalized (Section 8) -- not implemented
 * yet either, since finalization (Sections 6-8) is a later milestone; this class only covers the
 * founding step (Section 5): a settlement construction site is cleared and this entity spawns at
 * its center.
 *
 * <p><b>Per-player visibility is real, not a client-side illusion</b> -- {@link
 * #broadcastToPlayer(ServerPlayer)} is the exact mechanism vanilla itself uses to hide spectators
 * from non-spectators (confirmed against the decompiled source, design doc Section 2): returning
 * {@code false} for a given player means the server simply never sends them this entity's spawn
 * packet. A player who isn't permitted genuinely cannot see or interact with this entity at all,
 * not merely "the client is told not to render it."
 *
 * <p><b>Known v1 simplification</b>: visibility is currently just "founder only" -- there is no
 * permission-granting UI yet (Section 6's "grant Town Planner" flow), so nobody else can be added
 * as a viewer. The translucent "ghost" look itself is also not implemented yet (design doc Section
 * 2 calls for partial transparency) -- {@code founding.client.GhostTownHallCoreRenderer} renders a
 * plain floating icon for now, same pattern as Yconomics' {@code LootBagRenderer}. Both are
 * intentional scope cuts for this first milestone, not oversights -- see decisions.md.
 */
public class GhostTownHallCoreEntity extends Entity {

    private UUID founderId;
    private String settlementName = "";
    private final Set<UUID> townPlanners = new HashSet<>();
    private boolean boundaryVisible;
    // Cartographyr's own EntityId#value() once this settlement has been registered there (design
    // doc Section 8) -- null until the first successful Finalize. Lets a later Finalize (re-fitting
    // the perimeter) update the existing Cartographyr entity's geometry instead of creating a
    // duplicate every time. Not a Cartographyr type directly, since GhostTownHallCoreEntity must
    // stay loadable even if Cartographyr's own classes aren't on the classpath in some hypothetical
    // future -- see decisions.md for why Cartographyr is currently a required (not optional)
    // dependency regardless, this is just extra caution for this one persisted field's shape.
    private Long cartographyrEntityId;
    // The settlement's *real* (unpadded) fitted-polygon entity id (design doc Section 11a-adjacent,
    // added 2026-09-26) -- separate from cartographyrEntityId (the padded polygon), so Lyfe's HUD
    // can distinguish "genuinely inside the built town" from "inside the settlement's own outer
    // padding buffer only." See SettlemyntsMod#registerSettlementCore.
    private Long cartographyrCoreEntityId;
    // Set true by SettlemyntsMod#finalizeSettlement on success (design doc Section 8). Drives which
    // buttons FoundingScreen shows -- "Get Perimeter Stake"/"Finalize" only make sense pre-finalize,
    // and a finalized settlement's Town Planners should see plot-stake controls instead (Section
    // 10-11a). Added 2026-09-26 after a playtest report that those buttons lingered post-finalize.
    private boolean finalized;

    // Plot subdivision (design doc Section 11a, added 2026-09-26). Each Town Planner works on at
    // most one plot at a time, but different Planners can each be mid-staking their own plot
    // concurrently -- keyed per-planner rather than one shared "current session" for the whole
    // settlement. Reset to no entry once that Planner's plot is finalized (see SettlemyntsMod's
    // FinalizePlotPayload handler), so their next "Get Plot Placement Stake" starts a fresh plot.
    private final Map<UUID, UUID> activePlotSessionByPlanner = new HashMap<>();
    private final List<PlotRecord> plots = new ArrayList<>();
    private boolean showPlotPerimeters;

    public GhostTownHallCoreEntity(EntityType<? extends GhostTownHallCoreEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true; // Sits exactly where it's created -- no falling/pushing.
    }

    public static GhostTownHallCoreEntity create(ServerLevel level, double x, double y, double z, UUID founderId) {
        GhostTownHallCoreEntity core = new GhostTownHallCoreEntity(ModEntities.GHOST_TOWN_HALL_CORE.get(), level);
        core.setPos(x, y, z);
        core.founderId = founderId;
        core.townPlanners.add(founderId); // The founder is always a Town Planner (design doc Section 6).
        level.addFreshEntity(core);
        return core;
    }

    public String getSettlementName() {
        return settlementName;
    }

    public void setSettlementName(String settlementName) {
        this.settlementName = settlementName;
    }

    /** Design doc Section 9: "View Settlement Boundaries" -- whether {@code GhostBoundaryWallEntity} instances currently exist for this settlement. Existence-gated, see that class's own doc; this flag just tracks which state we're in so the UI shows the right label and toggling twice quickly doesn't double-create/double-discard. */
    public boolean isBoundaryVisible() {
        return boundaryVisible;
    }

    public void setBoundaryVisible(boolean boundaryVisible) {
        this.boundaryVisible = boundaryVisible;
    }

    public Long getCartographyrEntityId() {
        return cartographyrEntityId;
    }

    public void setCartographyrEntityId(long cartographyrEntityId) {
        this.cartographyrEntityId = cartographyrEntityId;
    }

    public Long getCartographyrCoreEntityId() {
        return cartographyrCoreEntityId;
    }

    public void setCartographyrCoreEntityId(long cartographyrCoreEntityId) {
        this.cartographyrCoreEntityId = cartographyrCoreEntityId;
    }

    public boolean isFinalized() {
        return finalized;
    }

    public void setFinalized(boolean finalized) {
        this.finalized = finalized;
    }

    /** This planner's currently in-progress plot, if any -- {@code null} means their next "Get Plot Placement Stake" starts a fresh one. */
    public UUID getActivePlotSession(UUID plannerId) {
        return activePlotSessionByPlanner.get(plannerId);
    }

    public void setActivePlotSession(UUID plannerId, UUID plotSessionId) {
        if (plotSessionId == null) {
            activePlotSessionByPlanner.remove(plannerId);
        } else {
            activePlotSessionByPlanner.put(plannerId, plotSessionId);
        }
    }

    public List<PlotRecord> getPlots() {
        return List.copyOf(plots);
    }

    public void addPlot(PlotRecord plot) {
        plots.add(plot);
    }

    public boolean isShowPlotPerimeters() {
        return showPlotPerimeters;
    }

    public void setShowPlotPerimeters(boolean showPlotPerimeters) {
        this.showPlotPerimeters = showPlotPerimeters;
    }

    public boolean isFounder(UUID playerId) {
        return founderId != null && founderId.equals(playerId);
    }

    public boolean isTownPlanner(UUID playerId) {
        return townPlanners.contains(playerId);
    }

    public Set<UUID> getTownPlanners() {
        return Set.copyOf(townPlanners);
    }

    /** Only the founder can grant the permission (design doc Section 6: "the player [who placed the flag] can grant... permissions"). */
    public boolean grantTownPlanner(UUID granterId, UUID targetId) {
        if (!isFounder(granterId)) {
            return false;
        }
        return townPlanners.add(targetId);
    }

    /** Player-facing names of every current Town Planner, resolved from the server's player list (offline planners are skipped -- a known v1 limitation, see decisions.md). */
    public List<String> getTownPlannerNames(MinecraftServer server) {
        List<String> names = new ArrayList<>();
        for (UUID id : townPlanners) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            names.add(player != null ? player.getName().getString() : id.toString());
        }
        return names;
    }

    /**
     * Visible to any current Town Planner (design doc Section 6, extended 2026-09-26 from the
     * founder-only v1 simplification now that the real permission set exists) -- see the class
     * doc's "per-player visibility is real" note for why this is a genuine visibility gate, not a
     * client-side hint.
     */
    @Override
    public boolean broadcastToPlayer(ServerPlayer player) {
        return isTownPlanner(player.getUUID());
    }

    // Entity#isPickable() defaults to false -- without this override this entity would be
    // invisible to the game's own crosshair/interaction raycast even for a player who CAN see it
    // (the same real bug Yconomics' LootBagEntity hit first, see that mod's decisions.md).
    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (!level().isClientSide() && player instanceof ServerPlayer serverPlayer) {
            PacketDistributor.sendToPlayer(serverPlayer, new OpenFoundingScreenPayload(
                    getId(), settlementName, getTownPlannerNames(serverPlayer.level().getServer()), isFounder(serverPlayer.getUUID()), boundaryVisible, finalized, showPlotPerimeters));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Ghost Town Hall Core");
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false; // Immune -- a ghost placeholder can't be destroyed by combat/explosions.
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        // No synced data needed -- visibility is entirely server-side (broadcastToPlayer), and
        // nothing about this entity's appearance depends on client-visible state yet.
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        founderId = input.read("FounderId", UUIDUtil.CODEC).orElse(null);
        settlementName = input.getStringOr("SettlementName", "");
        townPlanners.clear();
        townPlanners.addAll(input.read("TownPlanners", UUIDUtil.CODEC_SET).orElse(Set.of()));
        boundaryVisible = input.getBooleanOr("BoundaryVisible", false);
        cartographyrEntityId = input.read("CartographyrEntityId", Codec.LONG).orElse(null);
        cartographyrCoreEntityId = input.read("CartographyrCoreEntityId", Codec.LONG).orElse(null);
        finalized = input.getBooleanOr("Finalized", false);
        showPlotPerimeters = input.getBooleanOr("ShowPlotPerimeters", false);
        plots.clear();
        plots.addAll(input.read("Plots", Codec.list(PlotRecord.CODEC)).orElse(List.of()));
        activePlotSessionByPlanner.clear();
        for (ActivePlotSessionEntry entry : input.read("ActivePlotSessions", Codec.list(ActivePlotSessionEntry.CODEC)).orElse(List.of())) {
            activePlotSessionByPlanner.put(entry.plannerId(), entry.plotSessionId());
        }
    }

    /** Persistence-only pairing for {@link #activePlotSessionByPlanner} -- a {@code Map<UUID, UUID>} has no direct Codec, so it round-trips as a list of these instead. */
    private record ActivePlotSessionEntry(UUID plannerId, UUID plotSessionId) {
        static final Codec<ActivePlotSessionEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("planner_id").forGetter(ActivePlotSessionEntry::plannerId),
                UUIDUtil.CODEC.fieldOf("plot_session_id").forGetter(ActivePlotSessionEntry::plotSessionId)
        ).apply(i, ActivePlotSessionEntry::new));
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.storeNullable("FounderId", UUIDUtil.CODEC, founderId);
        output.putString("SettlementName", settlementName);
        output.store("TownPlanners", UUIDUtil.CODEC_SET, Set.copyOf(townPlanners));
        output.putBoolean("BoundaryVisible", boundaryVisible);
        output.storeNullable("CartographyrEntityId", Codec.LONG, cartographyrEntityId);
        output.storeNullable("CartographyrCoreEntityId", Codec.LONG, cartographyrCoreEntityId);
        output.putBoolean("Finalized", finalized);
        output.putBoolean("ShowPlotPerimeters", showPlotPerimeters);
        output.store("Plots", Codec.list(PlotRecord.CODEC), List.copyOf(plots));
        List<ActivePlotSessionEntry> sessionEntries = new ArrayList<>();
        activePlotSessionByPlanner.forEach((plannerId, plotSessionId) -> sessionEntries.add(new ActivePlotSessionEntry(plannerId, plotSessionId)));
        output.store("ActivePlotSessions", Codec.list(ActivePlotSessionEntry.CODEC), sessionEntries);
    }
}
