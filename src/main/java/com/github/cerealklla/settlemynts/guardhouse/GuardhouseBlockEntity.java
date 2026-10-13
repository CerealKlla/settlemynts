package com.github.cerealklla.settlemynts.guardhouse;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.settlemynts.registration.ModBlockEntities;
import com.github.cerealklla.settlemynts.registration.ModMenus;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
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

/**
 * A finalized Guardhouse plot's own structure (design doc Section 14a) -- its live identity
 * (mirrors {@code plotsign.PlotConfigSignBlockEntity}), a real food {@link Container} ("a
 * Guardhouse-specific inventory, distinct from a shop's"), and the per-slot garrison gear
 * permissions {@code client.ConfigureGarrisonScreen} edits (reached via the Plot Config Sign's new
 * "Configure Garrison" button, not this block's own right-click menu). Right-clicking this block
 * opens only the food {@link Container} via {@link GuardhouseFoodMenu} -- a real custom Menu/Slot
 * is needed (not vanilla's plain {@code ChestMenu}) specifically so "food only" is actually
 * enforced; see {@link GuardhouseFoodSlot}'s own doc for why.
 *
 * <p>No guard spawning/consumption exists yet -- {@link #totalFoodValue()} is read-only, exposed for
 * a later pass. Garrison slots are always allocated at {@link GuardhouseConstants#MAX_GARRISON_SIZE}
 * regardless of the plot's current Tier, so a later Tier change never needs a resize/migration step;
 * only the first {@code GuardhouseConstants#capacityForTier(tier)} are ever shown/settable.
 */
public class GuardhouseBlockEntity extends BlockEntity implements MenuProvider {

    // Sentinel for "no guard" in the persisted garrisonGuardIds list (see that field's own doc) --
    // avoids inventing a custom optional-list Codec for one field.
    private static final UUID NO_GUARD = new UUID(0L, 0L);

    private UUID settlementCoreId;
    private UUID plotId;

    /** Only accepts items carrying {@link DataComponents#FOOD} -- see {@link #canPlaceFood(ItemStack)}. */
    private final Container foodContainer = new SimpleContainer(9) {
        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return canPlaceFood(stack);
        }
    };

    private List<GarrisonSlot> garrisonSlots = defaultSlots();

    // Parallel to garrisonSlots (also always MAX_GARRISON_SIZE long) -- which GuardEntity (if any,
    // and if still alive/loaded) currently occupies each slot. Written only by GuardSpawnTicker;
    // GarrisonSlot itself stays guard-agnostic (it only ever describes permission/binding, not live
    // entity state). NO_GUARD is this list's own persisted "empty" sentinel.
    private List<UUID> garrisonGuardIds = defaultGuardIds();

    public GuardhouseBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.GUARDHOUSE.get(), pos, state);
    }

    private static List<GarrisonSlot> defaultSlots() {
        List<GarrisonSlot> slots = new ArrayList<>(GuardhouseConstants.MAX_GARRISON_SIZE);
        for (int i = 0; i < GuardhouseConstants.MAX_GARRISON_SIZE; i++) {
            slots.add(GarrisonSlot.DEFAULT);
        }
        return slots;
    }

    private static List<UUID> defaultGuardIds() {
        List<UUID> ids = new ArrayList<>(GuardhouseConstants.MAX_GARRISON_SIZE);
        for (int i = 0; i < GuardhouseConstants.MAX_GARRISON_SIZE; i++) {
            ids.add(NO_GUARD);
        }
        return ids;
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

    /**
     * Design doc: "a high volume of low-value food... or a small amount of high-value food... can
     * both satisfy the same requirement" -- "value," not item count. Rather than hand-author a value
     * table for every vanilla food item (real future tuning, not decided per the design doc), this
     * uses each item's own vanilla {@link FoodProperties#nutrition()} directly as its per-item value
     * -- a real, already-varying-per-item number (golden carrot nutrition 6 vs. apple 4 vs. bread 5,
     * etc.), not an arbitrary guess, and exactly matches "some foods are worth more than others"
     * without inventing a second data source. No guard spawning/consumption reads this yet.
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

    public List<GarrisonSlot> garrisonSlots() {
        return garrisonSlots;
    }

    public GarrisonSlot garrisonSlot(int index) {
        return garrisonSlots.get(index);
    }

    /** Clamped to the current Tier's own gear cap ({@link GuardhouseConstants#maxGearTierForTier}) -- a slot's own value can never exceed its Guardhouse's current permitted maximum. */
    public void setGarrisonSlot(int index, GarrisonSlot slot, int currentTier) {
        int clampedGearTier = Math.max(1, Math.min(GuardhouseConstants.maxGearTierForTier(currentTier), slot.maxGearTier()));
        garrisonSlots.set(index, new GarrisonSlot(clampedGearTier, slot.allowNeighborPurchase(), slot.kytLoadoutName()));
        setChanged();
    }

    /**
     * "Select Kyt" on {@code client.ConfigureGarrisonScreen} -- leaves this slot's gear-tier cap/
     * neighbor-purchase permission untouched, only (re)binds or clears its Kyt. {@code name}
     * empty/blank clears the binding. **v1 simplification**: rebinding here does not retroactively
     * re-equip or replace an already-living guard in this slot -- {@link GuardSpawnTicker} only ever
     * spawns into a slot it finds empty, so the new binding only takes effect once that guard dies
     * (or if it was never filled).
     */
    public void setGarrisonKyt(int index, String name) {
        GarrisonSlot current = garrisonSlots.get(index);
        java.util.Optional<String> kytLoadoutName = name == null || name.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(name);
        garrisonSlots.set(index, new GarrisonSlot(current.maxGearTier(), current.allowNeighborPurchase(), kytLoadoutName));
        setChanged();
    }

    /** Which {@code GuardEntity} (by id) currently occupies this slot, if any -- see {@link #garrisonGuardIds}'s own doc. */
    public Optional<UUID> guardId(int index) {
        UUID id = garrisonGuardIds.get(index);
        return NO_GUARD.equals(id) ? Optional.empty() : Optional.of(id);
    }

    /** Called by {@link GuardSpawnTicker} right after it spawns a fresh guard into this slot. */
    public void setGuardId(int index, UUID id) {
        garrisonGuardIds.set(index, id);
        setChanged();
    }

    /** Called by {@link GuardSpawnTicker} once it notices the previously-tracked guard is no longer alive/loaded, so the slot reads as empty again next scan. */
    public void clearGuardId(int index) {
        garrisonGuardIds.set(index, NO_GUARD);
        setChanged();
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Guardhouse Food Stores");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new GuardhouseFoodMenu(ModMenus.GUARDHOUSE_FOOD.get(), containerId, inventory, foodContainer);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        settlementCoreId = input.read("SettlementCoreId", UUIDUtil.CODEC).orElse(null);
        plotId = input.read("PlotId", UUIDUtil.CODEC).orElse(null);
        ContainerHelper.loadAllItems(input.childOrEmpty("Food"), ((SimpleContainer) foodContainer).getItems());
        List<GarrisonSlot> loaded = input.read("GarrisonSlots", GarrisonSlot.CODEC.listOf()).orElse(List.of());
        garrisonSlots = defaultSlots();
        for (int i = 0; i < Math.min(loaded.size(), GuardhouseConstants.MAX_GARRISON_SIZE); i++) {
            garrisonSlots.set(i, loaded.get(i));
        }
        List<UUID> loadedGuardIds = input.read("GarrisonGuardIds", Codec.list(UUIDUtil.CODEC)).orElse(List.of());
        garrisonGuardIds = defaultGuardIds();
        for (int i = 0; i < Math.min(loadedGuardIds.size(), GuardhouseConstants.MAX_GARRISON_SIZE); i++) {
            garrisonGuardIds.set(i, loadedGuardIds.get(i));
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.storeNullable("SettlementCoreId", UUIDUtil.CODEC, settlementCoreId);
        output.storeNullable("PlotId", UUIDUtil.CODEC, plotId);
        ContainerHelper.saveAllItems(output.child("Food"), ((SimpleContainer) foodContainer).getItems());
        output.store("GarrisonSlots", GarrisonSlot.CODEC.listOf(), garrisonSlots);
        output.store("GarrisonGuardIds", Codec.list(UUIDUtil.CODEC), garrisonGuardIds);
    }
}
