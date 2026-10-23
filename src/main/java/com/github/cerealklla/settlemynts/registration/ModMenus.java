package com.github.cerealklla.settlemynts.registration;

import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.guardhouse.GuardhouseFoodMenu;
import com.github.cerealklla.settlemynts.plotsign.PlotConfigMenu;
import com.github.cerealklla.settlemynts.plotsign.ShopCartMenu;
import com.github.cerealklla.settlemynts.watchtower.WatchTowerBeaconMenu;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** This mod's first {@link MenuType} registry -- same shape as Blueprynts' own {@code registration.ModMenus} (its Construction Box). */
public final class ModMenus {

    private ModMenus() {
    }

    public static final DeferredRegister<MenuType<?>> MENU_TYPES = DeferredRegister.create(Registries.MENU, SettlemyntsMod.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<GuardhouseFoodMenu>> GUARDHOUSE_FOOD = MENU_TYPES.register(
            "guardhouse_food",
            () -> IMenuTypeExtension.create((windowId, inventory, extraData) ->
                    new GuardhouseFoodMenu(null, windowId, inventory, extraData.readVarInt(), extraData.readBoolean(), extraData.readBlockPos())));

    public static final DeferredHolder<MenuType<?>, MenuType<ShopCartMenu>> SHOP_CART = MENU_TYPES.register(
            "shop_cart",
            () -> IMenuTypeExtension.create((windowId, inventory, extraData) -> ShopCartMenu.fromNetwork(windowId, inventory, extraData)));

    /** Added 2026-10-24 -- the whole Plot Config Sign screen, real Shop slots merged in (see PlotConfigMenu's own doc). */
    public static final DeferredHolder<MenuType<?>, MenuType<PlotConfigMenu>> PLOT_CONFIG = MENU_TYPES.register(
            "plot_config",
            () -> IMenuTypeExtension.create((windowId, inventory, extraData) -> PlotConfigMenu.fromNetwork(windowId, inventory, extraData)));

    public static final DeferredHolder<MenuType<?>, MenuType<WatchTowerBeaconMenu>> WATCH_TOWER_BEACON = MENU_TYPES.register(
            "watch_tower_beacon",
            () -> IMenuTypeExtension.create((windowId, inventory, extraData) ->
                    new WatchTowerBeaconMenu(null, windowId, inventory,
                            extraData.readBoolean(), extraData.readBoolean(), extraData.readBlockPos(), extraData.readVarInt())));
}
