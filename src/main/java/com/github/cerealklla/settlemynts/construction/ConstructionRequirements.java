package com.github.cerealklla.settlemynts.construction;

import java.util.List;

/**
 * A Zone Type + Tier's funding requirements (design-document.md Section 14a, captured 2026-09-29) --
 * the resource cost list a Construction Box's Supplied/Needed screen reads, plus the minimum/maximum
 * construction time. {@code minTimeTicks} caps real construction progress (elapsed time since
 * binding, decoupled from funding -- Blueprynts' own {@code ConstructionBoxBlockEntity#attemptCompletion},
 * confirmed live 2026-09-30). {@code maxTimeTicks} is NPC auto-funding's own target pace -- consumed
 * by {@link NpcAutoFundingTicker}, built 2026-09-30.
 */
public record ConstructionRequirements(List<ResourceCost> costs, int minTimeTicks, int maxTimeTicks) {

    public static final ConstructionRequirements NONE = new ConstructionRequirements(List.of(), 0, 0);
}
