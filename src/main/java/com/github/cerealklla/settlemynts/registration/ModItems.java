package com.github.cerealklla.settlemynts.registration;

import java.util.UUID;

import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.founding.PlannedPerimeterStakeItem;
import com.github.cerealklla.settlemynts.founding.SettlementClaimFlagItem;
import com.github.cerealklla.settlemynts.founding.TownHallCoreRelocatorItem;
import com.github.cerealklla.settlemynts.roadway.RoadwayStakeItem;
import com.github.cerealklla.settlemynts.zone.PlotPlacementStakeItem;
import com.github.cerealklla.settlemynts.zone.PlotSessionData;
import com.github.cerealklla.settlemynts.zone.RoadAccessFlagItem;

import net.minecraft.core.UUIDUtil;
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

    // Design doc Section 10: the "nearest roadway" flag -- granted from PlotStakeScreen, tagged with
    // the same PlotSessionData component (reused, not a new type) so placement never has to guess
    // which plot it belongs to, same shape as PLOT_PLACEMENT_STAKE above.
    public static final DeferredItem<RoadAccessFlagItem> ROAD_ACCESS_FLAG = ITEMS.register(
            "road_access_flag",
            id -> new RoadAccessFlagItem(new Item.Properties()
                    .stacksTo(1)
                    .setId(ResourceKey.create(Registries.ITEM, id))));

    // "Reposition Town Hall Core" (design doc, added 2026-09-30, explicit user request) -- a plain
    // Item, not a BlockItem, since the Core is a marker Entity with no block to place; useOn
    // teleports it directly instead.
    public static final DeferredItem<TownHallCoreRelocatorItem> TOWN_HALL_CORE_RELOCATOR = ITEMS.register(
            "town_hall_core_relocator",
            id -> new TownHallCoreRelocatorItem(new Item.Properties()
                    .stacksTo(1)
                    .setId(ResourceKey.create(Registries.ITEM, id))));

    // Roadways Milestone 1 (added 2026-10-06) -- mirrors PLOT_PLACEMENT_STAKE's shape, but carries
    // just a settlement UUID (no plot-session id concept, see roadway.RoadwayStakeEntity's own doc).
    public static final DeferredItem<RoadwayStakeItem> ROADWAY_STAKE = ITEMS.register(
            "roadway_stake",
            id -> new RoadwayStakeItem(new Item.Properties()
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

    // Plot Config Sign relocation (design doc Section 14a, added 2026-09-30) -- mirrors Blueprynts'
    // own SUPPLY_BOX_LOCATOR_BOX_ID component shape exactly, just carrying a plotId instead of a
    // constructionId.
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> PLOT_CONFIG_SIGN_LOCATOR_PLOT_ID =
            DATA_COMPONENTS.registerComponentType("plot_config_sign_locator_plot_id", builder -> builder
                    .persistent(UUIDUtil.CODEC)
                    .networkSynchronized(UUIDUtil.STREAM_CODEC));

    // The game time the Locator above was granted -- lets its walk-away auto-cancel (2026-09-30)
    // skip a short grace window right after granting, same reasoning/bug as Blueprynts' own
    // ModDataComponents#LOCATOR_GRANTED_AT_GAME_TIME (a player right-clicking the sign to start a
    // relocation is very often standing right where it was, which may not itself satisfy the plot's
    // own strict interior check).
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Long>> PLOT_CONFIG_SIGN_LOCATOR_GRANTED_AT =
            DATA_COMPONENTS.registerComponentType("plot_config_sign_locator_granted_at", builder -> builder
                    .persistent(com.mojang.serialization.Codec.LONG)
                    .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.VAR_LONG));

    // "Reposition Town Hall Core," added 2026-09-30 -- same shape as the two components above, just
    // carrying the Core's own persistent entity UUID (not a plot id) since a granted Locator may be
    // carried for a while before use, and the Core's transient network entity id isn't durable across
    // that span the way a real UUID is.
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> TOWN_HALL_CORE_LOCATOR_CORE_ID =
            DATA_COMPONENTS.registerComponentType("town_hall_core_locator_core_id", builder -> builder
                    .persistent(UUIDUtil.CODEC)
                    .networkSynchronized(UUIDUtil.STREAM_CODEC));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Long>> TOWN_HALL_CORE_LOCATOR_GRANTED_AT =
            DATA_COMPONENTS.registerComponentType("town_hall_core_locator_granted_at", builder -> builder
                    .persistent(com.mojang.serialization.Codec.LONG)
                    .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.VAR_LONG));

    // Roadways Milestone 1 -- the settlement that granted a Roadway Stake item, permanent for its
    // lifetime (same shape as TOWN_HALL_CORE_LOCATOR_CORE_ID above).
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> ROADWAY_STAKE_OWNER_CORE_ID =
            DATA_COMPONENTS.registerComponentType("roadway_stake_owner_core_id", builder -> builder
                    .persistent(UUIDUtil.CODEC)
                    .networkSynchronized(UUIDUtil.STREAM_CODEC));
}
