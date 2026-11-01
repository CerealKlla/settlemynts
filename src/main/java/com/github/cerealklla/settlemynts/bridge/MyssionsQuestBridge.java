package com.github.cerealklla.settlemynts.bridge;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import com.github.cerealklla.myssions.api.Myssions;
import com.github.cerealklla.myssions.quest.QuestCompletionHooks;
import com.github.cerealklla.myssions.quest.QuestDefinition;
import com.github.cerealklla.myssions.quest.QuestGiverMenu;
import com.github.cerealklla.myssions.quest.QuestMilestone;
import com.github.cerealklla.myssions.quest.QuestTimeLimit;
import com.github.cerealklla.settlemynts.plotsign.QuestMenuInfo;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

/**
 * Surfaces Myssions' quest-board offers and the player's own active quests inside this mod's own
 * "Press H" chat menu -- explicit user requests, 2026-10-10: "these quests should show up in the
 * Press H menu for the NPCs," then "you should be able to select your active quest by name, and
 * then in a sub-menu have the option to complete the quest." Optional soft dependency, same
 * isolation convention as the other bridges in this package (see {@code LyfeMerchantBridge}'s own
 * doc) -- every call site must check {@link #isLoaded()} itself before touching this class at all,
 * so its {@code Myssions.*}-referencing bytecode is never loaded on a Myssions-less server.
 */
public final class MyssionsQuestBridge {

    private MyssionsQuestBridge() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("myssions");
    }

    /** Translates Myssions' own {@code QuestGiverMenu} into this mod's network-safe DTO -- see {@code plotsign.QuestMenuInfo}'s own doc for why the translation happens here, not in a payload class. */
    public static QuestMenuInfo getMenu(ServerPlayer player, UUID plotId, String zoneTypeId) {
        QuestGiverMenu menu = Myssions.getQuestGiverMenu(player, plotId, zoneTypeId);
        var activeEntries = menu.activeQuests().stream()
                .map(e -> new QuestMenuInfo.ActiveEntry(e.instanceId(), e.questName(), e.readyToComplete()))
                .collect(Collectors.toList());
        var newOffers = menu.newOffers().stream()
                .map(o -> new QuestMenuInfo.NewOffer(o.questId(), o.questName(), o.description()))
                .collect(Collectors.toList());
        return new QuestMenuInfo(activeEntries, newOffers, java.util.List.of());
    }

    /** Accepts one specific offer (by definition id) sitting on the plot's board. */
    public static void acceptBoardOffer(ServerPlayer player, UUID plotId, Identifier questId) {
        Myssions.acceptBoardOffer(player, plotId, questId);
    }

    /**
     * Attempts to complete a specific active instance -- true if a reward GUI was opened (or
     * re-opened) server-side, or the instance was already fully done. The caller must NOT send a
     * fresh {@code OpenShopWishlistPayload}/open {@code ChatMenuScreen} in that case (2026-10-11,
     * real bug found wiring this in: Myssions' own reward-pickup GUI opens via a plain vanilla
     * {@code player.openMenu(...)} packet, and a custom payload arriving right after it would
     * immediately override/close that screen with the chat menu again).
     */
    public static boolean completeQuest(ServerPlayer player, UUID instanceId) {
        return Myssions.tryCompleteQuest(player, instanceId);
    }

    /**
     * Called right after {@code SettlemyntsMod#requestUpgradePlot}'s own real "Upgrade Plot" action
     * succeeds -- real live report, 2026-10-12: a player paid for and completed a real plot Tier
     * upgrade through this mod's own UI, but a Myssions "help upgrade this plot" quest themed around
     * the exact same upgrade had no way to find out, and stayed stuck forever. See Myssions'
     * {@code QuestEngine#completePlotUpgradeQuestsForPlot}'s own doc for the full fix.
     */
    public static void notifyPlotUpgraded(ServerPlayer player, UUID plotId) {
        Myssions.completePlotUpgradeQuestsForPlot(player, plotId);
    }

    /** Player-independent -- used for the global "?" nameplate, see {@code Myssions#hasPendingBoardOffer}'s own doc. */
    public static boolean hasPendingBoardOffer(ServerLevel level, UUID plotId) {
        return Myssions.hasPendingBoardOffer(level.getServer(), plotId);
    }

    /** Player-specific -- used for the issuer NPC's personal "!" head marker, see {@code Myssions#hasUrgentPersonalBusiness}'s own doc. */
    public static boolean hasUrgentPersonalBusiness(ServerPlayer player, UUID plotId, String zoneTypeId) {
        return Myssions.hasUrgentPersonalBusiness(player, plotId, zoneTypeId);
    }

    /**
     * Posts a new Establish Highway offer from {@code originPlaceId} to {@code destinationPlaceId}
     * -- added 2026-10-13 for the Town Hall Plot Config Sign's own button. Thin pass-through to
     * Myssions' already-built {@code quest.RoadQuestLogic#postOffer} (via {@code
     * api.Myssions#postRoadQuestOffer}) -- the road-connection quest mechanics themselves (stake
     * granting, progress checking, buyback, the 6-hour deadline, fail/abandon cleanup) needed no new
     * work at all; this is purely a new way to call the same real post. False if the pair already
     * has any entry (posted/in-progress/connected), or either settlement can't currently be resolved.
     */
    public static boolean postRoadQuestOffer(ServerLevel level, long originPlaceId, long destinationPlaceId,
                                              UUID postingPlotId, UUID posterPlayerId) {
        return Myssions.postRoadQuestOffer(level, originPlaceId, destinationPlaceId,
                java.util.Optional.of(postingPlotId), java.util.Optional.of(posterPlayerId));
    }

    /** Current state (if any) of a road-connection offer between these two settlements -- used to exclude already-posted/in-progress/connected pairs from the Establish Highway map picker's candidate list. */
    public static boolean hasAnyRoadQuestEntry(net.minecraft.server.MinecraftServer server, long placeIdA, long placeIdB) {
        return Myssions.getRoadQuestState(server, placeIdA, placeIdB).isPresent();
    }

    public record StakeThresholds(int minStakes, int optimalStakeCap) {
    }

    /** Read-only preview of real min/optimal Highway Stake counts for a distance -- see {@code api.Myssions#computeRoadQuestStakeThresholds}'s own doc. */
    public static StakeThresholds computeRoadQuestStakeThresholds(double straightLineDistanceBlocks) {
        var result = Myssions.computeRoadQuestStakeThresholds(straightLineDistanceBlocks);
        return new StakeThresholds(result.minStakes(), result.optimalStakeCap());
    }

    public record PostedOffer(long destinationPlaceId, String destinationName) {
    }

    /** Every not-yet-accepted road-quest offer this settlement has posted out -- see {@code api.Myssions#getPostedRoadQuestOffers}'s own doc. */
    public static java.util.List<PostedOffer> getPostedRoadQuestOffers(ServerLevel level, long postingPlaceId) {
        java.util.List<PostedOffer> offers = new java.util.ArrayList<>();
        for (var offer : Myssions.getPostedRoadQuestOffers(level.getServer(), level, postingPlaceId)) {
            offers.add(new PostedOffer(offer.destinationPlaceId(), offer.destinationName()));
        }
        return offers;
    }

    /** Accepts a posted road-quest offer -- see {@code api.Myssions#acceptRoadQuestOffer}'s own doc. */
    public static boolean acceptRoadQuestOffer(ServerPlayer player, long postingPlaceId, long destinationPlaceId) {
        return Myssions.acceptRoadQuestOffer(player, postingPlaceId, destinationPlaceId);
    }

    // --- Tavern Hiring Trial (design doc Section 17, 2026-10-31 correction) ---------------------
    //
    // A real Myssions quest, not a bespoke Settlemynts-local timer/counter (see
    // tavern.TavernHireHandler's own doc for the full story of why this replaced the original
    // TavernTrialTracker). Five static QuestDefinitions, one per Tavern tier (1-5) since a kill
    // milestone's requiredCount is fixed per-definition, not overridable per-instance, and
    // TavernHireRoller only ever produces 5 distinct (tier-scaled) kill counts anyway -- no side
    // table or dynamic-definition machinery needed. Each uses QuestMilestone#killAnyHostile (added
    // the same day specifically for this) so any hostile mob counts, not one specific entity type;
    // an empty rewards() list, since the real "reward" (a Companion Token) is delivered entirely by
    // the completion hook calling straight back into TavernHireHandler, not through Myssions' own
    // Gold/Item/XP reward kinds; and isSpecialAssignment = true so a Trial never competes with or
    // gets blocked by the player's unrelated 5-quest cap.

    private static final int MIN_TIER = 1;
    private static final int MAX_TIER = 5;
    private static final long TRIAL_TIME_LIMIT_TICKS = 3600L;

    public static void registerTavernTrialQuests() {
        for (int tier = MIN_TIER; tier <= MAX_TIER; tier++) {
            int requiredKills = 2 + tier;
            QuestMilestone milestone = QuestMilestone.killAnyHostile(
                    "defeat_hostiles", "Defeat " + requiredKills + " hostile mobs", requiredKills);
            QuestDefinition definition = new QuestDefinition(
                    tavernTrialId(tier), "Tavern Hiring Trial",
                    "Prove yourself before this companion will join you.", "tavern",
                    List.of(milestone), List.of(), QuestTimeLimit.withinTicksOfAssignment(TRIAL_TIME_LIMIT_TICKS),
                    List.of(), true);
            Myssions.registerQuestDefinition(definition);
        }
    }

    /** Starts a Tavern Hiring Trial for {@code tier} (clamped 1-5) -- empty if the player's already mid-Trial for this exact tier. */
    public static Optional<UUID> startTavernTrial(ServerPlayer player, int tier) {
        return Myssions.assignQuest(player, tavernTrialId(tier));
    }

    /** True if the player already has an in-progress Trial of this tier -- the Hire menu uses this to show "already in progress" instead of a Start button. */
    public static boolean hasActiveTavernTrial(ServerPlayer player, int tier) {
        Identifier questId = tavernTrialId(tier);
        return player.getData(com.github.cerealklla.myssions.registration.ModAttachments.QUEST_DATA).quests().values().stream()
                .anyMatch(i -> i.definitionId().equals(questId) && i.status() == com.github.cerealklla.myssions.quest.QuestStatus.IN_PROGRESS);
    }

    /** Registers the real completion callback -- fired the instant any tier's Trial is won, with the winning player and that instance's id. */
    public static void registerTavernTrialCompletionHook(BiConsumer<ServerPlayer, UUID> onCompleted) {
        for (int tier = MIN_TIER; tier <= MAX_TIER; tier++) {
            QuestCompletionHooks.register(tavernTrialId(tier), (player, instanceId, questId) -> onCompleted.accept(player, instanceId));
        }
    }

    private static Identifier tavernTrialId(int tier) {
        int clamped = Math.max(MIN_TIER, Math.min(MAX_TIER, tier));
        return Identifier.fromNamespaceAndPath("settlemynts", "tavern_trial_tier_" + clamped);
    }
}
