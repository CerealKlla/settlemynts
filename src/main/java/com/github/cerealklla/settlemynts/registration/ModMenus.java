package com.github.cerealklla.settlemynts.registration;

import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.guardhouse.GuardhouseFoodMenu;

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
            () -> IMenuTypeExtension.create((windowId, inventory, extraData) -> new GuardhouseFoodMenu(null, windowId, inventory)));
}
