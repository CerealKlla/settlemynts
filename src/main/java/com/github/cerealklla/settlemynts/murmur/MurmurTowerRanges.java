package com.github.cerealklla.settlemynts.murmur;

import com.github.cerealklla.settlemynts.founding.SettlementFounding;

/**
 * Pure function, finalized 2026-11-02 (see design-document.md's "Murmur Tower Plot Type" section
 * for the full real-world derivation). Taking the user's own "80% of the radius preventing a new
 * settlement from being too close" literally against {@link SettlementFounding#MIN_DISTANCE_BLOCKS}
 * (the full center-to-center separation between two settlements, not a per-settlement radius) would
 * let two settlements at exactly that legal minimum distance apart still overlap signals -- the 80%
 * factor has to apply to *half* of that distance (the radius each settlement actually "owns" in the
 * worst case) to truly guarantee no overlap. The formula, not just its current numbers, is the real
 * guarantee -- it holds regardless of how {@code MIN_DISTANCE_BLOCKS} itself is ever retuned later.
 */
public final class MurmurTowerRanges {

    private MurmurTowerRanges() {
    }

    /** Placeholder/tunable, like every other numeric curve in this suite -- T1~=49, T2~=98, T3~=146, T4~=195, T5~=244 at current MIN_DISTANCE_BLOCKS. */
    public static int maxRangeBlocks(int tier) {
        double tier5Cap = SettlementFounding.MIN_DISTANCE_BLOCKS / 2.0 * 0.8;
        return (int) Math.round(tier5Cap * tier / 5.0);
    }
}
