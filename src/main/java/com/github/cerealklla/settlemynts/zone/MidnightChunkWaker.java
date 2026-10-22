package com.github.cerealklla.settlemynts.zone;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;

/**
 * Real, explicit user requirement, 2026-10-22 (see design-document.md's "Midnight settlement
 * processing" section, decisions.md's own pending-work entry): every settlement's midnight pass
 * must run regardless of whether a player is anywhere near it, which means "wake" the whole
 * settlement -- force-load every chunk its processing will touch -- before running it, and let it
 * "rest on its own time" (release the forced chunks, no attempt to force an immediate unload)
 * once that settlement's full pipeline is done, rather than force/unforcing individual chunks
 * per-job (see decisions.md's own writeup of why that's actually worse: it doesn't bound anything
 * real, since releasing a ticket doesn't mean the chunk unloads immediately anyway, and it
 * introduces real churn re-forcing the same chunk repeatedly across one settlement's own many
 * small jobs).
 *
 * <p>Built on NeoForge's real {@code ping}-adjacent-but-unrelated chunk-loading API, {@link
 * TicketController} (confirmed against the real NeoForge sources jar, not guessed) -- the modern
 * replacement for the old Forge {@code ForgeChunkManager}. Registered with {@code
 * forceNaturalSpawning=false} (we only need loaded, readable/writable {@code Container} state for
 * direct transaction code to call into -- same as a player opening a chest -- not full mob-spawning
 * simulation, which is out of scope for this fix). Never persists across a restart: {@link
 * #validateTickets} unconditionally discards every ticket this controller owns on world load, since
 * a fresh midnight pass always re-requests whatever it needs from scratch -- a stray forced chunk
 * surviving a crash mid-pass is a wasted resource, not a correctness problem, but there's no reason
 * to let it linger either.
 */
public final class MidnightChunkWaker {

    private static final TicketController CONTROLLER = new TicketController(
            Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "midnight_settlement_wake"),
            MidnightChunkWaker::validateTickets);

    private static final Map<UUID, LongSet> forcedChunksBySettlement = new HashMap<>();

    private MidnightChunkWaker() {
    }

    public static void registerController(RegisterTicketControllersEvent event) {
        event.register(CONTROLLER);
    }

    private static void validateTickets(ServerLevel level, net.neoforged.neoforge.common.world.chunk.TicketHelper helper) {
        for (UUID owner : helper.getEntityTickets().keySet()) {
            helper.removeAllTickets(owner);
        }
    }

    /** Force-loads every chunk {@code positions} fall in, under {@code settlementId}'s own ownership -- safe to call again with a different/overlapping set, see {@link #sleep}. */
    public static void wake(ServerLevel level, UUID settlementId, Iterable<BlockPos> positions) {
        LongSet chunks = forcedChunksBySettlement.computeIfAbsent(settlementId, id -> new LongOpenHashSet());
        for (BlockPos pos : positions) {
            long packed = ChunkPos.pack(pos);
            if (chunks.add(packed)) {
                ChunkPos chunkPos = ChunkPos.unpack(packed);
                CONTROLLER.forceChunk(level, settlementId, chunkPos.x(), chunkPos.z(), true, false);
            }
        }
    }

    /** Releases every chunk {@code settlementId} currently has forced -- called once that settlement's full {@code MidnightWorkQueue} job sequence finishes (see {@code MidnightWorkQueue#enqueueForSettlement}). */
    public static void sleep(ServerLevel level, UUID settlementId) {
        LongSet chunks = forcedChunksBySettlement.remove(settlementId);
        if (chunks == null) {
            return;
        }
        for (long packed : chunks) {
            ChunkPos chunkPos = ChunkPos.unpack(packed);
            CONTROLLER.forceChunk(level, settlementId, chunkPos.x(), chunkPos.z(), false, false);
        }
    }
}
