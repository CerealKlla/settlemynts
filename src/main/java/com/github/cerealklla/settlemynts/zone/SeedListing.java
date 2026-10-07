package com.github.cerealklla.settlemynts.zone;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/**
 * One entry a {@link ShopSeedCatalog} wants seeded into a freshly-created or leveling-up Shop
 * (design doc Section 14a, added 2026-10-05, explicit user request: auto-seed a plot's Shop with
 * goods appropriate to its Zone Type). Two shapes, matching the two kinds of "stock" a seed catalog
 * needs to hand over:
 *
 * <ul>
 *   <li><b>Resource-backed</b> ({@code resource} set, {@code stacksToDeposit} empty): a generic/
 *   physical good identified by a {@link ShopResource} (e.g. plain cobblestone, or a tag) -- {@code
 *   stockCount} copies of the resource's representative item get deposited.</li>
 *   <li><b>Stack-backed</b> ({@code resource} empty, {@code stacksToDeposit} set): a list of already-
 *   built {@link ItemStack}s the catalog constructed itself -- the vehicle for Lyfe's Research/
 *   Recipe Notes, which carry embedded component data ({@code research.ResearchNoteTarget}) a bare
 *   {@link ShopResource} (plain item id or tag) can't represent. The listing this seeds is keyed by
 *   the stacks' own shared item id (so a Research Note listing matches any Research Note stack in a
 *   box, regardless of which specific recipe it unlocks -- see this feature's own flagged "one flat
 *   price per note" simplification). {@code stockCount} is ignored for this shape -- the deposited
 *   count is simply {@code stacksToDeposit.size()}.
 * </ul>
 */
public record SeedListing(Optional<ShopResource> resource, List<ItemStack> stacksToDeposit, int pricePerUnit, int stockCount) {

    public static SeedListing ofResource(ShopResource resource, int pricePerUnit, int stockCount) {
        return new SeedListing(Optional.of(resource), List.of(), pricePerUnit, stockCount);
    }

    public static SeedListing ofStacks(int pricePerUnit, List<ItemStack> stacksToDeposit) {
        return new SeedListing(Optional.empty(), List.copyOf(stacksToDeposit), pricePerUnit, stacksToDeposit.size());
    }

    /** The {@link ShopResource} this listing is keyed by, whichever shape it is. */
    public ShopResource listingResource() {
        return resource.orElseGet(() -> ShopResource.ofItem(BuiltInRegistries.ITEM.getKey(stacksToDeposit.get(0).getItem())));
    }
}
