package com.github.cerealklla.settlemynts.lumberyard;

import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.EntityId;
import com.github.cerealklla.cartographyr.geo.GeographicEntity;
import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A Lumberyard plot's worker NPC (design doc Section 14a, added 2026-10-05, explicit user request:
 * "At a lumberyard an NPC should walk around within the plot planting Tree sapplings... and
 * automatically harvesting fully grown trees"). Same shape as {@code resident.ResidentVillagerEntity}
 * almost verbatim (plain profession-less {@code Villager}, Brain never ticked, patrols/works via a
 * plain {@code goalSelector} goal instead) -- the one real difference is {@link #resolvePatrolArea}
 * resolves the plot's *real* polygon ({@code PlotRecord#cartographyrPlotEntityId}), not the padded
 * "Town Proper" buffer {@code ResidentPatrolGoal} uses -- planting/harvesting must stay strictly
 * inside the plot itself, not its outer padding.
 */
public class LumberjackWorkerEntity extends Villager {

    private static final int UNDERGROUND_TELEPORT_THRESHOLD_BLOCKS = 5;

    private UUID settlementCoreId;
    private UUID plotId;

    public LumberjackWorkerEntity(EntityType<? extends Villager> type, Level level) {
        super(type, level);
        skipDropExperience();
    }

    /** Called once by {@link LumberjackSpawnTicker} right after construction. */
    public void setPlotIdentity(UUID settlementCoreId, UUID plotId) {
        this.settlementCoreId = settlementCoreId;
        this.plotId = plotId;
    }

    public UUID plotId() {
        return plotId;
    }

    /** Same resolution chain as {@code resident.ResidentVillagerEntity#resolvePatrolArea}, but the real plot polygon, not the buffer. */
    public Optional<Geometry.Polygon> resolvePatrolArea(ServerLevel level) {
        if (settlementCoreId == null || plotId == null) {
            return Optional.empty();
        }
        if (!(level.getEntity(settlementCoreId) instanceof GhostTownHallCoreEntity core)) {
            return Optional.empty();
        }
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(plotId)).findFirst().orElse(null);
        if (plot == null) {
            return Optional.empty();
        }
        return Cartography.getEntity(level, new EntityId(plot.cartographyrPlotEntityId()))
                .map(GeographicEntity::geometry)
                .filter(Geometry.Polygon.class::isInstance)
                .map(Geometry.Polygon.class::cast);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Villager.createAttributes();
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(2, new LumberjackWorkGoal(this, 0.4));
        this.goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(4, new RandomLookAroundGoal(this));
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        // Deliberately never calls super.customServerAiStep (Villager's own) -- see class doc.
        int blockX = Mth.floor(getX());
        int blockZ = Mth.floor(getZ());
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
        if (surfaceY - getY() > UNDERGROUND_TELEPORT_THRESHOLD_BLOCKS) {
            getNavigation().stop();
            teleportTo(blockX + 0.5, surfaceY, blockZ + 0.5);
        }
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.storeNullable("SettlementCoreId", UUIDUtil.CODEC, settlementCoreId);
        output.storeNullable("PlotId", UUIDUtil.CODEC, plotId);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        settlementCoreId = input.read("SettlementCoreId", UUIDUtil.CODEC).orElse(null);
        plotId = input.read("PlotId", UUIDUtil.CODEC).orElse(null);
    }
}
