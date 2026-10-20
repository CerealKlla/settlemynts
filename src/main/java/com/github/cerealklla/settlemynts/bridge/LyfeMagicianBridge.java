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

    public static boolean canCastSparkOfLife(LivingEntity caster) {
        return com.github.cerealklla.lyfe.api.Lyfe.canCastSparkOfLife(caster);
    }

    public static boolean attemptCastSparkOfLife(LivingEntity caster) {
        return com.github.cerealklla.lyfe.api.Lyfe.attemptCastSparkOfLife(caster);
    }
}
