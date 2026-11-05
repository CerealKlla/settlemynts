package com.github.cerealklla.settlemynts.resident;

import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.cartographyr.api.Cartography;
import com.github.cerealklla.cartographyr.geo.EntityId;
import com.github.cerealklla.cartographyr.geo.GeographicEntity;
import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * The "resident" of an NPC-owned plot (design doc Section 14a, added 2026-10-05, explicit user
 * request: "When a plot is owned by NPC I want an actual villager to spawn and walk around that
 * plot"). A real, fully-vanilla-rendering {@code Villager} subclass (same reasoning as {@code
 * ZombieVillager}/{@code WanderingTrader} both being {@code AbstractVillager} subclasses, not a
 * separate concept) -- not a non-physical ghost marker like most of this mod's other entities, and
 * not {@code GuardEntity}'s {@code PathfinderMob} shape either, since "an actual Villager" was
 * explicit.
 *
 * <p><b>Deliberately skips vanilla's Brain-driven behavior</b> (bed-claiming, job-site-claiming,
 * trading, wandering off) -- confirmed via the decompiled source that {@code Villager} runs
 * entirely on its Brain, not the {@code goalSelector}/{@code targetSelector} system every other
 * custom {@code Mob} in this suite uses. {@link #customServerAiStep} never calls {@code
 * super.customServerAiStep} (Villager's own, which ticks the Brain) -- the Brain object still
 * exists (never ticked, so inert) but this resident instead patrols its own plot's "Town Proper"
 * buffer polygon via a plain {@link ResidentPatrolGoal}, the exact same mechanism {@code
 * GuardEntity}/{@code GuardPatrolAreaGoal} already use for the identical "confine to this plot's
 * real polygon" problem (see {@link #resolvePatrolArea}, copied from {@code GuardEntity} almost
 * verbatim). Left with profession {@code VillagerProfession.NONE} (the constructor default) --
 * never trades, never claims a job site.
 *
 * <p><b>No loot/XP on death</b> (explicit user request) -- a plain vanilla {@code Villager} already
 * grants 0 XP (confirmed via the decompiled source: {@code getBaseExperienceReward} isn't
 * overridden, defaults to 0) and vanilla's own loot table for villagers is empty, so neither needs
 * suppressing here. {@code Mob#getLootTable()} is {@code final} (can't be overridden to force an
 * empty table defensively), so {@link #skipDropExperience()} -- called in the constructor -- is the
 * one defensive measure actually available, kept as cheap insurance against a datapack ever adding
 * loot/XP to the real vanilla villager loot table this entity still reads from.
 *
 * <p><b>Respawn, 5 seconds after death</b> -- no separate timer/state needed. {@link
 * ResidentSpawnTicker} is an "is there a living resident for this plot right now?" scan every 5
 * seconds, same trigger shape {@code GuardSpawnTicker} already uses for guards -- a dead resident's
 * slot is naturally empty on the very next scan and gets refilled.
 *
 * <p><b>Zombie/lightning conversion prevented separately</b> -- see {@code
 * ResidentConversionGuard}, a {@code LivingConversionEvent.Pre} listener cancelling any conversion
 * targeting a {@code ResidentVillagerEntity}.
 */
public class ResidentVillagerEntity extends Villager implements com.github.cerealklla.settlemynts.zone.PlotNpc, com.github.cerealklla.nuclyus.statbar.StatBarOwner {

    private final com.github.cerealklla.settlemynts.npc.NpcStuckWatchdog stuckWatchdog = new com.github.cerealklla.settlemynts.npc.NpcStuckWatchdog();

    /** See {@code farm.FarmerWorkerEntity#DATA_DISPLAY_NAME}'s own doc for why this is real {@link SynchedEntityData}, not a direct {@code NpcIdentityStore} lookup from client-side rendering code. */
    private static final EntityDataAccessor<String> DATA_DISPLAY_NAME =
            SynchedEntityData.defineId(ResidentVillagerEntity.class, EntityDataSerializers.STRING);

    private UUID settlementCoreId;
    private UUID plotId;
    private net.minecraft.core.BlockPos spawnPos;

    public ResidentVillagerEntity(EntityType<? extends Villager> type, Level level) {
        super(type, level);
        skipDropExperience();
        // Real report, 2026-10-17: "NPCs keep getting stuck up in trees" -- see
        // guardhouse.GuardEntity's own identical fix for the full writeup.
        this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.LEAVES, -1.0F);
    }

    /** Called once by {@link ResidentSpawnTicker} right after construction -- which plot's "Town Proper" buffer this resident patrols (see {@link #resolvePatrolArea}). */
    public void setPlotIdentity(UUID settlementCoreId, UUID plotId) {
        this.settlementCoreId = settlementCoreId;
        this.plotId = plotId;
    }

    public UUID plotId() {
        return plotId;
    }

    /** Called once by {@link ResidentSpawnTicker} right after construction -- this resident's own spawn point (its Plot Config Sign's position), the rescue destination {@link #stuckWatchdog} teleports back to if this resident ever gets stuck. */
    public void setSpawnPos(net.minecraft.core.BlockPos spawnPos) {
        this.spawnPos = spawnPos;
    }

    public UUID settlementCoreId() {
        return settlementCoreId;
    }

    /** Floating nameplate text (Nuclyus's stat-bar overlay) -- reads the already-synced {@link #DATA_DISPLAY_NAME}. */
    @Override
    public net.minecraft.network.chat.Component displayName() {
        String name = entityData.get(DATA_DISPLAY_NAME);
        return name.isEmpty() ? null : net.minecraft.network.chat.Component.literal(name);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_DISPLAY_NAME, "");
    }

    /** Keeps {@link #DATA_DISPLAY_NAME} in sync with this resident's real persisted identity -- stops re-checking once a real (non-placeholder) name is resolved. */
    private void syncDisplayName() {
        if (plotId == null) {
            return;
        }
        String current = entityData.get(DATA_DISPLAY_NAME);
        if (!current.isEmpty() && !current.startsWith("Unnamed ")) {
            return;
        }
        com.github.cerealklla.settlemynts.npc.NpcIdentityStore
                .find(new com.github.cerealklla.settlemynts.npc.NpcIdentityKey(plotId, com.github.cerealklla.settlemynts.npc.NpcRole.RESIDENT))
                .ifPresent(identity -> entityData.set(DATA_DISPLAY_NAME, identity.name()));
    }

    /** Same resolution chain as {@code GuardEntity#resolvePatrolArea} -- see that method's own doc. */
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
        return Cartography.getEntity(level, new EntityId(plot.cartographyrBufferEntityId()))
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
        this.goalSelector.addGoal(2, new ResidentPatrolGoal(this, 0.4));
        this.goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(4, new RandomLookAroundGoal(this));
    }

    /** See {@code guardhouse.GuardEntity#customServerAiStep}'s own doc -- the old heightmap-scan-based rescue is gone; this just hands off to the shared {@link com.github.cerealklla.settlemynts.npc.NpcStuckWatchdog}. */
    @Override
    protected void customServerAiStep(ServerLevel level) {
        // Deliberately never calls super.customServerAiStep (Villager's own) -- see class doc.
        stuckWatchdog.tick(this, spawnPos);
        syncDisplayName();
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.storeNullable("SettlementCoreId", UUIDUtil.CODEC, settlementCoreId);
        output.storeNullable("PlotId", UUIDUtil.CODEC, plotId);
        output.storeNullable("SpawnPos", net.minecraft.core.BlockPos.CODEC, spawnPos);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        settlementCoreId = input.read("SettlementCoreId", UUIDUtil.CODEC).orElse(null);
        plotId = input.read("PlotId", UUIDUtil.CODEC).orElse(null);
        spawnPos = input.read("SpawnPos", net.minecraft.core.BlockPos.CODEC).orElse(null);
    }
}
