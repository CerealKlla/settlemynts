package com.github.cerealklla.settlemynts.plotsign;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.github.cerealklla.nuclyus.trade.CatalogSlot;
import com.github.cerealklla.nuclyus.trade.ConjuredItems;
import com.github.cerealklla.nuclyus.trade.ConjuredOnlySlot;
import com.github.cerealklla.nuclyus.trade.RealOnlySlot;
import com.github.cerealklla.nuclyus.trade.TradeMenuSupport;
import com.github.cerealklla.settlemynts.registration.ModMenus;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The real, single menu backing the whole Plot Config Sign screen (merged 2026-10-24, explicit
 * request: "Place it fully within the unified menu, not as a separate area" -- re: the Shop screen
 * previously popping its own separate {@code ShopCartMenu} window). Replaces that standalone class
 * for the Sign-interaction path specifically -- {@code ShopCartMenu} itself is untouched and still
 * backs the NPC-direct "Open Store" interaction ({@code NpcMenuScreen}), which has no sign/tabs
 * context to merge into and was intentionally left alone (see decisions.md for the scoping call).
 *
 * <p>Carries the Plot Config Sign's own tab-relevant data ({@link #signData}, the exact same fields
 * {@code OpenPlotConfigSignMenuPayload} used to carry as a standalone packet -- now just resolved
 * once, eagerly, at menu-open time, and ridden along on this menu's own extra-data channel instead)
 * plus, if this plot has a real Shop (see {@link #hasShop}), the same four real Shop/Buy/Sell/Player
 * lanes {@code ShopCartMenu} already had -- ported essentially verbatim (see that class's own doc
 * for the full lane-rule/scrolling design, unchanged here).
 *
 * <p><b>Shop slots are inert outside the Shop tab's own Shop sub-tab</b> -- every Shop/Buy/Sell/
 * Player slot overrides {@link Slot#isActive()} to consult {@link #shopSlotsActive}, which {@code
 * client.ShopTab} flips directly on the client's own local menu instance as its sub-tab changes (no
 * network round trip -- purely a client-side display/input concern, same reasoning {@code
 * GhostBlockDisplays}-style visibility toggles elsewhere in this suite already use). An inactive
 * slot renders nothing and can't be hovered/clicked at all (confirmed against the real decompiled
 * {@code AbstractContainerScreen} source -- every render/hover/click entry point checks {@code
 * isActive()} first), so Overview/Upgrade/Configure/Manage Shop/Planned Inventory/Garrison/
 * Management all just work exactly as before with zero awareness that real slots exist alongside
 * them. The server's own copy never bothers gating -- there's nothing exploitable about a modified
 * client clicking a "hidden" Shop slot, since buying/selling there is already something the player
 * is otherwise allowed to do the moment they switch to the real Shop sub-tab.
 */
public class PlotConfigMenu extends AbstractContainerMenu implements ShopCartLike {

    private static final int HOTBAR_SIZE = 9;
    private static final int MAIN_INVENTORY_SIZE = 27;

    private static final int COLUMN_WIDTH = 18;
    private static final int ROW_HEIGHT = 18;
    private static final int TOP_Y = 18;
    private static final int SHOP_X = 8;
    private static final int BUY_X = 230;
    private static final int SELL_X = 260;
    private static final int PLAYER_INV_X = 300;

    public static final int VISIBLE_SHOP_ROWS = 8;
    public static final int BUY_SELL_SLOTS = 8;

    private final OpenPlotConfigSignMenuPayload signData;
    private final boolean hasShop;
    private final ShopAnchor anchor;
    private final UUID plotId;
    private List<ShopListingEntry> listings;
    private final Container catalogContainer;
    private final Container buyContainer;
    private final Container sellContainer;
    private final DataSlot scrollOffsetSlot;
    private int shopGoldNuggets;
    private int playerGoldNuggets;

    /** Purely client-side display/input gate -- see class doc. Always {@code true} server-side (never restricted there). */
    private boolean shopSlotsActive;

    /** Server-side: {@code listings} is already-resolved real shop data (see {@code SettlemyntsMod#openPlotConfigMenu}). */
    public PlotConfigMenu(MenuType<?> type, int containerId, Inventory inventory, OpenPlotConfigSignMenuPayload signData, boolean hasShop,
                           ShopAnchor anchor, UUID plotId, List<ShopListingEntry> listings, int shopGoldNuggets, int playerGoldNuggets) {
        super(type, containerId);
        this.signData = signData;
        this.hasShop = hasShop;
        this.anchor = anchor;
        this.plotId = plotId;
        this.listings = listings;
        this.shopGoldNuggets = shopGoldNuggets;
        this.playerGoldNuggets = playerGoldNuggets;
        this.shopSlotsActive = !inventory.player.level().isClientSide();
        if (hasShop) {
            this.catalogContainer = new SimpleContainer(VISIBLE_SHOP_ROWS);
            this.buyContainer = new SimpleContainer(BUY_SELL_SLOTS);
            this.sellContainer = new SimpleContainer(BUY_SELL_SLOTS);
            layoutShopSlots(inventory);
            this.scrollOffsetSlot = this.addDataSlot(DataSlot.standalone());
            refreshCatalogWindow(0);
        } else {
            this.catalogContainer = null;
            this.buyContainer = null;
            this.sellContainer = null;
            this.scrollOffsetSlot = null;
        }
    }

    /** Client-side reconstruction -- see {@code registration.ModMenus#PLOT_CONFIG}. */
    public static PlotConfigMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf extraData) {
        OpenPlotConfigSignMenuPayload signData = OpenPlotConfigSignMenuPayload.STREAM_CODEC.decode(extraData);
        boolean hasShop = extraData.readBoolean();
        if (!hasShop) {
            return new PlotConfigMenu(ModMenus.PLOT_CONFIG.get(), containerId, inventory, signData, false, null, null, List.of(), 0, 0);
        }
        ShopAnchor anchor = ShopAnchor.STREAM_CODEC.decode(extraData);
        UUID plotId = ByteBufCodecs.fromCodec(net.minecraft.core.UUIDUtil.CODEC).decode(extraData);
        List<ShopListingEntry> listings = ByteBufCodecs.fromCodec(ShopListingEntry.CODEC.listOf()).decode(extraData);
        int shopGold = ByteBufCodecs.VAR_INT.decode(extraData);
        int playerGold = ByteBufCodecs.VAR_INT.decode(extraData);
        return new PlotConfigMenu(ModMenus.PLOT_CONFIG.get(), containerId, inventory, signData, true, anchor, plotId, listings, shopGold, playerGold);
    }

    public static void writeExtraData(RegistryFriendlyByteBuf buf, OpenPlotConfigSignMenuPayload signData, boolean hasShop,
                                       ShopAnchor anchor, UUID plotId, List<ShopListingEntry> listings, int shopGoldNuggets, int playerGoldNuggets) {
        OpenPlotConfigSignMenuPayload.STREAM_CODEC.encode(buf, signData);
        buf.writeBoolean(hasShop);
        if (!hasShop) {
            return;
        }
        ShopAnchor.STREAM_CODEC.encode(buf, anchor);
        ByteBufCodecs.fromCodec(net.minecraft.core.UUIDUtil.CODEC).encode(buf, plotId);
        ByteBufCodecs.fromCodec(ShopListingEntry.CODEC.listOf()).encode(buf, listings);
        ByteBufCodecs.VAR_INT.encode(buf, shopGoldNuggets);
        ByteBufCodecs.VAR_INT.encode(buf, playerGoldNuggets);
    }

    private static ItemStack representativeStack(ShopListingEntry listing) {
        Item item;
        if (listing.isTag()) {
            TagKey<Item> tag = TagKey.create(Registries.ITEM, listing.resourceKey());
            java.util.Iterator<net.minecraft.core.Holder<Item>> tagMembers = BuiltInRegistries.ITEM.getTagOrEmpty(tag).iterator();
            item = tagMembers.hasNext() ? tagMembers.next().value() : Items.BARRIER;
        } else {
            item = BuiltInRegistries.ITEM.getOptional(listing.resourceKey()).orElse(Items.BARRIER);
        }
        ItemStack stack = new ItemStack(item, item.getDefaultMaxStackSize());
        if (listing.customName().isPresent()) {
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    net.minecraft.network.chat.Component.literal(listing.customName().get()));
        }
        if (!listing.customModelDataStrings().isEmpty()) {
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA,
                    new net.minecraft.world.item.component.CustomModelData(java.util.List.of(), java.util.List.of(), listing.customModelDataStrings(), java.util.List.of()));
        }
        return stack;
    }

    private void layoutShopSlots(Inventory inventory) {
        for (int i = 0; i < catalogContainer.getContainerSize(); i++) {
            addSlot(new CatalogSlot(catalogContainer, i, SHOP_X, TOP_Y + i * ROW_HEIGHT) {
                @Override
                public boolean isActive() {
                    return shopSlotsActive;
                }
            });
        }
        for (int i = 0; i < buyContainer.getContainerSize(); i++) {
            addSlot(new BuySlot(buyContainer, i, BUY_X, TOP_Y + i * ROW_HEIGHT, this::listings));
        }
        for (int i = 0; i < sellContainer.getContainerSize(); i++) {
            addSlot(new SellSlot(sellContainer, i, SELL_X, TOP_Y + i * ROW_HEIGHT, this::listings));
        }
        int playerInvY = TOP_Y;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new ActiveGatedRealOnlySlot(inventory, col + row * 9 + 9, PLAYER_INV_X + col * COLUMN_WIDTH, playerInvY + row * ROW_HEIGHT));
            }
        }
        for (int col = 0; col < HOTBAR_SIZE; col++) {
            addSlot(new ActiveGatedRealOnlySlot(inventory, col, PLAYER_INV_X + col * COLUMN_WIDTH, playerInvY + 3 * ROW_HEIGHT + 4));
        }
    }

    public void setShopSlotsActive(boolean active) {
        this.shopSlotsActive = active;
    }

    public boolean hasShop() {
        return hasShop;
    }

    public OpenPlotConfigSignMenuPayload signData() {
        return signData;
    }

    /**
     * How many listing rows the Shop column scrolls past before the currently-visible window starts.
     * See {@code ShopCartMenu}'s own (now-historical) doc for the full scrolling design -- unchanged.
     */
    private void refreshCatalogWindow(int requestedOffset) {
        int maxOffset = Math.max(0, listings.size() - VISIBLE_SHOP_ROWS);
        int offset = Mth.clamp(requestedOffset, 0, maxOffset);
        for (int i = 0; i < catalogContainer.getContainerSize(); i++) {
            int listingIndex = offset + i;
            catalogContainer.setItem(i, listingIndex < listings.size()
                    ? ConjuredItems.conjure(representativeStack(listings.get(listingIndex)))
                    : ItemStack.EMPTY);
        }
        scrollOffsetSlot.set(offset);
    }

    public int scrollOffset() {
        return hasShop ? scrollOffsetSlot.get() : 0;
    }

    public void updateListings(List<ShopListingEntry> listings) {
        this.listings = listings;
        refreshCatalogWindow(scrollOffset());
    }

    @Override
    public ShopAnchor anchor() {
        return anchor;
    }

    public UUID plotId() {
        return plotId;
    }

    @Override
    public List<ShopListingEntry> listings() {
        return listings;
    }

    @Override
    public Container buyContainer() {
        return buyContainer;
    }

    @Override
    public Container sellContainer() {
        return sellContainer;
    }

    public int shopGoldNuggets() {
        return shopGoldNuggets;
    }

    public int playerGoldNuggets() {
        return playerGoldNuggets;
    }

    public void updateGold(int shopGoldNuggets, int playerGoldNuggets) {
        this.shopGoldNuggets = shopGoldNuggets;
        this.playerGoldNuggets = playerGoldNuggets;
    }

    public void clearCartContainers() {
        if (!hasShop) {
            return;
        }
        for (int i = 0; i < buyContainer.getContainerSize(); i++) {
            buyContainer.setItem(i, ItemStack.EMPTY);
        }
        for (int i = 0; i < sellContainer.getContainerSize(); i++) {
            sellContainer.setItem(i, ItemStack.EMPTY);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        if (!hasShop) {
            return ItemStack.EMPTY;
        }
        Slot clicked = slots.get(slotIndex);
        if (!clicked.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack original = clicked.getItem();
        ItemStack moving = original.copy();
        Container container = clicked.container;
        if (container == catalogContainer) {
            moveItemStackTo(ConjuredItems.conjure(original.copyWithCount(original.getMaxStackSize())),
                    catalogContainer.getContainerSize(), catalogContainer.getContainerSize() + buyContainer.getContainerSize(), false);
            return ItemStack.EMPTY;
        }
        if (container == buyContainer) {
            clicked.set(ItemStack.EMPTY);
            return ItemStack.EMPTY;
        }
        if (container == sellContainer) {
            int invStart = catalogContainer.getContainerSize() + buyContainer.getContainerSize() + sellContainer.getContainerSize();
            if (!moveItemStackTo(moving, invStart, invStart + MAIN_INVENTORY_SIZE + HOTBAR_SIZE, true)) {
                return ItemStack.EMPTY;
            }
            clicked.set(moving.isEmpty() ? ItemStack.EMPTY : moving);
            return ItemStack.EMPTY;
        }
        int sellStart = catalogContainer.getContainerSize() + buyContainer.getContainerSize();
        if (!moveItemStackTo(moving, sellStart, sellStart + sellContainer.getContainerSize(), false)) {
            return ItemStack.EMPTY;
        }
        clicked.set(moving.isEmpty() ? ItemStack.EMPTY : moving);
        return ItemStack.EMPTY;
    }

    public static final int CONFIRM_BUTTON_ID = 0;
    public static final int SCROLL_UP_BUTTON_ID = 1;
    public static final int SCROLL_DOWN_BUTTON_ID = 2;
    public static final int AUTO_SELL_BUTTON_ID = 3;

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (!hasShop) {
            return false;
        }
        if (id == SCROLL_UP_BUTTON_ID) {
            refreshCatalogWindow(scrollOffset() - 1);
            return true;
        }
        if (id == SCROLL_DOWN_BUTTON_ID) {
            refreshCatalogWindow(scrollOffset() + 1);
            return true;
        }
        if (id == AUTO_SELL_BUTTON_ID) {
            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                com.github.cerealklla.settlemynts.SettlemyntsMod.autoSellToShopCart(serverPlayer, this);
            }
            return true;
        }
        if (id != CONFIRM_BUTTON_ID || !(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) {
            return false;
        }
        com.github.cerealklla.settlemynts.SettlemyntsMod.confirmShopCart(serverPlayer, this);
        return true;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void removed(Player player) {
        TradeMenuSupport.resolveCarried(this, player);
        if (hasShop) {
            TradeMenuSupport.returnRealContents(sellContainer, player);
        }
    }

    private final class ActiveGatedRealOnlySlot extends RealOnlySlot {
        ActiveGatedRealOnlySlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean isActive() {
            return shopSlotsActive;
        }
    }

    /**
     * Stock-aware gating for the Buy side -- added 2026-10-24, real report (screenshot): a listing
     * showing "0 in stock" could still be conjured into a Buy slot, with no feedback that it would
     * come back empty at Confirm. Mirrors {@link SellSlot}'s own {@code plannedInventoryRemaining}
     * membership gate exactly, same "UX-only, not an exact quantity cap" precedent -- the real
     * enforcement was already correct and unchanged: {@code SettlemyntsMod#processBuyLine} ->
     * {@code bridge.YconomicsShopBridge#purchase} -> {@code api.Yconomics#purchaseFromShop} already
     * drains real stock and only charges/delivers however much it actually found, refunding nothing
     * extra -- this never risked a dupe or overcharge, it just let a doomed-to-fail 0/low-stock
     * listing sit in the cart with nothing telling the player why.
     */
    private final class BuySlot extends ConjuredOnlySlot {
        private final Supplier<List<ShopListingEntry>> listingsSupplier;

        BuySlot(Container container, int index, int x, int y, Supplier<List<ShopListingEntry>> listingsSupplier) {
            super(container, index, x, y);
            this.listingsSupplier = listingsSupplier;
        }

        @Override
        public boolean isActive() {
            return shopSlotsActive;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            if (!super.mayPlace(stack)) {
                return false;
            }
            return listingsSupplier.get().stream().anyMatch(l -> l.shopStock() > 0 && l.matches(stack));
        }
    }

    /** Same unsellable/Planned-Inventory gating {@code ShopCartMenu}'s own version had -- see its historical doc. */
    private final class SellSlot extends RealOnlySlot {
        private final Supplier<List<ShopListingEntry>> listingsSupplier;

        SellSlot(Container container, int index, int x, int y, Supplier<List<ShopListingEntry>> listingsSupplier) {
            super(container, index, x, y);
            this.listingsSupplier = listingsSupplier;
        }

        @Override
        public boolean isActive() {
            return shopSlotsActive;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            if (!super.mayPlace(stack) || UnsellableItems.isUnsellable(stack)) {
                return false;
            }
            return listingsSupplier.get().stream().anyMatch(l -> l.plannedInventoryRemaining() > 0 && l.matches(stack));
        }
    }
}
