package com.github.cerealklla.settlemynts.registration;

import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.founding.PlannedPerimeterStakeItem;
import com.github.cerealklla.settlemynts.founding.SettlementClaimFlagItem;
import com.github.cerealklla.settlemynts.zone.PlotPlacementStakeItem;
import com.github.cerealklla.settlemynts.zone.PlotSessionData;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Items for the settlement-founding mechanic (design doc Section 5). */
public final class ModItems {

    private ModItems() {
    }

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(SettlemyntsMod.MODID);

    // ENCHANTMENT_GLINT_OVERRIDE forces vanilla's own shimmer effect regardless of actual
    // enchantments -- same "glow like enchanted, no new art needed" treatment as Yconomics' Coin
    // Purse (design doc Section 5: "made to glow purple like an enchanted item").
    public static final DeferredItem<SettlementClaimFlagItem> SETTLEMENT_CLAIM_FLAG = ITEMS.register(
            "settlement_claim_flag",
            id -> new SettlementClaimFlagItem(new Item.Properties()
                    .stacksTo(1)
                    .setId(ResourceKey.create(Registries.ITEM, id))
                    .component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)));

    // Design doc Section 6: obtained from a settlement's Ghost Town Hall Core, not crafted --
    // stacksTo(1) since each placement is a distinct, individually-manageable perimeter marker.
    public static final DeferredItem<PlannedPerimeterStakeItem> PLANNED_PERIMETER_STAKE = ITEMS.register(
            "planned_perimeter_stake",
            id -> new PlannedPerimeterStakeItem(new Item.Properties()
                    .stacksTo(1)
                    .setId(ResourceKey.create(Registries.ITEM, id))));

    // Design doc Section 11a: obtained from a finalized settlement's Ghost Town Hall Core, tagged
    // with PlotSessionData at grant time so placement never has to guess which plot it belongs to.
    public static final DeferredItem<PlotPlacementStakeItem> PLOT_PLACEMENT_STAKE = ITEMS.register(
            "plot_placement_stake",
            id -> new PlotPlacementStakeItem(new Item.Properties()
                    .stacksTo(1)
                    .setId(ResourceKey.create(Registries.ITEM, id))));

    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, SettlemyntsMod.MODID);

    // Persisted (survives save/load, e.g. a server restart with a stake item sitting in someone's
    // inventory) and network-synchronized (not strictly needed client-side yet, but cheap and
    // consistent with every other data component in this suite).
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PlotSessionData>> PLOT_SESSION_DATA =
            DATA_COMPONENTS.registerComponentType("plot_session_data", builder -> builder
                    .persistent(PlotSessionData.CODEC)
                    .networkSynchronized(PlotSessionData.STREAM_CODEC));
}
