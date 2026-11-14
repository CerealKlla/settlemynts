package com.github.cerealklla.settlemynts.bridge;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

/**
 * BuildyngBlocks' Roller Door, 2026-11-14 -- a guard spawned inside a Guardhouse whose only exit
 * is a Roller Door needs to be able to open/close it to patrol in and out, the same way {@code
 * OpenDoorGoal}/{@code GuardGateOpenGoal} already let it handle real doors and fence gates. Same
 * isolation convention as every other bridge in this package: {@code guardhouse.GuardEntity} must
 * never reference {@code com.github.cerealklla.buildyngblocks.*} types directly -- the JVM
 * verifier resolves every type a class's methods reference once that class is touched at all, even
 * behind a runtime {@code isLoaded()} check (confirmed by the real incident documented in the root
 * project's own CLAUDE.md), so all of that referencing bytecode lives in this one bridge class
 * instead, which Guard code only ever reaches through {@link #isLoaded()}.
 */
public final class BuildyngBlocksBridge {

    private BuildyngBlocksBridge() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("buildyngblocks");
    }

    /** See {@code buildyngblocks.rollerdoor.pathfinding.RollerDoorAwareGroundNavigation}'s own doc -- the navigation override that lets pathfinding recognize a closed Roller Door as an openable obstacle at all. */
    public static PathNavigation createRollerDoorAwareNavigation(Mob mob, Level level) {
        return new com.github.cerealklla.buildyngblocks.rollerdoor.pathfinding.RollerDoorAwareGroundNavigation(mob, level);
    }

    /** See {@code buildyngblocks.rollerdoor.pathfinding.RollerDoorOpenGoal}'s own doc -- the goal that actually opens/closes a Roller Door the mob is pressed against. */
    public static Goal createRollerDoorOpenGoal(Mob mob) {
        return new com.github.cerealklla.buildyngblocks.rollerdoor.pathfinding.RollerDoorOpenGoal(mob);
    }
}
