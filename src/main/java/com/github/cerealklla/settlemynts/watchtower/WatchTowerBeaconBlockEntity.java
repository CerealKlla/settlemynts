package com.github.cerealklla.settlemynts.watchtower;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.bridge.BlueprintsConstructionBridge;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModBlockEntities;
import com.github.cerealklla.settlemynts.registration.ModMenus;
import com.github.cerealklla.settlemynts.zone.PlotPermissions;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.extensions.IMenuProviderExtension;

/**
 * A finalized Watch Tower plot's own structure (explicit user spec, 2026-10-22) -- its live
 * identity (mirrors {@code guardhouse.GuardhouseBlockEntity}), a 1-slot Coal/Charcoal fuel
 * {@link Container}, a manual on/off toggle for the fuel-doubling bonus, and the furnace-style
 * {@code litTimeRemaining}/{@code litTotalTime} pair (mirrors vanilla's own
 * {@code AbstractFurnaceBlockEntity}, confirmed against the decompiled source) purely for the UI's
 * flame-progress bar -- {@code litTimeRemaining > 0} is also literally "is the doubling bonus
 * currently active," no separate flag needed.
 *
 * <p>Burn model is a custom flat rate, NOT vanilla's per-item {@code litTime}: once a real minute
 * ({@link WatchTowerConstants#MINUTE_TICKS}), if {@link #manuallyEnabled} and the fuel slot holds
 * at least {@link WatchTowerConstants#fuelCostForTier} worth of Coal/Charcoal for the plot's own
 * built-structure Tier, consume exactly that much and relight for the next minute; otherwise the
 * bonus goes dark. The base (un-doubled) radius is always free, scaled by the same Tier -- see
 * {@link WatchTowerLightController#recompute}, called from the same once-a-minute check.
 */
public class WatchTowerBeaconBlockEntity extends BlockEntity implements MenuProvider, IMenuProviderExtension {

    private UUID settlementCoreId;
    private UUID plotId;
    private boolean manuallyEnabled = true;
    private int litTimeRemaining;
    private int litTotalTime;
    private int tickCounter;

    /** Every grid column this Beacon currently wants lit -- real placed positions, persisted so a
     * later radius shrink/removal can find and revert exactly what was placed, same "track exactly
     * what I placed, revert exactly that" discipline {@code roadway.RoadwayPaver} uses for terrain. */
    private List<BlockPos> desiredLightPositions = new ArrayList<>();

    /** Only accepts Coal/Charcoal -- see {@link #canPlaceFuel(ItemStack)}. */
    private final Container fuelContainer = new SimpleContainer(1) {
        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return canPlaceFuel(stack);
        }
    };

    public WatchTowerBeaconBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.WATCH_TOWER_BEACON.get(), pos, state);
    }

    private static boolean canPlaceFuel(ItemStack stack) {
        return stack.is(Items.COAL) || stack.is(Items.CHARCOAL);
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

    // Real report, 2026-10-22: "I wanted the custom block to be placed within the blueprynt itself,
    // like a light at the top of a Lighthouse" -- this block used to be externally spawned beside
    // the Plot Config Sign at Finalize time (WatchTowerSpawner, now deleted). Now it's just an
    // ordinary block an Ancestors Blueprint places wherever it wants (the tower's own lookout deck),
    // pasted by Blueprynts' real construction pipeline like any other block -- which means it starts
    // life with no plotId/settlementCoreId at all. It self-resolves those from its own world
    // position instead, the same way a Guard Kit Stand or worker NPC would -- a few seconds after
    // existing (not every tick -- see RESOLVE_RETRY_TICKS), via Settlemynts#findPlotAt, which is
    // real but bounded work (it iterates loaded Town Hall Cores, not every entity on the server --
    // confirmed safe before reusing it here, unlike ShopWishlist#computeDeficits' own real crash
    // earlier tonight). Finalize already registers the plot with Cartographyr before construction
    // ever starts, so by the time this block is actually pasted into the world the plot it's
    // standing on is always already resolvable.
    private static final int RESOLVE_RETRY_TICKS = 100; // 5 real seconds.

    private void tryResolveIdentity(ServerLevel level) {
        Settlemynts.findPlotAt(level, getBlockPos()).ifPresent(handle -> {
            setIdentity(handle.settlementCoreId(), handle.plotId());
            WatchTowerIndex.get(level.getServer()).put(handle.plotId(), GlobalPos.of(level.dimension(), getBlockPos()));
        });
    }

    public Container fuelContainer() {
        return fuelContainer;
    }

    public boolean manuallyEnabled() {
        return manuallyEnabled;
    }

    /** "Enable/Disable" toggle on {@code client.WatchTowerBeaconScreen} -- see {@code SettlemyntsMod#setWatchTowerEnabled}, the only caller (already re-checks {@link #canManage} itself). */
    public void setManuallyEnabled(boolean manuallyEnabled) {
        this.manuallyEnabled = manuallyEnabled;
        setChanged();
    }

    public int litTimeRemaining() {
        return litTimeRemaining;
    }

    public int litTotalTime() {
        return litTotalTime;
    }

    public List<BlockPos> desiredLightPositions() {
        return desiredLightPositions;
    }

    public void setDesiredLightPositions(List<BlockPos> desiredLightPositions) {
        this.desiredLightPositions = new ArrayList<>(desiredLightPositions);
        setChanged();
    }

    /** Resolves this Beacon's plot's own bound Blueprint Tier (0 if unbound or Blueprynts isn't loaded) -- same guarded pattern {@code guardhouse.GuardEntity#fundFastRespawn} already uses. */
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

    private int countFuel() {
        return fuelContainer.getItem(0).getCount();
    }

    private void consumeFuel(int amount) {
        fuelContainer.getItem(0).shrink(amount);
    }

    public void serverTick(ServerLevel level) {
        if (settlementCoreId == null) {
            tickCounter++;
            if (tickCounter >= RESOLVE_RETRY_TICKS) {
                tickCounter = 0;
                tryResolveIdentity(level);
            }
            return; // Nothing else to do until this Beacon knows which plot it's standing on.
        }
        if (litTimeRemaining > 0) {
            litTimeRemaining--;
        }
        tickCounter++;
        if (tickCounter < WatchTowerConstants.MINUTE_TICKS) {
            return;
        }
        tickCounter = 0;

        int builtTier = resolveBuiltTier(level);
        boolean becameLit = false;
        if (manuallyEnabled && builtTier > 0) {
            int required = WatchTowerConstants.fuelCostForTier(builtTier);
            if (countFuel() >= required) {
                consumeFuel(required);
                litTimeRemaining = WatchTowerConstants.MINUTE_TICKS;
                litTotalTime = WatchTowerConstants.MINUTE_TICKS;
                becameLit = true;
            }
        }
        if (!becameLit) {
            litTimeRemaining = 0;
        }

        int baseRadius = WatchTowerConstants.baseRadiusForTier(builtTier);
        int radius = becameLit ? baseRadius * 2 : baseRadius;
        WatchTowerLightController.recompute(level, this, radius);
        setChanged();
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Watch Tower Beacon");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        boolean canManage = canManage(player);
        // Real report, 2026-10-22: "nothing I'm doing is lighting the tower" -- turned out to just be
        // not enough fuel (the burn check needs the FULL per-minute amount up front, 10 + Tier*10,
        // minimum 20 even at Tier 1), with zero indication anywhere of what that number actually is.
        // Resolved here and shipped down so the screen can finally show it.
        int builtTier = level instanceof ServerLevel serverLevel ? resolveBuiltTier(serverLevel) : 0;
        int requiredFuel = builtTier > 0 ? WatchTowerConstants.fuelCostForTier(builtTier) : 0;
        return new WatchTowerBeaconMenu(ModMenus.WATCH_TOWER_BEACON.get(), containerId, inventory, fuelContainer,
                litTimeRemaining, litTotalTime, manuallyEnabled, canManage, getBlockPos(), requiredFuel);
    }

    @Override
    public void writeClientSideData(AbstractContainerMenu menu, RegistryFriendlyByteBuf buf) {
        WatchTowerBeaconMenu beaconMenu = (WatchTowerBeaconMenu) menu;
        buf.writeVarInt(beaconMenu.litTimeRemaining());
        buf.writeVarInt(beaconMenu.litTotalTime());
        buf.writeBoolean(beaconMenu.manuallyEnabled());
        buf.writeBoolean(beaconMenu.canManage());
        buf.writeBlockPos(beaconMenu.beaconPos());
        buf.writeVarInt(beaconMenu.requiredFuel());
    }

    /** Shared by {@link #createMenu} and {@code SettlemyntsMod#setWatchTowerEnabled}'s own re-validation. */
    public boolean canManage(Player player) {
        if (settlementCoreId == null || !(level instanceof ServerLevel serverLevel)
                || !(serverLevel.getEntity(settlementCoreId) instanceof GhostTownHallCoreEntity core)) {
            return false;
        }
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(plotId)).findFirst().orElse(null);
        return plot != null && PlotPermissions.canManage(plot, core, player.getUUID());
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        settlementCoreId = input.read("SettlementCoreId", UUIDUtil.CODEC).orElse(null);
        plotId = input.read("PlotId", UUIDUtil.CODEC).orElse(null);
        ContainerHelper.loadAllItems(input.childOrEmpty("Fuel"), ((SimpleContainer) fuelContainer).getItems());
        manuallyEnabled = input.getBooleanOr("ManuallyEnabled", true);
        litTimeRemaining = input.getIntOr("LitTimeRemaining", 0);
        litTotalTime = input.getIntOr("LitTotalTime", 0);
        desiredLightPositions = new ArrayList<>(input.listOrEmpty("DesiredLightPositions", BlockPos.CODEC).stream().toList());
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.storeNullable("SettlementCoreId", UUIDUtil.CODEC, settlementCoreId);
        output.storeNullable("PlotId", UUIDUtil.CODEC, plotId);
        ContainerHelper.saveAllItems(output.child("Fuel"), ((SimpleContainer) fuelContainer).getItems());
        output.putBoolean("ManuallyEnabled", manuallyEnabled);
        output.putInt("LitTimeRemaining", litTimeRemaining);
        output.putInt("LitTotalTime", litTotalTime);
        ValueOutput.TypedOutputList<BlockPos> list = output.list("DesiredLightPositions", BlockPos.CODEC);
        for (BlockPos pos : desiredLightPositions) {
            list.add(pos);
        }
    }
}
