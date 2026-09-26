package com.github.cerealklla.settlemynts.founding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.github.cerealklla.settlemynts.registration.ModEntities;

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
                    getId(), settlementName, getTownPlannerNames(serverPlayer.level().getServer()), isFounder(serverPlayer.getUUID()), boundaryVisible));
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
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.storeNullable("FounderId", UUIDUtil.CODEC, founderId);
        output.putString("SettlementName", settlementName);
        output.store("TownPlanners", UUIDUtil.CODEC_SET, Set.copyOf(townPlanners));
        output.putBoolean("BoundaryVisible", boundaryVisible);
    }
}
