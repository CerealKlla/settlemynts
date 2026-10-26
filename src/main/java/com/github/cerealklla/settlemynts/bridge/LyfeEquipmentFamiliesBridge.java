package com.github.cerealklla.settlemynts.bridge;

import java.util.ArrayList;
import java.util.List;

import com.github.cerealklla.kyt.generic.GenericFamily;
import com.github.cerealklla.lyfe.craft.EquipmentTierLadder;
import com.github.cerealklla.lyfe.craft.FocusLadder;
import com.github.cerealklla.lyfe.craft.NatureStaffLadder;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.equipment.ArmorType;
import net.neoforged.fml.ModList;

/**
 * Resolves a Kyt {@link GenericFamily} ("Use any Sword") into its real vanilla/Lyfe item ids,
 * lowest tier first -- explicit user spec, 2026-10-25: "When guards are spawned with Generics
 * specified they will use the lowest tier available of the generic first." Same isolation
 * convention as every other bridge in this package (see {@code LyfeMagicianBridge}'s own doc) --
 * {@code guardhouse.GuardEntity} must check {@link #isLoaded()} before calling this, same as it
 * already does for every other Lyfe integration. {@link GenericFamily} itself is Kyt's own type, not
 * Lyfe's -- safe to reference directly here (unlike a Lyfe type) since {@code GuardEntity} already
 * references Kyt types unconditionally (e.g. {@code LoadoutRecord}), confirming Kyt is treated as
 * effectively required wherever guard-equip code already lives.
 */
public final class LyfeEquipmentFamiliesBridge {

    private LyfeEquipmentFamiliesBridge() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("lyfe");
    }

    /** Every real item id this family can resolve to, lowest tier first -- a Guard tries each in order, equipping the first one actually in the plot's boxes. */
    public static List<Identifier> idsLowToHigh(GenericFamily family) {
        return switch (family) {
            case SWORD -> toolIds(EquipmentTierLadder.ToolType.SWORD);
            case PICKAXE -> toolIds(EquipmentTierLadder.ToolType.PICKAXE);
            case AXE -> toolIds(EquipmentTierLadder.ToolType.AXE);
            case SHOVEL -> toolIds(EquipmentTierLadder.ToolType.SHOVEL);
            case HOE -> toolIds(EquipmentTierLadder.ToolType.HOE);
            case SPEAR -> toolIds(EquipmentTierLadder.ToolType.SPEAR);
            case BOW -> List.of(Identifier.withDefaultNamespace("bow"));
            case CROSSBOW -> List.of(Identifier.withDefaultNamespace("crossbow"));
            case NATURE_STAFF -> NatureStaffLadder.allIds();
            case FOCUS -> FocusLadder.allIds();
            case HELMET -> armorIds(ArmorType.HELMET);
            case CHESTPLATE -> armorIds(ArmorType.CHESTPLATE);
            case LEGGINGS -> armorIds(ArmorType.LEGGINGS);
            case BOOTS -> armorIds(ArmorType.BOOTS);
        };
    }

    private static List<Identifier> toolIds(EquipmentTierLadder.ToolType type) {
        List<Identifier> ids = new ArrayList<>();
        for (int tier = EquipmentTierLadder.MIN_TIER; tier <= EquipmentTierLadder.MAX_REACHABLE_TIER; tier++) {
            ids.add(EquipmentTierLadder.itemId(type, tier));
        }
        return ids;
    }

    private static List<Identifier> armorIds(ArmorType type) {
        List<Identifier> ids = new ArrayList<>();
        for (int tier = EquipmentTierLadder.MIN_TIER; tier <= EquipmentTierLadder.MAX_REACHABLE_TIER; tier++) {
            ids.add(EquipmentTierLadder.armorItemId(type, tier));
        }
        return ids;
    }
}
