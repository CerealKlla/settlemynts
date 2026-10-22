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
import net.minecraft.world.inventory.ContainerData;
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
 * <p>Burn model mirrors vanilla's own {@code AbstractFurnaceBlockEntity} exactly (real correction,
 * 2026-10-22, second round -- the original once-a-minute lump-sum design is gone): every tick, if
 * dark and {@link #manuallyEnabled} and the fuel slot holds any Coal/Charcoal for a built plot,
 * burn exactly one piece for {@code MINUTE_TICKS / fuelCostForTier(tier)} ticks -- long enough that
 * burning that many pieces back to back adds up to one real minute lit, so the per-tier number
 * stays a true "coal per minute" rate, just paid out incrementally instead of up front. Reacts the
 * same tick fuel is deposited or the toggle flips on (there is no separate poll/cadence -- this is
 * the ordinary per-tick {@code serverTick}, same as a furnace's own). The base (un-doubled) radius
 * is always free, scaled by the same Tier -- see {@link WatchTowerLightController#recompute},
 * called only when the lit/dark state actually flips.
 */
public class WatchTowerBeaconBlockEntity extends BlockEntity implements MenuProvider, IMenuProviderExtension, com.github.cerealklla.nuclyus.ping.Pingable, com.github.cerealklla.settlemynts.guardhouse.DefenseSpendingRecipient {

    private UUID settlementCoreId;
    private UUID plotId;
    private boolean manuallyEnabled = true;
    private int litTimeRemaining;
    private int litTotalTime;
    private int tickCounter;
    // "Defense Spending" eligibility (explicit user request, 2026-10-22, alongside making this Plot
    // Type NPC-owned-only) -- mirrors guardhouse.GuardhouseBlockEntity#defenseSpendingAllocation
    // exactly: set only via SettlemyntsMod#setDefenseSpending (Town Hall Core's own screen), moved
    // at midnight by guardhouse.DefenseSpendingTransfer into this plot's own regular boxes (not the
    // fuel slot, which only ever accepts Coal/Charcoal) -- from there the existing Planned Inventory
    // midnight-trading engine can spend it buying Coal from another plot's Shop, same as any other
    // resource target, with zero new buy logic needed.
    private int defenseSpendingAllocation;

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

    // Real report, 2026-10-22: "it just says 'Unlit' no matter what" -- even with plenty of fuel in
    // the slot. The fuel SLOT itself was always live (ordinary vanilla container sync), but
    // litTimeRemaining/litTotalTime were only ever sent once, in the extra-data payload at the exact
    // moment the screen opened (a deliberate "snapshot, close/reopen to refresh" scope decision at
    // the time) -- so depositing fuel while the screen was already open could never visibly change
    // the status text or bar at all, no matter how long you waited. Mirrors vanilla's own
    // AbstractFurnaceBlockEntity#dataAccess exactly (confirmed against the decompiled source): a real
    // synced ContainerData, auto-broadcast by AbstractContainerMenu every tick a value actually
    // changes, not a one-shot read.
    private static final int DATA_LIT_TIME = 0;
    private static final int DATA_LIT_DURATION = 1;
    private final ContainerData dataAccess = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case DATA_LIT_TIME -> litTimeRemaining;
                case DATA_LIT_DURATION -> litTotalTime;
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            switch (index) {
                case DATA_LIT_TIME -> litTimeRemaining = value;
                case DATA_LIT_DURATION -> litTotalTime = value;
            }
        }

        @Override
        public int getCount() {
            return 2;
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

    @Override
    public int defenseSpendingAllocation() {
        return defenseSpendingAllocation;
    }

    @Override
    public void setDefenseSpendingAllocation(int defenseSpendingAllocation) {
        this.defenseSpendingAllocation = Math.max(0, defenseSpendingAllocation);
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

    /** This Beacon's currently-desired illumination radius -- shared by {@link #serverTick} and {@link #onPing}. */
    public int currentRadius(ServerLevel level) {
        int baseRadius = WatchTowerConstants.baseRadiusForTier(resolveBuiltTier(level));
        return litTimeRemaining > 0 ? baseRadius * 2 : baseRadius;
    }

    // Real correction, 2026-10-22: "ping handlers are supposed to specify how frequently they
    // respond to pings" -- Nuclyus' own ping.Pingable doc is explicit that the interface carries no
    // throttle of its own precisely so each implementation designs its own ("it's up to the mod
    // registering the pingable to also design its own PingHandler"). A global cooldown, not per-
    // player (via PingCooldown) -- recompute() is shared Beacon state, not something that benefits
    // from running again just because a second player happens to be standing nearby too.
    private static final long PING_RECOMPUTE_COOLDOWN_TICKS = 200; // 10 real seconds.
    private long lastPingRecomputeGameTime = Long.MIN_VALUE;

    /**
     * Explicit user request, 2026-10-22: "leverage the pingable implementation [Nuclyus'
     * {@code ping.Pingable}] for when a player is near one of these [Beacons] to force it to recheck
     * its own height." A grid column skipped during {@link WatchTowerLightController#recompute}
     * (unloaded chunk at the time) or left stale by terrain changing underneath it would otherwise
     * only get fixed on this Beacon's next lit/dark flip, which could be a long time away for a
     * continuously-fed tower. No registration/networking needed here at all -- implementing this
     * interface is the entire integration; Nuclyus' own {@code ping.client.ClientPingScanner}/
     * {@code ping.PingDispatcher} already find and re-validate every nearby {@code Pingable}
     * automatically, dispatching up to 2x/sec per nearby player -- throttled here so standing near
     * a Beacon doesn't force a full grid recompute that often for no reason.
     */
    @Override
    public void onPing(com.github.cerealklla.nuclyus.ping.PingPacket ping) {
        if (settlementCoreId == null || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        long now = serverLevel.getGameTime();
        if (now - lastPingRecomputeGameTime < PING_RECOMPUTE_COOLDOWN_TICKS) {
            return;
        }
        lastPingRecomputeGameTime = now;
        WatchTowerLightController.recompute(serverLevel, this, currentRadius(serverLevel));
    }

    private int countFuel() {
        return fuelContainer.getItem(0).getCount();
    }

    private void consumeFuel(int amount) {
        fuelContainer.getItem(0).shrink(amount);
    }

    private static final com.github.cerealklla.settlemynts.zone.ShopResource FUEL_RESOURCE =
            com.github.cerealklla.settlemynts.zone.ShopResource.ofTag(net.minecraft.tags.ItemTags.COALS);

    /**
     * Explicit user request, 2026-10-22: "when the stack runs out, check the plot and auto-pull in a
     * new stack... should be very low on ticks that way, but want to prevent light flickering." Only
     * ever called from {@link #serverTick} at the exact moment a fresh piece of fuel is actually
     * needed (the same per-tick check that decides whether to ignite) -- not a separate periodic
     * poll, so it's naturally as infrequent as the burn rate itself (every {@code burnTicks}, at most
     * once a real minute even at Tier 5) with no extra throttle needed. Pulls a full stack at once
     * (not just one piece), so most future ignitions for a long while don't need to repeat this at
     * all. Runs BEFORE the ignite check in the same tick the old piece would otherwise expire, so a
     * plot with any Coal/Charcoal stocked never actually goes dark for even one tick.
     */
    private void pullFuelFromPlot(ServerLevel level) {
        if (plotId == null) {
            return;
        }
        List<Container> plotBoxes = Settlemynts.resolvePlotBoxes(level, plotId);
        List<ItemStack> pulled = com.github.cerealklla.settlemynts.zone.ContainerWithdraw.drain(plotBoxes, FUEL_RESOURCE, 64);
        for (ItemStack stack : pulled) {
            com.github.cerealklla.settlemynts.zone.ContainerDeposit.depositIntoAny(List.of(fuelContainer), stack);
        }
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
        boolean wasLit = litTimeRemaining > 0;
        if (wasLit) {
            litTimeRemaining--;
        }

        // Real production incident, 2026-10-22: this used to call resolveBuiltTier(level)
        // unconditionally every tick -- it bottoms out in Blueprynts#getConstructionBoxTier, a live
        // level.getBlockEntity(boxPos) read, not a flat index. That's fine once a minute (the old
        // cadence), but every tick for every Beacon caused an 8 TPS server-wide stall from the moment
        // the fix shipped -- confirmed via "neoforge tps" (8.1 TPS) and the fact that every slow tick
        // was unaccounted for in TickProfiler's own named categories (this code was never wrapped in
        // one). Only resolve it when actually dark and enabled -- once lit, this is skipped entirely
        // until the next time fuel runs out, same cadence the pre-regression code had.
        if (litTimeRemaining <= 0 && manuallyEnabled) {
            int builtTier = resolveBuiltTier(level);
            if (builtTier > 0) {
                if (countFuel() <= 0) {
                    pullFuelFromPlot(level);
                }
                if (countFuel() > 0) {
                    int required = WatchTowerConstants.fuelCostForTier(builtTier);
                    int burnTicks = Math.max(1, WatchTowerConstants.MINUTE_TICKS / required);
                    consumeFuel(1);
                    litTimeRemaining = burnTicks;
                    litTotalTime = burnTicks;
                }
            }
        }

        boolean nowLit = litTimeRemaining > 0;
        if (nowLit != wasLit) {
            WatchTowerLightController.recompute(level, this, currentRadius(level));
        }
        setChanged();
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Watch Tower Beacon");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        boolean canManage = canManage(player);
        int builtTier = level instanceof ServerLevel serverLevel ? resolveBuiltTier(serverLevel) : 0;
        int requiredFuel = builtTier > 0 ? WatchTowerConstants.fuelCostForTier(builtTier) : 0;
        return new WatchTowerBeaconMenu(ModMenus.WATCH_TOWER_BEACON.get(), containerId, inventory, fuelContainer,
                dataAccess, manuallyEnabled, canManage, getBlockPos(), requiredFuel);
    }

    @Override
    public void writeClientSideData(AbstractContainerMenu menu, RegistryFriendlyByteBuf buf) {
        WatchTowerBeaconMenu beaconMenu = (WatchTowerBeaconMenu) menu;
        buf.writeBoolean(beaconMenu.manuallyEnabled());
        buf.writeBoolean(beaconMenu.canManage());
        buf.writeBlockPos(beaconMenu.beaconPos());
        buf.writeVarInt(beaconMenu.requiredFuel());
    }

    /** DEBUG ONLY, added 2026-10-22 while chasing a real "it just says Unlit no matter what" report
     * -- real ground-truth dump instead of theorizing further. See {@code debug.DebugCommands
     * #watchTowerInfo}. */
    public String debugDump(ServerLevel level) {
        int builtTier = resolveBuiltTier(level);
        return "settlementCoreId=" + settlementCoreId + " plotId=" + plotId
                + " builtTier=" + builtTier
                + " manuallyEnabled=" + manuallyEnabled
                + " litTimeRemaining=" + litTimeRemaining + " litTotalTime=" + litTotalTime
                + " tickCounter=" + tickCounter
                + " fuelCount=" + countFuel()
                + " requiredFuel=" + (builtTier > 0 ? WatchTowerConstants.fuelCostForTier(builtTier) : -1);
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
        defenseSpendingAllocation = input.getIntOr("DefenseSpendingAllocation", 0);
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
        output.putInt("DefenseSpendingAllocation", defenseSpendingAllocation);
        ValueOutput.TypedOutputList<BlockPos> list = output.list("DesiredLightPositions", BlockPos.CODEC);
        for (BlockPos pos : desiredLightPositions) {
            list.add(pos);
        }
    }
}
