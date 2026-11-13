package com.github.cerealklla.settlemynts.zone;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parsing/formatting for a settlement's required Blueprint tags (design doc's "Town Hall Core
 * required-tags filter," backlog item 5) -- a Mayor-set list of tags (see Blueprynts' own {@code
 * blueprint.BlueprintTags}, the tag vocabulary this is meant to match against) that a plot's
 * Blueprint choices must all carry. Deliberately not shared code with Blueprynts' identical
 * {@code BlueprintTags} -- this mod has no hard dependency on Blueprynts, and the parsing logic is
 * small enough that duplicating it is cheaper than introducing one just for this.
 */
public final class RequiredTags {

    private RequiredTags() {
    }

    /** Comma-separated raw Mayor input -> a trimmed, lowercased, de-duplicated, order-preserving tag set. Blank entries are dropped. */
    public static Set<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (String piece : raw.split(",")) {
            String tag = piece.trim().toLowerCase(Locale.ROOT);
            if (!tag.isEmpty()) {
                tags.add(tag);
            }
        }
        return tags;
    }

    /** The inverse of {@link #parse} for re-populating an edit field. */
    public static String format(Set<String> tags) {
        return String.join(", ", List.copyOf(tags));
    }
}
