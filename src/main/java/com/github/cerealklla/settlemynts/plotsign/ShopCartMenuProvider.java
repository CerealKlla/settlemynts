package com.github.cerealklla.settlemynts.plotsign;

import java.util.List;
import java.util.UUID;

import com.github.cerealklla.settlemynts.registration.ModMenus;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.common.extensions.IMenuProviderExtension;

/**
 * Opens {@link ShopCartMenu} via {@code ServerPlayer#openMenu} -- same shape as Kyt's own {@code
 * loadout.KytEditorMenuProvider}, since this menu isn't backed by a real {@code BlockEntity} either
 * (an NPC- or Sign-anchored shop). {@code anchor}/{@code plotId}/{@code listings}/the two gold
 * totals all ride along on this menu's own extra-data channel ({@link #writeClientSideData}), read
 * back by {@code registration.ModMenus}' client-reconstruction factory ({@link
 * ShopCartMenu#fromNetwork}).
 */
public final class ShopCartMenuProvider implements MenuProvider, IMenuProviderExtension {

    private final ShopAnchor anchor;
    private final UUID plotId;
    private final List<ShopListingEntry> listings;
    private final int shopGoldNuggets;
    private final int playerGoldNuggets;

    public ShopCartMenuProvider(ShopAnchor anchor, UUID plotId, List<ShopListingEntry> listings, int shopGoldNuggets, int playerGoldNuggets) {
        this.anchor = anchor;
        this.plotId = plotId;
        this.listings = listings;
        this.shopGoldNuggets = shopGoldNuggets;
        this.playerGoldNuggets = playerGoldNuggets;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Shop");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new ShopCartMenu(ModMenus.SHOP_CART.get(), containerId, inventory, anchor, plotId, listings, shopGoldNuggets, playerGoldNuggets);
    }

    @Override
    public void writeClientSideData(AbstractContainerMenu menu, RegistryFriendlyByteBuf buf) {
        ShopCartMenu.writeExtraData(buf, anchor, plotId, listings, shopGoldNuggets, playerGoldNuggets);
    }
}
