package com.github.cerealklla.settlemynts.founding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * The perimeter auto-fit algorithm (design doc Section 7a: "Radial Scale-Factor Bisection") --
 * pure 2D geometry, no live-world dependency, so unlike almost everything else in this mod it's
 * genuinely unit-testable. Takes the stakes' current positions and absolute/non-absolute flags and
 * resizes the shape (uniformly, around the settlement core) until its area is as close as
 * reasonably achievable to a target, without ever moving an absolute stake.
 *
 * <p>Deliberately synchronous, not literally running on a background thread -- the design doc
 * calls this an "asynchronous process," but at the scale involved (at most {@code
 * GhostPerimeterStakeEntity#MAX_STAKES_PER_SETTLEMENT} stakes, a fixed small iteration count) the
 * bisection search is fast enough that a real async job would add complexity without a measurable
 * benefit. "Asynchronous" in the original design read as a UX expectation (not instant/no visible
 * hitch), which this already satisfies.
 */
public final class PerimeterFit {

    /** Design doc Section 7: proposed v1 target, ~20,000 blocks^2 -- see design-document.md Section 7 for the full sizing rationale. First-draft, expected to be tuned via playtesting. */
    public static final double DEFAULT_TARGET_AREA_BLOCKS = 20_000.0;

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

        // Angular order around the core defines the polygon's vertex sequence -- radially scaling
        // any subset of vertices (what this algorithm does) never changes that order, so sorting
        // once upfront is valid regardless of the eventual scale factor (design doc Section 7a).
        // Sorted by index (not by value) so the result can be scattered back to the caller's
        // original order afterward without any fragile position-matching.
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingDouble(i -> Math.atan2(stakes.get(i).z() - coreZ, stakes.get(i).x() - coreX)));

        List<StakeInput> ordered = new ArrayList<>(n);
        for (int idx : order) {
            ordered.add(stakes.get(idx));
        }

        boolean anyNonAbsolute = ordered.stream().anyMatch(s -> !s.absolute());
        double k;
        if (!anyNonAbsolute) {
            // No adjustment possible -- accept whatever area the absolute stakes alone enclose,
            // however far from the target (design doc Section 7a's first edge case).
            k = 1.0;
        } else {
            double kMax = Double.MAX_VALUE;
            for (StakeInput s : ordered) {
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

            double areaAtMin = polygonArea(positionsAt(ordered, coreX, coreZ, MIN_SCALE));
            double areaAtMax = polygonArea(positionsAt(ordered, coreX, coreZ, kMax));

            if (targetArea <= areaAtMin) {
                k = MIN_SCALE;
            } else if (targetArea >= areaAtMax) {
                k = kMax;
            } else {
                k = bisect(ordered, coreX, coreZ, targetArea, MIN_SCALE, kMax);
            }
        }

        List<StakeInput> fittedOrdered = positionsAt(ordered, coreX, coreZ, k);

        // Scatter back to the caller's original order (order[j] is the original index of ordered.get(j)).
        StakeInput[] resultArray = new StakeInput[n];
        for (int j = 0; j < n; j++) {
            resultArray[order[j]] = fittedOrdered.get(j);
        }
        return new FitResult(Arrays.asList(resultArray), polygonArea(fittedOrdered));
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
     * Cartographyr entity exists. {@code vertices} should be in angular (polygon) order, e.g. the
     * ordering {@link #fit} itself uses internally -- callers checking "does the shape encapsulate
     * the core" should sort the same way first.
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

    /** Sorts {@code stakes} into angular order around {@code (centerX, centerZ)} -- the polygon-vertex order {@link #fit} and {@link #containsPoint} both expect. */
    public static List<StakeInput> sortAngularly(List<StakeInput> stakes, double centerX, double centerZ) {
        List<StakeInput> sorted = new ArrayList<>(stakes);
        sorted.sort(Comparator.comparingDouble(s -> Math.atan2(s.z() - centerZ, s.x() - centerX)));
        return sorted;
    }
}
