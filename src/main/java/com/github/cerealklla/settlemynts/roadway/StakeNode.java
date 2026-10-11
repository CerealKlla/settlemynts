package com.github.cerealklla.settlemynts.roadway;

import java.util.List;
import java.util.UUID;

/**
 * The minimal shape {@link RoadwayGraph}'s shared BFS needs from a stake entity -- implemented by
 * both {@link RoadwayStakeEntity} (intra-settlement) and {@link HighwayStakeEntity}
 * (inter-settlement, added 2026-10-11), two deliberately unrelated classes (the latter is a copy,
 * not a subclass, of the former -- see that class's own doc), so the pathfinding algorithm itself
 * only needs to be written once.
 */
public interface StakeNode {
    UUID getUUID();

    List<UUID> getConnectionIds();
}
