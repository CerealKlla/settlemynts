package com.github.cerealklla.settlemynts.npc;

import java.util.LinkedHashMap;
import java.util.Map;

import com.github.cerealklla.nuclyus.server.PersistedServerStore;

import net.minecraft.server.MinecraftServer;

/**
 * Persisted pool of ~50 unassigned settlement/town backstories -- moved here from Audyble's own
 * {@code npc.SettlementLoreStore}, 2026-11-05 (settlement-scoped data fits naturally alongside this
 * mod's other settlement ownership, same reasoning as the rest of the NPC identity/memory move -- see
 * {@link NpcIdentityStore}'s own class doc). {@link #generate()} seeds a hand-authored placeholder
 * pool directly in source (not a live LLM bulk-generation pass) so this store never depends on the
 * LLM being reachable at boot. Entries are intentionally short/generic placeholders, composed from a
 * few word pools rather than hand-written one at a time -- flagged as placeholder content, not final
 * flavor text, same spirit as this suite's other explicitly-flagged placeholder generation passes
 * (e.g. {@code tavern.TavernGearRoller}/{@code TavernStatRoller}).
 */
public final class SettlementLoreStore {

    private static final int POOL_SIZE = 50;

    private static final String[] FOUNDING_REASONS = {
            "founded by a handful of river traders seeking calmer waters",
            "built atop the ruins of a much older settlement nobody now remembers the name of",
            "started as a single watchtower guarding a mountain pass",
            "grew out of a mining claim that struck it rich",
            "settled by families fleeing a war in a distant land",
            "built around a well that never once ran dry, even in the worst droughts",
            "founded on the spot where a wandering preacher claimed to see a sign",
            "grew from a single farmstead whose harvests drew neighbors for miles",
            "built by veterans granted land after a long campaign",
            "started as a waystation for merchants crossing the old trade road",
    };

    private static final String[] NOTABLE_TRAITS = {
            "Its people are known for a stubborn, practical streak.",
            "Local legend holds that the nearby woods are not quite empty at night.",
            "A single old bell, rung only in emergencies, still hangs at its center.",
            "Its market is small but famous for one particular craft done nowhere else as well.",
            "Every generation, someone claims to have found something strange buried nearby.",
            "The town keeps a long, half-serious rivalry with its nearest neighbor.",
            "Visitors often remark on how quiet the place feels, even at midday.",
            "A modest festival is held there once a year, for reasons few outsiders remember.",
            "Its oldest residents still tell stories of a hard winter that nearly emptied the place.",
            "The town has rebuilt from fire or flood at least once, and remembers it.",
    };

    private SettlementLoreStore() {
    }

    private static final PersistedServerStore<Integer, LoreEntry> STORE =
            new PersistedServerStore<>("settlemynts_settlement_lore.json", LoreEntry.CODEC, LoreEntry::poolIndex) {
                @Override
                protected Map<Integer, LoreEntry> generate() {
                    Map<Integer, LoreEntry> result = new LinkedHashMap<>();
                    for (int i = 0; i < POOL_SIZE; i++) {
                        String reason = FOUNDING_REASONS[i % FOUNDING_REASONS.length];
                        String trait = NOTABLE_TRAITS[(i / FOUNDING_REASONS.length) % NOTABLE_TRAITS.length];
                        String text = Character.toUpperCase(reason.charAt(0)) + reason.substring(1) + ". " + trait;
                        result.put(i, new LoreEntry(i, text, java.util.Optional.empty()));
                    }
                    return result;
                }
            };

    public static void loadOrGenerate(MinecraftServer server) {
        STORE.loadOrGenerate(server);
    }

    /** Returns {@code placeId}'s already-assigned lore entry, or permanently assigns and returns the first free one. */
    public static synchronized LoreEntry getOrAssign(long placeId) {
        for (LoreEntry entry : STORE.all().values()) {
            if (entry.assignedPlaceId().isPresent() && entry.assignedPlaceId().get() == placeId) {
                return entry;
            }
        }
        for (LoreEntry entry : STORE.all().values()) {
            if (entry.assignedPlaceId().isEmpty()) {
                LoreEntry assigned = entry.assignedTo(placeId);
                STORE.put(assigned.poolIndex(), assigned);
                return assigned;
            }
        }
        // Pool exhausted -- fall back to a bare generic entry rather than throwing; a real future
        // content pass (more pool entries, or a live-generated one-off) can close this gap properly.
        return new LoreEntry(-1, "A settlement like many others along this road.", java.util.Optional.of(placeId));
    }
}
