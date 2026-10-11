package com.github.cerealklla.settlemynts.plotsign.client;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.TriState;
import net.minecraft.world.entity.npc.villager.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;

/**
 * Appends a personal "!" to a quest-giver NPC's nameplate for whichever single player is actually
 * looking at it right now (2026-10-11 user spec: "a ! only visible to players who have a active
 * Milestone to talk to them, or a quest ready to turn back in"). Registered on {@code
 * NeoForge.EVENT_BUS} from {@code SettlemyntsModClient}'s constructor, never the common mod
 * constructor -- {@link RenderNameTagEvent} only ever fires client-side, and this class must stay
 * reachable only from client-only code so its bytecode is never touched on a dedicated server (same
 * "client-only type referenced from a common class can crash Production" lesson the suite's own root
 * CLAUDE.md documents).
 *
 * <p><b>Why this can't just be another shared vanilla {@code CustomName}</b>, the way the public "?"
 * marker ({@code plotsign.PlotShopProximityTicker#updateWishlistNameplate}) works: a vanilla
 * nameplate is entity data synced identically to every player tracking that entity -- there is no
 * way to make the same {@code CustomName} read differently for two different viewers. {@link
 * RenderNameTagEvent.CanRender}, by contrast, fires once per viewing client for whatever that
 * client's own local player is actually rendering, which makes it the one hook in this codebase that
 * genuinely can show different content to different players looking at the same entity. It reads
 * {@code ClientPersonalQuestMarkerState} (kept current by {@code PersonalQuestMarkerPayload}, pushed
 * per-player by {@code PlotShopProximityTicker}'s own wider scan) rather than anything synced onto
 * the entity itself.
 */
public final class PersonalQuestMarkerRenderer {

    private static final Component PERSONAL_MARKER =
            Component.literal(" !").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);

    @SubscribeEvent
    public void onCanRender(RenderNameTagEvent.CanRender event) {
        if (!(event.getEntity() instanceof Villager villager)) {
            return;
        }
        if (!ClientPersonalQuestMarkerState.has(villager.getId())) {
            return;
        }
        Component original = event.getContent();
        MutableComponent combined = original != null ? original.copy() : Component.empty();
        event.setContent(combined.append(PERSONAL_MARKER));
        event.setCanRender(TriState.TRUE);
    }
}
