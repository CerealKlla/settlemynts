package com.github.cerealklla.settlemynts.tavern;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * What a seated Tavern NPC wants to be hired -- "These NPCs will have specific items they want,
 * some amount of gold, or they may want you to do a timed quest quickly, in order to hire them."
 * Rolled once per NPC at spawn time ({@code TavernHireRoller}), one fixed requirement per NPC (user's
 * explicit choice: "one random requirement per NPC," not a player's choice of several).
 */
public sealed interface HireRequirement {

    /** Short, player-facing description of what's needed -- shown on first interact and in chat feedback. */
    Component describe();

    record ItemRequirement(Identifier itemId, int quantity) implements HireRequirement {
        @Override
        public Component describe() {
            ItemStack sample = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(itemId));
            return Component.literal("Bring " + quantity + " " + sample.getHoverName().getString());
        }
    }

    record GoldRequirement(int amount) implements HireRequirement {
        @Override
        public Component describe() {
            return Component.literal(amount + " Gold Nuggets");
        }
    }

    /** "Do a timed quest quickly" -- a self-contained Settlemynts Trial, not routed through Myssions (see {@code TavernTrialTracker}'s own doc for why). */
    record TrialRequirement(int requiredKills, int timeLimitTicks) implements HireRequirement {
        @Override
        public Component describe() {
            return Component.literal("Defeat " + requiredKills + " hostile mobs within "
                    + (timeLimitTicks / 20) + " seconds (right-click again to start)");
        }
    }
}
