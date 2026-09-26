package com.github.cerealklla.settlemynts.registration;

import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.founding.SettlementClaimFlagItem;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Items for the settlement-founding mechanic (design doc Section 5). */
public final class ModItems {

    private ModItems() {
    }

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(SettlemyntsMod.MODID);

    // ENCHANTMENT_GLINT_OVERRIDE forces vanilla's own shimmer effect regardless of actual
    // enchantments -- same "glow like enchanted, no new art needed" treatment as Yconomics' Coin
    // Purse (design doc Section 5: "made to glow purple like an enchanted item").
    public static final DeferredItem<SettlementClaimFlagItem> SETTLEMENT_CLAIM_FLAG = ITEMS.register(
            "settlement_claim_flag",
            id -> new SettlementClaimFlagItem(new Item.Properties()
                    .stacksTo(1)
                    .setId(ResourceKey.create(Registries.ITEM, id))
                    .component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)));
}
