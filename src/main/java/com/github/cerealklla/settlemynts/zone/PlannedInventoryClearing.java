package com.github.cerealklla.settlemynts.zone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.github.cerealklla.cartographyr.geo.Geometry;
import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.bridge.LyfeCraftingBridge;
import com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Automated inter-plot shop trading at midnight (design doc Section 14a, added 2026-10-09, explicit
 * user request) -- a plot below its own "happy state" (see {@link PlannedInventoryTarget}) buys from
 * another plot in the same settlement that's currently above its own happy state for that resource,
 * paying the seller's own configured Shop sell price. Called once per settlement group by {@code
 * ShopMidnightRestockTicker} right after its existing restock pass.
 *
 * <p><b>Deliberately takes a plain {@code List<PlotRecord>}</b>, not "today's settlement" -- this is
 * the explicitly-planned foundation for a later traveling-merchant feature (not built yet): a trader
 * arriving in a settlement would run this exact same method against the settlement's real plot list
 * plus one more synthetic {@code PlotRecord}-shaped participant representing the merchant's own
 * stock/wants, with no signature change needed here.
 *
 * <p>Pricing is never decided here -- a plot with surplus but no real Shop listing price for that
 * resource is simply skipped this round (confirmed default, 2026-10-09): Planned Inventory only ever
 * decides quantities, the existing Shop system remains the sole source of price.
 *
 * <p><b>NPC plot crafting, added the same day</b> (explicit user request: "a Blacksmith may purchase
 * ingots from other plots and turn them into Armor and Weapons to sell... at midnight, it can check
 * all plots in town and buy what is cheaper, it may find a completed weapon that is listed cheaper
 * than it would be to buy the raw materials and craft one itself") -- when a buyer's deficit resource
 * is a known {@link LyfeCraftingBridge} recipe output, {@link #settleCraftableResource} replaces the
 * plain direct-buy-only handling below: it recursively compares buying the finished item outright
 * against buying its missing materials and crafting it (via {@code CraftingExecutor}), per unit,
 * choosing whichever is cheaper -- recursively, since a required material might itself be a recipe
 * output obtainable the same two ways (confirmed with the user: real recursion, not capped at one
 * level, guarded against cycles by {@code visiting} even though real Lyfe recipes only ever bottom out
 * at raw materials today). A resource with no known recipe (or Lyfe not loaded) falls through to the
 * original direct-buy-only logic, unchanged.
 */
public final class PlannedInventoryClearing {

    private PlannedInventoryClearing() {
    }

    private static final ShopResource GOLD_NUGGETS = ShopResource.ofItem(BuiltInRegistries.ITEM.getKey(Items.GOLD_NUGGET));
    private static final int MAX_CRAFTS_PER_PLOT_PER_TARGET = 64;

    private record Participant(PlotRecord plot, List<Container> boxes) {
    }

    public static void settleGroup(ServerLevel level, List<PlotRecord> plots) {
        // Temporary debug logging (2026-10-10, real report: "the server seems very laggy right now")
        // -- this is the once-a-day midnight auto-purchase/auto-craft pass; timed end to end so a lag
        // spike around a day boundary can be attributed to this specifically rather than guessed at.
        long start = System.nanoTime();
        List<Participant> participants = new ArrayList<>();
        for (PlotRecord plot : plots) {
            if (plot.shopId().isEmpty() || plot.plannedInventory().isEmpty()) {
                continue;
            }
            participants.add(new Participant(plot, Settlemynts.resolvePlotBoxes(level, plot.plotId())));
        }
        if (participants.size() < 2) {
            return; // Need at least one potential buyer and one potential seller.
        }

        Set<ResourceKey> resourceKeys = new LinkedHashSet<>();
        for (Participant p : participants) {
            for (PlannedInventoryTarget target : p.plot().plannedInventory()) {
                resourceKeys.add(new ResourceKey(target.resourceKey(), target.isTag()));
            }
        }

        SettlemyntsMod.LOGGER.info(
                "PlannedInventoryClearing: starting midnight settlement pass -- {} participant(s), {} resource(s)",
                participants.size(), resourceKeys.size());
        for (ResourceKey key : resourceKeys) {
            settleResource(level, participants, key);
        }
        double ms = (System.nanoTime() - start) / 1_000_000.0;
        SettlemyntsMod.LOGGER.info("PlannedInventoryClearing: midnight settlement pass finished in {}ms", String.format("%.1f", ms));
    }

    /**
     * "Use what you already have" production pass -- replaces the old time-based, whole-world-
     * scanning {@code zone.PlotCraftingTicker} (removed 2026-10-10, explicit user redesign: "Why
     * would the shop be checking the entire server?... it should only check the trade route list for
     * the settlement... not the whole world"). For every plot in {@code plots} with a sufficiently-
     * tiered crafting structure, cranks out as many units of each Planned Inventory target with a
     * known recipe as its own current box contents allow. Never buys from anywhere else -- that's
     * {@link #settleResource}/{@link #settleCraftableResource}'s job, already correctly scoped to
     * {@code plots} (this one settlement's own list, never a world scan). Called as the explicit
     * third step by {@code ShopMidnightRestockTicker}, right after {@link #settleGroup} -- so
     * materials that just changed hands via inter-plot trading get turned into finished goods the
     * same night -- and per-shop by {@code ShopCraftingDebounceTicker} after a player Buy/Sell has
     * gone quiet on that one shop for 10 seconds.
     */
    public static void craftFromOwnStockForGroup(ServerLevel level, List<PlotRecord> plots) {
        if (!LyfeCraftingBridge.isLoaded()) {
            return;
        }
        for (PlotRecord plot : plots) {
            if (plot.shopId().isEmpty() || plot.plannedInventory().isEmpty()) {
                continue;
            }
            Optional<Geometry.Polygon> polygon = Settlemynts.resolvePolygonDirect(level, plot);
            if (polygon.isEmpty()) {
                continue; // Natural-village plot, or otherwise unresolvable -- no Lyfe structure can exist there anyway.
            }
            List<Container> boxes = Settlemynts.resolveBoxesForPolygon(level, polygon.get());
            craftFromOwnStock(level, plot, polygon.get(), boxes);
        }
    }

    /** Single-plot entry point -- see {@link #craftFromOwnStockForGroup}'s own doc for the full story. */
    public static int craftFromOwnStock(ServerLevel level, PlotRecord plot, Geometry.Polygon polygon, List<Container> boxes) {
        if (!LyfeCraftingBridge.isLoaded() || boxes.isEmpty()) {
            return 0;
        }
        int structureTier = -1; // Lazily resolved once -- -1 means "not checked yet."
        int crafted = 0;
        for (PlannedInventoryTarget target : plot.plannedInventory()) {
            if (target.isTag()) {
                continue; // A crafting recipe's output is always a concrete item.
            }
            Optional<LyfeCraftingBridge.RecipeInfo> recipeOpt = LyfeCraftingBridge.getRecipe(target.resourceKey());
            if (recipeOpt.isEmpty()) {
                continue;
            }
            if (structureTier < 0) {
                structureTier = PlotCraftingStructures.maxCraftingStructureTier(level, polygon);
            }
            LyfeCraftingBridge.RecipeInfo recipe = recipeOpt.get();
            if (structureTier < recipe.tier()) {
                continue; // No sufficiently-tiered crafting structure present on this plot.
            }
            for (int i = 0; i < MAX_CRAFTS_PER_PLOT_PER_TARGET; i++) {
                Map<Identifier, Integer> available = Settlemynts.scanItemStock(boxes);
                int currentStock = available.getOrDefault(target.resourceKey(), 0);
                if (currentStock >= target.targetCount() || !CraftingExecutor.canCraftOne(available, recipe)) {
                    break;
                }
                CraftingExecutor.craftOne(boxes, recipe);
                crafted++;
            }
        }
        if (crafted > 0) {
            SettlemyntsMod.LOGGER.info("PlannedInventoryClearing: plot {} crafted {} item(s) from its own stock", plot.plotId(), crafted);
            ShopWishlistCache.invalidate(plot.plotId());
        }
        return crafted;
    }

    private record ResourceKey(Identifier id, boolean isTag) {
        ShopResource toShopResource() {
            return isTag ? ShopResource.ofTag(TagKey.create(net.minecraft.core.registries.Registries.ITEM, id)) : ShopResource.ofItem(id);
        }
    }

    private record Buyer(Participant participant, int remainingDeficit) {
    }

    private record Seller(Participant participant, int remainingSurplus, int pricePerUnit) {
    }

    private static void settleResource(ServerLevel level, List<Participant> participants, ResourceKey key) {
        if (!key.isTag() && LyfeCraftingBridge.isLoaded()) {
            Optional<LyfeCraftingBridge.RecipeInfo> recipe = LyfeCraftingBridge.getRecipe(key.id());
            if (recipe.isPresent()) {
                settleCraftableResource(level, participants, key, recipe.get());
                return;
            }
        }
        ShopResource resource = key.toShopResource();
        List<Buyer> buyers = new ArrayList<>();
        List<Seller> sellers = new ArrayList<>();

        for (Participant p : participants) {
            PlannedInventoryTarget target = findTarget(p.plot(), key);
            int currentStock = ContainerWithdraw.countAvailable(p.boxes(), resource);
            if (target != null && currentStock < target.targetCount()) {
                buyers.add(new Buyer(p, target.targetCount() - currentStock));
                continue;
            }
            // Surplus above its own declared target, or (real fix, 2026-10-10: "I set it to want to
            // have 64 apples in stock, but not list them for sale... but the grocer isn't buying
            // them" -- the Grocer had a target, the player's own apple-selling plot never did) its
            // whole current stock when it has no target at all. Previously a seller candidate with no
            // PlannedInventoryTarget of its own was silently skipped entirely, even with a real Shop
            // listing and real stock -- inconsistent with the craftable-resource path's own
            // cheapestSellerPrice, which already never required one (see that method's own doc: a
            // seller is just "anyone with a real listing price," always).
            int surplus = target != null ? currentStock - target.targetCount() : currentStock;
            if (surplus <= 0 || p.plot().shopId().isEmpty()) {
                continue;
            }
            int pricePerUnit = sellPriceFor(level, p.plot().shopId().get(), resource);
            if (pricePerUnit > 0) {
                sellers.add(new Seller(p, surplus, pricePerUnit));
            }
        }

        for (int bi = 0; bi < buyers.size(); bi++) {
            Buyer buyer = buyers.get(bi);
            int remainingDeficit = buyer.remainingDeficit();
            if (remainingDeficit <= 0) {
                continue;
            }
            for (int si = 0; si < sellers.size() && remainingDeficit > 0; si++) {
                Seller seller = sellers.get(si);
                if (seller.remainingSurplus() <= 0 || seller.participant() == buyer.participant()) {
                    continue;
                }
                int units = Math.min(remainingDeficit, seller.remainingSurplus());
                List<ItemStack> goods = ContainerWithdraw.drain(seller.participant().boxes(), resource, units);
                if (goods.isEmpty()) {
                    continue;
                }
                // Priced per actual stack drained, not a single flat pricePerUnit (2026-10-10, "an
                // npc vendor will always buy any kind of bread it sees and [pay] at that specific
                // listing price") -- a generic resource key (plain item id, no quality) still matches
                // any quality variant for *counting/draining* purposes, but a seller with several
                // quality-specific listings for the same base item must be paid each unit's own real
                // listing price, not one price for the whole batch. See PriceSplit's own doc.
                List<YconomicsShopBridge.ShopListingView> sellerListings =
                        YconomicsShopBridge.getListings(level, seller.participant().plot().shopId().orElseThrow());
                int buyerGold = ContainerWithdraw.countAvailable(buyer.participant().boxes(), GOLD_NUGGETS);
                PriceSplit split = priceWithinBudget(goods, sellerListings, buyerGold);
                for (ItemStack back : split.unaffordableOrUnpriced()) {
                    ContainerDeposit.depositIntoAny(seller.participant().boxes(), back);
                }
                if (split.totalUnits() <= 0) {
                    continue;
                }
                for (ItemStack stack : split.toDeliver()) {
                    ContainerDeposit.depositIntoAny(buyer.participant().boxes(), stack);
                }
                List<ItemStack> payment = ContainerWithdraw.drain(buyer.participant().boxes(), GOLD_NUGGETS, (int) split.totalCost());
                for (ItemStack stack : payment) {
                    ContainerDeposit.depositIntoAny(seller.participant().boxes(), stack);
                }
                remainingDeficit -= split.totalUnits();
                sellers.set(si, new Seller(seller.participant(), seller.remainingSurplus() - split.totalUnits(), seller.pricePerUnit()));
                SettlemyntsMod.LOGGER.info("PlannedInventoryClearing: plot {} bought {}x {} from plot {} for {} nuggets",
                        buyer.participant().plot().plotId(), split.totalUnits(), key.id(), seller.participant().plot().plotId(), split.totalCost());
                ShopWishlistCache.invalidate(buyer.participant().plot().plotId());
                ShopWishlistCache.invalidate(seller.participant().plot().plotId());
            }
            buyers.set(bi, new Buyer(buyer.participant(), remainingDeficit));
        }
    }

    private record PriceSplit(List<ItemStack> toDeliver, List<ItemStack> unaffordableOrUnpriced, int totalUnits, long totalCost) {
    }

    /**
     * Splits {@code drained} (real stacks just pulled from a seller's boxes, each carrying its own
     * real quality/variant) into what the buyer can actually afford at each stack's own real listing
     * price, greedily in list order -- the real per-unit pricing {@link #settleResource} needs (see
     * its own doc). A stack with no matching listing at all (shouldn't normally happen -- it was only
     * drained because a *different* variant's listing made the resource "sellable" at all) is treated
     * as unaffordable and handed back untouched, same as a stack that simply doesn't fit the budget.
     */
    private static PriceSplit priceWithinBudget(List<ItemStack> drained, List<YconomicsShopBridge.ShopListingView> sellerListings, int buyerGold) {
        List<ItemStack> toDeliver = new ArrayList<>();
        List<ItemStack> leftover = new ArrayList<>();
        int totalUnits = 0;
        long totalCost = 0;
        long remainingBudget = buyerGold;
        for (ItemStack stack : drained) {
            Optional<Integer> price = priceForStack(sellerListings, stack);
            if (price.isEmpty() || price.get() <= 0) {
                leftover.add(stack);
                continue;
            }
            int affordable = (int) Math.min(stack.getCount(), remainingBudget / price.get());
            if (affordable <= 0) {
                leftover.add(stack);
                continue;
            }
            if (affordable < stack.getCount()) {
                leftover.add(stack.copyWithCount(stack.getCount() - affordable));
            }
            toDeliver.add(stack.copyWithCount(affordable));
            totalUnits += affordable;
            long cost = (long) price.get() * affordable;
            totalCost += cost;
            remainingBudget -= cost;
        }
        return new PriceSplit(toDeliver, leftover, totalUnits, totalCost);
    }

    /**
     * The real price {@code stack} would sell for against {@code listings} -- prefers an exact
     * quality-variant listing (one whose own resource carries a matching custom name) over a plain,
     * no-variant listing, since the exact match is always the more specific/authoritative price; among
     * several plain listings that all generically match (shouldn't normally happen -- a shop only ever
     * has one plain listing per item), the cheapest. Empty if nothing listed covers this stack at all.
     */
    private static Optional<Integer> priceForStack(List<YconomicsShopBridge.ShopListingView> listings, ItemStack stack) {
        Integer genericPrice = null;
        for (YconomicsShopBridge.ShopListingView view : listings) {
            if (!view.resource().matches(stack)) {
                continue;
            }
            if (view.resource().customName().isPresent()) {
                return Optional.of(view.pricePerUnit());
            }
            if (genericPrice == null || view.pricePerUnit() < genericPrice) {
                genericPrice = view.pricePerUnit();
            }
        }
        return Optional.ofNullable(genericPrice);
    }

    private static PlannedInventoryTarget findTarget(PlotRecord plot, ResourceKey key) {
        for (PlannedInventoryTarget target : plot.plannedInventory()) {
            if (target.isTag() == key.isTag() && target.resourceKey().equals(key.id())) {
                return target;
            }
        }
        return null;
    }

    private static int sellPriceFor(ServerLevel level, UUID shopId, ShopResource resource) {
        for (YconomicsShopBridge.ShopListingView view : YconomicsShopBridge.getListings(level, shopId)) {
            if (sameResource(view.resource(), resource)) {
                return view.pricePerUnit();
            }
        }
        return 0;
    }

    private static boolean sameResource(ShopResource a, ShopResource b) {
        if (a.tag().isPresent() && b.tag().isPresent()) {
            return a.tag().get().equals(b.tag().get());
        }
        if (a.itemId().isPresent() && b.itemId().isPresent()) {
            return a.itemId().get().equals(b.itemId().get());
        }
        return false;
    }

    // ---- NPC plot crafting: buy-vs-craft midnight resolution -- see class doc. ----

    private static void settleCraftableResource(ServerLevel level, List<Participant> participants, ResourceKey key, LyfeCraftingBridge.RecipeInfo recipe) {
        for (Participant buyer : participants) {
            PlannedInventoryTarget target = findTarget(buyer.plot(), key);
            if (target == null) {
                continue;
            }
            int currentStock = ContainerWithdraw.countAvailable(buyer.boxes(), key.toShopResource());
            int deficit = target.targetCount() - currentStock;
            if (deficit <= 0) {
                continue;
            }
            int buyerTier = PlotCraftingStructures.maxCraftingStructureTier(level, buyer.plot().plotId());
            for (int i = 0; i < deficit; i++) {
                if (!fulfillOneUnit(level, participants, buyer, key, buyerTier, new LinkedHashSet<>())) {
                    break; // Can't make further progress on this resource for this buyer this round.
                }
            }
        }
    }

    /**
     * Obtains exactly one unit of {@code key} for {@code buyer}, choosing whichever of direct-buy or
     * self-craft is cheaper (or the only achievable one), and actually executes it. Returns whether it
     * succeeded. {@code visiting} is the cycle guard for the self-craft recursion below.
     */
    private static boolean fulfillOneUnit(ServerLevel level, List<Participant> participants, Participant buyer,
                                           ResourceKey key, int buyerTier, Set<Identifier> visiting) {
        if (visiting.contains(key.id())) {
            return false;
        }
        long directCost = cheapestSellerPrice(level, participants, buyer, key);

        Optional<LyfeCraftingBridge.RecipeInfo> recipe = key.isTag() || !LyfeCraftingBridge.isLoaded()
                ? Optional.empty() : LyfeCraftingBridge.getRecipe(key.id());
        boolean canCraft = recipe.isPresent() && buyerTier >= recipe.get().tier();
        long craftCost = -1;
        if (canCraft) {
            Set<Identifier> nextVisiting = withAdded(visiting, key.id());
            craftCost = estimateCraftCost(level, participants, buyer, recipe.get(), buyerTier, nextVisiting);
        }

        boolean useCraft = canCraft && craftCost >= 0 && (directCost < 0 || craftCost <= directCost);
        if (useCraft) {
            return executeCraftOne(level, participants, buyer, recipe.get(), buyerTier, withAdded(visiting, key.id()));
        }
        if (directCost >= 0) {
            return executeDirectBuyOne(level, participants, buyer, key);
        }
        return false;
    }

    /** Non-mutating: the recursive total cost to obtain every ingredient for one craft of {@code recipe}, or {@code -1} if any piece is unobtainable. */
    private static long estimateCraftCost(ServerLevel level, List<Participant> participants, Participant buyer,
                                           LyfeCraftingBridge.RecipeInfo recipe, int buyerTier, Set<Identifier> visiting) {
        long total = 0;
        for (Map.Entry<Identifier, Integer> entry : recipe.specificComponents().entrySet()) {
            long unitCost = cheapestCostForOneUnit(level, participants, buyer, new ResourceKey(entry.getKey(), false), buyerTier, visiting);
            if (unitCost < 0) {
                return -1;
            }
            total += unitCost * entry.getValue();
        }
        for (Map.Entry<String, Integer> entry : recipe.genericComponents().entrySet()) {
            long cheapestMemberUnitCost = -1;
            for (Identifier member : LyfeCraftingBridge.componentGroupMembers(entry.getKey())) {
                long cost = cheapestCostForOneUnit(level, participants, buyer, new ResourceKey(member, false), buyerTier, visiting);
                if (cost >= 0 && (cheapestMemberUnitCost < 0 || cost < cheapestMemberUnitCost)) {
                    cheapestMemberUnitCost = cost;
                }
            }
            if (cheapestMemberUnitCost < 0) {
                return -1;
            }
            total += cheapestMemberUnitCost * entry.getValue();
        }
        return total;
    }

    /** Non-mutating: the cheaper of direct-buy or recursive self-craft for one unit of {@code key}, or {@code -1} if neither is achievable. */
    private static long cheapestCostForOneUnit(ServerLevel level, List<Participant> participants, Participant buyer,
                                                ResourceKey key, int buyerTier, Set<Identifier> visiting) {
        long direct = cheapestSellerPrice(level, participants, buyer, key);
        long craft = -1;
        if (!key.isTag() && !visiting.contains(key.id()) && LyfeCraftingBridge.isLoaded()) {
            Optional<LyfeCraftingBridge.RecipeInfo> recipe = LyfeCraftingBridge.getRecipe(key.id());
            if (recipe.isPresent() && buyerTier >= recipe.get().tier()) {
                craft = estimateCraftCost(level, participants, buyer, recipe.get(), buyerTier, withAdded(visiting, key.id()));
            }
        }
        if (direct < 0) {
            return craft;
        }
        if (craft < 0) {
            return direct;
        }
        return Math.min(direct, craft);
    }

    /** Cheapest real Shop listing price for {@code key} across every participant except {@code buyer}, or {@code -1} if nobody lists it. Price-only -- not stock-limited, see class doc. */
    private static long cheapestSellerPrice(ServerLevel level, List<Participant> participants, Participant buyer, ResourceKey key) {
        ShopResource resource = key.toShopResource();
        long best = -1;
        for (Participant p : participants) {
            if (p == buyer || p.plot().shopId().isEmpty()) {
                continue;
            }
            int price = sellPriceFor(level, p.plot().shopId().get(), resource);
            if (price > 0 && (best < 0 || price < best)) {
                best = price;
            }
        }
        return best;
    }

    /** Buys exactly one unit of {@code key} from the cheapest seller that actually has stock and that {@code buyer} can afford, walking sellers in ascending price order. */
    private static boolean executeDirectBuyOne(ServerLevel level, List<Participant> participants, Participant buyer, ResourceKey key) {
        ShopResource resource = key.toShopResource();
        List<Participant> candidates = new ArrayList<>(participants);
        candidates.remove(buyer);
        candidates.sort(Comparator.comparingInt(p -> {
            int price = p.plot().shopId().map(id -> sellPriceFor(level, id, resource)).orElse(0);
            return price <= 0 ? Integer.MAX_VALUE : price;
        }));
        for (Participant seller : candidates) {
            if (seller.plot().shopId().isEmpty()) {
                continue;
            }
            int price = sellPriceFor(level, seller.plot().shopId().get(), resource);
            if (price <= 0 || ContainerWithdraw.countAvailable(seller.boxes(), resource) <= 0) {
                continue;
            }
            if (ContainerWithdraw.countAvailable(buyer.boxes(), GOLD_NUGGETS) < price) {
                continue;
            }
            List<ItemStack> goods = ContainerWithdraw.drain(seller.boxes(), resource, 1);
            if (goods.isEmpty()) {
                continue;
            }
            // Re-priced against the exact stack actually drained (2026-10-10, same "pay at that
            // specific listing price" fix as settleResource's own doc) -- `price` above was only
            // ever a pre-drain heuristic for sorting/affordability, generic across every quality
            // variant this seller might list.
            List<YconomicsShopBridge.ShopListingView> sellerListings =
                    YconomicsShopBridge.getListings(level, seller.plot().shopId().get());
            Optional<Integer> realPrice = priceForStack(sellerListings, goods.get(0));
            if (realPrice.isEmpty() || realPrice.get() <= 0 || ContainerWithdraw.countAvailable(buyer.boxes(), GOLD_NUGGETS) < realPrice.get()) {
                for (ItemStack stack : goods) {
                    ContainerDeposit.depositIntoAny(seller.boxes(), stack);
                }
                continue;
            }
            for (ItemStack stack : goods) {
                ContainerDeposit.depositIntoAny(buyer.boxes(), stack);
            }
            for (ItemStack stack : ContainerWithdraw.drain(buyer.boxes(), GOLD_NUGGETS, realPrice.get())) {
                ContainerDeposit.depositIntoAny(seller.boxes(), stack);
            }
            SettlemyntsMod.LOGGER.info("PlannedInventoryClearing: plot {} direct-bought 1x {} from plot {} for {} nuggets (craftable resource)",
                    buyer.plot().plotId(), key.id(), seller.plot().plotId(), realPrice.get());
            ShopWishlistCache.invalidate(buyer.plot().plotId());
            ShopWishlistCache.invalidate(seller.plot().plotId());
            return true;
        }
        return false;
    }

    /**
     * Crafts exactly one unit of {@code recipe} for {@code buyer} -- tops up any shortfall in its own
     * boxes first (buy-or-recursively-craft each missing ingredient, one unit at a time, via {@link
     * #fulfillOneUnit}), then calls {@code CraftingExecutor.craftOne} for real. Returns {@code false}
     * (no partial side effects beyond whatever ingredients were actually purchased/crafted along the
     * way -- same graceful partial-fulfillment philosophy the rest of this engine already has) if any
     * required ingredient couldn't be fully topped up.
     */
    private static boolean executeCraftOne(ServerLevel level, List<Participant> participants, Participant buyer,
                                            LyfeCraftingBridge.RecipeInfo recipe, int buyerTier, Set<Identifier> visiting) {
        // Scans the Participant's own already-resolved boxes directly -- same real lag fix as
        // zone.PlotCraftingTicker (see api.Settlemynts#scanItemStock's own doc): scanPlotItemStock(
        // level, plotId) redundantly re-resolves the whole box list (a full-world entity scan) when
        // this method already has `buyer.boxes()` in hand.
        Map<Identifier, Integer> available = Settlemynts.scanItemStock(buyer.boxes());

        for (Map.Entry<Identifier, Integer> entry : recipe.specificComponents().entrySet()) {
            int need = entry.getValue() - available.getOrDefault(entry.getKey(), 0);
            for (int i = 0; i < need; i++) {
                if (!fulfillOneUnit(level, participants, buyer, new ResourceKey(entry.getKey(), false), buyerTier, visiting)) {
                    return false;
                }
            }
        }
        for (Map.Entry<String, Integer> entry : recipe.genericComponents().entrySet()) {
            List<Identifier> members = LyfeCraftingBridge.componentGroupMembers(entry.getKey());
            int have = members.stream().mapToInt(m -> available.getOrDefault(m, 0)).sum();
            int need = entry.getValue() - have;
            for (int i = 0; i < need; i++) {
                Identifier cheapestMember = cheapestGroupMember(level, participants, buyer, members, buyerTier, visiting);
                if (cheapestMember == null || !fulfillOneUnit(level, participants, buyer, new ResourceKey(cheapestMember, false), buyerTier, visiting)) {
                    return false;
                }
            }
        }

        Map<Identifier, Integer> finalAvailable = Settlemynts.scanItemStock(buyer.boxes());
        if (!CraftingExecutor.canCraftOne(finalAvailable, recipe)) {
            return false; // Shouldn't normally happen given the top-ups above -- defensive guard.
        }
        CraftingExecutor.craftOne(buyer.boxes(), recipe);
        SettlemyntsMod.LOGGER.info("PlannedInventoryClearing: plot {} crafted 1x {} (structure tier {})",
                buyer.plot().plotId(), recipe.resultId(), buyerTier);
        ShopWishlistCache.invalidate(buyer.plot().plotId());
        return true;
    }

    private static Identifier cheapestGroupMember(ServerLevel level, List<Participant> participants, Participant buyer,
                                                    List<Identifier> members, int buyerTier, Set<Identifier> visiting) {
        Identifier best = null;
        long bestCost = -1;
        for (Identifier member : members) {
            long cost = cheapestCostForOneUnit(level, participants, buyer, new ResourceKey(member, false), buyerTier, visiting);
            if (cost >= 0 && (bestCost < 0 || cost < bestCost)) {
                bestCost = cost;
                best = member;
            }
        }
        return best;
    }

    private static Set<Identifier> withAdded(Set<Identifier> set, Identifier id) {
        Set<Identifier> copy = new LinkedHashSet<>(set);
        copy.add(id);
        return copy;
    }
}
