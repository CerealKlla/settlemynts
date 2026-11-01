package com.github.cerealklla.settlemynts.tavern;

import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * "Each tier of the tavern increases the base stats of the NPCs. NPC stats will be randomized on
 * top of base stats which increase with building tier." A genuinely new mechanic -- Guardhouse's own
 * Tier scaling only ever caps max GEAR Tier, never scales a guard's own raw attributes (see design
 * doc Section 17's own flagged distinction). Base values scale linearly with tier; the ±20% random
 * variance is applied on top via real post-spawn {@link AttributeInstance#setBaseValue} overrides
 * (the static {@code AttributeSupplier} every {@code EntityType} registers can only ever express one
 * fixed value, not a per-instance roll). Flagged placeholder/tunable, same footing as every other
 * numeric curve in this mod.
 */
public final class TavernStatRoller {

    private TavernStatRoller() {
    }

    private static final double VARIANCE_FRACTION = 0.20;

    public static void roll(LivingEntity npc, int tier, RandomSource random) {
        int clamped = Math.max(1, Math.min(5, tier));
        double baseHealth = 10.0 + 4.0 * clamped;
        double baseAttack = 2.0 + 1.0 * clamped;
        applyVariance(npc, Attributes.MAX_HEALTH, baseHealth, random);
        applyVariance(npc, Attributes.ATTACK_DAMAGE, baseAttack, random);
        npc.setHealth((float) npc.getMaxHealth());
    }

    private static void applyVariance(LivingEntity npc, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
            double base, RandomSource random) {
        AttributeInstance instance = npc.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        double variance = 1.0 + (random.nextDouble() * 2.0 - 1.0) * VARIANCE_FRACTION;
        instance.setBaseValue(base * variance);
    }
}
