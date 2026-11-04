package com.github.cerealklla.settlemynts.bridge;

import java.util.UUID;

import com.github.cerealklla.audyble.api.Audyble;
import com.github.cerealklla.audyble.npc.NpcIdentityKey;
import com.github.cerealklla.audyble.npc.PlotRole;
import com.github.cerealklla.settlemynts.zone.PlotWorkerKind;

import net.neoforged.fml.ModList;

/**
 * Resolves/creates a plot-bound worker's persistent NPC identity (Audyble Phase 5b) the instant it's
 * spawned -- optional soft dependency, same isolation convention as every other bridge in this
 * package (e.g. {@link LyfeMerchantBridge}): every call site elsewhere in Settlemynts must check
 * {@link #isLoaded()} itself before touching this class at all, and this class's own public method
 * signatures deliberately take only vanilla/Settlemynts-local types ({@link UUID}, {@link
 * PlotWorkerKind.Kind}) -- never an Audyble type directly -- so a caller's own compiled bytecode never
 * references Audyble at all, same discipline the root project's own CLAUDE.md documents (a type
 * reference from a common class, even behind a runtime guard, risks a {@code NoClassDefFoundError} on
 * an Audyble-less server). The translation into Audyble's own {@link PlotRole}/{@link NpcIdentityKey}
 * happens entirely inside this one class's method bodies.
 *
 * <p>{@code ownerId} is the owning plot's real {@code zone.PlotRecord#plotId} for the four worker
 * roles ({@link #assumeWorkerIdentity}), already a stable, globally-unique UUID every spawn ticker has
 * in hand -- confirmed no new {@code PlotRecord} field is needed to carry this. {@link
 * #assumeGuardIdentity} takes the Guard Kit Stand's own UUID instead, since a single plot can have
 * multiple stands (multiple guards) -- a stand's UUID is already this suite's own de facto "slot
 * identity" ({@code guardhouse.GuardKitStandIndex}'s map key).
 */
public final class AudybleIdentityBridge {

    private AudybleIdentityBridge() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("audyble");
    }

    /** For Farmer/Lumberjack/Miner/Resident -- {@code kind} must not be {@code NONE}. */
    public static void assumeWorkerIdentity(UUID plotId, PlotWorkerKind.Kind kind, Long settlementPlaceId) {
        PlotRole role = switch (kind) {
            case FARMER -> PlotRole.FARMER;
            case LUMBERJACK -> PlotRole.LUMBERJACK;
            case MINER -> PlotRole.MINER;
            case RESIDENT -> PlotRole.RESIDENT;
            case NONE -> throw new IllegalArgumentException("PlotWorkerKind.Kind.NONE has no NPC identity");
        };
        Audyble.resolveOrBeginBirth(new NpcIdentityKey(plotId, role), settlementPlaceId);
    }

    public static void assumeGuardIdentity(UUID standId, Long settlementPlaceId) {
        Audyble.resolveOrBeginBirth(new NpcIdentityKey(standId, PlotRole.GUARD), settlementPlaceId);
    }
}
