package com.github.cerealklla.settlemynts.guardhouse;

import java.util.UUID;

import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModBlockEntities;
import com.github.cerealklla.settlemynts.registration.ModMenus;
import com.github.cerealklla.settlemynts.zone.PlotPermissions;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.common.extensions.IMenuProviderExtension;

/**
 * A finalized Guardhouse plot's own structure (design doc Section 14a) -- its live identity
 * (mirrors {@code plotsign.PlotConfigSignBlockEntity}) and a real food {@link Container} ("a
 * Guardhouse-specific inventory, distinct from a shop's"). Right-clicking this block opens only the
 * food {@link Container} via {@link GuardhouseFoodMenu} -- a real custom Menu/Slot is needed (not
 * vanilla's plain {@code ChestMenu}) specifically so "food only" is actually enforced; see {@link
 * GuardhouseFoodSlot}'s own doc for why.
 *
 * <p>Guard Kit Stand overhaul (2026-10-16) -- the old per-slot {@code garrisonSlots}/{@code
 * garrisonGuardIds} bookkeeping this class used to own is gone entirely, replaced by real, placeable
 * {@code GuardKitStandEntity} structures (each its own Kyt binding, assigned guard, and respawn
 * timer -- see that class's own doc). This block now exists purely as the plot's food store:
 * {@link #totalFoodValue()} funds a dead guard's fast-respawn window (see {@code
 * GuardRespawnFunding}), consumed directly from {@link #foodContainer} at the moment a guard dies.
 *
 * <p>{@code preferredFoodValue} (added 2026-10-16, explicit user spec: "the Owner or Mayor/Town
 * Planners should be able to set the 'Preferred Food' count... used at midnight to buy enough food
 * to meet that threshold if enough room exists in the Food Store box") is set via a numeric field on
 * {@code client.GuardhouseFoodScreen} -- editable only for a player who can manage this plot, which
 * this block resolves itself in {@link #createMenu} and ships down via {@link IMenuProviderExtension}
 * (same pattern Kyt's own {@code loadout.KytEditorMenuProvider} uses), since a plain vanilla
 * menu-open packet has no room for extra data otherwise. See {@link GuardhouseFoodAutoBuy} for the
 * midnight purchase logic this threshold drives.
 */
public class GuardhouseBlockEntity extends BlockEntity implements MenuProvider, IMenuProviderExtension {

    private UUID settlementCoreId;
    private UUID plotId;
    private int preferredFoodValue;
    private int defenseSpendingAllocation;

    /** Only accepts items carrying {@link DataComponents#FOOD} -- see {@link #canPlaceFood(ItemStack)}. */
    private final Container foodContainer = new SimpleContainer(9) {
        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return canPlaceFood(stack);
        }
    };

    public GuardhouseBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.GUARDHOUSE.get(), pos, state);
    }

    private static boolean canPlaceFood(ItemStack stack) {
        return stack.get(DataComponents.FOOD) != null;
    }

    public UUID settlementCoreId() {
        return settlementCoreId;
    }

    public UUID plotId() {
        return plotId;
    }

    /** Only ever called once, immediately after placement -- see {@code GuardhouseSpawner}. */
    public void setIdentity(UUID settlementCoreId, UUID plotId) {
        this.settlementCoreId = settlementCoreId;
        this.plotId = plotId;
        setChanged();
    }

    public Container foodContainer() {
        return foodContainer;
    }

    public int preferredFoodValue() {
        return preferredFoodValue;
    }

    /** "Preferred Food" field + Save on {@code client.GuardhouseFoodScreen} -- see {@code SettlemyntsMod#setPreferredFood}, the only caller (already re-checks {@code zone.PlotPermissions#canManage} itself). */
    public void setPreferredFoodValue(int preferredFoodValue) {
        this.preferredFoodValue = Math.max(0, preferredFoodValue);
        setChanged();
    }

    public int defenseSpendingAllocation() {
        return defenseSpendingAllocation;
    }

    /**
     * "Defense Spending" (added 2026-10-16, explicit user spec: Town Hall's own screen lets a Town
     * Planner specify how much gold to top up into each Guardhouse; {@code
     * guardhouse.DefenseSpendingTransfer} is what actually moves it, at midnight) -- set only via
     * {@code SettlemyntsMod#setDefenseSpending}, which re-checks {@code zone.PlotPermissions#canManage}
     * against the Town Hall plot itself (not this Guardhouse's own, which is always NPC-owned).
     */
    public void setDefenseSpendingAllocation(int defenseSpendingAllocation) {
        this.defenseSpendingAllocation = Math.max(0, defenseSpendingAllocation);
        setChanged();
    }

    /**
     * Design doc: "a high volume of low-value food... or a small amount of high-value food... can
     * both satisfy the same requirement" -- "value," not item count. Rather than hand-author a value
     * table for every vanilla food item (real future tuning, not decided per the design doc), this
     * uses each item's own vanilla {@link FoodProperties#nutrition()} directly as its per-item value
     * -- a real, already-varying-per-item number (golden carrot nutrition 6 vs. apple 4 vs. bread 5,
     * etc.), not an arbitrary guess, and exactly matches "some foods are worth more than others"
     * without inventing a second data source. Read by {@code GuardRespawnFunding#tryFundFastRespawn}
     * at the moment a guard dies.
     */
    public int totalFoodValue() {
        int total = 0;
        for (int i = 0; i < foodContainer.getContainerSize(); i++) {
            ItemStack stack = foodContainer.getItem(i);
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food != null) {
                total += food.nutrition() * stack.getCount();
            }
        }
        return total;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Guardhouse Food Stores");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        boolean canManage = canManage(player);
        return new GuardhouseFoodMenu(ModMenus.GUARDHOUSE_FOOD.get(), containerId, inventory, foodContainer, preferredFoodValue, canManage, getBlockPos());
    }

    @Override
    public void writeClientSideData(AbstractContainerMenu menu, RegistryFriendlyByteBuf buf) {
        GuardhouseFoodMenu foodMenu = (GuardhouseFoodMenu) menu;
        buf.writeVarInt(foodMenu.preferredFoodValue());
        buf.writeBoolean(foodMenu.canManage());
        buf.writeBlockPos(foodMenu.guardhousePos());
    }

    /** Shared by {@link #createMenu} and {@code SettlemyntsMod#setPreferredFood}'s own re-validation. */
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
        ContainerHelper.loadAllItems(input.childOrEmpty("Food"), ((SimpleContainer) foodContainer).getItems());
        preferredFoodValue = input.getIntOr("PreferredFoodValue", 0);
        defenseSpendingAllocation = input.getIntOr("DefenseSpendingAllocation", 0);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.storeNullable("SettlementCoreId", UUIDUtil.CODEC, settlementCoreId);
        output.storeNullable("PlotId", UUIDUtil.CODEC, plotId);
        ContainerHelper.saveAllItems(output.child("Food"), ((SimpleContainer) foodContainer).getItems());
        output.putInt("PreferredFoodValue", preferredFoodValue);
        output.putInt("DefenseSpendingAllocation", defenseSpendingAllocation);
    }
}
