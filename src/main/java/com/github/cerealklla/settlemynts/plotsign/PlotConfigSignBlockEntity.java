package com.github.cerealklla.settlemynts.plotsign;

import java.util.UUID;

import com.github.cerealklla.settlemynts.registration.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A Plot Config Sign's live identity -- design doc Section 14a. Just enough to resolve back to the
 * owning {@code founding.GhostTownHallCoreEntity}/{@code zone.PlotRecord} for permission checks and
 * the "Relocate Plot Sign" flow; the sign carries no other state of its own (unlike the Building
 * Supply Box, it has no funding/build progress to track).
 */
public class PlotConfigSignBlockEntity extends BlockEntity {

    private UUID settlementCoreId;
    private UUID plotId;
    // NPC-owned-plot resident villager (2026-10-05, see resident.ResidentSpawnTicker) -- every plot
    // already has exactly one of these signs, so it's the natural per-plot mutable home for "which
    // villager currently lives here," same role GuardhouseBlockEntity's own guardId slots play for
    // garrison guards.
    private UUID residentVillagerId;
    // Lumberyard/Farm worker NPCs (2026-10-05, see lumberyard.LumberjackSpawnTicker/farm.FarmerSpawnTicker)
    // -- same per-plot mutable "who currently lives/works here" slot as residentVillagerId above,
    // just for the two zone types that get a specialized worker instead of a generic resident.
    private UUID lumberjackWorkerId;
    private UUID farmerWorkerId;

    public PlotConfigSignBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PLOT_CONFIG_SIGN.get(), pos, state);
    }

    public UUID settlementCoreId() {
        return settlementCoreId;
    }

    public UUID plotId() {
        return plotId;
    }

    /** Only ever called once, immediately after placement -- see {@code PlotConfigSignSpawnTicker}/{@code PlotConfigSignRelocatorItem}. */
    public void setIdentity(UUID settlementCoreId, UUID plotId) {
        this.settlementCoreId = settlementCoreId;
        this.plotId = plotId;
        setChanged();
    }

    public UUID residentVillagerId() {
        return residentVillagerId;
    }

    public void setResidentVillagerId(UUID residentVillagerId) {
        this.residentVillagerId = residentVillagerId;
        setChanged();
    }

    public UUID lumberjackWorkerId() {
        return lumberjackWorkerId;
    }

    public void setLumberjackWorkerId(UUID lumberjackWorkerId) {
        this.lumberjackWorkerId = lumberjackWorkerId;
        setChanged();
    }

    public UUID farmerWorkerId() {
        return farmerWorkerId;
    }

    public void setFarmerWorkerId(UUID farmerWorkerId) {
        this.farmerWorkerId = farmerWorkerId;
        setChanged();
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        settlementCoreId = input.read("SettlementCoreId", UUIDUtil.CODEC).orElse(null);
        plotId = input.read("PlotId", UUIDUtil.CODEC).orElse(null);
        residentVillagerId = input.read("ResidentVillagerId", UUIDUtil.CODEC).orElse(null);
        lumberjackWorkerId = input.read("LumberjackWorkerId", UUIDUtil.CODEC).orElse(null);
        farmerWorkerId = input.read("FarmerWorkerId", UUIDUtil.CODEC).orElse(null);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.storeNullable("SettlementCoreId", UUIDUtil.CODEC, settlementCoreId);
        output.storeNullable("PlotId", UUIDUtil.CODEC, plotId);
        output.storeNullable("ResidentVillagerId", UUIDUtil.CODEC, residentVillagerId);
        output.storeNullable("LumberjackWorkerId", UUIDUtil.CODEC, lumberjackWorkerId);
        output.storeNullable("FarmerWorkerId", UUIDUtil.CODEC, farmerWorkerId);
    }
}
