package com.github.cerealklla.settlemynts.bridge;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

/**
 * Lyfe's new Mana/Magician feature (design doc Section 24, its own CLAUDE.md) -- specifically "NPCs
 * must be able to cast spells too," called out by the user as "very important." Same isolation
 * convention as every other bridge in this package: {@code guardhouse.GuardEntity}/{@code
 * GuardSpellCastGoal} must never reference {@code com.github.cerealklla.lyfe.api.Lyfe} directly --
 * simply *loading* a class whose bytecode mentions an optional dependency's type can throw {@code
 * NoClassDefFoundError} on a Lyfe-less server even behind a runtime {@code isLoaded()} check (the
 * JVM verifier resolves every type a class's methods reference once that class is touched at all,
 * confirmed by this exact incident class documented in the root project's own CLAUDE.md) -- so all
 * of that referencing bytecode lives in this one bridge class instead, which Guard code only ever
 * reaches through {@link #isLoaded()}.
 */
public final class LyfeMagicianBridge {

    private LyfeMagicianBridge() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("lyfe");
    }

    public static boolean isNatureStaff(ItemStack stack) {
        return com.github.cerealklla.lyfe.api.Lyfe.isNatureStaff(stack);
    }

    /** Whether {@code offhandStack} is a Focus (Lyfe's design doc Section 25) that rolled a granted spell -- lets {@code GuardEntity#reassessWeaponGoal} swap in spell-casting behavior off an off-hand item too, not just a mainhand Nature Staff. */
    public static boolean hasOffhandSpell(ItemStack offhandStack) {
        return com.github.cerealklla.lyfe.api.Lyfe.hasOffhandSpell(offhandStack);
    }

    public static boolean canCastSparkOfLife(LivingEntity caster) {
        return com.github.cerealklla.lyfe.api.Lyfe.canCastSparkOfLife(caster);
    }

    public static boolean attemptCastSparkOfLife(LivingEntity caster) {
        return com.github.cerealklla.lyfe.api.Lyfe.attemptCastSparkOfLife(caster);
    }

    /** Whether {@code caster} casting Spark of Life could affect {@code target} at all right now -- see {@code api.Lyfe#canSpellAffectEntity}'s own doc. Lets {@code GuardSpellCastGoal} stop treating a target it can never affect (e.g. a Caryvan Wagon) as a valid heal/attack candidate. */
    public static boolean canSparkOfLifeAffect(LivingEntity caster, LivingEntity target) {
        return com.github.cerealklla.lyfe.api.Lyfe.canSpellAffectEntity(caster, target);
    }

    /** Clears {@code caster}'s cached {@code canSparkOfLifeAffect} results -- call whenever its available spells change (e.g. mainhand/offhand equip change), same as {@code GuardEntity#onEquipItem} already does. */
    public static void invalidateSpellTargetCache(LivingEntity caster) {
        com.github.cerealklla.lyfe.magician.SpellTargetCache.invalidate(caster);
    }
}
