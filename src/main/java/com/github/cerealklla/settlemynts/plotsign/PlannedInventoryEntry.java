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
 * One row of {@code client.PlannedInventoryScreen} (2026-10-09) -- mirrors {@link ShopInventoryEntry}'s
 * own shape, minus the price fields, plus the plot's current "happy state" target. {@code targetCount
 * < 0} means "not currently managed" (no {@code zone.PlannedInventoryTarget} exists for this resource
 * yet) -- the screen renders that as a blank EditBox rather than "0", so blank vs. a deliberate zero
 * target stays distinguishable, same as {@code ManageShopScreen}'s Sell Cost box treats blank vs. 0 as
 * different states.
 */
public record PlannedInventoryEntry(Identifier resourceKey, boolean isTag, int currentStock, int targetCount) {

    public static final Codec<PlannedInventoryEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("resource_key").forGetter(PlannedInventoryEntry::resourceKey),
            Codec.BOOL.fieldOf("is_tag").forGetter(PlannedInventoryEntry::isTag),
            Codec.INT.fieldOf("current_stock").forGetter(PlannedInventoryEntry::currentStock),
            Codec.INT.fieldOf("target_count").forGetter(PlannedInventoryEntry::targetCount)
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
