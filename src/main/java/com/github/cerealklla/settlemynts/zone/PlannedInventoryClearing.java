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
import com.github.cerealklla.settlemynts.bridge.BlueprintsConstructionBridge;
import com.github.cerealklla.settlemynts.bridge.LyfeCraftingBridge;
import com.github.cerealklla.settlemynts.bridge.YconomicsShopBridge;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;

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

    /** Ledger entry for a midnight inter-plot trade (2026-10-12) -- same hook as the real player buy/sell handlers in SettlemyntsMod, see BlueprintsConstructionBridge's own doc. Resolved live via constructionBoxId, not the stale plot.boxPos() snapshot (2026-10-16 fix). No-op if the plot has no box or Blueprynts isn't loaded. */
    private static void appendLedger(ServerLevel level, PlotRecord plot, String line) {
        if (ModList.get().isLoaded("blueprynts")) {
            BlueprintsConstructionBridge.appendLedgerEntry(level, plot, line);
        }
    }
    private static final int MAX_CRAFTS_PER_PLOT_PER_TARGET = 64;

    private record Participant(PlotRecord plot, List<Container> boxes) {
    }

    /**
     * Builds this settlement's buyer/seller participant list up front (confirmed fast -- see {@link
     * MidnightWorkQueue}'s own doc, the real production incident this fixed), then enqueues one small
     * job per resource onto {@code queue} instead of settling every resource synchronously in a single
     * tight loop. A settlement with a large participant/resource count now spreads its own settlement
     * pass across as many ticks as it takes to drain, instead of blocking the tick it was detected on
     * for however long the whole pass happens to take.
     */
    public static void settleGroup(ServerLevel level, List<PlotRecord> plots, MidnightWorkQueue queue) {
        List<Participant> participants = new ArrayList<>();
        for (PlotRecord plot : plots) {
            // Only exclude a plot that can do neither side of a trade -- a shop with real listings but
            // a completely blank Planned Inventory (never visited that screen at all) must still be
            // eligible as a seller, same as settleResource's own "no target = sell whole stock" logic
            // below already intends (2026-10-10 fix) but could never reach, since this gate used to
            // require BOTH a shop AND at least one Planned Inventory target just to enter the pass at
            // all. Real report, 2026-10-11: items listed and stocked via Manage Shop, no Planned
            // Inventory configured, never got auto-bought by another plot's real deficit.
            if (plot.shopId().isEmpty() && plot.plannedInventory().isEmpty()) {
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
        long start = System.nanoTime();
        for (ResourceKey key : resourceKeys) {
            queue.enqueue(() -> settleResource(level, participants, key, queue));
        }
        queue.enqueue(() -> {
            double ms = (System.nanoTime() - start) / 1_000_000.0;
            SettlemyntsMod.LOGGER.info("PlannedInventoryClearing: midnight settlement pass finished in {}ms (wall-clock, spread across ticks)", String.format("%.1f", ms));
        });
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
     * gone quiet on that one shop for 10 seconds (that single-shop path still goes through the plain
     * synchronous {@link #craftFromOwnStock}, not through {@link MidnightWorkQueue} -- it's already
     * scoped to one plot, not a whole settlement sweep).
     *
     * <p>Spreads the work across {@link MidnightWorkQueue} jobs at per-craft-attempt granularity
     * instead of one big job per plot -- real production incident, 2026-10-21: a plot's own crafting
     * loop (up to {@link #MAX_CRAFTS_PER_PLOT_PER_TARGET} craft attempts PER target, each rescanning
     * the plot's full box contents via {@link Settlemynts#scanItemStock}) could itself take long
     * enough, for a plot with several different craftable targets, to recreate the exact single-tick
     * freeze the per-resource job splitting above was built to fix -- confirmed live: the watchdog
     * fired well after {@link #settleGroup}'s own jobs had already finished cleanly in under 7
     * seconds, meaning this step (or one of the others {@code ShopMidnightRestockTicker} queues right
     * after it) was the next thing still capable of blocking a whole tick. Each queued job here now
     * does at most one craft attempt for one target on one plot, re-enqueuing itself for the next
     * attempt exactly like {@link #attemptOneCraftableUnit} already does for inter-plot trading.
     */
    public static void enqueueCraftFromOwnStockForGroup(ServerLevel level, List<PlotRecord> plots, MidnightWorkQueue queue) {
        for (PlotRecord plot : plots) {
            queue.enqueue(() -> enqueueCraftFromOwnStockForPlot(level, plot, queue));
        }
    }

    private static void enqueueCraftFromOwnStockForPlot(ServerLevel level, PlotRecord plot, MidnightWorkQueue queue) {
        if (plot.shopId().isEmpty() || plot.plannedInventory().isEmpty()) {
            return;
        }
        Optional<Geometry.Polygon> polygon = Settlemynts.resolvePolygonDirect(level, plot);
        if (polygon.isEmpty()) {
            return; // Natural-village plot, or otherwise unresolvable -- no Lyfe structure can exist there anyway.
        }
        List<Container> boxes = Settlemynts.resolveBoxesForPolygon(level, polygon.get());
        if (boxes.isEmpty()) {
            return;
        }
        PlotCraftingCapability capability = null; // Lazily resolved once per plot -- null means "not checked yet."
        for (PlannedInventoryTarget target : plot.plannedInventory()) {
            if (target.isTag()) {
                continue; // A crafting recipe's output is always a concrete item.
            }
            List<LyfeCraftingBridge.RecipeInfo> candidates = resolveRecipes(level, target.resourceKey());
            if (candidates.isEmpty()) {
                continue;
            }
            if (capability == null) {
                capability = PlotCraftingCapability.resolve(level, polygon.get());
            }
            PlotCraftingCapability finalCapability = capability;
            List<LyfeCraftingBridge.RecipeInfo> usable = candidates.stream()
                    .filter(r -> r.craftableWith(finalCapability))
                    .toList();
            if (usable.isEmpty()) {
                continue; // No sufficiently-tiered crafting structure (or Furnace/Stonecutter) present on this plot.
            }
            queue.enqueue(() -> attemptOneOwnStockCraft(level, plot, boxes, target, usable, finalCapability, queue, 0));
        }
    }

    /**
     * One craft-attempt of {@link #enqueueCraftFromOwnStockForPlot}'s own job chain -- mirrors {@link
     * #attemptOneCraftableUnit}'s shape exactly (re-check real stock, since a multi-output recipe can
     * satisfy several units' worth of deficit at once; re-enqueue for the next attempt if progress is
     * still possible and the target isn't met; bounded by {@link #MAX_CRAFTS_PER_PLOT_PER_TARGET}).
     */
    private static void attemptOneOwnStockCraft(ServerLevel level, PlotRecord plot, List<Container> boxes, PlannedInventoryTarget target,
                                                 List<LyfeCraftingBridge.RecipeInfo> usable, PlotCraftingCapability capability,
                                                 MidnightWorkQueue queue, int attemptsSoFar) {
        if (attemptsSoFar >= MAX_CRAFTS_PER_PLOT_PER_TARGET) {
            return;
        }
        Map<Identifier, Integer> available = Settlemynts.scanItemStock(boxes);
        int currentStock = available.getOrDefault(target.resourceKey(), 0);
        if (currentStock >= target.targetCount()) {
            return;
        }
        // Picks whichever alternate (e.g. furnace fuel) is actually currently stocked -- see the
        // original synchronous loop's own doc for why this must be re-checked every craft.
        LyfeCraftingBridge.RecipeInfo recipe = usable.stream()
                .filter(r -> CraftingExecutor.canCraftOne(available, r))
                .findFirst().orElse(null);
        if (recipe == null) {
            return;
        }
        CraftingExecutor.craftOne(boxes, recipe, capability);
        SettlemyntsMod.LOGGER.info("PlannedInventoryClearing: plot {} crafted 1x {} (structure tier {})",
                plot.plotId(), recipe.resultId(), capability.lyfeStructureTier());
        ShopWishlistCache.invalidate(plot.plotId());
        queue.enqueue(() -> attemptOneOwnStockCraft(level, plot, boxes, target, usable, capability, queue, attemptsSoFar + 1));
    }

    /** Single-plot entry point -- see {@link #craftFromOwnStockForGroup}'s own doc for the full story. */
    public static int craftFromOwnStock(ServerLevel level, PlotRecord plot, Geometry.Polygon polygon, List<Container> boxes) {
        if (boxes.isEmpty()) {
            return 0;
        }
        PlotCraftingCapability capability = null; // Lazily resolved once -- null means "not checked yet."
        int crafted = 0;
        for (PlannedInventoryTarget target : plot.plannedInventory()) {
            if (target.isTag()) {
                continue; // A crafting recipe's output is always a concrete item.
            }
            List<LyfeCraftingBridge.RecipeInfo> candidates = resolveRecipes(level, target.resourceKey());
            if (candidates.isEmpty()) {
                continue;
            }
            if (capability == null) {
                capability = PlotCraftingCapability.resolve(level, polygon);
            }
            PlotCraftingCapability finalCapability = capability;
            List<LyfeCraftingBridge.RecipeInfo> usable = candidates.stream()
                    .filter(r -> r.craftableWith(finalCapability))
                    .toList();
            if (usable.isEmpty()) {
                continue; // No sufficiently-tiered crafting structure (or Furnace/Stonecutter) present on this plot.
            }
            for (int i = 0; i < MAX_CRAFTS_PER_PLOT_PER_TARGET; i++) {
                Map<Identifier, Integer> available = Settlemynts.scanItemStock(boxes);
                int currentStock = available.getOrDefault(target.resourceKey(), 0);
                if (currentStock >= target.targetCount()) {
                    break;
                }
                // Picks whichever alternate (e.g. furnace fuel) is actually currently stocked, re-checked
                // every craft -- so running out of one fuel mid-run falls through to the next alternate
                // instead of stalling the whole target.
                LyfeCraftingBridge.RecipeInfo recipe = usable.stream()
                        .filter(r -> CraftingExecutor.canCraftOne(available, r))
                        .findFirst().orElse(null);
                if (recipe == null) {
                    break;
                }
                CraftingExecutor.craftOne(boxes, recipe, finalCapability);
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

        /**
         * Real report, 2026-10-17: "the ledger should use the language name, not the dev name for
         * items" -- every ledger-line call site below used to interpolate {@code key.id()} directly
         * (a raw {@code namespace:path} registry id), same bug class {@code RentLedgerListener
         * #formatCost} already avoids for rent entries. For a concrete item, resolves the real vanilla
         * display name ({@code ItemStack#getHoverName}); for a tag (no single item to name), falls
         * back to a friendly-ified version of the tag's own path (underscores to spaces, each word
         * capitalized) since no item-backed name applies.
         */
        String displayName() {
            if (!isTag) {
                return BuiltInRegistries.ITEM.getOptional(id)
                        .map(item -> item.getDefaultInstance().getHoverName().getString())
                        .orElse(id.getPath());
            }
            String[] words = id.getPath().split("_");
            StringBuilder sb = new StringBuilder();
            for (String word : words) {
                if (word.isEmpty()) {
                    continue;
                }
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
            return sb.toString();
        }
    }

    private record Buyer(Participant participant, int remainingDeficit) {
    }

    private record Seller(Participant participant, int remainingSurplus, int pricePerUnit) {
    }

    /**
     * Central recipe-source resolution -- {@link SmeltingRecipes}/{@link StonecuttingRecipes} (plain
     * vanilla, no Lyfe needed) first, then {@link LyfeCraftingBridge} (tool/weapon/armor/food, if
     * loaded) -- see {@code SmeltingRecipes}' own doc for why these are genuinely separate sources
     * rather than one merged into the other. Returns every known alternate recipe for {@code id}
     * (plural since {@link SmeltingRecipes} now returns one per real vanilla furnace fuel, 2026-10-16)
     * -- callers that only need "any one recipe" (e.g. the existence check below) can just take the
     * first entry; callers that need to choose the cheapest/currently-stocked fuel should not.
     */
    private static List<LyfeCraftingBridge.RecipeInfo> resolveRecipes(ServerLevel level, Identifier id) {
        List<LyfeCraftingBridge.RecipeInfo> smelting = SmeltingRecipes.getAll(level, id);
        if (!smelting.isEmpty()) {
            return smelting;
        }
        Optional<LyfeCraftingBridge.RecipeInfo> stonecutting = StonecuttingRecipes.get(level, id);
        if (stonecutting.isPresent()) {
            return List.of(stonecutting.get());
        }
        if (LyfeCraftingBridge.isLoaded()) {
            Optional<LyfeCraftingBridge.RecipeInfo> lyfe = LyfeCraftingBridge.getRecipe(id);
            if (lyfe.isPresent()) {
                return List.of(lyfe.get());
            }
            // Lyfe's cooking overhaul strips the real vanilla SmeltingRecipe for every cooked-food item
            // and replaces it with its own ServerFoodRecipeStore entry (see
            // LyfeCraftingBridge#getFoodRecipeAlternates' own doc) -- without this fallback,
            // SmeltingRecipes.getAll (which only reads the live vanilla RecipeManager) finds nothing at
            // all for those items on a Lyfe-loaded server. Plural, same as the Furnace-fuel alternates
            // above -- one per real vanilla fuel item.
            List<LyfeCraftingBridge.RecipeInfo> food = LyfeCraftingBridge.getFoodRecipeAlternates(level, id);
            if (!food.isEmpty()) {
                return food;
            }
        }
        return List.of();
    }

    /** Convenience wrapper over {@link #resolveRecipes} for call sites that only need to know whether {@code id} has any known recipe at all, not which one. */
    private static Optional<LyfeCraftingBridge.RecipeInfo> resolveRecipe(ServerLevel level, Identifier id) {
        List<LyfeCraftingBridge.RecipeInfo> all = resolveRecipes(level, id);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    private static void settleResource(ServerLevel level, List<Participant> participants, ResourceKey key, MidnightWorkQueue queue) {
        if (!key.isTag()) {
            Optional<LyfeCraftingBridge.RecipeInfo> recipe = resolveRecipe(level, key.id());
            if (recipe.isPresent()) {
                enqueueCraftableResource(level, participants, key, queue);
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
                appendLedger(level, buyer.participant().plot(),
                        "Bought " + split.totalUnits() + "x " + key.displayName() + " from neighboring plot for " + split.totalCost() + " nuggets");
                appendLedger(level, seller.participant().plot(),
                        "Sold " + split.totalUnits() + "x " + key.displayName() + " to neighboring plot for " + split.totalCost() + " nuggets");
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

    /**
     * Was a plain synchronous loop (up to {@link #MAX_CRAFTS_PER_PLOT_PER_TARGET} recursive
     * buy-vs-craft resolutions, per buyer, all inside the one {@code settleResource} job {@link
     * #settleGroup} already queues per resource) until a real production freeze, 2026-10-21 (see
     * {@link MidnightWorkQueue}'s own doc for the first incident this class caused) -- spreading work
     * by resource alone wasn't enough once a single craftable resource's own recursive cost search,
     * repeated up to 64 times per buyer, could itself take tens of seconds inside one queued job.
     * Now enqueues one job per (buyer, unit attempt) instead, each doing at most one recursive
     * resolution -- the real atomic unit of expensive work -- so even a settlement's worst-case
     * deficit spreads across as many ticks as it takes rather than piling into a single one.
     */
    private static void enqueueCraftableResource(ServerLevel level, List<Participant> participants, ResourceKey key, MidnightWorkQueue queue) {
        for (Participant buyer : participants) {
            PlannedInventoryTarget target = findTarget(buyer.plot(), key);
            if (target == null) {
                continue;
            }
            int startingStock = ContainerWithdraw.countAvailable(buyer.boxes(), key.toShopResource());
            if (startingStock >= target.targetCount()) {
                continue;
            }
            PlotCraftingCapability capability = PlotCraftingCapability.resolve(level, buyer.plot().plotId());
            queue.enqueue(() -> attemptOneCraftableUnit(level, participants, buyer, key, target, capability, queue, 0));
        }
    }

    /**
     * One unit-attempt of {@link #enqueueCraftableResource}'s own job chain -- re-checks real stock
     * (2026-10-18 fix, unchanged: a self-craft of a multi-output recipe can net several units at
     * once, so a fixed-count loop would over-consume ingredients for units nobody still needs) and,
     * if progress is still possible, re-enqueues itself for the next unit instead of looping inline.
     * {@code attemptsSoFar} is the same {@link #MAX_CRAFTS_PER_PLOT_PER_TARGET} safety bound the old
     * inline loop had, against looping forever if {@link #fulfillOneUnit} ever "succeeds" without
     * actually moving stock toward target.
     */
    private static void attemptOneCraftableUnit(ServerLevel level, List<Participant> participants, Participant buyer,
                                                 ResourceKey key, PlannedInventoryTarget target, PlotCraftingCapability capability,
                                                 MidnightWorkQueue queue, int attemptsSoFar) {
        if (attemptsSoFar >= MAX_CRAFTS_PER_PLOT_PER_TARGET) {
            return;
        }
        int currentStock = ContainerWithdraw.countAvailable(buyer.boxes(), key.toShopResource());
        if (currentStock >= target.targetCount()) {
            return;
        }
        if (!fulfillOneUnit(level, participants, buyer, key, capability, new LinkedHashSet<>())) {
            return; // Can't make further progress on this resource for this buyer this round.
        }
        queue.enqueue(() -> attemptOneCraftableUnit(level, participants, buyer, key, target, capability, queue, attemptsSoFar + 1));
    }

    /**
     * Obtains exactly one unit of {@code key} for {@code buyer}, choosing whichever of direct-buy or
     * self-craft is cheaper (or the only achievable one), and actually executes it. Returns whether it
     * succeeded. {@code visiting} is the cycle guard for the self-craft recursion below.
     */
    private static boolean fulfillOneUnit(ServerLevel level, List<Participant> participants, Participant buyer,
                                           ResourceKey key, PlotCraftingCapability capability, Set<Identifier> visiting) {
        if (visiting.contains(key.id())) {
            return false;
        }
        long directCost = cheapestSellerPrice(level, participants, buyer, key);

        // Real production incident, 2026-10-20: a single call here took 44 real seconds (one tick
        // away from a second watchdog crash the same night) -- the recursive cost search below has no
        // memoization at all, so the same ingredient's cost (e.g. a furnace fuel item shared by many
        // different smeltable alternates, itself possibly smeltable too) gets recomputed from scratch
        // every time it's reached by a different branch, blowing up combinatorially with recipe/fuel
        // alternate count rather than staying bounded by the number of distinct resources involved.
        // Fresh per real unit-fulfillment attempt (costs can legitimately change between attempts as
        // stock/participants shift), threaded down through the whole recursive search below.
        Map<ResourceKey, Long> costMemo = new java.util.HashMap<>();
        Optional<CraftOption> craftOption = key.isTag() ? Optional.empty()
                : cheapestCraftOption(level, participants, buyer, key.id(), capability, visiting, costMemo);

        boolean useCraft = craftOption.isPresent() && (directCost < 0 || craftOption.get().cost() <= directCost);
        if (useCraft) {
            return executeCraftOne(level, participants, buyer, craftOption.get().recipe(), capability, withAdded(visiting, key.id()));
        }
        if (directCost >= 0) {
            return executeDirectBuyOne(level, participants, buyer, key);
        }
        return false;
    }

    private record CraftOption(LyfeCraftingBridge.RecipeInfo recipe, long cost) {
    }

    /**
     * The cheapest craftable alternate recipe for {@code id} (e.g. whichever furnace fuel is cheapest
     * to obtain), or empty if {@code id} has no recipe at all, none are craftable with {@code
     * capability}, or every alternate's ingredients are themselves unobtainable. {@code visiting} must
     * NOT already contain {@code id} -- callers pass their own current visiting set, this method adds
     * {@code id} itself before recursing into {@link #estimateCraftCost}.
     */
    private static Optional<CraftOption> cheapestCraftOption(ServerLevel level, List<Participant> participants, Participant buyer,
                                                               Identifier id, PlotCraftingCapability capability, Set<Identifier> visiting,
                                                               Map<ResourceKey, Long> costMemo) {
        if (visiting.contains(id)) {
            return Optional.empty();
        }
        Set<Identifier> nextVisiting = withAdded(visiting, id);
        CraftOption best = null;
        for (LyfeCraftingBridge.RecipeInfo candidate : resolveRecipes(level, id)) {
            if (!candidate.craftableWith(capability)) {
                continue;
            }
            long cost = estimateCraftCost(level, participants, buyer, candidate, capability, nextVisiting, costMemo);
            if (cost >= 0 && (best == null || cost < best.cost())) {
                best = new CraftOption(candidate, cost);
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Non-mutating: the recursive cost to obtain every ingredient for one craft of {@code recipe},
     * amortized down to the cost of just ONE unit of its actual output -- {@code -1} if any
     * ingredient is unobtainable. Every real equipment/smelting/stonecutting/cooking recipe has
     * {@code outputCount() == 1} (division is a no-op there, unchanged behavior); {@code
     * craft.MiscRecipes}' own fixed, multi-output entries (Leaf Litter -> 10 Charcoal, added
     * 2026-10-18) need this -- without it, this method's return value (compared directly against a
     * real PER-UNIT seller price everywhere it's used, see {@link #fulfillOneUnit}/{@link
     * #cheapestCostForOneUnit}) would read as "one Charcoal costs as much as 64 Leaf Litter," instead
     * of correctly as "one Charcoal costs 1/10th that" -- silently making an objectively cheap
     * self-craft look far more expensive than buying, every time. Rounds UP (never down) so a
     * per-unit cost is never under-stated to the point a buy could wrongly look relatively pricier
     * than it really is.
     */
    private static long estimateCraftCost(ServerLevel level, List<Participant> participants, Participant buyer,
                                           LyfeCraftingBridge.RecipeInfo recipe, PlotCraftingCapability capability, Set<Identifier> visiting,
                                           Map<ResourceKey, Long> costMemo) {
        long total = 0;
        for (Map.Entry<Identifier, Integer> entry : recipe.specificComponents().entrySet()) {
            long unitCost = cheapestCostForOneUnit(level, participants, buyer, new ResourceKey(entry.getKey(), false), capability, visiting, costMemo);
            if (unitCost < 0) {
                return -1;
            }
            total += unitCost * entry.getValue();
        }
        for (Map.Entry<String, Integer> entry : recipe.genericComponents().entrySet()) {
            long cheapestMemberUnitCost = -1;
            for (Identifier member : LyfeCraftingBridge.componentGroupMembers(entry.getKey())) {
                long cost = cheapestCostForOneUnit(level, participants, buyer, new ResourceKey(member, false), capability, visiting, costMemo);
                if (cost >= 0 && (cheapestMemberUnitCost < 0 || cost < cheapestMemberUnitCost)) {
                    cheapestMemberUnitCost = cost;
                }
            }
            if (cheapestMemberUnitCost < 0) {
                return -1;
            }
            total += cheapestMemberUnitCost * entry.getValue();
        }
        int outputCount = Math.max(1, recipe.outputCount());
        return (total + outputCount - 1) / outputCount;
    }

    /**
     * Non-mutating: the cheaper of direct-buy or recursive self-craft for one unit of {@code key}, or
     * {@code -1} if neither is achievable. Checks/populates {@code costMemo} first -- see {@link
     * #fulfillOneUnit}'s own doc for why this exists (real incident: unmemoized, this recursion could
     * take 44+ real seconds for one single resource with a deep/wide alternate-fuel dependency chain).
     * Only ever caches a successfully-resolved ({@code >= 0}) result, never a {@code -1} -- a {@code -1}
     * can legitimately depend on which cyclic path reached this key first (the {@code visiting} guard),
     * so caching it risks reusing a cycle artifact from one path as a false negative for a different,
     * genuinely-unblocked path to the same resource; a real resolved cost has no such path-dependence
     * (the world state it's computed from doesn't change mid-search), so it's always safe to reuse.
     */
    private static long cheapestCostForOneUnit(ServerLevel level, List<Participant> participants, Participant buyer,
                                                ResourceKey key, PlotCraftingCapability capability, Set<Identifier> visiting,
                                                Map<ResourceKey, Long> costMemo) {
        Long cached = costMemo.get(key);
        if (cached != null) {
            return cached;
        }
        long direct = cheapestSellerPrice(level, participants, buyer, key);
        long craft = -1;
        if (!key.isTag()) {
            Optional<CraftOption> option = cheapestCraftOption(level, participants, buyer, key.id(), capability, visiting, costMemo);
            if (option.isPresent()) {
                craft = option.get().cost();
            }
        }
        long result = direct < 0 ? craft : (craft < 0 ? direct : Math.min(direct, craft));
        if (result >= 0) {
            costMemo.put(key, result);
        }
        return result;
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
            appendLedger(level, buyer.plot(), "Bought 1x " + key.displayName() + " from neighboring plot for " + realPrice.get() + " nuggets");
            appendLedger(level, seller.plot(), "Sold 1x " + key.displayName() + " to neighboring plot for " + realPrice.get() + " nuggets");
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
                                            LyfeCraftingBridge.RecipeInfo recipe, PlotCraftingCapability capability, Set<Identifier> visiting) {
        // Scans the Participant's own already-resolved boxes directly -- same real lag fix as
        // zone.PlotCraftingTicker (see api.Settlemynts#scanItemStock's own doc): scanPlotItemStock(
        // level, plotId) redundantly re-resolves the whole box list (a full-world entity scan) when
        // this method already has `buyer.boxes()` in hand.
        Map<Identifier, Integer> available = Settlemynts.scanItemStock(buyer.boxes());

        for (Map.Entry<Identifier, Integer> entry : recipe.specificComponents().entrySet()) {
            int need = entry.getValue() - available.getOrDefault(entry.getKey(), 0);
            for (int i = 0; i < need; i++) {
                if (!fulfillOneUnit(level, participants, buyer, new ResourceKey(entry.getKey(), false), capability, visiting)) {
                    return false;
                }
            }
        }
        for (Map.Entry<String, Integer> entry : recipe.genericComponents().entrySet()) {
            List<Identifier> members = LyfeCraftingBridge.componentGroupMembers(entry.getKey());
            int have = members.stream().mapToInt(m -> available.getOrDefault(m, 0)).sum();
            int need = entry.getValue() - have;
            for (int i = 0; i < need; i++) {
                Identifier cheapestMember = cheapestGroupMember(level, participants, buyer, members, capability, visiting);
                if (cheapestMember == null || !fulfillOneUnit(level, participants, buyer, new ResourceKey(cheapestMember, false), capability, visiting)) {
                    return false;
                }
            }
        }

        Map<Identifier, Integer> finalAvailable = Settlemynts.scanItemStock(buyer.boxes());
        if (!CraftingExecutor.canCraftOne(finalAvailable, recipe)) {
            return false; // Shouldn't normally happen given the top-ups above -- defensive guard.
        }
        CraftingExecutor.craftOne(buyer.boxes(), recipe, capability);
        SettlemyntsMod.LOGGER.info("PlannedInventoryClearing: plot {} crafted 1x {} (structure tier {})",
                buyer.plot().plotId(), recipe.resultId(), capability.lyfeStructureTier());
        ShopWishlistCache.invalidate(buyer.plot().plotId());
        return true;
    }

    private static Identifier cheapestGroupMember(ServerLevel level, List<Participant> participants, Participant buyer,
                                                    List<Identifier> members, PlotCraftingCapability capability, Set<Identifier> visiting) {
        // Fresh per call, same reasoning as fulfillOneUnit's own costMemo -- see that method's doc.
        Map<ResourceKey, Long> costMemo = new java.util.HashMap<>();
        Identifier best = null;
        long bestCost = -1;
        for (Identifier member : members) {
            long cost = cheapestCostForOneUnit(level, participants, buyer, new ResourceKey(member, false), capability, visiting, costMemo);
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
