package com.github.cerealklla.settlemynts.plotsign;

import java.util.List;
import java.util.UUID;

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
 * The real Shop screen, redesigned 2026-10-20 as a genuine {@link AbstractContainerMenu} (explicit
 * user spec, replacing the button-list cart shipped hours earlier the same day -- real report: "if a
 * player spam-click buys from a shop it lags the whole server," then "I'd like it to look something
 * like this" with a real four-container mockup). Four containers/"lanes": Shop (an infinite catalog,
 * one {@code CatalogSlot} per listing), Buy (a conjured staging area items move into from Shop),
 * Sell (a real staging area items move into from the player's own inventory), and the player's own
 * real inventory.
 *
 * <p><b>Exact lane rule, per explicit spec</b>: "A player can move things to and from the player
 * inventory and the sell inventory. They can separately freely move things between the shop's
 * inventory and the buy inventory... an item cannot be picked up from the shop's inventory and
 * placed in the sell or player inventory, nor can a player inventory item be placed in the buy or
 * shop inventory." Enforced at the slot level via Nuclyus's generic conjured-item lane primitives
 * ({@code ConjuredOnlySlot}/{@code RealOnlySlot}/{@code CatalogSlot}, see that package's own doc) --
 * every Shop/Buy slot only ever accepts conjured stacks, every Sell/Player slot only ever accepts
 * real ones, and the two never mix, so this is correct for every click/drag/shift-click path vanilla
 * already implements, with no hand-rolled click interception needed.
 *
 * <p>"Confirm Purchase" reads whatever's actually sitting in Buy/Sell at that moment and processes
 * it as one batched transaction (see {@code SettlemyntsMod#confirmShopCart}) -- nothing is charged
 * or delivered before that. Resolved by matching each real stack found against {@code listings} via
 * {@link ShopListingEntry#matches}, not by slot position -- a player is free to rearrange/merge
 * within Buy or Sell without breaking which listing a stack belongs to.
 *
 * <p>Deliberately does not include a crafting grid or armor slots -- the described flow never needs
 * either (shift-click only ever moves things between the player's main inventory and Sell), and
 * vanilla's own crafting-grid mechanics are a separate subsystem with no real use case here.
 *
 * <p><b>Scrolling + fixed scratch capacity, 2026-10-20</b> -- real report against the first cut of
 * this screen: "need to fix the scaling, and definitely need the ability to scroll through shop
 * items." The original version gave every listing its own row across all three lanes, so a shop
 * with more than a handful of listings grew the window past the screen's own height with no way to
 * reach the rest. The Shop (catalog) lane now only ever holds {@link #VISIBLE_SHOP_ROWS} real {@code
 * Slot}s at fixed positions; scrolling repopulates which {@link #listings} entries those slots
 * currently show (via {@link #refreshCatalogWindow}) rather than moving anything, since this
 * version's {@code Slot#x}/{@code Slot#y} are {@code final}. The current {@link #scrollOffset} is
 * kept in a synced {@link DataSlot} so a client-reconstructed menu always agrees with the server
 * about which window is showing, since that's also what a click on a catalog slot actually resolves
 * against server-side. Buy/Sell are no longer sized to match the listing count at all -- they're
 * free-form scratch space (never bound to a specific listing/row), so they're just a fixed {@link
 * #BUY_SELL_SLOTS}-slot column, same as a small chest.
 */
public class ShopCartMenu extends AbstractContainerMenu {

    private static final int HOTBAR_SIZE = 9;
    private static final int MAIN_INVENTORY_SIZE = 27;

    private static final int COLUMN_WIDTH = 18;
    private static final int ROW_HEIGHT = 18;
    private static final int TOP_Y = 18;
    private static final int SHOP_X = 8;
    // Widened 2026-10-20 -- real report, with a screenshot showing the Shop column's "N in stock
    // Pg / Sg" price text running directly into the Buy column's icons. BUY_X/SELL_X/PLAYER_INV_X
    // must stay in lockstep with ShopCartScreen's own identical copies of these constants (that
    // class's own doc explains why they're duplicated rather than shared).
    private static final int BUY_X = 180;
    private static final int SELL_X = 210;
    private static final int PLAYER_INV_X = 250;

    public static final int VISIBLE_SHOP_ROWS = 8;
    public static final int BUY_SELL_SLOTS = 8;

    private final ShopAnchor anchor;
    private final UUID plotId;
    private List<ShopListingEntry> listings;
    private final Container catalogContainer;
    private final Container buyContainer;
    private final Container sellContainer;
    private final DataSlot scrollOffsetSlot;
    private int shopGoldNuggets;
    private int playerGoldNuggets;

    /** Server-side: {@code listings} is already-resolved real shop data (see {@code SettlemyntsMod#handleRequestShop}). */
    public ShopCartMenu(MenuType<?> type, int containerId, Inventory inventory, ShopAnchor anchor, UUID plotId,
                         List<ShopListingEntry> listings, int shopGoldNuggets, int playerGoldNuggets) {
        super(type, containerId);
        this.anchor = anchor;
        this.plotId = plotId;
        this.listings = listings;
        this.shopGoldNuggets = shopGoldNuggets;
        this.playerGoldNuggets = playerGoldNuggets;
        this.catalogContainer = new SimpleContainer(VISIBLE_SHOP_ROWS);
        this.buyContainer = new SimpleContainer(BUY_SELL_SLOTS);
        this.sellContainer = new SimpleContainer(BUY_SELL_SLOTS);
        layoutSlots(inventory);
        this.scrollOffsetSlot = this.addDataSlot(DataSlot.standalone());
        refreshCatalogWindow(0);
    }

    /** Client-side reconstruction -- see {@code registration.ModMenus#SHOP_CART}. */
    public static ShopCartMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf extraData) {
        ShopAnchor anchor = ShopAnchor.STREAM_CODEC.decode(extraData);
        UUID plotId = ByteBufCodecs.fromCodec(net.minecraft.core.UUIDUtil.CODEC).decode(extraData);
        List<ShopListingEntry> listings = ByteBufCodecs.fromCodec(ShopListingEntry.CODEC.listOf()).decode(extraData);
        int shopGold = ByteBufCodecs.VAR_INT.decode(extraData);
        int playerGold = ByteBufCodecs.VAR_INT.decode(extraData);
        return new ShopCartMenu(ModMenus.SHOP_CART.get(), containerId, inventory, anchor, plotId, listings, shopGold, playerGold);
    }

    public static void writeExtraData(RegistryFriendlyByteBuf buf, ShopAnchor anchor, UUID plotId,
                                       List<ShopListingEntry> listings, int shopGoldNuggets, int playerGoldNuggets) {
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
        return stack;
    }

    private void layoutSlots(Inventory inventory) {
        for (int i = 0; i < catalogContainer.getContainerSize(); i++) {
            addSlot(new CatalogSlot(catalogContainer, i, SHOP_X, TOP_Y + i * ROW_HEIGHT));
        }
        for (int i = 0; i < buyContainer.getContainerSize(); i++) {
            addSlot(new ConjuredOnlySlot(buyContainer, i, BUY_X, TOP_Y + i * ROW_HEIGHT));
        }
        for (int i = 0; i < sellContainer.getContainerSize(); i++) {
            addSlot(new SellSlot(sellContainer, i, SELL_X, TOP_Y + i * ROW_HEIGHT));
        }
        int playerInvY = TOP_Y;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new RealOnlySlot(inventory, col + row * 9 + 9, PLAYER_INV_X + col * COLUMN_WIDTH, playerInvY + row * ROW_HEIGHT));
            }
        }
        for (int col = 0; col < HOTBAR_SIZE; col++) {
            addSlot(new RealOnlySlot(inventory, col, PLAYER_INV_X + col * COLUMN_WIDTH, playerInvY + 3 * ROW_HEIGHT + 4));
        }
    }

    /**
     * How many listing rows the Shop column scrolls past before the currently-visible window starts.
     * Clamped to {@code [0, max(0, listings.size() - VISIBLE_SHOP_ROWS)]}; repopulates the (fixed-size,
     * fixed-position) catalog slots' conjured stacks to match -- see the class doc for why this is a
     * re-population rather than a slot reposition.
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
        return scrollOffsetSlot.get();
    }

    /**
     * Re-resolves fresh listing data (price/stock) in place -- called from {@code
     * SettlemyntsModClient} when a {@code ShopStockUpdatePayload} arrives for an already-open cart,
     * same "patch state in place" shape as {@link #updateGold}. Keeps the current scroll position
     * (clamped, in case the listing count itself changed) rather than resetting to the top.
     */
    public void updateListings(List<ShopListingEntry> listings) {
        this.listings = listings;
        refreshCatalogWindow(scrollOffset());
    }

    public ShopAnchor anchor() {
        return anchor;
    }

    public UUID plotId() {
        return plotId;
    }

    public List<ShopListingEntry> listings() {
        return listings;
    }

    public Container buyContainer() {
        return buyContainer;
    }

    public Container sellContainer() {
        return sellContainer;
    }

    public int shopGoldNuggets() {
        return shopGoldNuggets;
    }

    public int playerGoldNuggets() {
        return playerGoldNuggets;
    }

    /** Called by {@code SettlemyntsModClient} when a {@code ShopGoldUpdatePayload} arrives while this menu is open. */
    public void updateGold(int shopGoldNuggets, int playerGoldNuggets) {
        this.shopGoldNuggets = shopGoldNuggets;
        this.playerGoldNuggets = playerGoldNuggets;
    }

    /**
     * Clears Buy/Sell back to empty -- called server-side once {@code SettlemyntsMod#confirmShopCart}
     * has finished processing their contents into a real transaction, so the staging areas don't
     * keep showing stacks that were already charged/delivered.
     */
    public void clearCartContainers() {
        for (int i = 0; i < buyContainer.getContainerSize(); i++) {
            buyContainer.setItem(i, ItemStack.EMPTY);
        }
        for (int i = 0; i < sellContainer.getContainerSize(); i++) {
            sellContainer.setItem(i, ItemStack.EMPTY);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        Slot clicked = slots.get(slotIndex);
        if (!clicked.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack original = clicked.getItem();
        ItemStack moving = original.copy();
        Container container = clicked.container;
        if (container == catalogContainer) {
            // Same as a plain click -- see CatalogSlot's own doc, a shift-click also just conjures a
            // fresh stack straight into Buy, never touching the cursor. The catalog slot itself never
            // empties either way (see CatalogSlot#onTake), so this always returns EMPTY.
            moveItemStackTo(ConjuredItems.conjure(original.copyWithCount(original.getMaxStackSize())),
                    catalogContainer.getContainerSize(), catalogContainer.getContainerSize() + buyContainer.getContainerSize(), false);
            return ItemStack.EMPTY;
        }
        if (container == buyContainer) {
            // "Return to shop" -- Buy has nowhere else conjured items are allowed to go but back out
            // of the cart entirely (never into Sell/Player, which are RealOnlySlot). Simplest correct
            // behavior: shift-click just removes it from the cart.
            clicked.set(ItemStack.EMPTY);
            return ItemStack.EMPTY;
        }
        if (container == sellContainer) {
            // Back to the player's own real inventory/hotbar.
            int invStart = catalogContainer.getContainerSize() + buyContainer.getContainerSize() + sellContainer.getContainerSize();
            if (!moveItemStackTo(moving, invStart, invStart + MAIN_INVENTORY_SIZE + HOTBAR_SIZE, true)) {
                return ItemStack.EMPTY;
            }
            clicked.set(moving.isEmpty() ? ItemStack.EMPTY : moving);
            return ItemStack.EMPTY;
        }
        // Player inventory/hotbar -> Sell.
        int sellStart = catalogContainer.getContainerSize() + buyContainer.getContainerSize();
        if (!moveItemStackTo(moving, sellStart, sellStart + sellContainer.getContainerSize(), false)) {
            return ItemStack.EMPTY;
        }
        clicked.set(moving.isEmpty() ? ItemStack.EMPTY : moving);
        return ItemStack.EMPTY;
    }

    public static final int CONFIRM_BUTTON_ID = 0;
    /** Scrolls the Shop column's visible window up/down by one row -- see {@link #refreshCatalogWindow}. */
    public static final int SCROLL_UP_BUTTON_ID = 1;
    public static final int SCROLL_DOWN_BUTTON_ID = 2;

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id == SCROLL_UP_BUTTON_ID) {
            refreshCatalogWindow(scrollOffset() - 1);
            return true;
        }
        if (id == SCROLL_DOWN_BUTTON_ID) {
            refreshCatalogWindow(scrollOffset() + 1);
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

    /**
     * See {@code nuclyus.trade.TradeMenuSupport}'s own doc -- discards conjured state, returns real
     * items. Deliberately does NOT call {@code super.removed(player)} -- same reasoning as {@code
     * Kyt.loadout.LoadoutMenu#removed}'s own precedent: vanilla's default is the one thing actually
     * responsible for dropping a still-carried item on the ground (e.g. on disconnect), and every
     * carried stack here is handled explicitly below instead.
     */
    @Override
    public void removed(Player player) {
        TradeMenuSupport.resolveCarried(this, player);
        TradeMenuSupport.returnRealContents(sellContainer, player);
        // buyContainer is intentionally NOT returned -- every stack in it is conjured (never a real
        // possession), safe to just let go out of scope.
    }

    /**
     * {@link RealOnlySlot}, further refusing a Coin Purse or Recallcinite Totem -- real request,
     * 2026-10-20: "need to prevent player selling their Recallcinite totem or coin purse." Blocking
     * it here (rather than only at confirm time) means it can never even be placed into the Sell
     * column in the first place, via any click/drag/shift-click path -- see {@code
     * plotsign.UnsellableItems}'s own doc.
     */
    private static final class SellSlot extends RealOnlySlot {
        SellSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return super.mayPlace(stack) && !UnsellableItems.isUnsellable(stack);
        }
    }
}
