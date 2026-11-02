package com.github.cerealklla.settlemynts.zone;

import java.util.UUID;

import com.github.cerealklla.nuclyus.chunkload.ChunkClaimId;
import com.github.cerealklla.nuclyus.chunkload.ChunkClaims;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Real, explicit user requirement, 2026-10-22 (see design-document.md's "Midnight settlement
 * processing" section, decisions.md's own pending-work entry): every settlement's midnight pass
 * must run regardless of whether a player is anywhere near it, which means "wake" the whole
 * settlement -- force-load every chunk its processing will touch -- before running it, and let it
 * "rest on its own time" (release the forced chunks) once that settlement's full pipeline is done.
 *
 * <p><b>Migrated onto Nuclyus's shared {@code chunkload.ChunkClaims}, 2026-11-02</b> -- see that
 * class's own doc for the full rationale. A second, independent reason to force-load a settlement's
 * chunks (proximity-based wake, see {@link SettlementProximityWaker}) needed to exist without
 * either reason's own release stepping on the other's chunks; rather than build a second bespoke
 * {@code TicketController} here, this class is now a thin wrapper over the shared primitive, using
 * a {@code "midnight_wake"}-tagged claim id. Its own public {@link #wake}/{@link #sleep} signatures
 * are unchanged on purpose -- every real call site ({@code ShopMidnightRestockTicker},
 * {@code MidnightWorkQueue}) needed zero changes for this migration.
 */
public final class MidnightChunkWaker {

    private MidnightChunkWaker() {
    }

    private static ChunkClaimId claimId(UUID settlementId) {
        return new ChunkClaimId("settlemynts", "midnight_wake", settlementId);
    }

    /** Force-loads every chunk {@code positions} fall in, under {@code settlementId}'s own ownership -- safe to call again with a different/overlapping set, see {@link #sleep}. */
    public static void wake(ServerLevel level, UUID settlementId, Iterable<BlockPos> positions) {
        ChunkClaims.claim(level, claimId(settlementId), positions);
    }

    /** Releases every chunk {@code settlementId} currently has forced under the midnight-wake reason -- called once that settlement's full {@code MidnightWorkQueue} job sequence finishes. */
    public static void sleep(ServerLevel level, UUID settlementId) {
        ChunkClaims.release(level.getServer(), claimId(settlementId));
    }
}
