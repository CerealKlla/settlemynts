package com.github.cerealklla.settlemynts.bridge;

import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.myssions.api.Myssions;
import com.github.cerealklla.myssions.quest.QuestGiverPreview;
import com.github.cerealklla.settlemynts.plotsign.QuestOfferInfo;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

/**
 * Surfaces Myssions' quest-board offers (and ready-to-turn-in deliveries) inside this mod's own
 * "Press H" wishlist screen -- explicit user request, 2026-10-10: "these quests should show up in
 * the Press H menu for the NPCs." Optional soft dependency, same isolation convention as the other
 * bridges in this package (see {@code LyfeMerchantBridge}'s own doc) -- every call site must check
 * {@link #isLoaded()} itself before touching this class at all, so its {@code Myssions.*}-
 * referencing bytecode is never loaded on a Myssions-less server.
 */
public final class MyssionsQuestBridge {

    private MyssionsQuestBridge() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("myssions");
    }

    /** Translates Myssions' own read-only preview into this mod's network-safe DTO -- see {@code plotsign.QuestOfferInfo}'s own doc for why the translation happens here, not in a payload class. */
    public static Optional<QuestOfferInfo> preview(ServerPlayer player, UUID plotId, String zoneTypeId) {
        QuestGiverPreview preview = Myssions.previewQuestGiver(player, plotId, zoneTypeId);
        return switch (preview.kind()) {
            case NOTHING -> Optional.empty();
            case WAITING_ON_PLAYER ->
                Optional.of(new QuestOfferInfo(preview.questId(), preview.questName(), preview.blurb(), QuestOfferInfo.QuestAction.NONE));
            case READY_TO_TURN_IN ->
                Optional.of(new QuestOfferInfo(preview.questId(), preview.questName(), preview.blurb(), QuestOfferInfo.QuestAction.TURN_IN));
            case NEW_OFFER ->
                Optional.of(new QuestOfferInfo(preview.questId(), preview.questName(), preview.blurb(), QuestOfferInfo.QuestAction.ACCEPT));
        };
    }

    /** Runs the real accept/turn-in action -- the same mutation a shift+right-click on the quest giver itself triggers, now also reachable from the wishlist screen's own button. */
    public static void interact(ServerPlayer player, UUID plotId, String zoneTypeId) {
        Myssions.interactWithQuestGiver(player, plotId, zoneTypeId);
    }

    /** Player-independent -- used for the global "!" nameplate, see {@code Myssions#hasPendingBoardOffer}'s own doc. */
    public static boolean hasPendingBoardOffer(ServerLevel level, UUID plotId) {
        return Myssions.hasPendingBoardOffer(level.getServer(), plotId);
    }
}
