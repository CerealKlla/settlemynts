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
 */
public final class NpcIdentityPrompting {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "settlemynts-npc-birth"));

    /** Total LLM attempts (including the first) before falling back to a mechanical disambiguator. */
    private static final int MAX_NAME_ATTEMPTS = 3;

    /** Stable, deterministic suffixes for the rare case a settlement's name pool collides 3 times in a row. */
    private static final String[] DISAMBIGUATING_EPITHETS = {
            "the Younger", "the Elder", "of the North Road", "of the West Gate", "the Second"
    };

    private NpcIdentityPrompting() {
    }

    /** Called from wherever this mod shuts its executors down on server stop -- interrupts any in-flight HTTP call immediately rather than waiting for it to finish or time out on its own. */
    public static void shutdown() {
        EXECUTOR.shutdownNow();
    }

    /**
     * Asynchronously generates a real name/backstory/key facts for {@code placeholder} via the shared
     * LLM client, persisting the result (overwriting the placeholder) once it returns. A no-op beyond
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

                // No-repeat-names-within-a-settlement -- births are already strictly sequential (this
                // whole class runs on one single-thread EXECUTOR), so by the time this task runs, every
                // earlier birth in this same settlement has already been persisted and would already be
                // reflected the next time a caller computes usedNamesIn fresh.
                List<String> usedNames = new ArrayList<>(usedNamesIn);
                NpcIdentity generated = null;
                String lastReply = null;
                for (int attempt = 1; attempt <= MAX_NAME_ATTEMPTS; attempt++) {
                    String userMessage = "Generate a brand-new " + roleName + " character for this settlement. "
                            + "Respond in EXACTLY this format and nothing else -- no preamble, no extra commentary:\n"
                            + "NAME: <a short first and last name fitting this world>\n"
                            + "BACKSTORY: <2 to 4 sentences of personal backstory>\n"
                            + "FACT: <one short fact about them>\n"
                            + "FACT: <one short fact about them>\n"
                            + "FACT: <one short fact about them>"
                            + (usedNames.isEmpty() ? "" : "\nNames already in use in this settlement -- do NOT reuse any of these: "
                                    + String.join(", ", usedNames) + ".");

                    lastReply = OllamaClient.chat(systemPrompt, List.of(), userMessage);
                    NpcIdentity candidate = parse(placeholder, lastReply);
                    if (candidate == null) {
                        break; // unparseable -- retrying won't help, fall through to the placeholder-stays-in-place branch below.
                    }
                    boolean collides = usedNames.stream().anyMatch(n -> n.equalsIgnoreCase(candidate.name()));
                    if (!collides) {
                        generated = candidate;
                        break;
                    }
                    usedNames.add(candidate.name()); // also exclude this rejected name on the next attempt.
                }

                if (generated == null && lastReply != null) {
                    // Every attempt either collided or (rare) kept re-parsing fine but kept colliding --
                    // apply a deterministic mechanical fallback rather than looping forever or leaving
                    // two NPCs in the same settlement with the literal same name.
                    NpcIdentity lastCandidate = parse(placeholder, lastReply);
                    if (lastCandidate != null) {
                        String epithet = DISAMBIGUATING_EPITHETS[Math.abs(placeholder.key().hashCode()) % DISAMBIGUATING_EPITHETS.length];
                        generated = new NpcIdentity(lastCandidate.key(), lastCandidate.name() + " " + epithet,
                                lastCandidate.backstory(), lastCandidate.keyFacts(), true, Optional.empty());
                        SettlemyntsMod.LOGGER.warn("Settlemynts NPC birth name collided {} times in settlement for {} -- disambiguated to '{}'",
                                MAX_NAME_ATTEMPTS, placeholder.key(), generated.name());
                    }
                }

                if (generated != null) {
                    NpcIdentityStore.put(generated);
                    if (onGenerated != null) {
                        onGenerated.accept(generated);
                    }
                } else {
                    SettlemyntsMod.LOGGER.warn("Settlemynts NPC birth generation returned an unparseable reply for {} -- leaving placeholder identity in place: {}",
                            placeholder.key(), lastReply);
                }
            } catch (Exception e) {
                SettlemyntsMod.LOGGER.warn("Settlemynts NPC birth generation failed for {} -- leaving placeholder identity in place", placeholder.key(), e);
            }
        });
    }

    private static NpcIdentity parse(NpcIdentity placeholder, String reply) {
        String name = null;
        String backstory = null;
        List<String> facts = new ArrayList<>();
        for (String line : reply.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.regionMatches(true, 0, "NAME:", 0, 5)) {
                name = trimmed.substring(5).trim();
            } else if (trimmed.regionMatches(true, 0, "BACKSTORY:", 0, 10)) {
                backstory = trimmed.substring(10).trim();
            } else if (trimmed.regionMatches(true, 0, "FACT:", 0, 5)) {
                facts.add(trimmed.substring(5).trim());
            }
        }
        if (name == null || name.isBlank() || backstory == null || backstory.isBlank()) {
            return null;
        }
        return new NpcIdentity(placeholder.key(), name, backstory, List.copyOf(facts), true, Optional.empty());
    }
}
