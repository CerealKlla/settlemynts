package com.github.cerealklla.settlemynts.guardhouse;

import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Optional external hook letting another mod mark a player as an ally of a specific settlement's
 * Guards for targeting purposes, without Settlemynts needing to depend on that mod at all -- mirrors
 * {@link GuardHostilityExtension} exactly. Added 2026-11-18 for Terrytories' Friendly standing: real
 * report ("my own guard isn't hurting me but he's still wasting time attacking me instead of
 * patrolling"). {@code reputation.CombatReputationListener} already zeroes Friendly mutual damage,
 * but that only stops the *damage* -- vanilla's own {@code HurtByTargetGoal} sets {@code
 * GuardEntity#setTarget} to whoever last hit it via {@code setLastHurtByMob}, independent of whether
 * any real damage landed, and vanilla's {@code TargetingConditions#forCombat()} is what actually
 * filters that out for an ally (via {@code Entity#isAlliedTo} -> the overridable {@code
 * considersEntityAsAlly} hook) -- see {@code tavern.TavernNpcEntity#considersEntityAsAlly}'s own doc
 * for the identical companion-owner case this mirrors. Consulted by {@code
 * GuardEntity#considersEntityAsAlly}.
 *
 * <p>A one-shot, last-writer-wins registration, same as its sibling.
 */
public final class GuardAllyExtension {

    private static volatile BiPredicate<UUID, UUID> predicate;

    private GuardAllyExtension() {
    }

    public static void register(BiPredicate<UUID, UUID> settlementCoreIdAndPlayerId) {
        predicate = settlementCoreIdAndPlayerId;
    }

    public static boolean isAlly(UUID settlementCoreId, UUID playerId) {
        BiPredicate<UUID, UUID> current = predicate;
        return current != null && settlementCoreId != null && current.test(settlementCoreId, playerId);
    }
}
