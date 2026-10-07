package com.github.cerealklla.settlemynts.construction;

import com.github.cerealklla.blueprynts.blueprint.GenericResource;

/** One required-resource row of a {@link ConstructionRequirements} table -- a generic category (not a specific item) and a total amount needed. */
public record ResourceCost(GenericResource resource, int amount) {
}
