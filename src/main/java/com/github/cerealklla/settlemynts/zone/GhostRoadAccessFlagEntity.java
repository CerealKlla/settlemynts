package com.github.cerealklla.settlemynts.zone;

import java.util.List;
import java.util.UUID;

import com.github.cerealklla.settlemynts.founding.GhostBlockDisplays;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModEntities;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;

/**
 * The "nearest roadway" flag (design doc Section 10, black flame -- a placeholder art choice, see
 * below) -- exactly one may exist per in-progress plot session, placeable only on the plot's own
 * live perimeter (checked against the current stakes' polygon at placement time, since the plot
 * isn't finalized yet). Used to auto-orient the Blueprynts Building Supply Box/structure toward the
 * road once the plot finalizes. Required before Finalize is allowed at all (design doc Section 10:
 * "Finalize... is only clickable once... a roadway flag has been placed").
 *
 * <p>Same real bug already fixed once for {@link GhostPlotStakeEntity} (2026-09-29) -- {@code
 * plotSessionId} is real {@link SynchedEntityData} from the start here, not a plain NBT-only field,
 * so a client-side consumer never silently sees {@code null}. Synced as a plain {@code String}
 * (empty = none), not a custom {@code EntityDataSerializer} -- see {@code GhostPlotStakeEntity}'s
 * own doc for why a custom one doesn't work here (NeoForge's registry-timing problem). {@code
 * ownerCoreId} stays a plain, unsynced field, same reasoning as that class.
 *
 * <p>Visual placeholder: floats vanilla's {@code Blocks.SOUL_LANTERN} (a real block-model {@link
 * Display.BlockDisplay}, same technique as every other ghost marker in this mod) -- no vanilla
 * block has a literal black flame, so this is a stand-in until real art exists, same "v1 placeholder
 * art" precedent already used elsewhere in this suite.
 */
public class GhostRoadAccessFlagEntity extends Display.BlockDisplay {

    private static final double SEARCH_RADIUS_BLOCKS = 500.0;

    private static final EntityDataAccessor<String> DATA_PLOT_SESSION_ID =
            SynchedEntityData.defineId(GhostRoadAccessFlagEntity.class, EntityDataSerializers.STRING);

    private UUID ownerCoreId;

    public GhostRoadAccessFlagEntity(EntityType<? extends GhostRoadAccessFlagEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_PLOT_SESSION_ID, "");
    }

    public static GhostRoadAccessFlagEntity create(ServerLevel level, int x, int y, int z, UUID ownerCoreId, UUID plotSessionId) {
        GhostRoadAccessFlagEntity flag = new GhostRoadAccessFlagEntity(ModEntities.GHOST_ROAD_ACCESS_FLAG.get(), level);
        flag.setPos(x, y, z);
        flag.ownerCoreId = ownerCoreId;
        flag.entityData.set(DATA_PLOT_SESSION_ID, plotSessionId == null ? "" : plotSessionId.toString());
        GhostBlockDisplays.setBlockState(flag, Blocks.SOUL_LANTERN.defaultBlockState());
        level.addFreshEntity(flag);
        return flag;
    }

    public UUID getOwnerCoreId() {
        return ownerCoreId;
    }

    public UUID getPlotSessionId() {
        String value = entityData.get(DATA_PLOT_SESSION_ID);
        return value.isEmpty() ? null : UUID.fromString(value);
    }

    /** At most one flag per session -- {@code null} if none has been placed yet. */
    public static GhostRoadAccessFlagEntity findBySession(ServerLevel level, UUID coreId, UUID plotSessionId) {
        Entity core = level.getEntity(coreId);
        if (core == null) {
            return null;
        }
        AABB searchBox = new AABB(
                core.getX() - SEARCH_RADIUS_BLOCKS, level.getMinY(), core.getZ() - SEARCH_RADIUS_BLOCKS,
                core.getX() + SEARCH_RADIUS_BLOCKS, level.getMaxY(), core.getZ() + SEARCH_RADIUS_BLOCKS);
        List<GhostRoadAccessFlagEntity> found = level.getEntities(ModEntities.GHOST_ROAD_ACCESS_FLAG.get(), searchBox,
                flag -> plotSessionId.equals(flag.getPlotSessionId()));
        return found.isEmpty() ? null : found.get(0);
    }

    private GhostTownHallCoreEntity findOwnerCore() {
        if (!(level() instanceof ServerLevel serverLevel) || ownerCoreId == null) {
            return null;
        }
        return serverLevel.getEntity(ownerCoreId) instanceof GhostTownHallCoreEntity core ? core : null;
    }

    /** Visible only to the owning settlement's current Town Planners -- same rule as every other plot ghost marker. */
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
    public Component getDisplayName() {
        return Component.literal("Road Access Flag");
    }

    /** Discards this entity -- no item handed back, same reasoning as every other ghost stake/flag in this mod. */
    public void remove(Player player) {
        discard();
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        ownerCoreId = input.read("OwnerCoreId", UUIDUtil.CODEC).orElse(null);
        UUID plotSessionId = input.read("PlotSessionId", UUIDUtil.CODEC).orElse(null);
        entityData.set(DATA_PLOT_SESSION_ID, plotSessionId == null ? "" : plotSessionId.toString());
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.storeNullable("OwnerCoreId", UUIDUtil.CODEC, ownerCoreId);
        output.storeNullable("PlotSessionId", UUIDUtil.CODEC, getPlotSessionId());
    }
}
