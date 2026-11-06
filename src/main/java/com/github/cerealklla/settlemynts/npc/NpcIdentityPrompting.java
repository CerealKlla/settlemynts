package com.github.cerealklla.settlemynts.npc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import com.github.cerealklla.nuclyus.llm.OllamaClient;
import com.github.cerealklla.nuclyus.llm.WorldLore;
import com.github.cerealklla.settlemynts.SettlemyntsMod;

/**
 * Builds the one-shot "a brand-new NPC is born" prompt and parses the LLM's reply -- moved here from
 * Audyble's own {@code npc.NpcIdentityPrompting}, 2026-11-05 (see {@link NpcIdentityStore}'s own class
 * doc for the full ownership-move writeup). Now calls {@link OllamaClient}/{@link WorldLore} directly
 * (both moved to Nuclyus the same day, per explicit user direction that the LLM is a generic
 * capability, not TTS-specific) instead of Audyble's own now-deleted copies. Takes {@code usedNames}
 * as a precomputed list from the caller ({@link NpcIdentityBirth}) rather than resolving it itself --
 * see {@link NpcSettlementNames#inSettlement} for why that resolution needs a live {@code
 * ServerLevel}/settlement core this class has no reason to otherwise depend on.
 *
 * <p>A dedicated single-thread executor, same shape as Audyble's own conversation pipeline executor,
 * so a slow/unreachable Ollama can never stall the server tick thread a birth was triggered from.
 *
 * <p><b>The name itself no longer comes from the LLM</b> -- real report, 2026-11-05: generated names
 * kept clustering around a small handful of fantasy-sounding roots ("Kaelin Vex"/"Kael Vex"/"Kaelin
 * Thorne" all in one settlement, two of them an exact full-name match), because a small local model
 * asked to invent a name from scratch regresses to the same narrow cluster no matter how the prompt is
 * worded. {@link NameBank} picks the name directly in code instead -- real, guaranteed variety, trivial
 * exact-match avoidance -- and the LLM is only asked for backstory/facts to go with it. This also
 * retired the old "ask 3 times, then bolt on a mechanical epithet" fallback entirely: there's no more
 * LLM-side name collision to fall back from.
 *
 * <p><b>Gender rolled once per birth</b> ({@link NpcGender#roll()}), same day -- picks the matching
 * {@link NameBank} pool and tells the LLM which pronouns to use, so name/backstory/voice/visual all
 * agree for a given NPC instead of everything defaulting to male.
 */
public final class NpcIdentityPrompting {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "settlemynts-npc-birth"));

    private NpcIdentityPrompting() {
    }

    /** Called from wherever this mod shuts its executors down on server stop -- interrupts any in-flight HTTP call immediately rather than waiting for it to finish or time out on its own. */
    public static void shutdown() {
        EXECUTOR.shutdownNow();
    }

    /**
     * Asynchronously generates a real backstory/key facts for {@code placeholder} via the shared LLM
     * client (the name itself is picked from {@link NameBank}, not the LLM -- see this class's own
     * doc), persisting the result (overwriting the placeholder) once it returns. A no-op beyond
     * logging a warning if the LLM is unreachable/unconfigured or the response can't be parsed -- the
     * placeholder identity (set by the caller before this was ever invoked) stays in place either way,
     * so a bad/missing response never leaves the NPC without SOME identity.
     *
     * @param onGenerated optional (nullable), called with the real generated identity once persisted
     *                     -- only on success, never on a no-op/unparseable/unreachable-LLM path, and
     *                     always from this class's own single-thread executor, never the server
     *                     thread. Added 2026-11-05 for {@link WanderingIdentityListener}'s own
     *                     nameplate push: a {@code WanderingTrader} is a real vanilla entity class
     *                     that can't implement {@code StatBarOwner} (no mixin exists for this suite's
     *                     one deliberate exception doesn't cover it), so its name has to be pushed
     *                     onto the live entity's own real {@code CustomName} instead of being resolved
     *                     live the way {@code Nuclyus's StatBarWorldOverlay} does for the other 5
     *                     roles -- this callback is how the caller gets notified once there's a real
     *                     name to push. Every other caller passes {@code null}.
     */
    public static void generateAsync(NpcIdentity placeholder, LoreEntry lore, String settlementName, List<String> usedNamesIn, Consumer<NpcIdentity> onGenerated) {
        // Gender and name are picked up front, synchronously -- both are plain in-memory picks, no
        // reason to defer them onto the executor, and NpcIdentityBirth's placeholder display text can
        // use the real given name immediately instead of "Unnamed X" even before the backstory comes
        // back. Rolled once, here, and never again for this NPC's whole life.
        NpcGender gender = NpcGender.roll();
        String givenName = NameBank.pickGivenName(gender, usedNamesIn);
        String name = NameBank.rollSurname() ? givenName + " " + NameBank.pickSurname() : givenName;

        if (!OllamaClient.isConfigured()) {
            return; // Placeholder identity stays in place -- see this method's own doc.
        }
        EXECUTOR.submit(() -> {
            try {
                // Real settlement name (2026-11-04, Audyble) -- without this, nothing in the birth prompt
                // ever names the actual town, so the model would invent/hallucinate its own place name
                // instead of using the real one. Blank/null (settlement name unresolvable, or a wandering
                // NPC with no settlement at all) just omits the sentence rather than naming a fake one.
                String settlementLine = (settlementName != null && !settlementName.isBlank())
                        ? "This settlement is called " + settlementName + ". "
                        : "";
                String systemPrompt = WorldLore.TEXT + "\n\nThis settlement: " + settlementLine + lore.text();
                String roleName = placeholder.key().role().name().toLowerCase(Locale.ROOT);

                String pronoun = gender == NpcGender.FEMALE ? "She/her" : "He/him";
                String userMessage = "This character is named " + name + " (" + pronoun + "), a brand-new " + roleName
                        + " for this settlement. Write their personal backstory and a few facts about "
                        + "them, using " + pronoun.toLowerCase(Locale.ROOT) + " pronouns throughout. Respond in "
                        + "EXACTLY this format and nothing else -- no preamble, no extra commentary, and do not "
                        + "invent or restate a different name:\n"
                        + "BACKSTORY: <2 to 4 sentences of personal backstory>\n"
                        + "FACT: <one short fact about them>\n"
                        + "FACT: <one short fact about them>\n"
                        + "FACT: <one short fact about them>";

                String reply = OllamaClient.chat(systemPrompt, List.of(), userMessage);
                NpcIdentity generated = parse(placeholder, name, gender, reply);

                if (generated != null) {
                    NpcIdentityStore.put(generated);
                    if (onGenerated != null) {
                        onGenerated.accept(generated);
                    }
                } else {
                    SettlemyntsMod.LOGGER.warn("Settlemynts NPC birth generation returned an unparseable reply for {} -- leaving placeholder identity in place: {}",
                            placeholder.key(), reply);
                }
            } catch (Exception e) {
                SettlemyntsMod.LOGGER.warn("Settlemynts NPC birth generation failed for {} -- leaving placeholder identity in place", placeholder.key(), e);
            }
        });
    }

    private static NpcIdentity parse(NpcIdentity placeholder, String name, NpcGender gender, String reply) {
        String backstory = null;
        List<String> facts = new ArrayList<>();
        for (String line : reply.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.regionMatches(true, 0, "BACKSTORY:", 0, 10)) {
                backstory = trimmed.substring(10).trim();
            } else if (trimmed.regionMatches(true, 0, "FACT:", 0, 5)) {
                facts.add(trimmed.substring(5).trim());
            }
        }
        if (backstory == null || backstory.isBlank()) {
            return null;
        }
        return new NpcIdentity(placeholder.key(), name, backstory, List.copyOf(facts), true, Optional.empty(), gender);
    }
}
