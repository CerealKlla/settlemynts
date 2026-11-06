package com.github.cerealklla.settlemynts.npc;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A fixed, hand-picked pool of plain, phonetically distinct given names (plus a small, ungendered
 * surname pool for the rare noble/official case) that NPC birth draws from directly in code -- added
 * 2026-11-05 after a real report that generated names kept clustering ("Kaelin Vex"/"Kael Vex"/
 * "Kaelin Thorne" all in one settlement, two of them an exact full-name match). The underlying cause
 * was never really a missing uniqueness check -- it was asking a small local LLM to invent a creative
 * name from scratch on every call, which reliably regresses to the same narrow cluster of "fantasy
 * name" tokens no amount of prompt wording fully steered it away from. Picking from a fixed list
 * sidesteps that entirely: real, guaranteed variety, and trivial exact-match avoidance, since each
 * pool is deliberately larger than any one settlement's population will realistically reach. The LLM
 * is still used for backstory/facts -- it's genuinely good at that, just not at staying varied across
 * many independent name-only calls.
 *
 * <p><b>Split into {@link #MALE_GIVEN_NAMES}/{@link #FEMALE_GIVEN_NAMES}, 2026-11-05</b> -- originally
 * one male-only list, added the same day every NPC entity in this mod rendered as a male-presenting
 * humanoid with no gender concept at all. Now paired with a real {@link NpcGender} on {@link
 * NpcIdentity} (rolled once at birth, {@link NpcGender#roll()}) and matching per-gender visuals
 * (Guard/Tavern's renderers) and Audyble voice config, so name/voice/visual always agree for a given
 * NPC regardless of which gender it rolled.
 */
public final class NameBank {

    public static final List<String> MALE_GIVEN_NAMES = List.of(
            "Bob", "Joe", "Hank", "Gus", "Otto", "Oskar", "Tomas", "Mateo", "Pavel", "Dmitri",
            "Felix", "Ezra", "Casper", "Fenn", "Nils", "Ravi", "Idris", "Tariq", "Kwame", "Lior",
            "Fitz", "Mungo", "Hugo", "Ambrose", "Fergus", "Cosmo", "Baz", "Emrys", "Reo", "Dash",
            "Clem", "Silas", "Garrick", "Jonas", "Elias", "Teodor", "Amir", "Niko", "Hendrik", "Rafiq"
    );

    public static final List<String> FEMALE_GIVEN_NAMES = List.of(
            "Mira", "Priya", "Yuki", "Delia", "Anya", "Lotte", "Soraya", "Imogen", "Noor", "Sable",
            "Maren", "Tessa", "Junie", "Nadia", "Petra", "Saoirse", "Pippa", "Vesna", "Ines", "Lucia",
            "Mabel", "Orla", "Ingrid", "Marisol", "Yara", "Dot", "Birdie", "Elowen", "Rosalind", "Thea",
            "Freya", "Nora", "Esme", "Marguerite", "Agnes", "Ottilie", "Perpetua", "Wren", "Sunniva", "Zinnia"
    );

    /** Surnames -- ungendered, only ever used for the rare noble/official/notable-family character, see {@link #rollSurname}. */
    public static final List<String> SURNAMES = List.of(
            "Ashworth", "Blackwood", "Stonefield", "Harrow", "Pembridge", "Vance", "Quill",
            "Marchetti", "Castellan", "Hollis", "Thorncastle", "Esparza", "Winterbourne", "Calloway"
    );

    private NameBank() {
    }

    /** A given name from {@code gender}'s own pool not already in {@code excluding} (case-insensitive) where possible -- falls back to a plain random pick from that same pool if it's somehow entirely taken. */
    public static String pickGivenName(NpcGender gender, List<String> excluding) {
        List<String> pool = gender == NpcGender.FEMALE ? FEMALE_GIVEN_NAMES : MALE_GIVEN_NAMES;
        List<String> lowerExcluded = excluding.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        List<String> available = pool.stream()
                .filter(n -> !lowerExcluded.contains(n.toLowerCase(Locale.ROOT)))
                .toList();
        List<String> finalPool = available.isEmpty() ? pool : available;
        return finalPool.get(ThreadLocalRandom.current().nextInt(finalPool.size()));
    }

    /** True if {@code givenName} belongs to either gendered pool -- used to tell a {@link NameBank} name apart from a leftover pre-2026-11-05 LLM-generated one, regardless of which gender it'd be. */
    public static boolean isKnownGivenName(String givenName) {
        return MALE_GIVEN_NAMES.stream().anyMatch(n -> n.equalsIgnoreCase(givenName))
                || FEMALE_GIVEN_NAMES.stream().anyMatch(n -> n.equalsIgnoreCase(givenName));
    }

    public static String pickSurname() {
        return SURNAMES.get(ThreadLocalRandom.current().nextInt(SURNAMES.size()));
    }

    /** ~1 in 10 people are notable enough (noble, official, prominent family) to carry a surname at all. */
    public static boolean rollSurname() {
        return ThreadLocalRandom.current().nextInt(10) == 0;
    }
}
