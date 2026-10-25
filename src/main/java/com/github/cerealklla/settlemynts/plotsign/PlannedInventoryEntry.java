package com.github.cerealklla.settlemynts.plotsign;

import java.util.Arrays;
import java.util.stream.Collectors;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * One row of {@code client.plotsign.client.ShopTab}'s Planned Inventory page -- mirrors {@link
 * ShopInventoryEntry}'s own shape, minus the price fields, plus the plot's current "happy state"
 * target. {@code targetCount < 0} means "not currently managed" (no {@code
 * zone.PlannedInventoryTarget} exists for this resource yet) -- the screen renders that as a blank
 * EditBox rather than "0", so blank vs. a deliberate zero target stays distinguishable, same as
 * {@code ManageShopScreen}'s Sell Cost box treats blank vs. 0 as different states.
 *
 * <p>{@code keepPrivateCount} (added 2026-10-24) -- the resource's current saved {@code
 * zone.PlannedInventoryTarget#keepPrivateCount}, always {@code 0} if {@code targetCount < 0} (no
 * target exists at all yet). See that class's own doc for what this controls.
 */
public record PlannedInventoryEntry(Identifier resourceKey, boolean isTag, int currentStock, int targetCount, int keepPrivateCount) {

    public static final Codec<PlannedInventoryEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("resource_key").forGetter(PlannedInventoryEntry::resourceKey),
            Codec.BOOL.fieldOf("is_tag").forGetter(PlannedInventoryEntry::isTag),
            Codec.INT.fieldOf("current_stock").forGetter(PlannedInventoryEntry::currentStock),
            Codec.INT.fieldOf("target_count").forGetter(PlannedInventoryEntry::targetCount),
            Codec.INT.optionalFieldOf("keep_private_count", 0).forGetter(PlannedInventoryEntry::keepPrivateCount)
    ).apply(i, PlannedInventoryEntry::new));

    /** Same translated-name/"Any &lt;Tag&gt;" convention as {@link ShopInventoryEntry#label()}. */
    public String label() {
        if (isTag) {
            String readable = Arrays.stream(resourceKey.getPath().replace('_', ' ').split(" "))
                    .map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1))
                    .collect(Collectors.joining(" "));
            return "Any " + readable;
        }
        Item item = BuiltInRegistries.ITEM.getValue(resourceKey);
        return item != Items.AIR ? new ItemStack(item).getHoverName().getString() : resourceKey.toString();
    }
}
