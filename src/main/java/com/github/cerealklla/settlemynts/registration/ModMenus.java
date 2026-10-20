package com.github.cerealklla.settlemynts.registration;

import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.guardhouse.GuardhouseFoodMenu;
import com.github.cerealklla.settlemynts.plotsign.ShopCartMenu;

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
}
