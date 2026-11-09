package com.github.cerealklla.settlemynts.guardhouse;

import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Optional external hook letting another mod mark a player as hostile to a specific settlement's
 * Guards, without Settlemynts needing to depend on that mod at all -- added 2026-11-08 for the
 * planned Terrytories reputation mod ("when rep is sufficiently low, NPCs for that settlement will
 * target you aggressively"). Registered via {@code api.Settlemynts#registerGuardHostilityExtension};
 * consulted by {@link GuardSightGoal#isHostileCandidate} alongside its existing {@code Enemy} scan.
 *
 * <p>A one-shot, last-writer-wins registration (a plain volatile reference, not a list) -- only one
 * real consumer of this is ever expected to exist at a time, same as this suite's other single-slot
 * external hooks (e.g. Nuclyus's TTS/LLM facades).
 */
public final class GuardHostilityExtension {

    private static volatile BiPredicate<UUID, UUID> predicate;

    private GuardHostilityExtension() {
    }

    public static void register(BiPredicate<UUID, UUID> settlementCoreIdAndPlayerId) {
        predicate = settlementCoreIdAndPlayerId;
    }

    public static boolean isHostile(UUID settlementCoreId, UUID playerId) {
        BiPredicate<UUID, UUID> current = predicate;
        return current != null && settlementCoreId != null && current.test(settlementCoreId, playerId);
    }
}
