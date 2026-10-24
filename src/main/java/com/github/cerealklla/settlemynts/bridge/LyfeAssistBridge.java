package com.github.cerealklla.settlemynts.bridge;

import java.util.Set;
import java.util.UUID;

import com.github.cerealklla.lyfe.api.Lyfe;

import net.minecraft.world.entity.LivingEntity;
import net.neoforged.fml.ModList;

/**
 * The General "assist marker" kill-credit system (explicit user spec, 2026-10-16, built in Lyfe --
 * see that mod's own {@code combat.AssistTracker}/{@code api.Lyfe#getAssistedAttackerIds}), widened
 * 2026-10-23 to cover non-player attackers too -- same bridge shape Myssions already uses for its
 * own quest-kill crediting. Optional soft dependency, same isolation convention as every other
 * bridge in this package: every call site elsewhere in Settlemynts must check {@link #isLoaded()}
 * itself before touching this class at all, so its {@code Lyfe.*}-referencing bytecode is never
 * loaded on a Lyfe-less server. First consumer: {@code guardhouse.GuardKillGoldPayment}, which needs
 * to find a Guard among a mob's assist-credited attackers, not just player credit.
 */
public final class LyfeAssistBridge {

    private LyfeAssistBridge() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("lyfe");
    }

    /** Every attacker id (player, NPC, or summoned companion) currently credited toward killing {@code deadMob}. */
    public static Set<UUID> getAssistedAttackerIds(LivingEntity deadMob) {
        return Lyfe.getAssistedAttackerIds(deadMob);
    }
}
