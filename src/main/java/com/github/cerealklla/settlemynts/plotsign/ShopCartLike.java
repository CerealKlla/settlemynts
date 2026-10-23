package com.github.cerealklla.settlemynts.plotsign;

import java.util.List;

import net.minecraft.world.Container;

/**
 * The real-slot Shop surface {@code SettlemyntsMod#confirmShopCart}/{@code #autoSellToShopCart}
 * actually need -- implemented by both {@link ShopCartMenu} (the NPC-direct "Open Store" screen)
 * and {@link PlotConfigMenu} (the Plot Config Sign's own merged Shop sub-tab, 2026-10-24), so both
 * transaction methods work against either without caring which real screen the player opened.
 */
public interface ShopCartLike {

    ShopAnchor anchor();

    List<ShopListingEntry> listings();

    Container buyContainer();

    Container sellContainer();
}
