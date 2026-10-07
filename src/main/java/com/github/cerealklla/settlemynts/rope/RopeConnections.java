package com.github.cerealklla.settlemynts.rope;

/**
 * The max leash-pull distance shared by every rope mechanic in this mod (real Rope Fence Posts,
 * ghost plot staking, the carry preview) -- kept as its own tiny class since it's referenced from
 * both the {@code rope} and {@code zone} packages and doesn't obviously belong to either.
 *
 * <p><b>No longer owns any tripwire-generation code</b> (removed 2026-09-30) -- every rope segment in
 * this mechanic, permanent and transient alike, now uses real vanilla {@link
 * net.minecraft.world.entity.Leashable} rendering ({@code RopeAnchorEntity}, {@code
 * zone.GhostPlotStakeEntity}, {@code zone.GhostPlotFencePostEntity}). The transient "carrying"
 * preview was the last holdout, on the reasoning that it was "cheap/ephemeral and was never the part
 * the user objected to" -- a later playtest found it did in fact look bad ("every rope needs to look
 * like a rope. That tripwire looks terrible"), so it was rebuilt on the same real rendering too.
 */
public final class RopeConnections {

    // Raised 5.0 -> 15.0 on 2026-09-30, then 15.0 -> 20.0 on 2026-10-01, both explicit user requests.
    // Vanilla's own Leashable#leashSnapDistance() defaults to 12.0 and isn't otherwise affected by
    // this constant -- every Leashable entity this mod owns (GhostPlotStakeEntity,
    // GhostPlotFencePostEntity, RopeAnchorEntity) now overrides it to a value comfortably above this
    // one, so vanilla's own auto-break can never fire before this mod's own pull-back mechanic
    // (SettlemyntsMod's tick handler) gets a chance to act.
    public static final double MAX_ROPE_LENGTH_BLOCKS = 20.0;

    // Generous margin above MAX_ROPE_LENGTH_BLOCKS -- see the field's own doc above.
    public static final double LEASH_SNAP_DISTANCE_OVERRIDE = MAX_ROPE_LENGTH_BLOCKS + 5.0;

    private RopeConnections() {
    }
}
