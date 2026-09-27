package com.github.cerealklla.settlemynts.founding;

import java.util.ArrayList;
import java.util.List;

/**
 * The perimeter auto-fit algorithm (design doc Section 7a: "Radial Scale-Factor Bisection") --
 * pure 2D geometry, no live-world dependency, so unlike almost everything else in this mod it's
 * genuinely unit-testable. Takes the stakes' current positions and absolute/non-absolute flags and
 * resizes the shape (uniformly, around the settlement core) until its area is as close as
 * reasonably achievable to a target, without ever moving an absolute stake.
 *
 * <p>{@code stakes} must already be in **placement order** (the actual sequence a Town Planner
 * placed them in), not angularly sorted -- angular sorting was removed 2026-09-27 (see decisions.md
 * same date) because it only produces a valid polygon when the shape is star-shaped from the core,
 * which excludes C-shapes, rings, and anything else the user explicitly wants supported.
 *
 * <p>Deliberately synchronous, not literally running on a background thread -- the design doc
 * calls this an "asynchronous process," but at the scale involved (at most {@code
 * GhostPerimeterStakeEntity#MAX_STAKES_PER_SETTLEMENT} stakes, a fixed small iteration count) the
 * bisection search is fast enough that a real async job would add complexity without a measurable
 * benefit. "Asynchronous" in the original design read as a UX expectation (not instant/no visible
 * hitch), which this already satisfies.
 */
public final class PerimeterFit {

    /**
     * Design doc Section 7's original proposed v1 target was ~20,000 blocks^2; doubled to 40,000
     * (2026-09-26, user request after a live test -- felt too small once actually walked/seen in
     * game) -- still first-draft, still expected to be tuned further via playtesting.
     */
    public static final double DEFAULT_TARGET_AREA_BLOCKS = 40_000.0;

    /**
     * The bound to actually pass as {@code maxRadiusFromCore} when fitting toward {@link
     * #DEFAULT_TARGET_AREA_BLOCKS} -- deliberately **not** {@code
     * GhostPerimeterStakeEntity#MAX_PLACEMENT_RADIUS_BLOCKS} (~152 blocks). Placement radius
     * constrains where a Planner may physically walk to place a stake; it has nothing to do with
     * how far the fit is allowed to grow that stake afterward, and reusing it here was a real bug
     * (2026-09-26 playtest): a stake placed anywhere near the placement radius left almost no room
     * to grow, so the fit silently capped out well short of the target -- observed as a 14,327
     * vs. 20,000 blocks^2 result, not the "same area every time, tiny variance" the user actually
     * wants. This bound is chosen generously larger than any legitimate stake's starting distance
     * (which can never exceed the placement radius in the first place), so it essentially never
     * binds in practice -- the search converges on the real target instead of an artificial cap.
     */
    public static final double DEFAULT_MAX_FIT_RADIUS_BLOCKS = 2000.0;

    // Bounds worst-case cost, same "don't loop forever" precedent used elsewhere in this suite
    // (e.g. Cartographyr's MAX_CELLS/MAX_NAME_ATTEMPTS) -- far more than needed for float
    // precision at this scale.
    private static final int MAX_BISECTION_ITERATIONS = 60;
    private static final double MIN_SCALE = 0.01;

    private PerimeterFit() {
    }

    /** One stake's absolute world position and whether it's pinned. */
    public record StakeInput(double x, double z, boolean absolute) {
    }

    /** {@code fittedStakes} is in the same order as the input list passed to {@link #fit}. {@code achievedArea} is the actual resulting polygon area (may not exactly hit the target -- see class doc). */
    public record FitResult(List<StakeInput> fittedStakes, double achievedArea) {
    }

    /**
     * Runs the fit. {@code stakes} needs at least 3 entries to form a real polygon; fewer than
     * that returns the input unchanged with area 0 (nothing meaningful to fit).
     *
     * @param coreX the settlement core's X position -- stakes scale toward/away from this point.
     * @param coreZ the settlement core's Z position.
     * @param targetArea the area (in blocks^2) to try to reach.
     * @param maxRadiusFromCore the furthest any non-absolute stake may end up from the core (e.g. the placement radius) -- bounds the search so a stake can never be scaled out past where it could ever have legitimately been placed.
     */
    public static FitResult fit(double coreX, double coreZ, List<StakeInput> stakes, double targetArea, double maxRadiusFromCore) {
        int n = stakes.size();
        if (n < 3) {
            return new FitResult(List.copyOf(stakes), 0.0);
        }

        // stakes is already in placement order (see class doc) -- radially scaling any subset of
        // vertices (what this algorithm does) never changes that order, so no sort/scatter-back step
        // is needed here at all (removed 2026-09-27 along with the angular-sort approach it went with).
        boolean anyNonAbsolute = stakes.stream().anyMatch(s -> !s.absolute());
        double k;
        if (!anyNonAbsolute) {
            // No adjustment possible -- accept whatever area the absolute stakes alone enclose,
            // however far from the target (design doc Section 7a's first edge case).
            k = 1.0;
        } else {
            double kMax = Double.MAX_VALUE;
            for (StakeInput s : stakes) {
                if (s.absolute()) {
                    continue;
                }
                double distance = Math.hypot(s.x() - coreX, s.z() - coreZ);
                if (distance > 1.0e-9) {
                    kMax = Math.min(kMax, maxRadiusFromCore / distance);
                }
            }
            if (kMax == Double.MAX_VALUE) {
                kMax = 1.0; // Every non-absolute stake sits exactly on the core -- degenerate, nothing sensible to scale.
            }

            double areaAtMin = polygonArea(positionsAt(stakes, coreX, coreZ, MIN_SCALE));
            double areaAtMax = polygonArea(positionsAt(stakes, coreX, coreZ, kMax));

            if (targetArea <= areaAtMin) {
                k = MIN_SCALE;
            } else if (targetArea >= areaAtMax) {
                k = kMax;
            } else {
                k = bisect(stakes, coreX, coreZ, targetArea, MIN_SCALE, kMax);
            }
        }

        List<StakeInput> fitted = snapToWholeBlocks(positionsAt(stakes, coreX, coreZ, k));
        return new FitResult(fitted, polygonArea(fitted));
    }

    private static double bisect(List<StakeInput> ordered, double coreX, double coreZ, double targetArea, double lo, double hi) {
        for (int i = 0; i < MAX_BISECTION_ITERATIONS; i++) {
            double mid = (lo + hi) / 2.0;
            double area = polygonArea(positionsAt(ordered, coreX, coreZ, mid));
            if (area < targetArea) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2.0;
    }

    /** {@code ordered} with every non-absolute stake scaled by {@code k} relative to the core; absolute stakes unchanged. */
    private static List<StakeInput> positionsAt(List<StakeInput> ordered, double coreX, double coreZ, double k) {
        List<StakeInput> result = new ArrayList<>(ordered.size());
        for (StakeInput s : ordered) {
            if (s.absolute()) {
                result.add(s);
            } else {
                result.add(new StakeInput(coreX + k * (s.x() - coreX), coreZ + k * (s.z() - coreZ), false));
            }
        }
        return result;
    }

    /**
     * Snaps every *non-absolute* stake to a whole block ({@code floor(x) + 0.5}, the same centering
     * convention every other stake/wall entity in this mod uses) -- fixed 2026-09-27, playtest
     * feedback: the bisection scale factor {@code k} is an arbitrary real number, so a non-absolute
     * stake's fitted position ({@code coreX + k * (x - coreX)}) almost never lands on a whole block
     * on its own, which was silently propagating fractional coordinates into the settlement's
     * registered polygon, its wall layout, and the actual placed {@code GhostPerimeterStakeEntity}
     * positions -- "as close to 40,000 blocks^2 as reasonably possible" was never meant to sacrifice
     * whole-block placement to hit the target more precisely. Absolute stakes are pinned exactly
     * where a Planner placed them and must never move at all, snap included. {@code achievedArea} is
     * (and must be) recomputed from these snapped positions, not the pre-snap ones, so the reported
     * area matches what's actually registered. A side effect worth knowing: since a stake can land
     * anywhere within its own block before snapping, the snap can shift a non-absolute stake's
     * distance from the core by up to {@code sqrt(0.5)} (~0.71) blocks either way -- {@code
     * maxRadiusFromCore} bounds the pre-snap search, not a hard post-snap guarantee.
     */
    private static List<StakeInput> snapToWholeBlocks(List<StakeInput> stakes) {
        List<StakeInput> snapped = new ArrayList<>(stakes.size());
        for (StakeInput s : stakes) {
            if (s.absolute()) {
                snapped.add(s);
            } else {
                snapped.add(new StakeInput(Math.floor(s.x()) + 0.5, Math.floor(s.z()) + 0.5, false));
            }
        }
        return snapped;
    }

    /** Shoelace formula. */
    private static double polygonArea(List<StakeInput> vertices) {
        double sum = 0.0;
        int n = vertices.size();
        for (int i = 0; i < n; i++) {
            StakeInput a = vertices.get(i);
            StakeInput b = vertices.get((i + 1) % n);
            sum += a.x() * b.z() - b.x() * a.z();
        }
        return Math.abs(sum) / 2.0;
    }

    /**
     * Standard even-odd ray-casting point-in-polygon test, same algorithm Cartographyr's own
     * {@code Geometry.Polygon#contains} uses -- but over a plain stake list rather than that
     * class's own vertex type, since this operates purely on {@link StakeInput}s before any
     * Cartographyr entity exists. {@code orderedVertices} should be in **placement order** (see
     * class doc), not angularly sorted -- callers checking "does the shape encapsulate the core"
     * should sort by placement index the same way {@link #fit} expects.
     */
    public static boolean containsPoint(List<StakeInput> orderedVertices, double x, double z) {
        boolean inside = false;
        int n = orderedVertices.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            StakeInput vi = orderedVertices.get(i);
            StakeInput vj = orderedVertices.get(j);
            boolean edgeCrossesRay = (vi.z() > z) != (vj.z() > z)
                    && x < (vj.x() - vi.x()) * (z - vi.z()) / (vj.z() - vi.z()) + vi.x();
            if (edgeCrossesRay) {
                inside = !inside;
            }
        }
        return inside;
    }
}
