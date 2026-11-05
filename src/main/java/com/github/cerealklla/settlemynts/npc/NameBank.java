package com.github.cerealklla.settlemynts.npc;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A fixed, hand-picked pool of plain, phonetically distinct given names (plus a small surname pool
 * for the rare noble/official case) that NPC birth now draws from directly in code -- added
 * 2026-11-05 after a real report that generated names kept clustering ("Kaelin Vex"/"Kael Vex"/
 * "Kaelin Thorne" all in one settlement, two of them an exact full-name match). The underlying cause
 * was never really a missing uniqueness check -- it was asking a small local LLM to invent a creative
 * name from scratch on every call, which reliably regresses to the same narrow cluster of "fantasy
 * name" tokens no amount of prompt wording fully steered it away from. Picking from a fixed list
 * sidesteps that entirely: real, guaranteed variety, and trivial exact-match avoidance, since the
 * list is deliberately larger than any one settlement's population will realistically reach. The LLM
 * is still used for backstory/facts -- it's genuinely good at that, just not at staying varied across
 * many independent name-only calls.
 *
 * <p><b>Male names only, for now</b> -- real follow-up report, same day: every NPC entity this mod
 * spawns renders as a male-presenting humanoid (worker/resident NPCs are vanilla {@code Villager}s
 * using its own base model; Guard/Tavern NPCs use a reskinned-{@code Zombie} placeholder renderer --
 * see each one's own class doc), and Audyble's NPC conversation voice is one single config value used
 * for every NPC (now also switched to a male Kokoro voice, same day). There's no actual per-NPC gender
 * concept or visual variety anywhere in this mod to assign a name against -- until there is, every
 * name needs to agree with the one presentation that actually exists, rather than introduce a mismatch
 * the other direction. Revisit this list (and give it a real male/female split) if/when NPC models
 * ever gain real visual gender variety.
 */
public final class NameBank {

    public static final List<String> GIVEN_NAMES = List.of(
            "Bob", "Joe", "Hank", "Gus", "Otto", "Oskar", "Tomas", "Mateo", "Pavel", "Dmitri",
            "Felix", "Ezra", "Casper", "Fenn", "Nils", "Ravi", "Idris", "Tariq", "Kwame", "Lior",
            "Fitz", "Mungo", "Hugo", "Ambrose", "Fergus", "Cosmo", "Baz", "Emrys", "Reo", "Dash",
            "Clem", "Silas", "Garrick", "Jonas", "Elias", "Teodor", "Amir", "Niko", "Hendrik", "Rafiq"
    );

    /** Surnames -- only ever used for the rare noble/official/notable-family character, see {@link #rollSurname}. */
    public static final List<String> SURNAMES = List.of(
            "Ashworth", "Blackwood", "Stonefield", "Harrow", "Pembridge", "Vance", "Quill",
            "Marchetti", "Castellan", "Hollis", "Thorncastle", "Esparza", "Winterbourne", "Calloway"
    );

    private NameBank() {
    }

    /** A given name not already in {@code excluding} (case-insensitive) where possible -- falls back to a plain random pick if the whole pool is somehow already taken. */
    public static String pickGivenName(List<String> excluding) {
        List<String> lowerExcluded = excluding.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        List<String> available = GIVEN_NAMES.stream()
                .filter(n -> !lowerExcluded.contains(n.toLowerCase(Locale.ROOT)))
                .toList();
        List<String> pool = available.isEmpty() ? GIVEN_NAMES : available;
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    public static String pickSurname() {
        return SURNAMES.get(ThreadLocalRandom.current().nextInt(SURNAMES.size()));
    }

    /** ~1 in 10 people are notable enough (noble, official, prominent family) to carry a surname at all. */
    public static boolean rollSurname() {
        return ThreadLocalRandom.current().nextInt(10) == 0;
    }
}
