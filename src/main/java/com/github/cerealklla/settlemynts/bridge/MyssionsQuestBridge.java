package com.github.cerealklla.settlemynts.bridge;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.github.cerealklla.myssions.api.Myssions;
import com.github.cerealklla.myssions.quest.QuestGiverMenu;
import com.github.cerealklla.settlemynts.plotsign.QuestMenuInfo;

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
        Optional<QuestMenuInfo.NewOffer> newOffer = menu.newOffer()
                .map(o -> new QuestMenuInfo.NewOffer(o.questId(), o.questName(), o.description()));
        return new QuestMenuInfo(activeEntries, newOffer);
    }

    /** Accepts the plot's current board offer. */
    public static void acceptBoardOffer(ServerPlayer player, UUID plotId) {
        Myssions.acceptBoardOffer(player, plotId);
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

    /** Player-independent -- used for the global "?" nameplate, see {@code Myssions#hasPendingBoardOffer}'s own doc. */
    public static boolean hasPendingBoardOffer(ServerLevel level, UUID plotId) {
        return Myssions.hasPendingBoardOffer(level.getServer(), plotId);
    }

    /** Player-specific -- used for the issuer NPC's personal "!" head marker, see {@code Myssions#hasUrgentPersonalBusiness}'s own doc. */
    public static boolean hasUrgentPersonalBusiness(ServerPlayer player, UUID plotId, String zoneTypeId) {
        return Myssions.hasUrgentPersonalBusiness(player, plotId, zoneTypeId);
    }
}
