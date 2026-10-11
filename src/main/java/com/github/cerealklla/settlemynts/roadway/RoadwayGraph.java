package com.github.cerealklla.settlemynts.roadway;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shared breadth-first search over a {@link StakeNode} graph -- extracted 2026-10-11 from {@code
 * guardhouse.GuardPatrolAreaGoal}'s own private {@code findStakePath}, generalized so the new
 * inter-settlement Highway Stake feature can reuse the exact same pathfinding rather than carrying
 * a second copy of it. Unweighted (the graph is small and connections are already roughly uniform
 * in length, same assumption the original guard-patrol use already made).
 */
public final class RoadwayGraph {

    private RoadwayGraph() {
    }

    /** The ordered hop path from {@code start} to {@code goal} (inclusive of both), or empty if they're not connected. */
    public static <T extends StakeNode> List<T> shortestPath(List<T> nodes, T start, T goal) {
        if (start.getUUID().equals(goal.getUUID())) {
            return List.of(start);
        }
        Map<UUID, T> byId = new HashMap<>();
        for (T node : nodes) {
            byId.put(node.getUUID(), node);
        }
        Map<UUID, UUID> prev = new HashMap<>();
        Set<UUID> visited = new HashSet<>();
        Deque<UUID> queue = new ArrayDeque<>();
        visited.add(start.getUUID());
        queue.add(start.getUUID());
        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            if (current.equals(goal.getUUID())) {
                break;
            }
            T currentNode = byId.get(current);
            if (currentNode == null) {
                continue;
            }
            for (UUID next : currentNode.getConnectionIds()) {
                if (visited.add(next)) {
                    prev.put(next, current);
                    queue.add(next);
                }
            }
        }
        if (!visited.contains(goal.getUUID())) {
            return List.of();
        }
        List<UUID> idPath = new ArrayList<>();
        UUID current = goal.getUUID();
        idPath.add(current);
        while (!current.equals(start.getUUID())) {
            current = prev.get(current);
            if (current == null) {
                return List.of(); // shouldn't happen given the visited check above, but don't trust it blindly
            }
            idPath.add(current);
        }
        Collections.reverse(idPath);
        List<T> path = new ArrayList<>(idPath.size());
        for (UUID id : idPath) {
            path.add(byId.get(id));
        }
        return path;
    }
}
