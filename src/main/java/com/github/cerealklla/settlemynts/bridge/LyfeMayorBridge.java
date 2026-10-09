package com.github.cerealklla.settlemynts.bridge;

import java.util.UUID;

import com.github.cerealklla.lyfe.api.Lyfe;
import com.github.cerealklla.lyfe.skill.Skills;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;

/**
 * Grants/reads Lyfe's Mayor skill (added 2026-10-09, design doc: "Gains XP any time a plot in a
 * Settlement you are the mayor of is upgraded. Gain half xp if you are a Town Planner for that town
 * but not the Mayor"). Same optional-soft-dependency isolation convention as {@code
 * LyfeMerchantBridge} -- every call site elsewhere in Settlemynts must check {@link #isLoaded()}
 * itself first.
 *
 * <p>XP is granted to every qualifying player regardless of who actually clicked "Upgrade Plot" --
 * the skill's own spec is passive ("a Settlement YOU are the mayor of," not "a plot YOU upgraded"),
 * so this always loops the settlement's founder (full XP) and every other Town Planner (half XP),
 * never just the acting player. See {@code SettlemyntsMod#requestUpgradePlot}'s own call site.
 */
public final class LyfeMayorBridge {

    private LyfeMayorBridge() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("lyfe");
    }

    public static void grantMayorXp(MinecraftServer server, UUID playerId, long amount) {
        if (amount > 0) {
            Lyfe.addXp(server, playerId, Skills.MAYOR_ID, amount);
        }
    }

    public static int getMayorLevel(MinecraftServer server, UUID playerId) {
        return Lyfe.getLevel(server, playerId, Skills.MAYOR_ID);
    }

    public static int xpForPlotUpgrade(int newTier) {
        return Lyfe.mayorXpForPlotUpgrade(newTier);
    }

    /** "Mayor should also gain xp for every 10 gold taxed" -- not called by anything yet, no tax system exists here to call it from (explicit user note); ready for whenever one does. */
    public static int xpForGoldTaxed(int goldTaxed) {
        return Lyfe.mayorXpForGoldTaxed(goldTaxed);
    }

    public static int minMayorLevelForZoneType(Identifier zoneTypeId) {
        return Lyfe.minMayorLevelForZoneType(zoneTypeId);
    }
}
