package com.github.cerealklla.settlemynts.npc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.github.cerealklla.nuclyus.server.PersistedServerStore;

import net.minecraft.server.MinecraftServer;

/**
 * Persisted store of every {@link NpcIdentity} ever born.
 *
 * <p><b>Moved here from Audyble's own {@code npc.NpcIdentityStore}, 2026-11-05</b> -- real report:
 * "This is the second time now that my gold tool has changed to copper" led into an unrelated bug
 * investigation that surfaced a much bigger one: NPCs still didn't show unique names anywhere, traced
 * to Audyble's live conversation code path ({@code npc.NpcConversationEngine}) passing {@code
 * settlementPlaceId=null} on every single call, so the name-collision check built 2026-11-04 never
 * actually saw real settlement data. The user then corrected the whole ownership model: "Audyble is
 * for conveying sound/text from player to npc and back. Settlemynt spawns the NPCs, moves the NPCs,
 * and should therefore be managing their history" -- and separately, that the LLM client itself
 * ("anything should be able to use the LLM if needed") belongs in Nuclyus, not Audyble (see {@code
 * nuclyus.llm.OllamaClient}'s own doc). This store, {@link NpcIdentity}, {@link NpcIdentityKey},
 * {@link NpcRole}, {@link NpcIdentityPrompting}, {@link NpcIdentityBirth}, {@code PlayerRelationship}/
 * {@code RelationshipKey}/{@code PlayerRelationshipStore}, and {@code SettlementLoreStore}/{@code
 * LoreEntry} all moved from Audyble to here as a result -- Audyble keeps only {@code
 * PlayerPronunciationStore} (a player-keyed TTS setting, not NPC history) and becomes a stateless
 * conversation pipeline that reads/writes this mod's own data through {@code api.Settlemynts}, the
 * same way any other optional Settlemynts-integration mod does.
 *
 * <p>Thin static facade over {@link PersistedServerStore}, same shape as {@code Lyfe/socket/
 * ServerRunewordStore}. {@link #generate()} returns nothing -- identities are born lazily (see {@link
 * NpcIdentityBirth}), never pre-seeded, so a fresh server starts with an empty file.
 */
public final class NpcIdentityStore {

    private static final PersistedServerStore<NpcIdentityKey, NpcIdentity> STORE =
            new PersistedServerStore<>("settlemynts_npc_identities.json", NpcIdentity.CODEC, NpcIdentity::key) {
                @Override
                protected Map<NpcIdentityKey, NpcIdentity> generate() {
                    return Map.of();
                }
            };

    private NpcIdentityStore() {
    }

    public static void loadOrGenerate(MinecraftServer server) {
        STORE.loadOrGenerate(server);
    }

    public static Optional<NpcIdentity> find(NpcIdentityKey key) {
        return STORE.get(key);
    }

    public static NpcIdentity put(NpcIdentity identity) {
        STORE.put(identity.key(), identity);
        return identity;
    }

    /** Every identity whose body has permanently died -- the "deceased characters registry," a plain filter over this one store rather than a separate store. */
    public static List<NpcIdentity> deceased() {
        return STORE.all().values().stream().filter(id -> !id.alive()).toList();
    }
}
