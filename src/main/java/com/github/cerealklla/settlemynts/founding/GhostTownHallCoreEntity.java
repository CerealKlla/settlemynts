package com.github.cerealklla.settlemynts.founding;

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
import net.minecraft.world.phys.Vec3;

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

    public GhostTownHallCoreEntity(EntityType<? extends GhostTownHallCoreEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true; // Sits exactly where it's created -- no falling/pushing.
    }

    public static GhostTownHallCoreEntity create(ServerLevel level, double x, double y, double z, UUID founderId) {
        GhostTownHallCoreEntity core = new GhostTownHallCoreEntity(ModEntities.GHOST_TOWN_HALL_CORE.get(), level);
        core.setPos(x, y, z);
        core.founderId = founderId;
        level.addFreshEntity(core);
        return core;
    }

    /**
     * Founder-only for now (see class doc's "known v1 simplification") -- the real permission set
     * (Town Planners, per Section 6) doesn't exist yet.
     */
    @Override
    public boolean broadcastToPlayer(ServerPlayer player) {
        return founderId != null && founderId.equals(player.getUUID());
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
            // Placeholder until Section 6's permissions/staking UI exists -- this milestone only
            // covers founding (Section 5), not staking/finalization.
            serverPlayer.sendSystemMessage(Component.literal(
                    "Ghost Town Hall Core -- permissions and staking UI not yet implemented."));
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
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.storeNullable("FounderId", UUIDUtil.CODEC, founderId);
    }
}
