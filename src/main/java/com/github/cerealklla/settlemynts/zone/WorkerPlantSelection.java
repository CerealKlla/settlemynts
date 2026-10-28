package com.github.cerealklla.settlemynts.zone;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;

/**
 * Farm/Lumberyard "Configure" Plant Selection -- real explicit user spec, 2026-10-28: "The plot will
 * now have a config gui similar to the Mine, where you can select the Seed/Sappling for the plot.
 * Each tier grants an additional slot... Each time a sappling/seed is planted, the sappling/seed is
 * chosen at random from the configured options (blank are skipped and a different seed is used
 * instead)." Shared by both zone types (a plot is never both, so one storage/algorithm pair covers
 * either reading -- Farm reads its slots as Seeds, Lumberyard as Saplings).
 *
 * <p>Storage ({@link PlotRecord.PlotMiscState#plantSelections}) is a plain {@code List<String>} of
 * real item ids (or {@code ""} for an explicitly-cleared/never-set slot), always normalized to
 * exactly {@link #MAX_SLOTS} entries by {@link #normalized} -- same "allocate at max capacity up
 * front" convention {@code guardhouse.GuardhouseConstants} already established for an identical
 * tier-gated-slot-count shape, so a later Tier-up never needs a resize/migration, just unlocks
 * reading further into the same fixed list.
 */
public final class WorkerPlantSelection {

    private WorkerPlantSelection() {
    }

    public static final int MAX_SLOTS = 5;

    /** "Each tier grants an additional slot" -- Tier 1 = 1 slot, up to Tier 5 = {@link #MAX_SLOTS}. */
    public static int slotsForTier(final int tier) {
        return Math.max(1, Math.min(tier, MAX_SLOTS));
    }

    /** Always exactly {@link #MAX_SLOTS} entries, padding/truncating a possibly-shorter/corrupt stored list -- never trust raw storage length directly. */
    public static List<String> normalized(final List<String> stored) {
        final List<String> result = new ArrayList<>(MAX_SLOTS);
        for (int i = 0; i < MAX_SLOTS; i++) {
            result.add(i < stored.size() && stored.get(i) != null ? stored.get(i) : "");
        }
        return result;
    }

    /** Sets (or, with {@code itemId == null}, clears) one slot -- the caller is responsible for the real item consumption this represents. */
    public static List<String> withSlot(final List<String> stored, final int slotIndex, final Identifier itemId) {
        final List<String> result = normalized(stored);
        result.set(slotIndex, itemId == null ? "" : itemId.toString());
        return result;
    }

    /**
     * One real, resolved {@link Item} per currently-visible (within {@code tier}'s own slot count),
     * non-blank slot -- used both by the config screen (to show what's actually selected) and {@link
     * #chooseRandom} (to pick from).
     */
    public static List<Item> configuredItems(final List<String> stored, final int tier) {
        final List<String> normalized = normalized(stored);
        final int visibleSlots = slotsForTier(tier);
        final List<Item> items = new ArrayList<>(visibleSlots);
        for (int i = 0; i < visibleSlots; i++) {
            final String raw = normalized.get(i);
            if (raw.isEmpty()) {
                continue;
            }
            final Identifier id = Identifier.tryParse(raw);
            if (id == null) {
                continue;
            }
            final Item item = BuiltInRegistries.ITEM.getValue(id);
            if (item != null && item != net.minecraft.world.item.Items.AIR) {
                items.add(item);
            }
        }
        return items;
    }

    /**
     * Real random pick among the plot's own configured, non-blank slots (within the current Tier's
     * unlocked count) -- blanks are already excluded by {@link #configuredItems}, so there's nothing
     * special to "skip" here beyond simply never adding them to the candidate list in the first
     * place. Falls back to {@code fallback} (the existing biome-based auto-pick) if nothing's been
     * configured at all yet -- an unconfigured plot (including every pre-existing saved one) keeps
     * working exactly as it did before this feature, no silent "plants nothing" regression.
     */
    public static Item chooseRandom(final List<String> stored, final int tier, final RandomSource random, final Item fallback) {
        final List<Item> configured = configuredItems(stored, tier);
        if (configured.isEmpty()) {
            return fallback;
        }
        return configured.get(random.nextInt(configured.size()));
    }

    /** The resolved item currently sitting in {@code slotIndex} (within {@code tier}'s own visible range), if any -- for the config screen's own display. */
    public static Optional<Item> itemInSlot(final List<String> stored, final int slotIndex, final int tier) {
        if (slotIndex < 0 || slotIndex >= slotsForTier(tier)) {
            return Optional.empty();
        }
        final String raw = normalized(stored).get(slotIndex);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        final Identifier id = Identifier.tryParse(raw);
        if (id == null) {
            return Optional.empty();
        }
        final Item item = BuiltInRegistries.ITEM.getValue(id);
        return item != null && item != net.minecraft.world.item.Items.AIR ? Optional.of(item) : Optional.empty();
    }
}
