package com.github.cerealklla.settlemynts.npc;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One NPC character's persistent identity -- moved here from Audyble's own {@code npc.NpcIdentity},
 * 2026-11-05 (see {@link NpcIdentityStore}'s own class doc for the full ownership-move writeup).
 * {@code keyFacts} is a short list, one plain clause each (e.g. "Lost a brother to bandits five years
 * ago"), meant to be injected into an LLM prompt verbatim. {@code alive}/{@code deathContext} together
 * are the "deceased characters registry" concept: a dead identity is never deleted, just flipped to
 * {@code alive = false} with a short death context other NPCs can reference -- see {@link
 * NpcIdentityStore#deceased()}.
 *
 * <p><b>No longer carries a {@code settlementPlaceId} field</b> (real fix, 2026-11-05) -- the old
 * Audyble-owned version cached a copy of "which settlement this identity belongs to" directly on the
 * record, which (a) was never actually populated by the real live conversation call site in the first
 * place (a genuine bug: the NPC-naming-uniqueness feature never saw real settlement data because of
 * this), and (b) duplicated data Settlemynts already authoritatively owns and can resolve live. {@link
 * NpcSettlementNames#inSettlement} now walks a settlement's own real plot list (and known Guard Kit
 * Stands) directly instead of trusting a cached field that could silently go stale or stay unset.
 */
public record NpcIdentity(NpcIdentityKey key, String name, String backstory, List<String> keyFacts,
                           boolean alive, Optional<String> deathContext) {

    public static final Codec<NpcIdentity> CODEC = RecordCodecBuilder.create(i -> i.group(
            NpcIdentityKey.CODEC.fieldOf("key").forGetter(NpcIdentity::key),
            Codec.STRING.fieldOf("name").forGetter(NpcIdentity::name),
            Codec.STRING.fieldOf("backstory").forGetter(NpcIdentity::backstory),
            Codec.STRING.listOf().fieldOf("key_facts").forGetter(NpcIdentity::keyFacts),
            Codec.BOOL.fieldOf("alive").forGetter(NpcIdentity::alive),
            Codec.STRING.optionalFieldOf("death_context").forGetter(NpcIdentity::deathContext)
    ).apply(i, NpcIdentity::new));

    public NpcIdentity withDeceased(String deathContext) {
        return new NpcIdentity(key, name, backstory, keyFacts, false, Optional.of(deathContext));
    }

    /** Joins {@link #keyFacts} into a simple bullet list for direct concatenation into an LLM prompt. */
    public String keyFactsAsPromptText() {
        return keyFacts.isEmpty() ? "" : "- " + String.join("\n- ", keyFacts);
    }
}
