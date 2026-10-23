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

/** Opens {@link PlotConfigMenu} via {@code ServerPlayer#openMenu} -- see that class's own doc. */
public final class PlotConfigMenuProvider implements MenuProvider, IMenuProviderExtension {

    private final OpenPlotConfigSignMenuPayload signData;
    private final boolean hasShop;
    private final ShopAnchor anchor;
    private final UUID plotId;
    private final List<ShopListingEntry> listings;
    private final int shopGoldNuggets;
    private final int playerGoldNuggets;

    public PlotConfigMenuProvider(OpenPlotConfigSignMenuPayload signData, boolean hasShop, ShopAnchor anchor, UUID plotId,
                                   List<ShopListingEntry> listings, int shopGoldNuggets, int playerGoldNuggets) {
        this.signData = signData;
        this.hasShop = hasShop;
        this.anchor = anchor;
        this.plotId = plotId;
        this.listings = listings;
        this.shopGoldNuggets = shopGoldNuggets;
        this.playerGoldNuggets = playerGoldNuggets;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Plot Config");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new PlotConfigMenu(ModMenus.PLOT_CONFIG.get(), containerId, inventory, signData, hasShop, anchor, plotId, listings, shopGoldNuggets, playerGoldNuggets);
    }

    @Override
    public void writeClientSideData(AbstractContainerMenu menu, RegistryFriendlyByteBuf buf) {
        PlotConfigMenu.writeExtraData(buf, signData, hasShop, anchor, plotId, listings, shopGoldNuggets, playerGoldNuggets);
    }
}
