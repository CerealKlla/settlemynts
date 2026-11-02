package com.github.cerealklla.settlemynts.murmur;

import java.util.UUID;

import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.bridge.BlueprintsConstructionBridge;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModBlockEntities;
import com.github.cerealklla.settlemynts.registration.ModBlocks;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.fml.ModList;

/**
 * A finalized Murmur Tower plot's own structure -- deliberately much simpler than {@code
 * watchtower.WatchTowerBeaconBlockEntity}: no fuel/burn model, no menu/GUI, always broadcasting
 * once bound to a built Tier. Placed as part of the Blueprint content itself (same as Watch
 * Tower's own Beacon), so it starts life with no plot/settlement identity at all and self-resolves
 * it from its own world position, mirroring {@code WatchTowerBeaconBlockEntity}'s own {@code
 * setLevel}/scheduled-tick-retry pattern exactly ("at all costs avoid non-event-driven chron jobs"
 * -- a continuous per-tick poll was never considered here either).
 */
public class MurmurTowerBlockEntity extends BlockEntity {

    static final int RESOLVE_RETRY_TICKS = 100; // 5 real seconds.

    private UUID settlementCoreId;
    private UUID plotId;

    public MurmurTowerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MURMUR_TOWER.get(), pos, state);
    }

    public UUID settlementCoreId() {
        return settlementCoreId;
    }

    public UUID plotId() {
        return plotId;
    }

    public void setIdentity(UUID settlementCoreId, UUID plotId) {
        this.settlementCoreId = settlementCoreId;
        this.plotId = plotId;
        setChanged();
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (settlementCoreId == null && level instanceof ServerLevel serverLevel) {
            serverLevel.scheduleTick(getBlockPos(), ModBlocks.MURMUR_TOWER.get(), RESOLVE_RETRY_TICKS);
        }
    }

    private void tryResolveIdentity(ServerLevel level) {
        Settlemynts.findPlotAt(level, getBlockPos()).ifPresent(handle -> {
            setIdentity(handle.settlementCoreId(), handle.plotId());
            MurmurTowerIndex.get(level.getServer()).put(handle.plotId(), GlobalPos.of(level.dimension(), getBlockPos()));
        });
    }

    /** Fires only at the exact future tick a previous identity-resolution attempt scheduled itself for -- never a continuous poll. */
    public void onScheduledTick(ServerLevel level) {
        if (settlementCoreId == null) {
            tryResolveIdentity(level);
            if (settlementCoreId == null) {
                level.scheduleTick(getBlockPos(), ModBlocks.MURMUR_TOWER.get(), RESOLVE_RETRY_TICKS);
            }
        }
    }

    /** Resolves this tower's plot's own bound Blueprint Tier (0 if unbound or Blueprynts isn't loaded) -- same guarded pattern {@code watchtower.WatchTowerBeaconBlockEntity#resolveBuiltTier} already uses, not {@code PlotRecord#tier}. */
    public int resolveBuiltTier(ServerLevel level) {
        if (settlementCoreId == null || !(level.getEntity(settlementCoreId) instanceof GhostTownHallCoreEntity core)) {
            return 0;
        }
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(plotId)).findFirst().orElse(null);
        if (plot == null) {
            return 0;
        }
        return ModList.get().isLoaded("blueprynts") ? BlueprintsConstructionBridge.resolveTier(level, plot) : 0;
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        settlementCoreId = input.read("SettlementCoreId", UUIDUtil.CODEC).orElse(null);
        plotId = input.read("PlotId", UUIDUtil.CODEC).orElse(null);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.storeNullable("SettlementCoreId", UUIDUtil.CODEC, settlementCoreId);
        output.storeNullable("PlotId", UUIDUtil.CODEC, plotId);
    }
}
