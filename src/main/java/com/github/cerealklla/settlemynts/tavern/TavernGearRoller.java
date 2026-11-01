package com.github.cerealklla.settlemynts.tavern;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * "These NPCs can have an entire set of armor, an offhand, and a hotbar like guards" -- unlike a
 * Guard (equipped from a player-authored Kyt), a Tavern NPC's gear is freshly rolled at spawn time
 * off a flat tier-to-vanilla-material table, independent of Lyfe's own {@code
 * craft.EquipmentTierLadder} (no new cross-mod coupling needed for this). "A hotbar like guards" is
 * interpreted as "a real mainhand weapon slot" -- there's no Kyt-style actual hotbar backing a
 * randomly-rolled NPC, so this never populates one; the mainhand slot alone is what ends up captured
 * into the eventual Companion Token's saved state. Flagged placeholder/tunable, same footing as
 * every other tier-to-material table in this mod.
 */
public final class TavernGearRoller {

    private TavernGearRoller() {
    }

    public static void equip(net.minecraft.world.entity.Mob npc, int tier) {
        int clamped = Math.max(1, Math.min(5, tier));
        switch (clamped) {
            case 1 -> equipSet(npc, Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS, Items.WOODEN_SWORD, null);
            case 2 -> equipSet(npc, Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS, Items.STONE_SWORD, null);
            case 3 -> equipSet(npc, Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS, Items.IRON_SWORD, Items.SHIELD);
            case 4 -> equipSet(npc, Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS, Items.DIAMOND_SWORD, Items.SHIELD);
            default -> equipSet(npc, Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS, Items.NETHERITE_SWORD, Items.SHIELD);
        }
    }

    private static void equipSet(net.minecraft.world.entity.Mob npc, net.minecraft.world.item.Item helmet,
            net.minecraft.world.item.Item chest, net.minecraft.world.item.Item legs, net.minecraft.world.item.Item boots,
            net.minecraft.world.item.Item weapon, net.minecraft.world.item.Item offhand) {
        npc.setItemSlot(EquipmentSlot.HEAD, new ItemStack(helmet));
        npc.setItemSlot(EquipmentSlot.CHEST, new ItemStack(chest));
        npc.setItemSlot(EquipmentSlot.LEGS, new ItemStack(legs));
        npc.setItemSlot(EquipmentSlot.FEET, new ItemStack(boots));
        npc.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(weapon));
        npc.setItemSlot(EquipmentSlot.OFFHAND, offhand == null ? ItemStack.EMPTY : new ItemStack(offhand));
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            npc.setDropChance(slot, 0.0F);
        }
    }
}
